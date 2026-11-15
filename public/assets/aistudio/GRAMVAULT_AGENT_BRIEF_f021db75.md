# GramVault: Agent Brief

Read this file and `GRAMVAULT_CORE_ARCHITECTURE.md` (what is already built). This brief explains what matters, how a mature TON wallet works behind the scenes, and what to do next. Use your own judgment for details. Verify every library API against its real docs before using it, and say "unknown" if you cannot.

## 1. What this is
A non-custodial wallet for the TON network (native coin GRAM, formerly Toncoin; plus USDT). Android, Kotlin + Jetpack Compose only, no native code. The core is already implemented. Do not rebuild it. Goal: behave like Tonkeeper or MyTonWallet so a user can move between this app and those apps freely.

## 2. How to work
1. One task at a time. Send a short plan (max 10 lines) first and wait for my "go".
2. After each task send a short report: what changed, tests run, risks, what you did not do. Then stop.
3. Touch only what the task needs. Do not refactor finished work.
4. If anything conflicts with security, security wins. Ask me when unsure.

## 3. Principles that must never break
- **Never lose or mis-show a transaction.** The chain is the source of truth; the local DB is a rebuildable cache. Persist before broadcasting. Never claim "confirmed" or "expired" without proof from the chain. When unsure, show "uncertain" and block new sends.
- **Secrets never leave the device** and never appear in logs, DB, backups, clipboard, analytics or crash reports. Keys stay encrypted with Android Keystore. Zero sensitive buffers after use.
- **Money is exact:** `BigInteger` in smallest units; decimals come from the asset (GRAM 9, USDT 6). Never floating point. Mainnet and Testnet never mix (types, addresses, wallet ids, UI banner).
- **No server, no domain, no push.** Every phone talks directly to public TON APIs with client-side pacing, failover and caching. Do not propose a backend, proxy, analytics or user counting.
- **Do not trust API data for security decisions.** Indexed APIs return no proofs. Only the allow-listed USDT master counts as USDT. Never trust token names, logos or comments.
- **Clean code:** no mention of "AI", "Gemini", "generated", "assistant", "LLM" or "prompt" anywhere (code, comments, strings, README, commits). Neutral comments that explain why. Search for these words at the end of each task.

## 4. How the layers should fit (behind the scenes)
Keep these layers separate. Each talks only to the one below it.

1. **UI** (Compose screens, ViewModel). Shows state, never touches keys or raw API data.
2. **WalletCore facade.** The only entry point for the UI: create/import/unlock, balances, prepare/confirm send, history, settings.
3. **Policy layer.** Allow-lists (USDT), amount and comment limits, address and network validation, scam/spam filtering, send rules (one pending tx at a time).
4. **Message builder and signer.** Builds the internal message and the wallet-contract external message (V5R1 and older versions), attaches StateInit on the first send, signs. Only this layer sees the private key, and only briefly.
5. **Key vault.** Keystore-encrypted seed, auth gate (biometric/device credential/PIN), auto-lock, wipe.
6. **Chain gateway.** Provider router (TON Center, TonAPI) with failover, rate limiting, circuit breaker, caching. Gives one clean interface (balance, seqno, account state, fee emulation, broadcast, message status, history). Providers differ in format, so convert every response to our own models in one place.
7. **Ledger.** Room cache plus the transaction state machine and reconciler. Matches local pending txs with chain history by hash; heals wrong states on resync.
8. **Normalizers/parsers.** Turn raw indexer data (transactions, messages, Jetton transfers, comments) into one internal "activity" model: incoming/outgoing, GRAM/USDT, status, fee, hash, counterparty, comment. Handle both hash formats (hex/base64) and bounced/failed cases.

Rule of thumb: if a bug would cost money, it belongs in layers 3, 4 or 7 and must have tests.

## 5. Compatibility with other wallets (important)
The user must be able to take the same recovery phrase to Tonkeeper or MyTonWallet and see the same address and funds, and the reverse.
- **Mnemonics:** support the three real-world kinds: 12-word BIP-39 multichain (new wallets), 24-word TON legacy, 24-word multichain. For 24 words where both schemes validate, show both results and let the user choose. Never auto-pick. Derivation path for multichain is `m/44'/607'/0'` (SLIP-0010 Ed25519).
- **Wallet versions:** on import, discover accounts for V5R1, V4R2, V3R2, V3R1 and show balances for each; the user picks. Older wallets often hold funds in V4R2 or V3. V5R1 uses a different wallet id on Mainnet and Testnet.
- **Address formats:** accept raw (`0:hex`), friendly bounceable (`EQ…`), friendly non-bounceable (`UQ…`) and the Testnet variants; all refer to the same account. Show the user their **non-bounceable** address for receiving. Reason: if someone sends to a bounceable address of a wallet that is not deployed yet, the money bounces back to the sender.
- **Sending rule:** if the destination is uninitialized, send non-bounceable; otherwise bounceable.
- **Comments / memo:** support a text comment on GRAM and USDT sends. Exchanges often require a memo or comment for deposits; without it funds can be delayed. Put a small hint on the send screen and always show the comment on the confirm screen.
- **Deep links:** parse and build `ton://transfer/<address>?amount=&text=&jetton=` (amount in smallest units; for USDT, `jetton` is the master address). Always show the parsed address, amount and network on the confirm screen. Reject network mismatches and duplicated parameters.
- **QR:** receive screen QR encodes that same link; the scanner uses the same parser.
- **First send deploys the wallet** (StateInit attached while the account is uninitialized). A funded but never-used wallet showing "uninitialized" is normal.
- **USDT:** it is a Jetton. Balance lives in the user's Jetton wallet for the USDT master. Sends follow the TEP-74 transfer message, with TON attached for fees and the excess returned to the sender. Incoming tokens from unknown masters are hidden.
- **Later, not now:** TON Connect (dApp connections), `.ton` domain names, NFTs, swaps, staking, multiple accounts, hardware wallets. Design nothing that blocks them, build nothing for them yet.

## 6. Behaviors users expect from a mature wallet
- Backup flow at creation (show phrase on a screenshot-blocked screen, then verify a few random words) and a "reveal phrase" option behind fresh authentication.
- Clear transaction states: pending, confirmed, failed, expired, uncertain. A details screen with amount, fee, time, comment, the real transaction hash with a "copy full hash" button, and "view in explorer" (link built in code from a fixed domain list per network; never from API data).
- Fee estimate before sending (emulation, with a safe fallback), and "send max" that leaves enough for fees.
- Stale-data indicator instead of blank screens when offline; cached balance and history are shown first.
- Auto-lock when the app goes to background (configurable). Biometric or device credential, or PIN where no device lock exists.
- Address book with network-strict addresses.
- Display-only fiat prices, never used in any logic.
- A visible TESTNET banner in all testnet builds.

## 7. Next tasks (in this order)
1. **Fix the audit findings**, one at a time, each with tests:
   - Transaction state: never conclude CONFIRMED or EXPIRED from a seqno change alone (another device with the same key, indexer lag or ignored-error send mode can move seqno). Add an "uncertain" state, keep checking by hash, block new sends until resolved.
   - USDT confirmation only after the whole Jetton transfer completes (no bounce back).
   - The Testnet USDT address in the allow-list is not an official Tether asset (USDT exists on mainnet only): relabel it as a test token or remove it.
   - PIN mode: lockout after failed attempts, at least 6 digits. (Decision pending from me: require a device lock instead.)
   - Debug builds must not use Mainnet.
   - Remove WalletKit if it is unused for transactions (its signer touches key storage; early version).
   - Hygiene: change `namespace` from `com.example` to `com.gramvault.wallet`, delete the Phase0 spike file, replace placeholder URLs.
   - Security hygiene: `FLAG_SECURE` on recovery and send-confirm screens, release build with R8 and no logging, clear clipboard, `debuggable=false`, no secrets in backups.
   - Update old dependencies (e.g. okhttp), pin versions, list licenses. No GPL without my approval.
   - Handle phone clock skew (`valid_until`), and check APIs and price feeds still work after the TON→GRAM rename.
   - Extra tests to add: kill/crash between every transaction state change; network cut during broadcast; duplicated or partial API responses; repeated 429s; official test vectors for mnemonic, key and address (not only self-made ones); random-value tests for money math and for build/decode round trips of messages; "send all" (amount + fee + minimum kept); send to self; testnet address on mainnet. Robolectric does not replace hardware Keystore tests, so those need a real device.
   - Small mismatch to fix: deep links allow comments up to 500 characters but the transfer builder accepts 120 bytes. Validate the same limit everywhere, before the confirm screen.
2. **Cross-wallet compatibility tests on Testnet** (and tiny amounts on Mainnet): create a wallet here, restore the phrase in Tonkeeper (testnet mode) and MyTonWallet; addresses must match for each wallet version. Send both ways. Test import of 12-word, 24-word legacy, 24-word multichain, and the ambiguous 24-word case.
3. **Real-device tests** (at least two phones, old and new Android): create, restore from phrase, receive, send, kill the app mid-send, bad network, rate limits (429), send to an uninitialized address, send with a comment, USDT test token, clock skew.
4. **Pre-release:** independent security review of key storage, signing and the transaction state machine; license audit; our own signing key; tiny-amount Mainnet test.

## 8. Later (do NOT build now)
Optional incoming-deposit notifications with WorkManager (off by default, 15+ minute delay, public address only, never unlock the vault), buy-crypto link through a third-party provider, multiple languages (all text from resources, addresses and hashes always left-to-right, recovery phrase stays English), swap, staking, NFTs, TON Connect.

## 9. First message to start with
"Read GRAMVAULT_AGENT_BRIEF.md and GRAMVAULT_CORE_ARCHITECTURE.md. Start with the first audit item (transaction state). Send me a plan of at most 10 lines first and wait."

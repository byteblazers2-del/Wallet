# GramVault Core Architecture & Technical Audit Specification

This document describes the **actual, currently implemented core architecture** of **GramVault** (`applicationId = "com.gramvault.wallet"`, `namespace = "com.example"`), a non-custodial TON-only Android wallet written in Kotlin and Jetpack Compose.

Every module, class, constant, cryptographic parameter, TL-B layout, database schema, network endpoint, and behavioral limitation listed here reflects the exact implementation present in the repository.

---

## 1. Platform Configuration & Core Dependencies

- **Build & SDK Targets** (`app/build.gradle.kts`, `gradle/libs.versions.toml`):
  - `applicationId`: `com.gramvault.wallet` | `namespace`: `com.example`
  - `minSdk = 26` (Android 8.0), `targetSdk = 36`, `compileSdk = 36.1`, Java 17 / Kotlin `2.2.10`
- **Core Libraries**:
  - `org.ton.kotlin:ton-kotlin-block-tlb:0.5.0`, `ton-kotlin-crypto:0.5.0`, `ton-kotlin-tvm:0.5.0` — Ed25519 keys/signatures, Cell/BOC serialization, and TL-B codecs.
  - `org.ton:walletkit:0.0.6` — Initialized via `WalletKitBridge` for V5R1/V4R2 adapter attachment and address verification.
  - `androidx.biometric:biometric:1.1.0` — `BiometricPrompt` integration (`BIOMETRIC_STRONG` / `DEVICE_CREDENTIAL`).
  - `androidx.room:room-runtime:2.7.0` + `room-ktx` + KSP compiler — Local transaction ledger, balance cache, and address book (`GramVaultDatabase` v4).
  - `androidx.datastore:datastore-preferences:1.1.7` — Non-secret app preferences (`AppSettingsStore`).
  - `com.squareup.okhttp3:okhttp:4.10.0` + `com.squareup.moshi:moshi-kotlin:1.15.2` (streaming `JsonReader`/`JsonWriter`) — Direct TON RPC/indexer networking without a proxy server.
- **Permissions & OS Backup Exclusion** (`app/src/main/AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`):
  - Declares only `android.permission.INTERNET` and `android.permission.USE_BIOMETRIC`.
  - Sets `android:allowBackup="false"` and explicitly excludes `file`, `database`, `sharedpref`, `external`, and `root` domains from both cloud backup and device-to-device transfer.

---

## 2. Package Map & Dependency Wiring

```text
app/src/main/java/com/example/
├── WalletViewModel.kt                 # Lifecycle-aware state holder, foreground live monitor, UI error sanitization
├── MainActivity.kt                    # Compose host, edge-to-edge setup, foreground/background lifecycle observer
└── core/
    ├── WalletCoreFactory.kt           # Composition root (constructor injection)
    ├── keystore/
    │   ├── BiometricGate.kt           # AuthGate + BiometricPrompt bridge
    │   └── SecureKeyStore.kt          # Android Keystore AES-256-GCM + App PIN PBKDF2 envelope + vault_seed.bin
    ├── model/
    │   ├── Network.kt                 # MAINNET / TESTNET + V5R1 walletId constants
    │   ├── TonAddress.kt              # Raw & CRC16-XMODEM friendly parser, network isolation
    │   ├── Money.kt                   # BigInteger fixed-point asset math (GRAM 9 decimals) + BalanceState
    │   ├── JettonModels.kt            # Hardcoded USDT allowlist (6 decimals) + Jetton domain models
    │   ├── WalletModels.kt            # MnemonicPhrase, VaultState, VaultMetadata, CandidateAccount, WalletVersion
    │   └── TxModels.kt                # TxStatus, TransferPreview, TxRecord, ChainMonitorState
    ├── wallet/
    │   ├── WalletCore.kt              # WalletCore interface & WalletCoreImpl orchestrator
    │   ├── MnemonicService.kt         # BIP-39 12/24, SLIP-0010 m/44'/607'/0', TON Legacy 24-word KDF
    │   ├── Bip39Wordlist.kt           # Embedded 2048-word English BIP-39 dictionary
    │   ├── AddressService.kt          # V5R1, V4R2, V3R2, V3R1 StateInit & deterministic address derivation
    │   ├── TransferBuilder.kt         # Off-chain GRAM & TEP-74 Jetton TL-B serialization + Ed25519 signing
    │   ├── TonTransferUri.kt          # ton://transfer/ parser & builder
    │   ├── VaultMetadataStore.kt      # JSON metadata persistence (vault_meta.json)
    │   ├── AppSettingsStore.kt        # DataStore preferences (auto-lock, live monitor)
    │   ├── WalletKitBridge.kt         # org.ton:walletkit:0.0.6 adapter sync bridge
    │   └── Phase0Spike.kt             # Early prototype helper for WalletKit smoke verification
    ├── network/
    │   ├── ProviderModels.kt          # ChainGateway, TonNetworkProvider, typed ProviderException hierarchy
    │   ├── ProviderRouter.kt          # Failover router (TON Center -> TonAPI) + shared OkHttpClient
    │   ├── ProviderRateLimiter.kt     # Per-provider pacing, jittered exponential backoff, circuit breaker
    │   ├── TonCenterClient.kt         # Direct TON Center v3 / v2 / emulateTrace client
    │   ├── TonApiClient.kt            # Direct TonAPI v2 fallback client
    │   ├── TonCommentDecoder.kt       # Raw BOC UTF-8 text comment decoder & hash hex normalizer
    │   └── PriceService.kt            # Display-only USD price feed (TonAPI -> CoinGecko, 90s TTL)
    ├── tx/
    │   └── TxManager.kt               # Mutex-serialized send pipeline, Room crash recovery, incremental sync
    └── data/
        ├── GramVaultDatabase.kt       # Room v4 SQLite DB (gramvault_cache.db) + MIGRATION_3_4
        ├── BalanceCacheEntity.kt / BalanceCacheDao.kt / BalanceRepository.kt
        ├── TxEntity.kt / TxDao.kt
        └── AddressBookEntity.kt / AddressBookDao.kt / AddressBookRepository.kt
```

### Build-Type Security Defaults (`WalletCoreFactory.kt`)
- `devSkipAuth = BuildConfig.DEBUG`: Enables unauthenticated Keystore wrapper mode (`DEV_UNAUTH_KEYSTORE`) only in debug builds.
- `defaultAutoLock = !BuildConfig.DEBUG`: Release builds lock automatically on `ON_STOP`; debug builds default auto-lock to `false` unless changed in settings.

---

## 3. Cryptography, Key Storage & Authentication Architecture

### 3.1 Encrypted Seed Vault (`core/keystore/SecureKeyStore.kt`)
Secrets (`seed32` and `mnemonicUtf8`) are persisted in `filesDir/vault/vault_seed.bin` (written atomically via `vault_seed.bin.tmp` and `renameTo`), separate from public metadata.

- **Inner Plaintext Envelope (`MAGIC = "GV1"`)**:
  ```text
  [ "GV1" (3 bytes) | seedLen (1 byte, = 32) | seed32 (32 bytes) | mnemonicLen (uint16 BE) | mnemonicUtf8 (N bytes) ]
  ```
- **Hardware Outer Cipher (`AndroidKeyStoreCipherProvider`)**:
  - Cipher: `AES/GCM/NoPadding`, 256-bit AES key, 128-bit GCM authentication tag, randomized IV enforced.
  - Binary envelope v1: `[ version=1 (1B) | ivLen (1B, 12..16) | iv | ciphertext_with_tag ]`.
  - Keystore Aliases:
    - `gramvault_master_aes_v1` (`requireUserAuth = true`): `setUserAuthenticationRequired(true)` with a **30-second** validity window (`DEFAULT_AUTH_VALIDITY_SECONDS = 30`). On API 30+, binds to `AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`.
    - `gramvault_pin_hw_v1` (`requireUserAuth = false`): Hardware-backed AES-256-GCM key without Keystore-enforced user-auth timeout, used as the outer hardware wrap for `APP_PIN_WITH_KEYSTORE` and directly in `DEV_UNAUTH_KEYSTORE`.

### 3.2 Three Vault Security Modes (`VaultSecurityMode`)

| Mode | Condition | Outer Wrap | Inner Encryption | Auth UX / Session |
| :--- | :--- | :--- | :--- | :--- |
| `DEVICE_KEYSTORE` | `!devSkipAuth && isDeviceSecure()` | `gramvault_master_aes_v1` (user-auth required, 30s) | Direct `GV1` plaintext | `BiometricGate` (`BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` on API 30+, `BIOMETRIC_WEAK \| DEVICE_CREDENTIAL` on API 26–29) |
| `APP_PIN_WITH_KEYSTORE` | `!devSkipAuth && !isDeviceSecure()` | `gramvault_pin_hw_v1` (hw-backed, no Keystore auth) | Envelope v2: `PBKDF2WithHmacSHA256` (100k iter, 16B `SecureRandom` salt) + AES-256-GCM | 4–8 digit App PIN; caches derived AES key in `activePinSessionKey` for `30,000 ms`, wiped on lock/expiry |
| `DEV_UNAUTH_KEYSTORE` | `BuildConfig.DEBUG` only (`devSkipAuth = true`) | `gramvault_pin_hw_v1` | Direct `GV1` plaintext | **Blocked in release (`!devSkipAuth`)** inside `unlockWallet`, `revealRecoveryPhrase`, `confirmBackup`, and `confirmAndSend` |

### 3.3 Key Invalidation, Tamper Handling & Memory Hygiene
- `KeyPermanentlyInvalidatedException` (e.g., biometrics/screen lock removed or re-enrolled) throws `VaultKeyInvalidatedException`, transitioning `vaultState` to `VaultState.KeyInvalidated(metadata)` so the user can re-import safely.
- `UserNotAuthenticatedException` throws `VaultAuthRequiredException`.
- Wrong App PIN (`AEADBadTagException` / `GeneralSecurityException`) throws `VaultWrongPinException`.
- **Startup Integrity (`WalletCoreImpl.loadInitialState`)**: If `vault_meta.json` exists without `vault_seed.bin`, stale metadata is deleted and the vault starts as `VaultState.Uninitialized`. If both exist, starts `VaultState.Locked(metadata)`. On unlock, `WalletCoreImpl` re-derives `PrivateKeyEd25519(seed32).publicKey()` and asserts equality against `metadata.publicKeyHex`.
- **Memory & Disk Zeroization**:
  - `MnemonicPhrase` stores defensive `CharArray` copies and wipes them via `wipe()` / `AutoCloseable`.
  - `SecureKeyStore` wipes `plaintext`, `seed32`, `mnemonicBytes`, `salt`, `pinKey`, and `PBEKeySpec` in `finally` blocks; `WalletCoreImpl` / `WalletViewModel` zero-fill `CharArray` PINs and backup answers immediately after use.
  - `resetVault()` overwrites `vault_seed.bin` with zero bytes (minimum 64 bytes) before deleting it, deletes both Android Keystore aliases, clears `vault_meta.json`, and wipes Room caches (`balance_cache`, `tx`).

---

## 4. Mnemonic Standards & Key Derivation (`core/wallet/MnemonicService.kt`)

All input words are NFKD Unicode-normalized, trimmed, and lowercased before dictionary lookup against the embedded 2048-word English BIP-39 wordlist (`Bip39Wordlist`).

1. **New Wallet Creation (`generate12WordMnemonic`)**:
   - Generates 16 bytes (128 bits) via `SecureRandom`, appends the top 4 bits of `SHA-256(entropy)` as checksum, encodes 12 11-bit indices.
   - Derives keys using `MnemonicScheme.MULTICHAIN` and defaults to `WalletVersion.V5R1`.

2. **Multichain Scheme (`MnemonicScheme.MULTICHAIN`, 12 or 24 words)**:
   - Validates BIP-39 word membership and SHA-256 checksum (4 bits for 12 words; 8 bits for 24 words).
   - Seed expansion: `PBKDF2WithHmacSHA512` (2048 iterations, salt `"mnemonic"`, 512-bit output).
   - SLIP-0010 Ed25519 key derivation (`HmacSHA512` root key `"ed25519 seed"`) along hardened path:
     - `m / 44' / 607' / 0'` (`0x8000002C`, `0x8000025F`, `0x80000000`) -> 32-byte `seed32` -> Ed25519 public key (`publicKey32`).

3. **TON Legacy Scheme (`MnemonicScheme.TON_LEGACY`, 24 words)**:
   - Computes 64-byte entropy via `HMAC-SHA512(key = spaceJoinedNormalizedWordsUtf8, data = empty)`.
   - Validates TON seed version: `PBKDF2-HMAC-SHA512(entropy, salt = "TON seed version", iterations = 390)` requires `firstByte == 0`.
   - Derives key: `PBKDF2-HMAC-SHA512(entropy, salt = "TON default seed", iterations = 100_000)` -> first 32 bytes become `seed32`.

4. **Ambiguous Import Discovery (`discoverImportAccounts`)**:
   - For 12 words, checks `MULTICHAIN`.
   - For 24 words, checks **both** `TON_LEGACY` and `MULTICHAIN`. Returns all valid `(scheme × [V5R1, V4R2, V3R2, V3R1])` candidates with `isAmbiguous24 = (schemes.size > 1)` and requires explicit user selection without auto-picking.

5. **Backup Verification (`createBackupChallenge`, `verifyBackupChallenge`)**:
   - Selects 3 random sorted 1-based positions from the 12 words and verifies answers via constant-time character XOR comparison (`constantTimeNormalizedEquals`).

---

## 5. TON Addresses, Wallet Contracts & `ton://` URIs

### 5.1 Address Validation & Network Isolation (`core/model/TonAddress.kt`, `Network.kt`)
- Networks: `MAINNET (v5r1WalletId = 0x7FFFFF11L)`, `TESTNET (v5r1WalletId = 0x7FFFFFFDL)`.
- Accepts raw `workchain:64hex` (strictly workchain `0` or `-1`) and 48-character Base64 / URL-safe Base64 user-friendly addresses.
- **Friendly validation**:
  - Verifies decoded length (`36` bytes) and `CRC16-XMODEM` over bytes `0..33` against bytes `34..35`.
  - Verifies tag `(tag and 0x3F) == 0x11` and workchain `0` or `-1`.
  - Enforces **strict network isolation**: rejects testnet-tagged addresses (`(tag and 0x80) != 0`) on Mainnet and mainnet-tagged addresses on Testnet.
  - Preserves bounceability flag (`isBounceable = (tag and 0x40) == 0`).
  - Friendly formatting defaults to `urlSafe = true` and `bounceable = false` for user receive addresses.

### 5.2 Wallet Contract StateInit (`core/wallet/AddressService.kt`)
Embeds compiled code BOCs (verified against `org.ton:walletkit:0.0.6` and official TON wallet sources) and constructs `StateInit(code, data)` cells deterministically:

| Version | Code Format | Initial `data` Cell Bit Layout |
| :--- | :--- | :--- |
| `V5R1` | Hex BOC (`WALLET_V5R1_CODE_BOC_HEX`) | `signatureAllowed(1b=1)` \| `seqno(32u=0)` \| `network.v5r1WalletId(32u)` \| `pubkey(256b)` \| `extensions(1b=0)` |
| `V4R2` | Base64 BOC (`WALLET_V4R2_CODE_BOC_BASE64`) | `seqno(32u=0)` \| `698983191L(32u)` \| `pubkey(256b)` \| `plugins(1b=0)` |
| `V3R2` | Base64 BOC (`WALLET_V3R2_CODE_BOC_BASE64`) | `seqno(32u=0)` \| `(698983191L + workchain)(32u)` \| `pubkey(256b)` |
| `V3R1` | Base64 BOC (`WALLET_V3R1_CODE_BOC_BASE64`) | `seqno(32u=0)` \| `(698983191L + workchain)(32u)` \| `pubkey(256b)` |

### 5.3 `WalletKitBridge` Scope (`core/wallet/WalletKitBridge.kt`)
- Initializes a singleton `ITONWalletKit` configured with `TONWalletKitStorageType.Memory` and TON Center endpoints.
- On create, import, unlock, and network switch, `WalletCoreImpl.syncWalletKit` attaches `V5R1` or `V4R2` adapters (passing a `WalletSigner` backed by `SecureKeyStore`) and asserts `kitAddr.raw == expectedAddr.raw` (`V3R2`/`V3R1` are not supported by the bridge and return `walletKitSynced = false`).
- **Important**: `WalletKitBridge` is **not** used for transaction building or broadcasting; all transfer serialization, Ed25519 signing, and BOC emission happen directly in `TransferBuilder` + `TxManager` + `ProviderRouter`.

### 5.4 Transfer URIs (`core/wallet/TonTransferUri.kt`)
- Parses and builds `ton://transfer/<friendlyAddress>` with optional `amount` (nanotons, positive integer), `text` (comment), `jetton`, and `exp`. Rejects duplicate query keys and enforces address checksum + network matching.
- Note: `TonTransferUri.build` permits comments up to `MAX_COMMENT_LENGTH = 500`, whereas `TransferBuilder` restricts outgoing on-chain comment payloads to `MAX_COMMENT_UTF8_BYTES = 120` UTF-8 bytes.

---

## 6. Transfer Construction & Off-Chain Signing (`core/wallet/TransferBuilder.kt`)

### 6.1 Common Parameters & Safeguards
- **Send Mode**: `DEFAULT_SEND_MODE = 3` (`PAY_GAS_SEPARATELY (1) + IGNORE_ERRORS (2)`).
- **TTL**: `DEFAULT_VALID_UNTIL_TTL_SECONDS = 120L`.
- **Fallback Fee Estimates** (when trace emulation fails): `15_000_000` nano (`0.015 GRAM`) for native GRAM; `20_000_000` nano (`0.020 GRAM`) for Jetton USDT.
- **Comments (`buildCommentBodyCell`)**: Empty string produces `Cell.empty()`; non-empty produces `0x00000000` (32-bit text opcode) + UTF-8 bytes (`<= 120` bytes so it always fits within one cell without multi-cell chaining).

### 6.2 Native GRAM Transfer & Dynamic Bounce Rule
In `WalletCoreImpl.prepareTransfer`:
- Checks destination on-chain status first:
  - `effectiveBounce = (destinationStatus != NONEXIST && destinationStatus != UNINIT)`
  - Sends **non-bounceable** internal messages to uninitialized/nonexistent addresses (so funds are credited rather than bounced) and **bounceable** internal messages to active/frozen smart contracts.
- Constructs `CommonMsgInfoRelaxed.IntMsgInfoRelaxed` wrapped in `MessageRelaxed`.

### 6.3 TEP-74 USDT Jetton Transfer (`buildJettonTransferBodyCell`)
Targets the sender's own Jetton Wallet contract (`senderJettonWallet`, internal message `bounce = true`) with `DEFAULT_JETTON_ATTACHED_GRAM_NANO = 55_000_000L` (`0.055 GRAM`, excess gas automatically refunded to sender via `response_destination`):

```text
transfer#0f8a7ea5
  query_id:uint64                  = 0
  amount:(VarUInteger 16)          = jettonAmountUnits (6 decimals)
  destination:MsgAddressInt        = AddrStd(recipient.raw)
  response_destination:MsgAddressInt = AddrStd(sender.raw)
  custom_payload:(Maybe ^Cell)     = false (0 bit)
  forward_ton_amount:(VarUInteger 16) = 1 nanoton
  forward_payload:(Either Cell ^Cell) =
    - false (0 bit) if comment empty
    - true  (1 bit) + ^Ref(commentBodyCell) if comment present
```
> **Note**: Both `destination` and `response_destination` are serialized using `MsgAddressInt.tlbCodec()` (not `AddrStd.tlbCodec()`) to include the mandatory `10` constructor prefix (`addr_std$10`).

### 6.4 Contract-Specific Body & Signature Placement
All wallet versions sign `unsignedBody.hash()` (SHA-256 cell hash) using `PrivateKeyEd25519(seed32)`:
- **`V5R1`**:
  - Unsigned cell: `0x7369676e (signed_external)` \| `v5r1WalletId(32)` \| `validUntil(32)` \| `seqno(32)` \| `actionsList` (`outActions=true` -> `ref(Cell.empty() + out_action#0ec3c86d(32) + sendMode(8) + ref(internalMsg))`, `extendedActions=false`).
  - Signed cell: **signature placed at the end** (`unsignedPayload || signature64`).
- **`V4R2`**:
  - Unsigned cell: `698983191L(32)` \| `validUntil(32)` \| `seqno(32)` \| `op=0(8)` \| `sendMode(8)` \| `ref(internalMsg)`.
  - Signed cell: **signature placed at the start** (`signature64 || unsignedPayload`).
- **`V3R2` / `V3R1`**:
  - Unsigned cell: `698983191L(32)` \| `validUntil(32)` \| `seqno(32)` \| `sendMode(8)` \| `ref(internalMsg)`.
  - Signed cell: **signature placed at the start** (`signature64 || unsignedPayload`).

### 6.5 External Message Envelope & Dual Hashes
`packExternalMessageCell` creates `Message(ExtInMsgInfo(src = AddrNone, dest = AddrStd(sender.raw), importFee = 0), init, body)`:
- Attaches `StateInit` (`Just(Either.Right(CellRef(StateInit)))`) when `senderStatus` is `NONEXIST` or `UNINIT`, deploying the wallet contract on the user's first outgoing transaction.
- Computes and stores **both** hashes in `SignedExternalTransfer`:
  1. `messageHashHex`: Hash of the transmitted message (including `StateInit` if attached).
  2. `normalizedHashHex`: Hash of the same external message with `init = Nothing()`, used for reliable trace/message matching (`message_hash_norm`) across TON indexers.
- `buildDummyTransferBocForEstimation` & `buildDummyJettonTransferBocForEstimation` build identical structures with a zeroed 64-byte dummy signature for remote fee emulation (`ignore_chksig = true`).

---

## 7. Curated USDT Allowlist & Anti-Spoofing (`core/model/JettonModels.kt`)

To prevent fake token/airdrop spoofing, **only official Tether USD (`USDT`, strictly `decimals = 6`)** is recognized:
- **Mainnet Master**: `EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs`
- **Testnet Master**: `kQD0GKBM8ZbryVk2aESmzfU6b9b_8era_IkvBSELujFZPsyy`

Defense layers enforced in `JettonAllowList.findVerified`, `TonCenterClient`, and `TonApiClient`:
1. Matches canonical `masterAddress.raw` case-insensitively **on the active network only**.
2. Validates `owner` raw address matches the wallet owner when returned by TON Center.
3. Throws `InvalidPayloadException` if a provider reports `decimals != 6`.
4. Ignores any Jetton balance or transfer from non-allowlisted masters; `TonApiClient` also skips events marked `is_scam == true` or `in_progress == true`.

---

## 8. Zero-Server Networking, Failover & Rate Limiting

GramVault uses **zero intermediate backend servers**; all chain reads, fee emulation, BOC broadcasts, and fiat quotes go directly from the Android `OkHttpClient` (8s connect/read/write timeouts, `retryOnConnectionFailure(false)`).

### 8.1 Provider Priority & Endpoints (`ProviderRouter`, `TonCenterClient`, `TonApiClient`)
No paid API keys are bundled (`apiKeyProvider` defaults to `{ null }`). Failover order:

1. **Primary — `TON Center`** (`https://toncenter.com` / `https://testnet.toncenter.com`, min interval `1,150 ms`):
   - Balance & `last_transaction_lt`: `GET /api/v3/accountStates?address=...&include_boc=false`
   - Jetton wallet & balance: `GET /api/v3/jetton/wallets?owner_address=...&jetton_address=...&limit=10`
   - Wallet seqno: `GET /api/v3/walletInformation` (fallback to `accountStates` + `POST /api/v3/runGetMethod` `seqno`)
   - Fee emulation: `POST /api/emulate/v1/emulateTrace` (`ignore_chksig = true`)
   - Broadcast: `POST /api/v3/message` -> on non-rejection, non-429 `ProviderException`, falls back internally to `POST /api/v2/sendBocReturnHash`
   - Message status: `GET /api/v3/transactionsByMessage?msg_hash=...&direction=in&limit=1` (normalized hash then message hash)
   - Account & Jetton history: `GET /api/v3/transactions` (with incremental `start_lt = latestLt + 1`), `GET /api/v3/jetton/transfers`

2. **Fallback — `TonAPI`** (`https://tonapi.io` / `https://testnet.tonapi.io`, min interval `4,250 ms`):
   - Balance: `GET /v2/accounts/{raw}`
   - Jettons: `GET /v2/accounts/{raw}/jettons`
   - Seqno: `GET /v2/wallet/{raw}/seqno` -> fallback `GET /v2/blockchain/accounts/{raw}/methods/seqno`
   - Fee emulation: `POST /v2/traces/emulate?ignore_signature_check=true` (recursively sums `total_fees` across trace tree)
   - Broadcast: `POST /v2/blockchain/message`
   - Message status: `GET /v2/blockchain/messages/{hash}/transaction`
   - Account & Jetton history: `GET /v2/blockchain/accounts/{raw}/transactions` (`after_lt`), `GET /v2/accounts/{raw}/jettons/history`

### 8.2 Rate Limiter & Circuit Breaker (`ProviderRateLimiter`)
- Serializes request start times per provider (`1,150 ms` TON Center; `4,250 ms` TonAPI), including internal multi-step sub-calls via `requestPacer`.
- Exponential backoff on failures: `baseBackoffMs = 400 ms` up to `maxBackoffMs = 8,000 ms` with `[0.75, 1.25]` jitter, honoring HTTP 429 `Retry-After` headers.
- Opens a provider's circuit after `failureThresholdToOpenCircuit = 2` consecutive errors (or immediately on HTTP `429`) for `openCircuitBaseMs = 5,000 ms` exponentially capped at `48,000 ms`.
- **Half-Open Fallback**: If all providers have open circuits, probes the single provider whose cooldown expires soonest.
- **Operation-Specific Routing (`ProviderRouter`)**:
  - `estimateExternalMessageFee` sets `openCircuitOnNonRateLimit = false` so emulation rejections on undeployed wallets do not trip the provider circuit for real transactions.
  - `sendBoc` sets `failoverOnRejection = true`, trying the backup provider even if the first rejects HTTP 400/422, returning `RejectedTransactionException` only if all candidate providers reject.

### 8.3 Deposit Classification & Comment Decoding
- Both `TonCenterClient` and `TonApiClient` inspect `credit_ph` (`credit_phase`), `compute_ph` (`compute_phase` skipped/`no_state`), and `bounce` (`bounce_phase`) across both top-level and `description` JSON objects.
- Incoming non-bounceable deposits to an uninitialized wallet (where TVM compute is skipped with `no_state` and TON Center often marks `aborted = true`) are accurately recorded as **`CONFIRMED`** whenever `credit > 0` or `((computeSkippedNoState || aborted) && !msg.bounce)`, provided no bounce executed (`!bounceExecuted`).
- `TonCommentDecoder` parses raw BOC hex/Base64 comment payloads (`0x00000000` opcode + up to 4 reference cell hops) whenever an indexer does not provide a pre-decoded text field, stripping invalid control characters and capping length at 240 chars.

### 8.4 Display-Only Fiat Rates (`PriceService`)
- Fetches `TON` and `USDT` USD prices from `tonapi.io/v2/rates` with fallback to `api.coingecko.com/api/v3/simple/price`, cached for `90_000 ms`.
- Defaults USDT rate to `1.00` USD if TON price succeeds without an explicit USDT quote. Never used for transaction validation, signing, or thresholds.

---

## 9. Transaction Lifecycle, Crash Recovery & History Sync (`core/tx/TxManager.kt`)

All mutating ledger and reconciliation operations acquire `txMutex: Mutex` inside `TxManager` to guarantee ordering and eliminate double-spend races.

### 9.1 Send & Atomic Persistence Flow
```text
prepareTransfer()
  ├── validate address/URI + amount + UTF-8 comment (<= 120B)
  ├── ensureNoActivePendingTx()  <-- reconciles non-terminal rows first; blocks concurrent sends
  ├── fetchTransferChainContext() <-- sender balance/status/seqno + destination status
  ├── fetchJettonChainContext()   <-- (USDT only) verifies sender Jetton wallet & USDT balance
  └── estimateTransferFee()       <-- dummy-signature trace emulation (or local fallback)
        |
        v
confirmAndSend()
  ├── re-verify unlocked wallet, network match, preview validUntil, user auth / PIN
  ├── ensureNoActivePendingTx()
  ├── SecureKeyStore.withDecryptedSeed { TransferBuilder.buildSigned... }
  └── TxManager.persistSignedAndBroadcast(record, bocBase64):
        1. txDao.persistSignedAtomic() -> saves SIGNED + bocBase64 to Room BEFORE network I/O
        2. chainGateway.sendBoc()
           ├── Success       -> state = BROADCASTED, bocBase64 = NULL -> startTracking()
           ├── Rejected (4xx)-> state = FAILED,      bocBase64 = NULL -> return Failure
           └── Transient Err -> keeps state = SIGNED + bocBase64 in Room -> startTracking() auto-retries
```

### 9.2 Background Polling & Reconciliation (`reconcileEntityLocked`)
- `startTracking` polls non-terminal transactions (`SIGNED` / `BROADCASTED`) every `3,500 ms` up to `45` attempts (~157.5 seconds).
- At each step (and on `unlockWallet()`, `switchNetwork()`, `recoverPendingTransactions()`, `refreshTransactionStatus()`):
  1. Queries `lookupMessageStatus(normalizedHash, messageHash)` -> if `CONFIRMED` or `FAILED`, marks terminal with `txHash`, `lt`, `fee`, clears `bocBase64`, and forces `balanceRepository.refreshBalance(force = true)`.
  2. Queries `fetchWalletInfo` seqno:
     - If `walletInfo.seqno > entity.seqno`:
       - If `BROADCASTED` and elapsed >= `SEQNO_CONFIRM_LAG_SECONDS` (`8s`), marks `CONFIRMED` (handles indexer lag).
       - If `SIGNED` (never broadcasted) and past `validUntil + EXPIRY_GRACE_SECONDS` (`15s`), marks `EXPIRED`.
     - If `walletInfo.seqno <= entity.seqno` and past `validUntil + 15s`, marks `EXPIRED`.
     - Otherwise, if still `SIGNED`, `allowRebroadcast == true`, `bocBase64` is present, and `now < validUntil`, safely re-broadcasts the identical signed BOC (preserving exact `seqno` and preventing double spends).

### 9.3 Incremental History Sync & Reconciliation (`syncAccountHistory`)
- **Zero-Cost Cache Shortcut**: If local DB already has chain-synced rows (`lt != null`), `countFailedIncoming == 0`, no pending local transactions exist, and `knownAccountLt <= latestLocalLt`, sync returns immediately from `"Cache"` with **0 history HTTP requests**.
  - On the first sync per `(publicKey, network)` session (`!baselineDone` in `WalletViewModel`) or explicit pull-to-refresh (`ignoreLtShortcut = true`), the shortcut is bypassed so historical/failed deposits or Jetton transfers are fetched cleanly.
- **Deduplication & Healing**:
  - Outgoing GRAM records created locally as `SIGNED`/`BROADCASTED` are matched against chain transactions via `inMsgHash` / `inMsgNormalizedHash` (`txDao.findByMessageHashes`) and promoted cleanly with chain `txHash`, `lt`, and actual `fee`.
  - Incoming GRAM records previously mis-cached as `FAILED` get healed to `CONFIRMED` on resync (`countFailedIncoming > 0` forces re-check).
  - Outgoing USDT transfers are matched/deduplicated against local Jetton rows by `txHash` OR `(counterpartyRaw, amountUnits)` within a `±180,000 ms` window (`countMatchingOutgoingJetton`).
  - Unmatched chain records use deterministic primary keys (`chain_<net>_<lt>_<txHash16>_<dir>_<seq>` and `jetton_<net>_<lt>_<txHash16>_<dir>_<seq>`) upserted inside `@Transaction applyHistorySyncAtomic`.

---

## 10. Local Persistence Schema & Cache Policy (`core/data/*`)

- **Room Database (`GramVaultDatabase` v4, `gramvault_cache.db`)**:
  - `balance_cache`: PK `(accountAddr, network, assetId)`. Stores `amountUnits` (integer string), `decimals`, `accountStatus`, `providerName`, `fetchedAt`, `extraAddress` (sender USDT Jetton wallet), `lastTxLt`. TTL = `25,000 ms` (`BalanceRepository.DEFAULT_CACHE_TTL_MS`). `BalanceRepository.observeBalance` combines GRAM + USDT rows + in-memory `FetchStatus`, exposing `isStale` without wiping cached balances on network errors.
  - `tx`: PK `id`, indexed on `(accountRaw, network, createdAtEpochMs)`, `(accountRaw, network, state)`, and `(accountRaw, network, seqno)`.
  - `address_book`: PK `id`, unique index on `(network, rawAddress)`. Enforces label `1..40` chars and network-strict `TonAddress` validation (`AddressBookRepository`).
  - Includes explicit `MIGRATION_3_4` alongside `fallbackToDestructiveMigration(dropAllTables = true)` for pre-v3 dev schemas.
- **DataStore (`AppSettingsStore`, `"gramvault_settings"`)**:
  - `auto_lock_on_background` (Boolean) and `live_monitor_enabled` (Boolean, default `true`).

---

## 11. Foreground Live Monitor & Lifecycle Coupling (`WalletViewModel.kt`, `MainActivity.kt`)

- Because GramVault has no backend server or push infrastructure, real-time incoming detection uses a **foreground-only coroutine poller** (`MONITOR_POLL_INTERVAL_MS = 10_000L`, 10 seconds):
  - Starts only when: `vaultState is VaultState.Unlocked`, Activity is at least `STARTED` (`ON_START`), `liveMonitorEnabled == true`, and `subScreen` is neither `SEND_FORM` nor `SEND_CONFIRM` (`pauseForSend`, preventing form disruption or seqno drift mid-preparation).
  - Each cycle runs: `refreshBalance(force = true)` -> `refreshRatesIfNeeded(force = false)` -> `syncTransactionHistory(...)` -> `recoverPendingTransactions()`. Emits an incoming deposit banner when new confirmed incoming transfers appear.
  - On `ON_STOP`: Immediately stops polling (`setForegroundMonitoring(false)`) and, if `autoLockOnBackground && !isBusy && pinPromptMode == null`, calls `lockWallet()` (clearing `activePinSessionKey` and setting `VaultState.Locked`).

---

## 12. Known Boundaries & Implemented Scope Limitations

1. **No Backend / No Push Notifications**: Transaction monitoring stops when backgrounded (`ON_STOP`). Uses public unauthenticated TON Center / TonAPI tiers by default, governed by `ProviderRateLimiter` client-side pacing.
2. **Asset Scope**: Native **GRAM (TON)** and **Tether USD (USDT, NEP/TEP-74 allowlisted master only)**. Arbitrary Jettons and NFTs are intentionally excluded/filtered.
3. **Single-Message Outgoing Transfers**: Builds 1 internal message per external wallet transaction (`sendMode = 3`), single-cell UTF-8 text comments up to `120` bytes, `120s` `validUntil` TTL, and fixed `0.055 GRAM` attached gas for USDT Jetton transfers.
4. **No In-App Staking / Swap / TON Connect dApp Session UI**: `WalletKitBridge` registers `SendTransactionFeature` and a bridge manifest placeholder (`https://gramvault.example`), but no dApp browser, WalletConnect/TON Connect QR pairing, swap, or staking flows are exposed in `WalletCore` or `WalletViewModel`.
5. **Account Versioning**: Single active account keypair (`m/44'/607'/0'` or legacy 24-word seed 0) selected during wallet creation (`V5R1`) or import discovery (`V5R1`, `V4R2`, `V3R2`, `V3R1` at workchain `0`); multi-subwallet switching or Ledger/hardware-signer pairing is not implemented.

---

## 13. Automated Verification Coverage (Verified in Repo)

All 57 unit and Robolectric tests under `app/src/test/java/com/example/` pass (`gradle :app:testDebugUnitTest`):
- **`ExampleUnitTest.kt` (25 tests)**: BIP-39 12/24 validation & SLIP-0010 `m/44'/607'/0'`, TON legacy 24-word KDF, V5R1/V4R2/V3R2/V3R1 StateInit & `codeHashHex`, RFC-8032 Ed25519 external message verification, TON address CRC16 & network isolation, `Money` arithmetic, `ton://transfer/` URI parsing, `TonCenterClient` v3->v2 fallback & aborted non-bounceable incoming deposit classification, `ProviderRateLimiter` & `ProviderRouter` failover/circuit rules.
- **`ExampleRobolectricTest.kt` (28 tests)**: `DEVICE_KEYSTORE` & `APP_PIN_WITH_KEYSTORE` encryption/decryption & restart locking, `DEV_UNAUTH_KEYSTORE` gating, ambiguous 24-word import discovery & UI, Room cache + staleness in `BalanceRepository`, `TxManager` persist-before-broadcast / duplicate blocking / crash recovery / incoming `FAILED`-to-`CONFIRMED` healing, USDT Jetton `prepareTransfer` + TEP-74 signing, `AddressBookRepository` & `AppSettingsStore` persistence, and Compose UI rendering checks.
- **`Phase7And8UnitTest.kt` (4 tests)**: `JettonAllowList` verification, full round-trip TL-B decode verification of signed TEP-74 Jetton V5R1/V4R2 BOCs, `TonCenterClient`/`TonApiClient` Jetton balance/spoofed-decimals rejection/`is_scam` filtering/`last_transaction_lt` parsing, and `PriceService` TonAPI -> CoinGecko fallback + TTL caching.

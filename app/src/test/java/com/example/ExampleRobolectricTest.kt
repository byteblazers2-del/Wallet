package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.data.AddressBookRepository
import com.example.core.data.BalanceRepository
import com.example.core.data.GramVaultDatabase
import com.example.core.keystore.AndroidKeyStoreCipherProvider
import com.example.core.keystore.AuthGate
import com.example.core.keystore.KeystoreCipherProvider
import com.example.core.keystore.SecureKeyStore
import com.example.core.keystore.VaultAuthRequiredException
import com.example.core.keystore.VaultKeyInvalidatedException
import com.example.core.keystore.VaultWrongPinException
import com.example.core.model.AccountOnChainStatus
import com.example.core.model.AllowedJettonSpec
import com.example.core.model.AuthReason
import com.example.core.model.AuthResult
import com.example.core.model.BalanceState
import com.example.core.model.ChainMonitorState
import com.example.core.model.JettonAllowList
import com.example.core.model.MnemonicPhrase
import com.example.core.model.MnemonicScheme
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.RemoteAccountBalance
import com.example.core.model.RemoteJettonBalance
import com.example.core.model.RemoteJettonHistoryPage
import com.example.core.model.RemoteJettonTransfer
import com.example.core.model.TonAddress
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.TxStatus
import com.example.core.model.USDT_DECIMALS
import com.example.core.model.VaultMetadata
import com.example.core.model.VaultSecurityMode
import com.example.core.model.VaultState
import com.example.core.model.WalletVersion
import com.example.core.network.BalanceFetcher
import com.example.core.network.BroadcastResult
import com.example.core.network.ChainGateway
import com.example.core.network.FiatRates
import com.example.core.network.OnChainMessageStatus
import com.example.core.network.RateLimitedException
import com.example.core.network.RemoteChainTransfer
import com.example.core.network.RemoteFeeEstimate
import com.example.core.network.RemoteTxHistoryPage
import com.example.core.network.RemoteTxLookup
import com.example.core.network.RemoteWalletInfo
import com.example.core.network.TransportException
import com.example.core.tx.PendingTransferConflictException
import com.example.core.tx.TxManager
import com.example.core.wallet.AddressService
import com.example.core.wallet.AppSettingsStore
import com.example.core.wallet.MnemonicService
import com.example.core.wallet.VaultMetadataStore
import com.example.core.wallet.WalletCoreImpl
import com.example.core.wallet.WalletKitBridge
import com.example.ui.screens.AddressBookScreen
import com.example.ui.screens.ImportSelectAccountScreen
import com.example.ui.screens.LockedVaultScreen
import com.example.ui.screens.ReceiveSheetContent
import com.example.ui.screens.RecoveryPhraseScreen
import com.example.ui.screens.SendConfirmScreen
import com.example.ui.screens.SendFormScreen
import com.example.ui.screens.SendStatusScreen
import com.example.ui.screens.TransactionHistoryScreen
import com.example.ui.screens.WalletHomeScreen
import com.example.ui.screens.WelcomeCreateScreen
import com.example.ui.theme.GramVaultTheme
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun read_app_name_from_context() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("GramVault", appName)
  }

  @Test
  fun welcome_screen_without_system_lock_keeps_create_button_enabled() {
    composeTestRule.setContent {
      GramVaultTheme {
        WelcomeCreateScreen(
          isDeviceSecure = false,
          isBusy = false,
          errorMessage = null,
          onCreateWallet = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("pin_fallback_info_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("welcome_create_wallet_button").assertIsEnabled()
  }

  @Test
  fun mainnet_home_renders_wallet_card_and_assets_without_testnet_banner() {
    val metadata =
      VaultMetadata(
        publicKeyHex = "00".repeat(32),
        mainnetRaw = "0:" + "00".repeat(32),
        testnetRaw = "0:" + "00".repeat(32),
        isBackedUp = false,
        createdAtEpochMs = 1_700_000_000_000L,
      )
    composeTestRule.setContent {
      GramVaultTheme {
        WalletHomeScreen(
          metadata = metadata,
          activeNetwork = Network.MAINNET,
          bannerMessage = null,
          onClearBanner = {},
          onSelectNetwork = {},
          onOpenBackup = {},
          onLockWallet = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("testnet_banner").assertDoesNotExist()
    composeTestRule.onNodeWithTag("backup_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("wallet_hero_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("assets_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Gram").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Tether USD").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun testnet_mode_displays_testnet_banner() {
    val metadata =
      VaultMetadata(
        publicKeyHex = "00".repeat(32),
        mainnetRaw = "0:" + "00".repeat(32),
        testnetRaw = "0:" + "00".repeat(32),
        isBackedUp = true,
        createdAtEpochMs = 1_700_000_000_000L,
      )
    composeTestRule.setContent {
      GramVaultTheme {
        WalletHomeScreen(
          metadata = metadata,
          activeNetwork = Network.TESTNET,
          bannerMessage = null,
          onClearBanner = {},
          onSelectNetwork = {},
          onOpenBackup = {},
          onLockWallet = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("testnet_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("backup_warning_banner").assertDoesNotExist()
    composeTestRule.onNodeWithTag("backed_up_badge").assertIsDisplayed()
  }

  @Test
  fun recovery_phrase_and_locked_screens_render() {
    val sampleWords = List(12) { idx -> "word${idx + 1}" }
    composeTestRule.setContent {
      GramVaultTheme {
        RecoveryPhraseScreen(
          words = sampleWords,
          isAlreadyBackedUp = false,
          onBack = {},
          onProceedToVerify = {},
          onSkipBackup = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("mnemonic_word_1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mnemonic_word_12").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("proceed_backup_verify_button").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun locked_screen_renders_unlock_button() {
    composeTestRule.setContent {
      GramVaultTheme {
        LockedVaultScreen(
          isKeyInvalidated = false,
          isBusy = false,
          errorMessage = null,
          onUnlock = {},
          onResetVault = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("unlock_wallet_button").assertIsDisplayed()
  }

  @Test
  fun vault_supports_device_keystore_auth_gating_and_restart_lock() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()

    val fakeAuth = FakeAuthGate(deviceSecure = true, nextResult = AuthResult.Authenticated)
    val fakeCipher = InMemoryAesCipherProvider()
    val keyStore = SecureKeyStore(context, fakeCipher)
    val metaStore = VaultMetadataStore(context)

    val core = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)
    assertEquals(VaultState.Uninitialized, core.vaultState.value)

    fakeCipher.isAuthenticated = true
    val created = core.createWallet(Network.MAINNET).getOrThrow()
    val firstWords = (0 until created.mnemonic.wordCount).map { String(created.mnemonic.wordAt(it)) }
    assertEquals(12, firstWords.size)
    assertTrue(keyStore.hasVault())

    val rawCipherFile = File(context.filesDir, "vault/vault_seed.bin").readBytes()
    val cipherAscii = rawCipherFile.decodeToString()
    assertFalse(firstWords.any { cipherAscii.contains(it) })

    val challenge = core.createBackupChallenge()
    val answers = challenge.indicesOneBased.associateWith { i -> created.mnemonic.wordAt(i - 1) }
    assertTrue(core.confirmBackup(challenge, answers).getOrThrow())
    created.mnemonic.wipe()
    val unlockedState = core.vaultState.value as VaultState.Unlocked
    assertEquals(VaultSecurityMode.DEVICE_KEYSTORE, unlockedState.metadata.securityMode)
    assertTrue(unlockedState.metadata.isBackedUp)

    fakeCipher.isAuthenticated = false
    fakeAuth.nextResult = AuthResult.Cancelled
    val restartedCore = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)
    assertTrue(restartedCore.vaultState.value is VaultState.Locked)
    assertTrue(restartedCore.unlockWallet(Network.MAINNET).isFailure)

    fakeAuth.nextResult = AuthResult.Authenticated
    fakeCipher.isAuthenticated = true
    val unlockedMeta = restartedCore.unlockWallet(Network.MAINNET).getOrThrow()
    assertEquals(created.publicKeyHex, unlockedMeta.publicKeyHex)
    assertEquals(created.mainnetAddress.raw, unlockedMeta.addressFor(Network.MAINNET).raw)
    assertEquals(created.testnetAddress.raw, unlockedMeta.addressFor(Network.TESTNET).raw)

    fakeCipher.keyInvalidated = true
    assertTrue(restartedCore.unlockWallet(Network.MAINNET).isFailure)
    assertTrue(restartedCore.vaultState.value is VaultState.KeyInvalidated)
  }

  @Test
  fun app_pin_fallback_encrypts_vault_and_enforces_pin_on_restart() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()

    val fakeAuth = FakeAuthGate(deviceSecure = false, nextResult = AuthResult.NoDeviceSecurity)
    val fakeCipher = InMemoryAesCipherProvider()
    val keyStore = SecureKeyStore(context, fakeCipher)
    val metaStore = VaultMetadataStore(context)

    val core = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)
    assertTrue(core.createWallet(Network.MAINNET, appPin = null).isFailure)

    val created = core.createWallet(Network.MAINNET, appPin = "2468".toCharArray()).getOrThrow()
    val words = (0 until created.mnemonic.wordCount).map { String(created.mnemonic.wordAt(it)) }
    val rawCipherFile = File(context.filesDir, "vault/vault_seed.bin").readBytes().decodeToString()
    assertFalse(words.any { rawCipherFile.contains(it) })
    created.mnemonic.wipe()

    val restartedCore = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)
    assertTrue(restartedCore.vaultState.value is VaultState.Locked)

    val wrongPinResult = restartedCore.unlockWallet(Network.MAINNET, appPin = "9999".toCharArray())
    assertTrue(wrongPinResult.exceptionOrNull() is VaultWrongPinException)
    assertTrue(restartedCore.vaultState.value is VaultState.Locked)

    val okMeta = restartedCore.unlockWallet(Network.MAINNET, appPin = "2468".toCharArray()).getOrThrow()
    assertEquals(VaultSecurityMode.APP_PIN_WITH_KEYSTORE, okMeta.securityMode)
    assertEquals(created.publicKeyHex, okMeta.publicKeyHex)
  }

  @Test
  fun import_discovers_candidates_without_autopicking_and_persists_selected_version() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()

    val fakeAuth = FakeAuthGate(deviceSecure = true, nextResult = AuthResult.Authenticated)
    val fakeCipher = InMemoryAesCipherProvider()
    val keyStore = SecureKeyStore(context, fakeCipher)
    val metaStore = VaultMetadataStore(context)
    val core = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)

    val chars12 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray()
    val phrase = MnemonicService.parseSpaceSeparatedChars(chars12)
    chars12.fill('\u0000')
    try {
      val discovery = core.discoverImportAccounts(phrase).getOrThrow()
      assertFalse(discovery.isAmbiguous24)
      assertEquals(4, discovery.candidates.size)
      assertEquals(VaultState.Uninitialized, core.vaultState.value)
      assertFalse(keyStore.hasVault())

      val v4r2Candidate = discovery.candidates.first { it.version == WalletVersion.V4R2 }
      val savedMeta = core.selectImportedAccount(phrase, v4r2Candidate, Network.MAINNET).getOrThrow()
      assertEquals(WalletVersion.V4R2, savedMeta.walletVersion)
      assertEquals(MnemonicScheme.MULTICHAIN, savedMeta.mnemonicScheme)
      assertTrue(savedMeta.isBackedUp)
      assertEquals(v4r2Candidate.mainnetAddress.raw, savedMeta.mainnetRaw)
    } finally {
      phrase.wipe()
    }

    val restartedCore = WalletCoreImpl(context, fakeAuth, keyStore, metaStore, walletKitBridge = null)
    assertTrue(restartedCore.vaultState.value is VaultState.Locked)
    val unlocked = restartedCore.unlockWallet(Network.TESTNET).getOrThrow()
    assertEquals(WalletVersion.V4R2, unlocked.walletVersion)
    assertEquals(MnemonicScheme.MULTICHAIN, unlocked.mnemonicScheme)
  }

  @Test
  fun import_ambiguous_24_word_ui_shows_warning_and_requires_explicit_selection() {
    val ambiguousWords =
      ("bunker present derive member useless upset corn measure obey void problem desk " +
        "crack jelly large caught vendor mean purpose library secret deliver patient frame")
        .toCharArray()
    val phrase = MnemonicService.parseSpaceSeparatedChars(ambiguousWords)
    ambiguousWords.fill('\u0000')
    val candidates =
      try {
        MnemonicService.detectValidSchemes(phrase).flatMap { scheme ->
          val key = MnemonicService.deriveKeyForScheme(phrase, scheme)
          try {
            AddressService.discoverCandidateAccounts(key.publicKey32, scheme)
          } finally {
            key.wipe()
          }
        }
      } finally {
        phrase.wipe()
      }

    var selectedId: String? = null
    composeTestRule.setContent {
      GramVaultTheme {
        ImportSelectAccountScreen(
          candidates = candidates,
          isAmbiguous24 = true,
          isBusy = false,
          errorMessage = null,
          onBack = {},
          onConfirmAccount = { selectedId = it.id },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("import_ambiguous_24_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("import_confirm_account_button").performScrollTo().assertIsNotEnabled()

    val targetCardTag = "candidate_card_${MnemonicScheme.TON_LEGACY.name}_${WalletVersion.V4R2.name}"
    composeTestRule.onNodeWithTag(targetCardTag).performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("import_confirm_account_button").performScrollTo().assertIsEnabled().performClick()

    assertEquals("${MnemonicScheme.TON_LEGACY.name}_${WalletVersion.V4R2.name}", selectedId)
  }

  @Test
  fun balance_repository_cache_then_refresh_marks_stale_on_failure_and_persists_text_units() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db =
        Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java)
          .allowMainThreadQueries()
          .build()
      try {
        var nowMs = 1_000_000L
        var nextFetchResult: Result<RemoteAccountBalance> =
          Result.success(
            RemoteAccountBalance(
              address = TonAddress("0:" + "33".repeat(32), Network.TESTNET),
              balance = Money.ofNano(12_340_000_000L),
              status = AccountOnChainStatus.ACTIVE,
              providerName = "TonAPI",
              fetchedAtEpochMs = nowMs,
            )
          )
        val fetcher =
          object : BalanceFetcher {
            override suspend fun fetchAccountBalance(address: TonAddress) = nextFetchResult

            override suspend fun fetchJettonBalance(
              ownerAddress: TonAddress,
              spec: AllowedJettonSpec,
            ): Result<RemoteJettonBalance> =
              Result.success(
                RemoteJettonBalance(
                  ownerAddress = ownerAddress,
                  jettonMaster = spec.masterAddress,
                  jettonWalletAddress = null,
                  balance = Money.zero(USDT_DECIMALS),
                  providerName = "TonAPI",
                  fetchedAtEpochMs = nowMs,
                )
              )
          }
        val repo =
          BalanceRepository(
            balanceDao = db.balanceCacheDao(),
            balanceFetcher = fetcher,
            clockMs = { nowMs },
            cacheTtlMs = 25_000L,
          )
        val address = TonAddress("0:" + "33".repeat(32), Network.TESTNET)

        assertTrue(repo.refreshBalance(address).isSuccess)
        val cachedEntity =
          db.balanceCacheDao().getBalance(address.raw, Network.TESTNET.name, "GRAM")!!
        assertEquals("12340000000", cachedEntity.amountUnits)

        val freshState = repo.observeBalance(address).first()
        assertEquals("12.34", freshState.gramBalance?.format())
        assertFalse(freshState.isStale)

        // Advance past TTL and simulate provider 429 failure -> cached value preserved with stale flag
        nowMs += 30_000L
        nextFetchResult = Result.failure(RateLimitedException("TonAPI", retryAfterMs = 1_000L))
        assertTrue(repo.refreshBalance(address, force = true).isFailure)

        val staleState = repo.observeBalance(address).first()
        assertEquals("12.34", staleState.gramBalance?.format())
        assertTrue(staleState.isStale)
        assertTrue(staleState.errorMessage?.isNotBlank() == true)

        // Recovery refresh clears stale flag and updates amount
        nextFetchResult =
          Result.success(
            RemoteAccountBalance(
              address = address,
              balance = Money.ofNano(25_500_000_000L),
              status = AccountOnChainStatus.ACTIVE,
              providerName = "TON Center",
              fetchedAtEpochMs = nowMs,
            )
          )
        assertTrue(repo.refreshBalance(address, force = true).isSuccess)
        val recoveredState = repo.observeBalance(address).first()
        assertEquals("25.50", recoveredState.gramBalance?.format())
        assertFalse(recoveredState.isStale)
        assertEquals("TON Center", recoveredState.providerName)

        repo.clearCache()
        assertEquals(null, db.balanceCacheDao().getBalance(address.raw, Network.TESTNET.name, "GRAM"))
      } finally {
        db.close()
      }
    }

  @Test
  fun wallet_home_displays_formatted_balance_stale_badge_and_refresh_action() {
    val metadata =
      VaultMetadata(
        publicKeyHex = "00".repeat(32),
        mainnetRaw = "0:" + "00".repeat(32),
        testnetRaw = "0:" + "00".repeat(32),
        isBackedUp = true,
        createdAtEpochMs = 1_700_000_000_000L,
      )
    var refreshCount = 0
    val staleBalance =
      BalanceState(
        network = Network.TESTNET,
        address = metadata.addressFor(Network.TESTNET),
        gramBalance = Money.ofNano(12_345_000_000L),
        accountStatus = AccountOnChainStatus.ACTIVE,
        isLoading = false,
        isStale = true,
        fetchedAtEpochMs = 1_700_000_000_000L,
        providerName = "TonAPI",
        errorMessage = "Network rate limit reached. Try again shortly.",
      )

    composeTestRule.setContent {
      GramVaultTheme {
        WalletHomeScreen(
          metadata = metadata,
          activeNetwork = Network.TESTNET,
          bannerMessage = null,
          onClearBanner = {},
          onSelectNetwork = {},
          onOpenBackup = {},
          onLockWallet = {},
          balanceState = staleBalance,
          onRefreshBalance = { refreshCount++ },
        )
      }
    }

    composeTestRule.onNodeWithTag("hero_balance_text").assertIsDisplayed().assertTextEquals("12.345 GRAM")
    composeTestRule.onNodeWithTag("asset_gram_balance").performScrollTo().assertIsDisplayed().assertTextEquals("12.345 GRAM")
    composeTestRule.onNodeWithTag("balance_stale_badge").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("balance_error_banner").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("refresh_balance_button").performScrollTo().performClick()
    assertEquals(1, refreshCount)
  }

  @Test
  fun receive_sheet_builds_transfer_link_and_validates_addresses() {
    val testnetAddr = TonAddress("0:" + "55".repeat(32), Network.TESTNET)
    val friendlyNonBounce = testnetAddr.toFriendly(bounceable = false)
    val testnetBounce = testnetAddr.toFriendly(bounceable = true)
    val mainnetNonBounce = TonAddress(testnetAddr.raw, Network.MAINNET).toFriendly(bounceable = false)

    composeTestRule.setContent {
      GramVaultTheme {
        ReceiveSheetContent(
          network = Network.TESTNET,
          friendlyAddress = friendlyNonBounce,
          rawAddress = testnetAddr.raw,
          onCopyAddress = { _, _, _ -> },
          onSharePayload = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("receive_full_address_text").assertTextEquals(friendlyNonBounce)
    composeTestRule.onNodeWithTag("receive_raw_address_text").assertTextEquals(testnetAddr.raw)

    // Switch to Transfer Link mode and enter amount + comment
    composeTestRule.onNodeWithTag("qr_mode_link_button").performClick()
    composeTestRule.onNodeWithTag("receive_amount_input").performScrollTo().performTextInput("2.5")
    composeTestRule.onNodeWithTag("receive_comment_input").performScrollTo().performTextInput("Coffee")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("receive_transfer_uri_text")
      .performScrollTo()
      .assertTextContains("ton://transfer/$friendlyNonBounce?amount=2500000000&text=Coffee")

    // Invalid amount triggers inline validation error without crash
    composeTestRule.onNodeWithTag("receive_amount_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("receive_amount_input").performTextInput("0")
    composeTestRule.onNodeWithTag("receive_amount_error").performScrollTo().assertIsDisplayed()

    // Open Address Validity Checker and verify valid Testnet bounceable vs Mainnet mismatch vs bad checksum
    composeTestRule.onNodeWithTag("address_checker_toggle").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("address_checker_input").performScrollTo().performTextInput(testnetBounce)
    composeTestRule.onNodeWithTag("address_checker_result")
      .performScrollTo()
      .assertTextContains("Valid Testnet address (Friendly • Bounceable)", substring = true)

    composeTestRule.onNodeWithTag("address_checker_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("address_checker_input").performTextInput(mainnetNonBounce)
    composeTestRule.onNodeWithTag("address_checker_result")
      .performScrollTo()
      .assertTextContains("Mainnet address is not allowed on Testnet", substring = true)

    val badChecksum = testnetBounce.dropLast(1) + if (testnetBounce.last() == 'A') 'B' else 'A'
    composeTestRule.onNodeWithTag("address_checker_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("address_checker_input").performTextInput(badChecksum)
    composeTestRule.onNodeWithTag("address_checker_result")
      .performScrollTo()
      .assertTextContains("Invalid TON address checksum", substring = true)
  }

  @Test
  fun dev_skip_auth_mode_creates_unlocks_and_reveals_backup_without_pin_or_biometric() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()

    val fakeAuth = FakeAuthGate(deviceSecure = false, nextResult = AuthResult.NoDeviceSecurity)
    val fakeCipher = InMemoryAesCipherProvider().apply { isAuthenticated = false }
    val keyStore = SecureKeyStore(context, fakeCipher)
    val metaStore = VaultMetadataStore(context)

    val core =
      WalletCoreImpl(
        applicationContext = context,
        authGate = fakeAuth,
        secureKeyStore = keyStore,
        metadataStore = metaStore,
        walletKitBridge = null,
        devSkipAuth = true,
      )
    assertEquals(VaultSecurityMode.DEV_UNAUTH_KEYSTORE, core.activeSecurityMode())

    val created = core.createWallet(Network.MAINNET, appPin = null).getOrThrow()
    val unlockedState = core.vaultState.value as VaultState.Unlocked
    assertEquals(VaultSecurityMode.DEV_UNAUTH_KEYSTORE, unlockedState.metadata.securityMode)

    val revealed = core.revealRecoveryPhrase(appPin = null).getOrThrow()
    assertEquals(12, revealed.wordCount)
    revealed.wipe()

    val challenge = core.createBackupChallenge()
    val answers = challenge.indicesOneBased.associateWith { i -> created.mnemonic.wordAt(i - 1) }
    assertTrue(core.confirmBackup(challenge, answers, appPin = null).getOrThrow())
    created.mnemonic.wipe()

    val restartedCore =
      WalletCoreImpl(
        applicationContext = context,
        authGate = fakeAuth,
        secureKeyStore = keyStore,
        metadataStore = metaStore,
        walletKitBridge = null,
        devSkipAuth = true,
      )
    assertTrue(restartedCore.vaultState.value is VaultState.Locked)
    val unlockedMeta = restartedCore.unlockWallet(Network.MAINNET, appPin = null).getOrThrow()
    assertEquals(VaultSecurityMode.DEV_UNAUTH_KEYSTORE, unlockedMeta.securityMode)
    assertEquals(created.publicKeyHex, unlockedMeta.publicKeyHex)
    assertTrue(unlockedMeta.isBackedUp)
  }

  private class FakeAuthGate(
    var deviceSecure: Boolean,
    var nextResult: AuthResult,
  ) : AuthGate {
    override fun isDeviceSecure(): Boolean = deviceSecure

    override suspend fun authenticate(reason: AuthReason): AuthResult = nextResult
  }

  private class InMemoryAesCipherProvider : KeystoreCipherProvider {
    private var authKeyBytes: ByteArray? = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var hwKeyBytes: ByteArray? = ByteArray(32).also { SecureRandom().nextBytes(it) }
    var isAuthenticated: Boolean = true
    var keyInvalidated: Boolean = false

    override fun ensureKeyExists(requireUserAuth: Boolean) {
      if (requireUserAuth && authKeyBytes == null) {
        authKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      }
      if (!requireUserAuth && hwKeyBytes == null) {
        hwKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      }
    }

    override fun encrypt(plaintext: ByteArray, requireUserAuth: Boolean): ByteArray {
      if (keyInvalidated) throw VaultKeyInvalidatedException()
      if (requireUserAuth && !isAuthenticated) throw VaultAuthRequiredException()
      ensureKeyExists(requireUserAuth)
      val key = if (requireUserAuth) authKeyBytes else hwKeyBytes
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
      return AndroidKeyStoreCipherProvider.encodeEnvelope(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(envelope: ByteArray, requireUserAuth: Boolean): ByteArray {
      if (keyInvalidated) throw VaultKeyInvalidatedException()
      if (requireUserAuth && !isAuthenticated) throw VaultAuthRequiredException()
      val key = (if (requireUserAuth) authKeyBytes else hwKeyBytes) ?: throw VaultKeyInvalidatedException()
      val (iv, ct) = AndroidKeyStoreCipherProvider.decodeEnvelope(envelope)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
      return cipher.doFinal(ct)
    }

    override fun deleteKey(requireUserAuth: Boolean) {
      if (requireUserAuth) authKeyBytes = null else hwKeyBytes = null
    }
  }

  @Test
  fun send_gram_sets_bounce_from_dest_state_persists_signed_before_broadcast_blocks_dups_and_recovers() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      File(context.filesDir, "vault").deleteRecursively()
      val db =
        Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java)
          .allowMainThreadQueries()
          .build()
      try {
        var nowMs = 1_700_000_000_000L
        val fakeAuth = FakeAuthGate(deviceSecure = true, nextResult = AuthResult.Authenticated)
        val fakeCipher = InMemoryAesCipherProvider().apply { isAuthenticated = true }
        val keyStore = SecureKeyStore(context, fakeCipher)
        val metaStore = VaultMetadataStore(context)

        val uninitDest = TonAddress("0:" + "11".repeat(32), Network.TESTNET)
        val activeDest = TonAddress("0:" + "22".repeat(32), Network.TESTNET)
        val gateway = FakeChainGateway(db = db, nowProvider = { nowMs })
        gateway.walletStatuses[uninitDest.raw] = AccountOnChainStatus.NONEXIST
        gateway.walletStatuses[activeDest.raw] = AccountOnChainStatus.ACTIVE
        gateway.senderBalance = Money.ofNano(50_000_000_000L)
        gateway.senderStatus = AccountOnChainStatus.ACTIVE
        gateway.senderSeqno = 3

        val txManager =
          TxManager(
            txDao = db.txDao(),
            chainGateway = gateway,
            clockMs = { nowMs },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
          )
        val core =
          WalletCoreImpl(
            applicationContext = context,
            authGate = fakeAuth,
            secureKeyStore = keyStore,
            metadataStore = metaStore,
            walletKitBridge = null,
            txManager = txManager,
            clockMs = { nowMs },
          )

        val created = core.createWallet(Network.TESTNET).getOrThrow()
        created.mnemonic.wipe()
        val senderRaw = created.testnetAddress.raw
        gateway.senderAddressRaw = senderRaw

        // 1. NONEXIST destination -> effectiveBounce MUST be false
        val previewUninit =
          core.prepareTransfer(uninitDest.toFriendly(bounceable = true), "2.5", "To uninit").getOrThrow()
        assertFalse(previewUninit.effectiveBounce)
        assertEquals(AccountOnChainStatus.NONEXIST, previewUninit.destinationStatus)
        assertEquals(3, previewUninit.seqno)
        assertFalse(previewUninit.senderNeedsStateInit)

        // 2. ACTIVE destination -> effectiveBounce MUST be true
        val previewActive =
          core.prepareTransfer(activeDest.toFriendly(bounceable = false), "2.5", "To active").getOrThrow()
        assertTrue(previewActive.effectiveBounce)
        assertEquals(AccountOnChainStatus.ACTIVE, previewActive.destinationStatus)

        // 3. Persist SIGNED in Room BEFORE broadcast, then BROADCASTED
        val sentTx = core.confirmAndSend(previewUninit).getOrThrow()
        assertEquals(TxStatus.BROADCASTED, sentTx.state)
        assertEquals("SIGNED", gateway.roomStateObservedDuringSendBoc)
        assertTrue(!gateway.bocObservedDuringSendBoc.isNullOrBlank())
        assertEquals(null, db.txDao().getById(sentTx.id)?.bocBase64)

        // 4. Duplicate send while active tx in progress is blocked
        val duplicate = core.prepareTransfer(activeDest.toFriendly(bounceable = false), "1.0")
        assertTrue(duplicate.isFailure)
        assertTrue(duplicate.exceptionOrNull() is PendingTransferConflictException)

        // 5. On-chain confirmation moves BROADCASTED -> CONFIRMED
        gateway.nextLookup =
          RemoteTxLookup(
            status = OnChainMessageStatus.CONFIRMED,
            txHash = "confirmed_hash_01",
            lt = "9001",
            fee = Money.ofNano(4_500_000L),
            providerName = "TonAPI",
          )
        val refreshed = core.refreshTransactionStatus(sentTx.id)!!
        assertEquals(TxStatus.CONFIRMED, refreshed.state)
        assertEquals("confirmed_hash_01", refreshed.txHash)

        // 6. Crash / transient broadcast failure leaves SIGNED in Room and recovers cleanly
        gateway.senderSeqno = 4
        gateway.nextLookup = RemoteTxLookup(OnChainMessageStatus.PENDING, null, null, null, "TonAPI")
        gateway.sendBocFailure = TransportException("TonAPI", IOException("network timeout"))
        val previewCrash = core.prepareTransfer(activeDest.toFriendly(false), "1.25").getOrThrow()
        val queuedTx = core.confirmAndSend(previewCrash).getOrThrow()
        assertEquals(TxStatus.SIGNED, queuedTx.state)
        assertTrue(!db.txDao().getById(queuedTx.id)?.bocBase64.isNullOrBlank())

        // Simulate app restart & recovery within valid_until -> rebroadcasts exact stored BOC
        gateway.sendBocFailure = null
        gateway.sendBocCount = 0
        core.recoverPendingTransactions()
        val afterRebroadcast = db.txDao().getById(queuedTx.id)!!
        assertEquals("BROADCASTED", afterRebroadcast.state)
        assertEquals(1, gateway.sendBocCount)

        // 7. After valid_until + grace expires and seqno has NOT advanced -> EXPIRED
        nowMs += 200_000L
        core.recoverPendingTransactions()
        val afterExpiry = db.txDao().getById(queuedTx.id)!!
        assertEquals("EXPIRED", afterExpiry.state)
      } finally {
        db.close()
      }
    }

  @Test
  fun send_ui_screens_render_destination_bounce_badge_and_status_correctly() {
    val dest = TonAddress("0:" + "66".repeat(32), Network.TESTNET)
    val preview =
      com.example.core.model.TransferPreview(
        network = Network.TESTNET,
        fromAddress = TonAddress("0:" + "55".repeat(32), Network.TESTNET),
        destination = com.example.core.model.ParsedAddress(dest, isRaw = false, isBounceable = false, isTestnetOnly = true),
        destinationStatus = AccountOnChainStatus.UNINIT,
        effectiveBounce = false,
        amount = Money.ofNano(2_500_000_000L),
        estimatedFee = Money.ofNano(5_000_000L),
        totalDebit = Money.ofNano(2_505_000_000L),
        comment = "Test note",
        seqno = 1,
        senderNeedsStateInit = true,
        validUntilEpochSec = 1_800_000_120L,
      )

    var confirmedClicks = 0
    composeTestRule.setContent {
      GramVaultTheme {
        SendConfirmScreen(
          preview = preview,
          isBusy = false,
          errorMessage = null,
          onConfirmAndSend = { confirmedClicks++ },
          onBack = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("confirm_destination_address")
      .assertTextEquals(dest.toFriendly(bounceable = false))
    composeTestRule.onNodeWithTag("confirm_bounce_badge")
      .assertTextContains("Non-bounceable", substring = true)
    composeTestRule.onNodeWithTag("send_confirm_button").performScrollTo().performClick()
    assertEquals(1, confirmedClicks)
  }

  @Test
  fun home_screen_displays_send_button_and_recent_transfers() {
    val metadata =
      VaultMetadata(
        publicKeyHex = "00".repeat(32),
        mainnetRaw = "0:" + "00".repeat(32),
        testnetRaw = "0:" + "00".repeat(32),
        isBackedUp = true,
        createdAtEpochMs = 1_700_000_000_000L,
      )
    val tx =
      TxRecord(
        id = "tx-1",
        network = Network.TESTNET,
        accountRaw = metadata.testnetRaw,
        messageHash = "aa".repeat(32),
        normalizedHash = "bb".repeat(32),
        txHash = "cc".repeat(32),
        seqno = 5,
        direction = TxDirection.OUT,
        kind = TxAssetKind.GRAM,
        amount = Money.ofNano(3_000_000_000L),
        fee = Money.ofNano(4_000_000L),
        counterpartyRaw = "0:" + "99".repeat(32),
        comment = null,
        state = TxStatus.CONFIRMED,
        validUntilEpochSec = 1_800_000_120L,
        lt = "123",
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_005_000L,
      )
    var sendOpened = false
    composeTestRule.setContent {
      GramVaultTheme {
        WalletHomeScreen(
          metadata = metadata,
          activeNetwork = Network.TESTNET,
          bannerMessage = null,
          onClearBanner = {},
          onSelectNetwork = {},
          onOpenBackup = {},
          onLockWallet = {},
          onOpenSend = { sendOpened = true },
          txRecords = listOf(tx),
        )
      }
    }

    composeTestRule.onNodeWithTag("send_button").assertIsDisplayed().performClick()
    assertTrue(sendOpened)
    composeTestRule.onNodeWithTag("recent_txs_card").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("tx_row_tx-1").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("view_all_history_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("chain_monitor_card").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun metadata_store_persists_last_active_network_across_instances() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()
    val store1 = VaultMetadataStore(context)
    val meta =
      VaultMetadata(
        publicKeyHex = "aa".repeat(32),
        mainnetRaw = "0:" + "11".repeat(32),
        testnetRaw = "0:" + "22".repeat(32),
        isBackedUp = false,
        createdAtEpochMs = 1_700_000_000_000L,
        lastActiveNetwork = Network.TESTNET,
      )
    store1.save(meta)
    val store2 = VaultMetadataStore(context)
    val reloaded = store2.load()
    assertEquals(Network.TESTNET, reloaded?.lastActiveNetwork)
    store2.save(meta.copy(lastActiveNetwork = Network.MAINNET))
    assertEquals(Network.MAINNET, VaultMetadataStore(context).load()?.lastActiveNetwork)
  }

  @Test
  fun tx_manager_sync_account_history_reconciles_outgoing_and_detects_incremental_incoming() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()
    val db = Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java).allowMainThreadQueries().build()
    try {
      var fakeNowMs = 1_710_000_000_000L
      val fakeAuth = FakeAuthGate(deviceSecure = true, nextResult = AuthResult.Authenticated)
      val fakeCipher = InMemoryAesCipherProvider().apply { isAuthenticated = true }
      val keyStore = SecureKeyStore(context, fakeCipher)
      val metaStore = VaultMetadataStore(context)
      val gateway = FakeChainGateway(db) { fakeNowMs }
      gateway.senderBalance = Money.ofNano(50_000_000_000L)
      gateway.senderStatus = AccountOnChainStatus.ACTIVE
      gateway.senderSeqno = 2

      val other = TonAddress("0:" + "22".repeat(32), Network.MAINNET)
      gateway.walletStatuses[other.raw] = AccountOnChainStatus.ACTIVE

      val txManager =
        TxManager(
          txDao = db.txDao(),
          chainGateway = gateway,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
          clockMs = { fakeNowMs },
        )
      val core =
        WalletCoreImpl(
          applicationContext = context,
          authGate = fakeAuth,
          secureKeyStore = keyStore,
          metadataStore = metaStore,
          walletKitBridge = null,
          txManager = txManager,
          clockMs = { fakeNowMs },
        )

      val created = core.createWallet(Network.MAINNET).getOrThrow()
      created.mnemonic.wipe()
      val sender = created.mainnetAddress
      gateway.senderAddressRaw = sender.raw

      val preview =
        core.prepareTransfer(
          recipientInput = other.toFriendly(bounceable = false),
          amountText = "0.5",
          comment = "out-test",
        ).getOrThrow()
      val pendingTx = core.confirmAndSend(preview).getOrThrow()
      assertEquals(TxStatus.BROADCASTED, pendingTx.state)

      // First history page includes our pending OUT transfer (matching inMsgHash) + 1 incoming IN transfer
      gateway.historyTransfers =
        listOf(
          RemoteChainTransfer(
            txHash = "11".repeat(32),
            lt = "5000",
            utimeEpochSec = 1710000100L,
            direction = TxDirection.IN,
            amount = Money.ofNano(250_000_000L),
            fee = Money.ofNano(1_000_000L),
            counterpartyRaw = other.raw,
            comment = "Test deposit",
            status = OnChainMessageStatus.CONFIRMED,
            inMsgHash = "22".repeat(32),
            inMsgNormalizedHash = "22".repeat(32),
          ),
          RemoteChainTransfer(
            txHash = "33".repeat(32),
            lt = "4900",
            utimeEpochSec = 1710000050L,
            direction = TxDirection.OUT,
            amount = Money.ofNano(500_000_000L),
            fee = Money.ofNano(3_500_000L),
            counterpartyRaw = other.raw,
            comment = "out-test",
            status = OnChainMessageStatus.CONFIRMED,
            inMsgHash = pendingTx.messageHash,
            inMsgNormalizedHash = pendingTx.normalizedHash,
          ),
        )

      val firstSync = txManager.syncAccountHistory(sender).getOrThrow()
      assertEquals(1, firstSync.reconciledCount)
      assertEquals(1, firstSync.newIncomingTxs.size)
      assertEquals("Test deposit", firstSync.newIncomingTxs[0].comment)

      val rowsAfterFirst = db.txDao().observeByAccount(sender.raw, Network.MAINNET.name).first()
      assertEquals(2, rowsAfterFirst.size)
      val reconciledOut = rowsAfterFirst.first { it.id == pendingTx.id }
      assertEquals(TxStatus.CONFIRMED.name, reconciledOut.state)
      assertEquals("33".repeat(32), reconciledOut.txHash)

      // Second sync must pass afterLt = "5000" and detect newly arrived IN transfer without duplicating older ones
      val newIncoming =
        RemoteChainTransfer(
          txHash = "44".repeat(32),
          lt = "5100",
          utimeEpochSec = 1710000200L,
          direction = TxDirection.IN,
          amount = Money.ofNano(100_000_000L),
          fee = null,
          counterpartyRaw = other.raw,
          comment = "Second transfer",
          status = OnChainMessageStatus.CONFIRMED,
          inMsgHash = "55".repeat(32),
          inMsgNormalizedHash = "55".repeat(32),
        )
      gateway.historyTransfers = listOf(newIncoming)

      val secondSync = txManager.syncAccountHistory(sender).getOrThrow()
      assertEquals("5000", gateway.lastHistoryAfterLt)
      assertEquals(1, secondSync.newIncomingTxs.size)
      assertEquals("5100", secondSync.newIncomingTxs[0].lt)

      // Idempotent re-sync of same lt does not emit duplicate incoming alerts
      val idempotentSync = txManager.syncAccountHistory(sender).getOrThrow()
      assertEquals("5100", gateway.lastHistoryAfterLt)
      assertTrue(idempotentSync.newIncomingTxs.isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun transaction_history_and_incoming_detail_ui_filter_and_display_correctly() {
    val accountRaw = "0:" + "00".repeat(32)
    val inTx =
      TxRecord(
        id = "chain_MAINNET_5000_1111_IN_0",
        network = Network.MAINNET,
        accountRaw = accountRaw,
        messageHash = "aa".repeat(32),
        normalizedHash = "aa".repeat(32),
        txHash = "11".repeat(32),
        seqno = 0,
        direction = TxDirection.IN,
        kind = TxAssetKind.GRAM,
        amount = Money.ofNano(1_250_000_000L),
        fee = Money.ofNano(1_000_000L),
        counterpartyRaw = "0:" + "77".repeat(32),
        comment = "Invoice #42",
        state = TxStatus.CONFIRMED,
        validUntilEpochSec = 0L,
        lt = "5000",
        createdAtEpochMs = 1_710_000_000_000L,
        updatedAtEpochMs = 1_710_000_000_000L,
      )
    val outTx =
      inTx.copy(
        id = "local-out-1",
        direction = TxDirection.OUT,
        comment = "Sent gift",
        lt = "4900",
      )

    composeTestRule.setContent {
      GramVaultTheme {
        TransactionHistoryScreen(
          activeNetwork = Network.MAINNET,
          txRecords = listOf(inTx, outTx),
          isSyncing = false,
          onSyncNow = {},
          onSelectTx = {},
          onBack = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("history_row_${inTx.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_row_${outTx.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_filter_in").performClick()
    composeTestRule.onNodeWithTag("history_row_${inTx.id}").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_row_${outTx.id}").assertDoesNotExist()
  }

  @Test
  fun incoming_send_status_screen_renders_received_title_and_positive_amount() {
    val inTx =
      TxRecord(
        id = "chain_MAINNET_5000_1111_IN_0",
        network = Network.MAINNET,
        accountRaw = "0:" + "00".repeat(32),
        messageHash = "aa".repeat(32),
        normalizedHash = "aa".repeat(32),
        txHash = "11".repeat(32),
        seqno = 0,
        direction = TxDirection.IN,
        kind = TxAssetKind.GRAM,
        amount = Money.ofNano(1_250_000_000L),
        fee = null,
        counterpartyRaw = "0:" + "77".repeat(32),
        comment = "Invoice #42",
        state = TxStatus.CONFIRMED,
        validUntilEpochSec = 0L,
        lt = "5000",
        createdAtEpochMs = 1_710_000_000_000L,
        updatedAtEpochMs = 1_710_000_000_000L,
      )

    composeTestRule.setContent {
      GramVaultTheme {
        SendStatusScreen(
          txRecord = inTx,
          onRefreshStatus = {},
          onDone = {},
        )
      }
    }

    composeTestRule.onNodeWithTag("send_status_badge").assertTextEquals("Received on TON")
    composeTestRule.onNodeWithTag("tx_status_amount").assertTextContains("+1.25 GRAM", substring = true)
  }

  @Test
  fun tx_manager_heals_cached_failed_incoming_deposit_on_resync() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java).allowMainThreadQueries().build()
    try {
      val nowMs = 1_710_000_000_000L
      val account = TonAddress("0:" + "11".repeat(32), Network.MAINNET)
      val other = TonAddress("0:" + "22".repeat(32), Network.MAINNET)
      val gateway = FakeChainGateway(db) { nowMs }
      val txManager =
        TxManager(
          txDao = db.txDao(),
          chainGateway = gateway,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
          clockMs = { nowMs },
        )

      db.txDao().upsert(
        com.example.core.data.TxEntity(
          id = "chain_MAINNET_5000_1111_IN_0",
          network = Network.MAINNET.name,
          accountRaw = account.raw,
          messageHash = "aa".repeat(32),
          normalizedHash = "aa".repeat(32),
          txHash = "11".repeat(32),
          seqno = 0,
          direction = TxDirection.IN.name,
          kind = TxAssetKind.GRAM.name,
          jettonMaster = null,
          amountUnits = "500000000",
          feeUnits = "1000000",
          counterpartyRaw = other.raw,
          comment = null,
          state = TxStatus.FAILED.name,
          validUntilEpochSec = 0L,
          lt = "5000",
          bocBase64 = null,
          createdAtEpochMs = nowMs,
          updatedAtEpochMs = nowMs,
          errorDetail = "Transaction aborted on-chain",
        )
      )

      gateway.historyTransfers =
        listOf(
          RemoteChainTransfer(
            txHash = "11".repeat(32),
            lt = "5000",
            utimeEpochSec = 1710000000L,
            direction = TxDirection.IN,
            amount = Money.ofNano(500_000_000L),
            fee = Money.ofNano(1_000_000L),
            counterpartyRaw = other.raw,
            comment = null,
            status = OnChainMessageStatus.CONFIRMED,
            inMsgHash = "aa".repeat(32),
            inMsgNormalizedHash = "aa".repeat(32),
          )
        )

      txManager.syncAccountHistory(account).getOrThrow()
      assertEquals(null, gateway.lastHistoryAfterLt)
      val rows = db.txDao().observeByAccount(account.raw, Network.MAINNET.name).first()
      assertEquals(1, rows.size)
      assertEquals(TxStatus.CONFIRMED.name, rows.first().state)
      assertEquals(null, rows.first().errorDetail)
    } finally {
      db.close()
    }
  }

  @Test
  fun send_form_renders_concise_error_card() {
    composeTestRule.setContent {
      GramVaultTheme {
        SendFormScreen(
          activeNetwork = Network.MAINNET,
          balanceState = BalanceState(network = Network.MAINNET, gramBalance = Money.ofNano(1_000_000_000L)),
          recipientInput = "",
          amountInput = "",
          commentInput = "",
          isBusy = false,
          errorMessage = "Network is busy. Wait a few seconds and try again.",
          onRecipientChange = {},
          onAmountChange = {},
          onCommentChange = {},
          onValidateRecipient = { com.example.core.wallet.AddressCheckResult.Invalid("Empty") },
          onReviewTransfer = {},
          onBack = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("send_error_card").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun jetton_usdt_transfer_validates_balance_and_gas_and_signs_tep74_boc() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    File(context.filesDir, "vault").deleteRecursively()
    val db =
      Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    try {
      val nowMs = 1_710_000_000_000L
      val fakeAuth = FakeAuthGate(deviceSecure = true, nextResult = AuthResult.Authenticated)
      val fakeCipher = InMemoryAesCipherProvider().apply { isAuthenticated = true }
      val keyStore = SecureKeyStore(context, fakeCipher)
      val metaStore = VaultMetadataStore(context)
      val gateway = FakeChainGateway(db) { nowMs }
      gateway.senderStatus = AccountOnChainStatus.ACTIVE
      gateway.senderSeqno = 2
      gateway.senderJettonWallet = TonAddress("0:" + "ab".repeat(32), Network.TESTNET)
      gateway.senderUsdtBalance = Money.fromDecimalString("25.50", USDT_DECIMALS)
      gateway.senderBalance = Money.ofNano(40_000_000L) // less than 0.055 GRAM + fee

      val txManager =
        TxManager(
          txDao = db.txDao(),
          chainGateway = gateway,
          clockMs = { nowMs },
          scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
      val core =
        WalletCoreImpl(
          applicationContext = context,
          authGate = fakeAuth,
          secureKeyStore = keyStore,
          metadataStore = metaStore,
          walletKitBridge = null,
          txManager = txManager,
          clockMs = { nowMs },
        )
      val created = core.createWallet(Network.TESTNET).getOrThrow()
      created.mnemonic.wipe()
      gateway.senderAddressRaw = created.testnetAddress.raw
      val dest = TonAddress("0:" + "44".repeat(32), Network.TESTNET)

      // 1. Insufficient GRAM for attached Jetton gas
      val gasShort =
        core.prepareTransfer(dest.toFriendly(false), "10.0", "USDT gas check", TxAssetKind.JETTON)
      assertTrue(gasShort.isFailure)
      assertTrue(gasShort.exceptionOrNull()?.message?.contains("gas", ignoreCase = true) == true)

      // 2. Top up GRAM gas, test insufficient USDT
      gateway.senderBalance = Money.ofNano(1_000_000_000L)
      val usdtShort =
        core.prepareTransfer(dest.toFriendly(false), "30.0", null, TxAssetKind.JETTON)
      assertTrue(usdtShort.isFailure)
      assertTrue(usdtShort.exceptionOrNull()?.message?.contains("USDT", ignoreCase = true) == true)

      // 3. Valid USDT prepare + confirmAndSend
      val preview =
        core.prepareTransfer(dest.toFriendly(false), "12.50", "Tea payment", TxAssetKind.JETTON).getOrThrow()
      assertEquals(TxAssetKind.JETTON, preview.assetKind)
      assertEquals("USDT", preview.assetSymbol)
      assertEquals(Money.fromDecimalString("12.50", USDT_DECIMALS), preview.amount)
      assertEquals(JettonAllowList.TESTNET_USDT.masterAddress.raw, preview.jettonMasterRaw)

      val sent = core.confirmAndSend(preview).getOrThrow()
      assertEquals(TxStatus.BROADCASTED, sent.state)
      assertEquals(TxAssetKind.JETTON, sent.kind)
      assertEquals("USDT", sent.assetSymbol)
      assertTrue(!gateway.bocObservedDuringSendBoc.isNullOrBlank())

      // 4. Jetton history sync filters unverified spoofed master and deduplicates app-sent OUT
      gateway.historyTransfers = emptyList()
      val spoofMaster = "0:" + "ff".repeat(32)
      gateway.jettonHistoryTransfers =
        listOf(
          RemoteJettonTransfer(
            txHash = "jhash1",
            lt = "6001",
            utimeEpochSec = 1710000100L,
            direction = TxDirection.IN,
            jettonMasterRaw = spoofMaster,
            amount = Money.fromDecimalString("9999.00", USDT_DECIMALS),
            counterpartyRaw = dest.raw,
            comment = "Fake airdrop",
            status = OnChainMessageStatus.CONFIRMED,
          ),
          RemoteJettonTransfer(
            txHash = "jhash2",
            lt = "6002",
            utimeEpochSec = 1710000200L,
            direction = TxDirection.IN,
            jettonMasterRaw = JettonAllowList.TESTNET_USDT.masterAddress.raw,
            amount = Money.fromDecimalString("5.25", USDT_DECIMALS),
            counterpartyRaw = dest.raw,
            comment = "Real USDT",
            status = OnChainMessageStatus.CONFIRMED,
          ),
        )
      val jettonSync = txManager.syncAccountHistory(created.testnetAddress).getOrThrow()
      assertEquals(1, jettonSync.newIncomingTxs.size)
      assertEquals(TxAssetKind.JETTON, jettonSync.newIncomingTxs[0].kind)
      assertEquals("5.25", jettonSync.newIncomingTxs[0].amount.format())

      // 5. knownAccountLt <= latestLocalLt short-circuits redundant history calls when no pending txs
      db.txDao().markTerminal(sent.id, TxStatus.CONFIRMED.name, "outer_hash", "6000", "5000000", nowMs, null)
      gateway.historyCallCount = 0
      val skippedSync = txManager.syncAccountHistory(created.testnetAddress, knownAccountLt = "6002").getOrThrow()
      assertEquals("Cache", skippedSync.providerName)
      assertEquals(0, gateway.historyCallCount)
    } finally {
      db.close()
    }
  }

  @Test
  fun address_book_and_settings_store_isolate_networks_and_persist_preferences() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db =
      Room.inMemoryDatabaseBuilder(context, GramVaultDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    try {
      val repo = AddressBookRepository(db.addressBookDao())
      val mainAddr = TonAddress("0:" + "12".repeat(32), Network.MAINNET).toFriendly(false)
      val testAddr = TonAddress("0:" + "34".repeat(32), Network.TESTNET).toFriendly(false)

      // Rejects wrong-network address
      assertTrue(repo.saveContact(Network.MAINNET, "Bad", testAddr).isFailure)
      assertTrue(repo.saveContact(Network.TESTNET, "Bad", mainAddr).isFailure)

      val savedMain = repo.saveContact(Network.MAINNET, "Alice", mainAddr).getOrThrow()
      val savedTest = repo.saveContact(Network.TESTNET, "Bob", testAddr).getOrThrow()

      val mainContacts = repo.observeEntries(Network.MAINNET).first()
      val testContacts = repo.observeEntries(Network.TESTNET).first()
      assertEquals(1, mainContacts.size)
      assertEquals("Alice", mainContacts[0].label)
      assertEquals(1, testContacts.size)
      assertEquals("Bob", testContacts[0].label)

      repo.deleteContact(savedMain.id)
      assertTrue(repo.observeEntries(Network.MAINNET).first().isEmpty())
      assertEquals(1, repo.observeEntries(Network.TESTNET).first().size)
      assertEquals(savedTest.id, repo.observeEntries(Network.TESTNET).first()[0].id)

      val settingsStore = AppSettingsStore(context, defaultAutoLock = true)
      settingsStore.setAutoLockOnBackground(false)
      settingsStore.setLiveMonitorEnabled(false)
      val reloaded = settingsStore.settingsFlow.first()
      assertFalse(reloaded.autoLockOnBackground)
      assertFalse(reloaded.liveMonitorEnabled)
    } finally {
      db.close()
    }
  }

  @Test
  fun home_send_and_address_book_ui_render_usdt_usd_and_settings() {
    val metadata =
      VaultMetadata(
        publicKeyHex = "00".repeat(32),
        mainnetRaw = "0:" + "00".repeat(32),
        testnetRaw = "0:" + "00".repeat(32),
        isBackedUp = true,
        createdAtEpochMs = 1_700_000_000_000L,
      )
    val balanceState =
      BalanceState(
        network = Network.MAINNET,
        address = metadata.addressFor(Network.MAINNET),
        gramBalance = Money.ofNano(2_000_000_000L),
        usdtBalance = Money.fromDecimalString("15.25", USDT_DECIMALS),
      )
    val rates =
      FiatRates(
        gramUsdPrice = BigDecimal("5.00"),
        usdtUsdPrice = BigDecimal("1.00"),
        fetchedAtEpochMs = 1_710_000_000_000L,
        sourceName = "TonAPI",
      )

    composeTestRule.setContent {
      GramVaultTheme {
        WalletHomeScreen(
          metadata = metadata,
          activeNetwork = Network.MAINNET,
          bannerMessage = null,
          onClearBanner = {},
          onSelectNetwork = {},
          onOpenBackup = {},
          onLockWallet = {},
          balanceState = balanceState,
          fiatRates = rates,
        )
      }
    }

    composeTestRule.onNodeWithTag("hero_usd_total").assertTextContains("$25.25", substring = true)
    composeTestRule.onNodeWithTag("asset_usdt_balance").performScrollTo().assertTextEquals("15.25 USDT")
    composeTestRule.onNodeWithTag("open_address_book_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_settings_button").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun send_form_switches_gram_and_usdt_and_address_book_screen_saves_contact() {
    var selectedAsset = TxAssetKind.GRAM
    val testnetBalance =
      BalanceState(
        network = Network.TESTNET,
        gramBalance = Money.ofNano(5_000_000_000L),
        usdtBalance = Money.fromDecimalString("42.50", USDT_DECIMALS),
      )
    composeTestRule.setContent {
      GramVaultTheme {
        SendFormScreen(
          activeNetwork = Network.TESTNET,
          balanceState = testnetBalance,
          recipientInput = "",
          amountInput = "",
          commentInput = "",
          isBusy = false,
          errorMessage = null,
          onRecipientChange = {},
          onAmountChange = {},
          onCommentChange = {},
          onValidateRecipient = { com.example.core.wallet.AddressCheckResult.Invalid("Empty") },
          onReviewTransfer = {},
          onBack = {},
          selectedAsset = TxAssetKind.JETTON,
          onAssetChange = { selectedAsset = it },
        )
      }
    }
    composeTestRule.onNodeWithTag("send_available_balance_text").assertTextContains("42.50 USDT", substring = true)
    composeTestRule.onNodeWithTag("send_asset_gram").performClick()
    assertEquals(TxAssetKind.GRAM, selectedAsset)
  }

  @Test
  fun address_book_and_settings_composables_render_and_trigger_callbacks() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var savedLabel = ""
    var savedAddress = ""
    val sampleDest = TonAddress("0:" + "55".repeat(32), Network.MAINNET).toFriendly(false)
    composeTestRule.setContent {
      GramVaultTheme {
        AddressBookScreen(
          activeNetwork = Network.MAINNET,
          entries = emptyList(),
          errorMessage = null,
          onSaveContact = { label, addr ->
            savedLabel = label
            savedAddress = addr
          },
          onDeleteContact = {},
          onSendToContact = {},
          onValidateAddress = { input ->
            TonAddress.parseDetailed(input, Network.MAINNET).fold(
              onSuccess = { com.example.core.wallet.AddressCheckResult.Valid(it) },
              onFailure = { com.example.core.wallet.AddressCheckResult.Invalid("Invalid") },
            )
          },
          onBack = {},
        )
      }
    }
    composeTestRule.onNodeWithTag("address_book_empty_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("address_book_name_input").performTextInput("Treasury")
    composeTestRule.onNodeWithTag("address_book_address_input").performTextInput(sampleDest)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("address_book_save_button").assertIsEnabled().performClick()
    assertEquals("Treasury", savedLabel)
    assertEquals(sampleDest, savedAddress)
  }

  private class FakeChainGateway(
    private val db: GramVaultDatabase,
    private val nowProvider: () -> Long,
  ) : ChainGateway {
    var senderAddressRaw: String = ""
    var senderBalance: Money = Money.ofNano(10_000_000_000L)
    var senderStatus: AccountOnChainStatus = AccountOnChainStatus.ACTIVE
    var senderSeqno: Int = 0
    var senderJettonWallet: TonAddress? = null
    var senderUsdtBalance: Money = Money.zero(USDT_DECIMALS)
    val walletStatuses = mutableMapOf<String, AccountOnChainStatus>()
    var nextFeeNano: Long = 5_000_000L
    var sendBocFailure: Throwable? = null
    var sendBocCount: Int = 0
    var roomStateObservedDuringSendBoc: String? = null
    var bocObservedDuringSendBoc: String? = null
    var nextLookup: RemoteTxLookup =
      RemoteTxLookup(OnChainMessageStatus.PENDING, null, null, null, "TonAPI")
    var historyTransfers: List<RemoteChainTransfer> = emptyList()
    var jettonHistoryTransfers: List<RemoteJettonTransfer> = emptyList()
    var lastHistoryAfterLt: String? = null
    var historyCallCount: Int = 0

    override suspend fun fetchAccountBalance(address: TonAddress): Result<RemoteAccountBalance> {
      val status =
        if (address.raw == senderAddressRaw) senderStatus
        else walletStatuses[address.raw] ?: AccountOnChainStatus.NONEXIST
      val bal = if (address.raw == senderAddressRaw) senderBalance else Money.zero()
      return Result.success(RemoteAccountBalance(address, bal, status, "TonAPI", nowProvider()))
    }

    override suspend fun fetchJettonBalance(
      ownerAddress: TonAddress,
      spec: AllowedJettonSpec,
    ): Result<RemoteJettonBalance> =
      Result.success(
        RemoteJettonBalance(
          ownerAddress = ownerAddress,
          jettonMaster = spec.masterAddress,
          jettonWalletAddress = senderJettonWallet,
          balance = senderUsdtBalance,
          providerName = "TonAPI",
          fetchedAtEpochMs = nowProvider(),
        )
      )

    override suspend fun fetchWalletInfo(address: TonAddress): Result<RemoteWalletInfo> {
      val status =
        if (address.raw == senderAddressRaw) senderStatus
        else walletStatuses[address.raw] ?: AccountOnChainStatus.NONEXIST
      val seqno = if (address.raw == senderAddressRaw) senderSeqno else 0
      val bal = if (address.raw == senderAddressRaw) senderBalance else Money.zero()
      return Result.success(RemoteWalletInfo(address, bal, status, seqno, "TonAPI", nowProvider()))
    }

    override suspend fun estimateExternalMessageFee(address: TonAddress, bocBase64: String) =
      Result.success(RemoteFeeEstimate(Money.ofNano(nextFeeNano), "TonAPI"))

    override suspend fun sendBoc(network: Network, bocBase64: String): Result<BroadcastResult> {
      val pending = db.txDao().getNonTerminalForAccount(senderAddressRaw, network.name).firstOrNull()
      roomStateObservedDuringSendBoc = pending?.state
      bocObservedDuringSendBoc = pending?.bocBase64
      sendBocFailure?.let { return Result.failure(it) }
      sendBocCount++
      return Result.success(BroadcastResult("fakehash", "TonAPI"))
    }

    override suspend fun lookupMessageStatus(
      network: Network,
      messageHashHex: String,
      normalizedHashHex: String,
    ) = Result.success(nextLookup)

    override suspend fun fetchAccountHistory(
      address: TonAddress,
      afterLt: String?,
      limit: Int,
    ): Result<RemoteTxHistoryPage> {
      historyCallCount++
      lastHistoryAfterLt = afterLt
      return Result.success(RemoteTxHistoryPage(historyTransfers.take(limit), "TonAPI"))
    }

    override suspend fun fetchJettonHistory(
      ownerAddress: TonAddress,
      spec: AllowedJettonSpec,
      limit: Int,
    ): Result<RemoteJettonHistoryPage> =
      Result.success(RemoteJettonHistoryPage(jettonHistoryTransfers.take(limit), "TonAPI"))
  }
}

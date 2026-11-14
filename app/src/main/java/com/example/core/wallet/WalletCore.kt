package com.example.core.wallet

import android.content.Context
import com.example.core.data.BalanceRepository
import com.example.core.keystore.AuthGate
import com.example.core.keystore.SecureKeyStore
import com.example.core.keystore.VaultAuthRequiredException
import com.example.core.keystore.VaultKeyInvalidatedException
import com.example.core.keystore.VaultWrongPinException
import com.example.core.model.AccountOnChainStatus
import com.example.core.model.AuthReason
import com.example.core.model.AuthResult
import com.example.core.model.BackupChallenge
import com.example.core.model.BalanceState
import com.example.core.model.CandidateAccount
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.HistorySyncSummary
import com.example.core.model.ImportDiscoveryResult
import com.example.core.model.JettonAllowList
import com.example.core.model.MnemonicPhrase
import com.example.core.model.MnemonicScheme
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.NewWallet
import com.example.core.model.ParsedAddress
import com.example.core.model.TonAddress
import com.example.core.model.TransferPreview
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.TxStatus
import com.example.core.model.VaultMetadata
import com.example.core.model.VaultSecurityMode
import com.example.core.model.VaultState
import com.example.core.model.WalletVersion
import com.example.core.tx.TxManager
import java.math.BigInteger
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface AddressCheckResult {
  data class Valid(
    val parsedAddress: ParsedAddress,
    val uriAmount: Money? = null,
    val uriComment: String? = null,
  ) : AddressCheckResult

  data class Invalid(val reason: String) : AddressCheckResult
}

interface WalletCore {
  val vaultState: StateFlow<VaultState>
  val isDevAuthBypassed: Boolean

  fun isDeviceSecure(): Boolean

  fun activeSecurityMode(): VaultSecurityMode

  suspend fun createWallet(
    initialNetwork: Network = Network.MAINNET,
    appPin: CharArray? = null,
  ): Result<NewWallet>

  suspend fun unlockWallet(
    network: Network = Network.MAINNET,
    appPin: CharArray? = null,
  ): Result<VaultMetadata>

  suspend fun revealRecoveryPhrase(appPin: CharArray? = null): Result<MnemonicPhrase>

  fun createBackupChallenge(): BackupChallenge

  suspend fun confirmBackup(
    challenge: BackupChallenge,
    answers: Map<Int, CharArray>,
    appPin: CharArray? = null,
  ): Result<Boolean>

  suspend fun discoverImportAccounts(phrase: MnemonicPhrase): Result<ImportDiscoveryResult>

  suspend fun selectImportedAccount(
    phrase: MnemonicPhrase,
    candidate: CandidateAccount,
    initialNetwork: Network = Network.MAINNET,
    appPin: CharArray? = null,
  ): Result<VaultMetadata>

  suspend fun switchNetwork(network: Network)

  fun lockWallet()

  fun resetVault()

  fun getReceiveAddress(network: Network? = null): TonAddress?

  fun buildReceiveTransferUri(
    network: Network? = null,
    amount: Money? = null,
    comment: String? = null,
  ): String?

  fun validateReceiveInput(input: String, network: Network? = null): AddressCheckResult

  fun observeBalance(): Flow<BalanceState>

  suspend fun refreshBalance(force: Boolean = false)

  suspend fun prepareTransfer(
    recipientInput: String,
    amountText: String,
    comment: String? = null,
    assetKind: TxAssetKind = TxAssetKind.GRAM,
  ): Result<TransferPreview>

  suspend fun confirmAndSend(
    preview: TransferPreview,
    appPin: CharArray? = null,
  ): Result<TxRecord>

  fun observeTransactions(): Flow<List<TxRecord>>

  suspend fun recoverPendingTransactions()

  suspend fun refreshTransactionStatus(txId: String): TxRecord?

  fun preferredNetwork(): Network

  suspend fun syncTransactionHistory(
    limit: Int = 25,
    ignoreLtShortcut: Boolean = false,
  ): Result<HistorySyncSummary>
}

class WalletCoreImpl(
  applicationContext: Context,
  private val authGate: AuthGate,
  private val secureKeyStore: SecureKeyStore = SecureKeyStore(applicationContext),
  private val metadataStore: VaultMetadataStore = VaultMetadataStore(applicationContext),
  private val walletKitBridge: WalletKitBridge? = WalletKitBridge(applicationContext, secureKeyStore),
  private val balanceRepository: BalanceRepository? = null,
  private val txManager: TxManager? = null,
  private val devSkipAuth: Boolean = false,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) : WalletCore {

  private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _vaultState = MutableStateFlow(loadInitialState())
  override val vaultState: StateFlow<VaultState> = _vaultState.asStateFlow()
  override val isDevAuthBypassed: Boolean
    get() = devSkipAuth

  private fun loadInitialState(): VaultState {
    val meta = metadataStore.load()
    return if (meta != null && secureKeyStore.hasVault()) {
      VaultState.Locked(meta)
    } else {
      if (meta != null && !secureKeyStore.hasVault()) {
        metadataStore.clear()
      }
      VaultState.Uninitialized
    }
  }

  override fun isDeviceSecure(): Boolean = authGate.isDeviceSecure()

  override fun activeSecurityMode(): VaultSecurityMode {
    val existing = metadataStore.load()?.securityMode
    if (existing != null) return existing
    if (devSkipAuth) return VaultSecurityMode.DEV_UNAUTH_KEYSTORE
    return if (authGate.isDeviceSecure()) VaultSecurityMode.DEVICE_KEYSTORE
    else VaultSecurityMode.APP_PIN_WITH_KEYSTORE
  }

  override suspend fun createWallet(initialNetwork: Network, appPin: CharArray?): Result<NewWallet> {
    val targetMode =
      when {
        devSkipAuth -> VaultSecurityMode.DEV_UNAUTH_KEYSTORE
        authGate.isDeviceSecure() -> VaultSecurityMode.DEVICE_KEYSTORE
        else -> VaultSecurityMode.APP_PIN_WITH_KEYSTORE
      }

    when (targetMode) {
      VaultSecurityMode.DEV_UNAUTH_KEYSTORE -> Unit
      VaultSecurityMode.DEVICE_KEYSTORE -> {
        when (val auth = authGate.authenticate(AuthReason.CREATE_WALLET)) {
          is AuthResult.Authenticated -> Unit
          is AuthResult.Cancelled -> {
            appPin?.let { Arrays.fill(it, '\u0000') }
            return Result.failure(VaultException("Authentication cancelled"))
          }
          is AuthResult.NoDeviceSecurity ->
            return Result.failure(VaultException("Device lock screen is required"))
          is AuthResult.Failed -> {
            appPin?.let { Arrays.fill(it, '\u0000') }
            return Result.failure(VaultException(auth.message))
          }
        }
      }
      VaultSecurityMode.APP_PIN_WITH_KEYSTORE -> {
        if (appPin == null || appPin.size < 4) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultException("Set a 4 to 8 digit App PIN to protect your wallet"))
        }
      }
    }

    return runCatching {
      val mnemonic = withContext(Dispatchers.Default) { MnemonicService.generate12WordMnemonic() }
      val derived = withContext(Dispatchers.Default) { MnemonicService.deriveMultichainKey(mnemonic) }
      val mnemonicUtf8 = MnemonicService.encodePhraseToUtf8(mnemonic)
      try {
        val mainnetAddr = AddressService.deriveV5R1Address(derived.publicKey32, Network.MAINNET)
        val testnetAddr = AddressService.deriveV5R1Address(derived.publicKey32, Network.TESTNET)
        val pubHex = AddressService.bytesToHex(derived.publicKey32)

        withContext(Dispatchers.IO) {
          when (targetMode) {
            VaultSecurityMode.DEVICE_KEYSTORE ->
              secureKeyStore.saveWithDeviceAuth(derived.seed32, mnemonicUtf8)
            VaultSecurityMode.APP_PIN_WITH_KEYSTORE ->
              secureKeyStore.saveWithAppPin(derived.seed32, mnemonicUtf8, appPin!!)
            VaultSecurityMode.DEV_UNAUTH_KEYSTORE ->
              secureKeyStore.saveWithoutUserAuth(derived.seed32, mnemonicUtf8)
          }
        }
        val meta =
          VaultMetadata(
            publicKeyHex = pubHex,
            mainnetRaw = mainnetAddr.raw,
            testnetRaw = testnetAddr.raw,
            isBackedUp = false,
            createdAtEpochMs = System.currentTimeMillis(),
            securityMode = targetMode,
            walletVersion = WalletVersion.V5R1,
            mnemonicScheme = MnemonicScheme.MULTICHAIN,
            lastActiveNetwork = initialNetwork,
          )
        withContext(Dispatchers.IO) { metadataStore.save(meta) }

        val synced = syncWalletKit(meta, initialNetwork)
        _vaultState.value =
          VaultState.Unlocked(
            metadata = meta,
            activeNetwork = initialNetwork,
            walletKitSynced = synced,
          )
        NewWallet(
          mainnetAddress = mainnetAddr,
          testnetAddress = testnetAddr,
          publicKeyHex = pubHex,
          mnemonic = mnemonic,
        )
      } catch (t: Throwable) {
        mnemonic.wipe()
        throw t
      } finally {
        derived.wipe()
        Arrays.fill(mnemonicUtf8, 0.toByte())
        appPin?.let { Arrays.fill(it, '\u0000') }
      }
    }
  }

  override suspend fun discoverImportAccounts(phrase: MnemonicPhrase): Result<ImportDiscoveryResult> {
    if (phrase.wordCount != 12 && phrase.wordCount != 24) {
      return Result.failure(VaultException("Recovery phrase must contain 12 or 24 words"))
    }
    return runCatching {
      withContext(Dispatchers.Default) {
        val schemes = MnemonicService.detectValidSchemes(phrase)
        if (schemes.isEmpty()) {
          throw VaultException("Invalid recovery phrase words or checksum")
        }
        val candidates =
          schemes.flatMap { scheme ->
            val derived = MnemonicService.deriveKeyForScheme(phrase, scheme)
            try {
              AddressService.discoverCandidateAccounts(derived.publicKey32, scheme)
            } finally {
              derived.wipe()
            }
          }
        ImportDiscoveryResult(
          candidates = candidates,
          isAmbiguous24 = schemes.size > 1,
        )
      }
    }
  }

  override suspend fun selectImportedAccount(
    phrase: MnemonicPhrase,
    candidate: CandidateAccount,
    initialNetwork: Network,
    appPin: CharArray?,
  ): Result<VaultMetadata> {
    val targetMode =
      when {
        devSkipAuth -> VaultSecurityMode.DEV_UNAUTH_KEYSTORE
        authGate.isDeviceSecure() -> VaultSecurityMode.DEVICE_KEYSTORE
        else -> VaultSecurityMode.APP_PIN_WITH_KEYSTORE
      }

    when (targetMode) {
      VaultSecurityMode.DEV_UNAUTH_KEYSTORE -> Unit
      VaultSecurityMode.DEVICE_KEYSTORE -> {
        when (val auth = authGate.authenticate(AuthReason.IMPORT_WALLET)) {
          is AuthResult.Authenticated -> Unit
          is AuthResult.Cancelled -> {
            appPin?.let { Arrays.fill(it, '\u0000') }
            return Result.failure(VaultException("Authentication cancelled"))
          }
          is AuthResult.NoDeviceSecurity ->
            return Result.failure(VaultException("Device lock screen is required"))
          is AuthResult.Failed -> {
            appPin?.let { Arrays.fill(it, '\u0000') }
            return Result.failure(VaultException(auth.message))
          }
        }
      }
      VaultSecurityMode.APP_PIN_WITH_KEYSTORE -> {
        if (appPin == null || appPin.size < 4) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultException("Set a 4 to 8 digit App PIN to protect your wallet"))
        }
      }
    }

    return runCatching {
      val derived =
        withContext(Dispatchers.Default) { MnemonicService.deriveKeyForScheme(phrase, candidate.scheme) }
      val mnemonicUtf8 = MnemonicService.encodePhraseToUtf8(phrase)
      try {
        val pubHex = AddressService.bytesToHex(derived.publicKey32)
        require(pubHex == candidate.publicKeyHex) { "Selected account key mismatch" }
        val mainnetAddr = AddressService.deriveAddress(derived.publicKey32, candidate.version, Network.MAINNET)
        val testnetAddr = AddressService.deriveAddress(derived.publicKey32, candidate.version, Network.TESTNET)

        withContext(Dispatchers.IO) {
          when (targetMode) {
            VaultSecurityMode.DEVICE_KEYSTORE ->
              secureKeyStore.saveWithDeviceAuth(derived.seed32, mnemonicUtf8)
            VaultSecurityMode.APP_PIN_WITH_KEYSTORE ->
              secureKeyStore.saveWithAppPin(derived.seed32, mnemonicUtf8, appPin!!)
            VaultSecurityMode.DEV_UNAUTH_KEYSTORE ->
              secureKeyStore.saveWithoutUserAuth(derived.seed32, mnemonicUtf8)
          }
        }
        val meta =
          VaultMetadata(
            publicKeyHex = pubHex,
            mainnetRaw = mainnetAddr.raw,
            testnetRaw = testnetAddr.raw,
            isBackedUp = true,
            createdAtEpochMs = System.currentTimeMillis(),
            securityMode = targetMode,
            walletVersion = candidate.version,
            mnemonicScheme = candidate.scheme,
            lastActiveNetwork = initialNetwork,
          )
        withContext(Dispatchers.IO) { metadataStore.save(meta) }

        val synced = syncWalletKit(meta, initialNetwork)
        _vaultState.value =
          VaultState.Unlocked(
            metadata = meta,
            activeNetwork = initialNetwork,
            walletKitSynced = synced,
          )
        meta
      } finally {
        derived.wipe()
        Arrays.fill(mnemonicUtf8, 0.toByte())
        appPin?.let { Arrays.fill(it, '\u0000') }
      }
    }
  }

  override suspend fun unlockWallet(network: Network, appPin: CharArray?): Result<VaultMetadata> {
    val currentMeta = metadataStore.load()
    if (currentMeta == null) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("No wallet exists on this device"))
    }

    when (currentMeta.securityMode) {
      VaultSecurityMode.DEV_UNAUTH_KEYSTORE -> {
        if (!devSkipAuth) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultException("Development vault cannot be unlocked in release build"))
        }
      }
      VaultSecurityMode.DEVICE_KEYSTORE -> {
        when (val auth = authGate.authenticate(AuthReason.UNLOCK_WALLET)) {
          is AuthResult.Authenticated -> Unit
          is AuthResult.Cancelled -> return Result.failure(VaultException("Authentication cancelled"))
          is AuthResult.NoDeviceSecurity ->
            return Result.failure(VaultException("Device lock screen is required"))
          is AuthResult.Failed -> return Result.failure(VaultException(auth.message))
        }
      }
      VaultSecurityMode.APP_PIN_WITH_KEYSTORE -> {
        if (appPin == null || appPin.size < 4) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultAuthRequiredException())
        }
      }
    }

    val useAppPin = currentMeta.securityMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE
    val requireUserAuth = currentMeta.securityMode != VaultSecurityMode.DEV_UNAUTH_KEYSTORE

    return try {
      val derivedPubHex =
        withContext(Dispatchers.IO) {
          secureKeyStore.withDecryptedSeed(
            pin = appPin,
            useAppPinMode = useAppPin,
            requireUserAuth = requireUserAuth,
          ) { seed32 ->
            val pub = org.ton.kotlin.crypto.PrivateKeyEd25519(seed32).publicKey().key.toByteArray()
            AddressService.bytesToHex(pub)
          }
        }
      require(derivedPubHex == currentMeta.publicKeyHex) { "Key mismatch detected in vault" }
      val synced = syncWalletKit(currentMeta, network)
      _vaultState.value =
        VaultState.Unlocked(
          metadata = currentMeta,
          activeNetwork = network,
          walletKitSynced = synced,
        )
      cleanupScope.launch { txManager?.recoverPendingTransactions(currentMeta.addressFor(network)) }
      Result.success(currentMeta)
    } catch (e: VaultKeyInvalidatedException) {
      _vaultState.value = VaultState.KeyInvalidated(currentMeta)
      Result.failure(e)
    } catch (e: VaultWrongPinException) {
      Result.failure(e)
    } catch (e: VaultAuthRequiredException) {
      _vaultState.value = VaultState.Locked(currentMeta)
      Result.failure(e)
    } catch (t: Throwable) {
      Result.failure(t)
    } finally {
      appPin?.let { Arrays.fill(it, '\u0000') }
    }
  }

  override suspend fun revealRecoveryPhrase(appPin: CharArray?): Result<MnemonicPhrase> {
    val current = _vaultState.value as? VaultState.Unlocked
    if (current == null) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Unlock your wallet first"))
    }
    when (current.metadata.securityMode) {
      VaultSecurityMode.DEV_UNAUTH_KEYSTORE -> {
        if (!devSkipAuth) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultException("Development vault cannot be accessed in release build"))
        }
      }
      VaultSecurityMode.DEVICE_KEYSTORE -> {
        when (val auth = authGate.authenticate(AuthReason.VIEW_RECOVERY_PHRASE)) {
          is AuthResult.Authenticated -> Unit
          is AuthResult.Cancelled -> return Result.failure(VaultException("Authentication cancelled"))
          is AuthResult.NoDeviceSecurity ->
            return Result.failure(VaultException("Device lock screen is required"))
          is AuthResult.Failed -> return Result.failure(VaultException(auth.message))
        }
      }
      VaultSecurityMode.APP_PIN_WITH_KEYSTORE -> {
        if (appPin == null || appPin.size < 4) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultAuthRequiredException())
        }
      }
    }

    val useAppPin = current.metadata.securityMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE
    val requireUserAuth = current.metadata.securityMode != VaultSecurityMode.DEV_UNAUTH_KEYSTORE

    return try {
      val phrase =
        withContext(Dispatchers.IO) {
          secureKeyStore.withDecryptedMnemonic(
            pin = appPin,
            useAppPinMode = useAppPin,
            requireUserAuth = requireUserAuth,
          ) { utf8 ->
            MnemonicService.decodePhraseFromUtf8(utf8)
          }
        }
      Result.success(phrase)
    } catch (e: VaultKeyInvalidatedException) {
      _vaultState.value = VaultState.KeyInvalidated(current.metadata)
      Result.failure(e)
    } catch (t: Throwable) {
      Result.failure(t)
    } finally {
      appPin?.let { Arrays.fill(it, '\u0000') }
    }
  }

  override fun createBackupChallenge(): BackupChallenge =
    MnemonicService.createBackupChallenge(wordCount = 12, sampleCount = 3)

  override suspend fun confirmBackup(
    challenge: BackupChallenge,
    answers: Map<Int, CharArray>,
    appPin: CharArray?,
  ): Result<Boolean> {
    val current = _vaultState.value as? VaultState.Unlocked
    if (current == null) {
      answers.values.forEach { a -> Arrays.fill(a, '\u0000') }
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Unlock your wallet first"))
    }
    if (current.metadata.securityMode == VaultSecurityMode.DEV_UNAUTH_KEYSTORE && !devSkipAuth) {
      answers.values.forEach { a -> Arrays.fill(a, '\u0000') }
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Development vault cannot be accessed in release build"))
    }
    val useAppPin = current.metadata.securityMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE
    val requireUserAuth = current.metadata.securityMode != VaultSecurityMode.DEV_UNAUTH_KEYSTORE

    return try {
      val passed =
        try {
          withContext(Dispatchers.IO) {
            secureKeyStore.withDecryptedMnemonic(
              pin = appPin,
              useAppPinMode = useAppPin,
              requireUserAuth = requireUserAuth,
            ) { utf8 ->
              val phrase = MnemonicService.decodePhraseFromUtf8(utf8)
              try {
                MnemonicService.verifyBackupChallenge(phrase, challenge, answers)
              } finally {
                phrase.wipe()
              }
            }
          }
        } catch (e: VaultAuthRequiredException) {
          if (useAppPin || !requireUserAuth) throw e
          when (val auth = authGate.authenticate(AuthReason.VIEW_RECOVERY_PHRASE)) {
            is AuthResult.Authenticated -> {
              withContext(Dispatchers.IO) {
                secureKeyStore.withDecryptedMnemonic(
                  pin = null,
                  useAppPinMode = false,
                  requireUserAuth = true,
                ) { utf8 ->
                  val phrase = MnemonicService.decodePhraseFromUtf8(utf8)
                  try {
                    MnemonicService.verifyBackupChallenge(phrase, challenge, answers)
                  } finally {
                    phrase.wipe()
                  }
                }
              }
            }
            is AuthResult.Cancelled -> return Result.failure(VaultException("Authentication cancelled"))
            is AuthResult.NoDeviceSecurity ->
              return Result.failure(VaultException("Device lock screen is required"))
            is AuthResult.Failed -> return Result.failure(VaultException(auth.message))
          }
        }

      if (passed) {
        val updatedMeta = current.metadata.copy(isBackedUp = true)
        withContext(Dispatchers.IO) { metadataStore.save(updatedMeta) }
        _vaultState.value = current.copy(metadata = updatedMeta)
      }
      Result.success(passed)
    } catch (e: VaultKeyInvalidatedException) {
      _vaultState.value = VaultState.KeyInvalidated(current.metadata)
      Result.failure(e)
    } catch (t: Throwable) {
      Result.failure(t)
    } finally {
      answers.values.forEach { Arrays.fill(it, '\u0000') }
      appPin?.let { Arrays.fill(it, '\u0000') }
    }
  }

  override suspend fun switchNetwork(network: Network) {
    val current = _vaultState.value as? VaultState.Unlocked ?: return
    val updatedMeta =
      if (current.metadata.lastActiveNetwork != network) {
        current.metadata.copy(lastActiveNetwork = network).also { meta ->
          withContext(Dispatchers.IO) { metadataStore.save(meta) }
        }
      } else {
        current.metadata
      }
    if (current.activeNetwork == network && current.walletKitSynced) {
      if (updatedMeta != current.metadata) {
        _vaultState.value = current.copy(metadata = updatedMeta)
      }
      return
    }
    val synced = syncWalletKit(updatedMeta, network)
    _vaultState.value = current.copy(metadata = updatedMeta, activeNetwork = network, walletKitSynced = synced)
    cleanupScope.launch { txManager?.recoverPendingTransactions(updatedMeta.addressFor(network)) }
  }

  override fun lockWallet() {
    secureKeyStore.clearPinSession()
    val current = _vaultState.value as? VaultState.Unlocked ?: return
    _vaultState.value = VaultState.Locked(current.metadata)
  }

  override fun resetVault() {
    secureKeyStore.clearVault()
    metadataStore.clear()
    cleanupScope.launch {
      balanceRepository?.clearCache()
      txManager?.clearAll()
    }
    _vaultState.value = VaultState.Uninitialized
  }

  override fun getReceiveAddress(network: Network?): TonAddress? {
    val current = _vaultState.value as? VaultState.Unlocked ?: return null
    val targetNetwork = network ?: current.activeNetwork
    return current.metadata.addressFor(targetNetwork)
  }

  override fun buildReceiveTransferUri(
    network: Network?,
    amount: Money?,
    comment: String?,
  ): String? {
    val addr = getReceiveAddress(network) ?: return null
    return TonTransferUri.build(
      recipient = addr,
      bounceable = false,
      amount = amount,
      comment = comment,
    )
  }

  override fun validateReceiveInput(input: String, network: Network?): AddressCheckResult {
    val targetNetwork =
      network ?: (_vaultState.value as? VaultState.Unlocked)?.activeNetwork ?: Network.MAINNET
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
      return AddressCheckResult.Invalid("Enter a TON address or ton://transfer/ link")
    }
    return if (trimmed.startsWith("ton://", ignoreCase = true)) {
      TonTransferUri.parse(trimmed, targetNetwork)
        .fold(
          onSuccess = { parsedUri ->
            AddressCheckResult.Valid(
              parsedAddress = parsedUri.parsedRecipient,
              uriAmount = parsedUri.amount,
              uriComment = parsedUri.comment,
            )
          },
          onFailure = { err ->
            AddressCheckResult.Invalid(err.message ?: "Invalid TON transfer link")
          },
        )
    } else {
      TonAddress.parseDetailed(trimmed, targetNetwork)
        .fold(
          onSuccess = { parsed -> AddressCheckResult.Valid(parsedAddress = parsed) },
          onFailure = { err -> AddressCheckResult.Invalid(err.message ?: "Invalid TON address") },
        )
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeBalance(): Flow<BalanceState> =
    vaultState.flatMapLatest { state ->
      when (state) {
        is VaultState.Unlocked -> {
          val address = state.metadata.addressFor(state.activeNetwork)
          balanceRepository?.observeBalance(address)
            ?: flowOf(BalanceState(network = state.activeNetwork, address = address))
        }
        else -> flowOf(BalanceState())
      }
    }

  override suspend fun refreshBalance(force: Boolean) {
    val current = _vaultState.value as? VaultState.Unlocked ?: return
    val address = current.metadata.addressFor(current.activeNetwork)
    balanceRepository?.refreshBalance(address, force = force)
  }

  override suspend fun prepareTransfer(
    recipientInput: String,
    amountText: String,
    comment: String?,
    assetKind: TxAssetKind,
  ): Result<TransferPreview> {
    val current =
      _vaultState.value as? VaultState.Unlocked
        ?: return Result.failure(VaultException("Unlock your wallet first"))
    val manager =
      txManager ?: return Result.failure(VaultException("Transfer service unavailable"))
    val activeNetwork = current.activeNetwork
    val fromAddress = current.metadata.addressFor(activeNetwork)

    val checked = validateReceiveInput(recipientInput, activeNetwork)
    if (checked is AddressCheckResult.Invalid) {
      return Result.failure(VaultException(checked.reason))
    }
    val validAddress = checked as AddressCheckResult.Valid

    val usdtSpec = if (assetKind == TxAssetKind.JETTON) JettonAllowList.usdtFor(activeNetwork) else null
    val expectedDecimals = usdtSpec?.decimals ?: GRAM_DECIMALS
    val symbolLabel = usdtSpec?.symbol ?: "GRAM"

    val cleanAmountText = amountText.trim()
    val amount =
      if (cleanAmountText.isNotEmpty()) {
        runCatching { Money.fromDecimalString(cleanAmountText, expectedDecimals) }
          .getOrElse {
            return Result.failure(
              VaultException("Enter a valid $symbolLabel amount (up to $expectedDecimals decimals)")
            )
          }
      } else {
        if (assetKind == TxAssetKind.GRAM) {
          validAddress.uriAmount
            ?: return Result.failure(VaultException("Enter a GRAM amount to send"))
        } else {
          return Result.failure(VaultException("Enter a USDT amount to send"))
        }
      }

    if (amount.units <= BigInteger.ZERO) {
      return Result.failure(VaultException("Send amount must be greater than zero"))
    }

    val normalizedComment =
      comment?.trim()?.takeIf { it.isNotEmpty() } ?: validAddress.uriComment?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedComment != null) {
      val commentBytes = normalizedComment.toByteArray(Charsets.UTF_8).size
      if (commentBytes > TransferBuilder.MAX_COMMENT_UTF8_BYTES) {
        return Result.failure(
          VaultException(
            "Comment is too long ($commentBytes bytes; maximum ${TransferBuilder.MAX_COMMENT_UTF8_BYTES} bytes)"
          )
        )
      }
    }

    return runCatching {
      manager.ensureNoActivePendingTx(fromAddress)

      val chainCtx =
        manager
          .fetchTransferChainContext(fromAddress, validAddress.parsedAddress.address)
          .getOrThrow()

      val senderNeedsStateInit =
        chainCtx.senderStatus == AccountOnChainStatus.NONEXIST ||
          chainCtx.senderStatus == AccountOnChainStatus.UNINIT

      val nowSec = clockMs() / 1000L
      val validUntilEpochSec = nowSec + TransferBuilder.DEFAULT_VALID_UNTIL_TTL_SECONDS
      val publicKey32 = hexToBytes(current.metadata.publicKeyHex)

      if (assetKind == TxAssetKind.GRAM) {
        val effectiveBounce =
          chainCtx.destinationStatus != AccountOnChainStatus.NONEXIST &&
            chainCtx.destinationStatus != AccountOnChainStatus.UNINIT

        val dummyBoc =
          withContext(Dispatchers.Default) {
            TransferBuilder.buildDummyTransferBocForEstimation(
              senderAddress = fromAddress,
              publicKey32 = publicKey32,
              walletVersion = current.metadata.walletVersion,
              destination = validAddress.parsedAddress.address,
              amount = amount,
              bounce = effectiveBounce,
              seqno = chainCtx.senderSeqno,
              validUntilEpochSec = validUntilEpochSec,
              needsStateInit = senderNeedsStateInit,
              comment = normalizedComment,
            )
          }

        val remoteFee = manager.estimateTransferFee(fromAddress, dummyBoc)
        val estimatedFee = remoteFee ?: Money.ofNano(TransferBuilder.LOCAL_FALLBACK_FEE_NANO)
        val totalDebit = amount + estimatedFee
        if (chainCtx.senderBalance.units < totalDebit.units) {
          throw VaultException(
            "Insufficient GRAM balance: available ${chainCtx.senderBalance.format()} GRAM, needed ${totalDebit.format()} GRAM (including fee)"
          )
        }

        TransferPreview(
          network = activeNetwork,
          fromAddress = fromAddress,
          destination = validAddress.parsedAddress,
          destinationStatus = chainCtx.destinationStatus,
          effectiveBounce = effectiveBounce,
          amount = amount,
          estimatedFee = estimatedFee,
          totalDebit = totalDebit,
          comment = normalizedComment,
          seqno = chainCtx.senderSeqno,
          senderNeedsStateInit = senderNeedsStateInit,
          validUntilEpochSec = validUntilEpochSec,
          feeIsEstimateFallback = (remoteFee == null),
          assetKind = TxAssetKind.GRAM,
        )
      } else {
        val spec = usdtSpec!!
        val jettonCtx = manager.fetchJettonChainContext(fromAddress).getOrThrow()
        val jettonWallet =
          jettonCtx.jettonWalletAddress
            ?: throw VaultException("No active USDT Jetton wallet found on this account")
        if (jettonCtx.balance.units < amount.units) {
          throw VaultException(
            "Insufficient USDT balance: available ${jettonCtx.balance.format()} USDT, needed ${amount.format()} USDT"
          )
        }

        val attachedGram = Money.ofNano(TransferBuilder.DEFAULT_JETTON_ATTACHED_GRAM_NANO)
        val dummyBoc =
          withContext(Dispatchers.Default) {
            TransferBuilder.buildDummyJettonTransferBocForEstimation(
              senderAddress = fromAddress,
              senderJettonWallet = jettonWallet,
              publicKey32 = publicKey32,
              walletVersion = current.metadata.walletVersion,
              destination = validAddress.parsedAddress.address,
              jettonAmount = amount,
              seqno = chainCtx.senderSeqno,
              validUntilEpochSec = validUntilEpochSec,
              needsStateInit = senderNeedsStateInit,
              comment = normalizedComment,
              attachedGramNano = attachedGram.units.toLong(),
            )
          }

        val remoteFee = manager.estimateTransferFee(fromAddress, dummyBoc)
        val estimatedFee = remoteFee ?: Money.ofNano(TransferBuilder.LOCAL_JETTON_FALLBACK_FEE_NANO)
        val requiredGram = attachedGram + estimatedFee
        if (chainCtx.senderBalance.units < requiredGram.units) {
          throw VaultException(
            "Insufficient GRAM for USDT gas: available ${chainCtx.senderBalance.format()} GRAM, needed ${requiredGram.format()} GRAM"
          )
        }

        TransferPreview(
          network = activeNetwork,
          fromAddress = fromAddress,
          destination = validAddress.parsedAddress,
          destinationStatus = chainCtx.destinationStatus,
          effectiveBounce = true,
          amount = amount,
          estimatedFee = estimatedFee,
          totalDebit = amount,
          comment = normalizedComment,
          seqno = chainCtx.senderSeqno,
          senderNeedsStateInit = senderNeedsStateInit,
          validUntilEpochSec = validUntilEpochSec,
          feeIsEstimateFallback = (remoteFee == null),
          assetKind = TxAssetKind.JETTON,
          jettonMasterRaw = spec.masterAddress.raw,
          senderJettonWallet = jettonWallet,
          attachedGramNano = attachedGram,
        )
      }
    }
  }

  override suspend fun confirmAndSend(
    preview: TransferPreview,
    appPin: CharArray?,
  ): Result<TxRecord> {
    val current = _vaultState.value as? VaultState.Unlocked
    if (current == null) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Unlock your wallet first"))
    }
    val manager = txManager
    if (manager == null) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Transfer service unavailable"))
    }
    if (current.activeNetwork != preview.network) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Active network changed. Review transfer again."))
    }

    val expectedFrom = current.metadata.addressFor(preview.network)
    if (expectedFrom.raw != preview.fromAddress.raw) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Active wallet mismatch"))
    }

    val nowMs = clockMs()
    val nowEpochSec = nowMs / 1000L
    if (nowEpochSec >= preview.validUntilEpochSec) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return Result.failure(VaultException("Transfer review expired. Prepare transfer again."))
    }

    when (current.metadata.securityMode) {
      VaultSecurityMode.DEV_UNAUTH_KEYSTORE -> {
        if (!devSkipAuth) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultException("Development vault cannot sign in release build"))
        }
      }
      VaultSecurityMode.DEVICE_KEYSTORE -> {
        when (val auth = authGate.authenticate(AuthReason.SIGN_ACTION)) {
          is AuthResult.Authenticated -> Unit
          is AuthResult.Cancelled -> return Result.failure(VaultException("Signing cancelled"))
          is AuthResult.NoDeviceSecurity ->
            return Result.failure(VaultException("Device lock screen is required"))
          is AuthResult.Failed -> return Result.failure(VaultException(auth.message))
        }
      }
      VaultSecurityMode.APP_PIN_WITH_KEYSTORE -> {
        if (appPin == null || appPin.size < 4) {
          appPin?.let { Arrays.fill(it, '\u0000') }
          return Result.failure(VaultAuthRequiredException())
        }
      }
    }

    val useAppPin = current.metadata.securityMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE
    val requireUserAuth = current.metadata.securityMode != VaultSecurityMode.DEV_UNAUTH_KEYSTORE

    return try {
      manager.ensureNoActivePendingTx(preview.fromAddress)

      val signEpochSec = clockMs() / 1000L
      val txValidUntilSec = signEpochSec + TransferBuilder.DEFAULT_VALID_UNTIL_TTL_SECONDS
      val publicKey32 = hexToBytes(current.metadata.publicKeyHex)

      val signed =
        withContext(Dispatchers.IO) {
          secureKeyStore.withDecryptedSeed(
            pin = appPin,
            useAppPinMode = useAppPin,
            requireUserAuth = requireUserAuth,
          ) { seed32 ->
            if (preview.assetKind == TxAssetKind.JETTON) {
              val jettonWallet =
                preview.senderJettonWallet
                  ?: throw VaultException("Missing sender Jetton wallet address")
              val attachedNano =
                preview.attachedGramNano?.units?.toLong()
                  ?: TransferBuilder.DEFAULT_JETTON_ATTACHED_GRAM_NANO
              TransferBuilder.buildSignedJettonTransfer(
                seed32 = seed32,
                publicKey32 = publicKey32,
                senderAddress = preview.fromAddress,
                senderJettonWallet = jettonWallet,
                walletVersion = current.metadata.walletVersion,
                destination = preview.destination.address,
                jettonAmount = preview.amount,
                seqno = preview.seqno,
                validUntilEpochSec = txValidUntilSec,
                needsStateInit = preview.senderNeedsStateInit,
                comment = preview.comment,
                attachedGramNano = attachedNano,
              )
            } else {
              TransferBuilder.buildSignedTransfer(
                seed32 = seed32,
                publicKey32 = publicKey32,
                senderAddress = preview.fromAddress,
                walletVersion = current.metadata.walletVersion,
                destination = preview.destination.address,
                amount = preview.amount,
                bounce = preview.effectiveBounce,
                seqno = preview.seqno,
                validUntilEpochSec = txValidUntilSec,
                needsStateInit = preview.senderNeedsStateInit,
                comment = preview.comment,
              )
            }
          }
        }

      val record =
        TxRecord(
          id = UUID.randomUUID().toString(),
          network = preview.network,
          accountRaw = preview.fromAddress.raw,
          messageHash = signed.messageHashHex,
          normalizedHash = signed.normalizedHashHex,
          txHash = null,
          seqno = signed.seqno,
          direction = TxDirection.OUT,
          kind = preview.assetKind,
          amount = preview.amount,
          fee = preview.estimatedFee,
          counterpartyRaw = preview.destination.address.raw,
          comment = preview.comment,
          state = TxStatus.SIGNED,
          validUntilEpochSec = signed.validUntilEpochSec,
          lt = null,
          createdAtEpochMs = nowMs,
          updatedAtEpochMs = nowMs,
          jettonMaster = preview.jettonMasterRaw,
        )

      manager.persistSignedAndBroadcast(record, signed.externalBocBase64)
    } catch (e: VaultKeyInvalidatedException) {
      _vaultState.value = VaultState.KeyInvalidated(current.metadata)
      Result.failure(e)
    } catch (t: Throwable) {
      Result.failure(t)
    } finally {
      appPin?.let { Arrays.fill(it, '\u0000') }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeTransactions(): Flow<List<TxRecord>> =
    vaultState.flatMapLatest { state ->
      val manager = txManager
      if (state is VaultState.Unlocked && manager != null) {
        val address = state.metadata.addressFor(state.activeNetwork)
        manager.observeTransactions(address.raw, state.activeNetwork)
      } else {
        flowOf(emptyList())
      }
    }

  override suspend fun recoverPendingTransactions() {
    val current = _vaultState.value as? VaultState.Unlocked ?: return
    txManager?.recoverPendingTransactions(current.metadata.addressFor(current.activeNetwork))
  }

  override suspend fun refreshTransactionStatus(txId: String): TxRecord? =
    txManager?.refreshTransactionStatus(txId)

  override fun preferredNetwork(): Network =
    metadataStore.load()?.lastActiveNetwork ?: Network.MAINNET

  override suspend fun syncTransactionHistory(
    limit: Int,
    ignoreLtShortcut: Boolean,
  ): Result<HistorySyncSummary> {
    val current =
      _vaultState.value as? VaultState.Unlocked
        ?: return Result.failure(VaultException("Unlock your wallet first"))
    val manager =
      txManager ?: return Result.failure(VaultException("Transaction history service unavailable"))
    val address = current.metadata.addressFor(current.activeNetwork)
    val knownLt =
      if (ignoreLtShortcut) null else balanceRepository?.getCachedLastTxLt(address)
    return manager.syncAccountHistory(address, limit = limit, knownAccountLt = knownLt)
  }

  private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    require(clean.length % 2 == 0) { "Invalid hex string length" }
    return ByteArray(clean.length / 2) { i ->
      clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  private suspend fun syncWalletKit(metadata: VaultMetadata, network: Network): Boolean {
    val bridge = walletKitBridge ?: return false
    val useAppPin = metadata.securityMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE
    val requireUserAuth = metadata.securityMode != VaultSecurityMode.DEV_UNAUTH_KEYSTORE
    return runCatching {
        val kitAddr =
          bridge.attachWallet(
            publicKeyHex = metadata.publicKeyHex,
            network = network,
            walletVersion = metadata.walletVersion,
            useAppPinMode = useAppPin,
            requireUserAuth = requireUserAuth,
          ) ?: return false
        val expectedAddr = metadata.addressFor(network)
        kitAddr.raw == expectedAddr.raw
      }
      .getOrDefault(false)
  }
}

package com.example

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.WalletCoreFactory
import com.example.core.data.AddressBookEntry
import com.example.core.keystore.BiometricGate
import com.example.core.keystore.VaultAuthRequiredException
import com.example.core.model.BackupChallenge
import com.example.core.model.BalanceState
import com.example.core.model.CandidateAccount
import com.example.core.model.ChainMonitorState
import com.example.core.model.MnemonicPhrase
import com.example.core.model.Network
import com.example.core.model.TransferPreview
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.VaultSecurityMode
import com.example.core.model.VaultState
import com.example.core.network.AllProvidersFailedException
import com.example.core.network.FiatRates
import com.example.core.network.InvalidPayloadException
import com.example.core.network.RateLimitedException
import com.example.core.network.RejectedTransactionException
import com.example.core.network.ServerErrorException
import com.example.core.network.TransportException
import com.example.core.tx.TransferRejectedException
import com.example.core.wallet.AddressCheckResult
import com.example.core.wallet.AppSettings
import com.example.core.wallet.MnemonicService
import com.example.core.wallet.WalletCore
import java.lang.ref.WeakReference
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActiveSubScreen {
  NONE,
  RECOVERY_PHRASE,
  BACKUP_VERIFY,
  IMPORT_PHRASE,
  IMPORT_SELECT_ACCOUNT,
  SEND_FORM,
  SEND_CONFIRM,
  SEND_STATUS,
  TX_HISTORY,
  ADDRESS_BOOK,
}

enum class PinPromptMode {
  SETUP_FOR_CREATE,
  SETUP_FOR_IMPORT,
  ENTER_FOR_UNLOCK,
  ENTER_FOR_PHRASE,
  ENTER_FOR_BACKUP_CONFIRM,
  ENTER_FOR_SIGN_SEND,
}

data class AppUiState(
  val vaultState: VaultState = VaultState.Uninitialized,
  val subScreen: ActiveSubScreen = ActiveSubScreen.NONE,
  val phraseWordsForDisplay: List<String> = emptyList(),
  val backupChallenge: BackupChallenge? = null,
  val importCandidates: List<CandidateAccount> = emptyList(),
  val importIsAmbiguous24: Boolean = false,
  val pinPromptMode: PinPromptMode? = null,
  val pinPromptError: String? = null,
  val isBusy: Boolean = false,
  val errorMessage: String? = null,
  val bannerMessage: String? = null,
  val isDeviceSecure: Boolean = true,
  val isDevAuthBypassed: Boolean = false,
  val balanceState: BalanceState = BalanceState(),
  val fiatRates: FiatRates = FiatRates(),
  val selectedSendAsset: TxAssetKind = TxAssetKind.GRAM,
  val sendRecipientInput: String = "",
  val sendAmountInput: String = "",
  val sendCommentInput: String = "",
  val transferPreview: TransferPreview? = null,
  val activeSendTxId: String? = null,
  val txRecords: List<TxRecord> = emptyList(),
  val addressBookEntries: List<AddressBookEntry> = emptyList(),
  val appSettings: AppSettings = AppSettings(autoLockOnBackground = !BuildConfig.DEBUG, liveMonitorEnabled = true),
  val monitorState: ChainMonitorState = ChainMonitorState(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WalletViewModel(application: Application) : AndroidViewModel(application) {
  private var activityRef: WeakReference<FragmentActivity> = WeakReference(null)
  private val authGate = BiometricGate(application.applicationContext) { activityRef.get() }
  private val dependencies =
    WalletCoreFactory.createDependencies(applicationContext = application.applicationContext, authGate = authGate)
  private val walletCore: WalletCore = dependencies.walletCore
  private val addressBookRepository = dependencies.addressBookRepository
  private val priceService = dependencies.priceService
  private val appSettingsStore = dependencies.appSettingsStore

  private var transientPhrase: MnemonicPhrase? = null
  private var transientImportPhrase: MnemonicPhrase? = null
  private var pendingImportCandidate: CandidateAccount? = null
  private var pendingConfirmAnswers: Map<Int, CharArray>? = null
  private var balanceRefreshJob: Job? = null
  private var monitorJob: Job? = null
  private var isForegroundActive: Boolean = false
  private var lastUnlockedBalanceKey: Pair<String, Network>? = null
  private val initialSyncedAccounts = mutableSetOf<Pair<String, Network>>()

  private val _uiState =
    MutableStateFlow(
      AppUiState(
        vaultState = walletCore.vaultState.value,
        isDeviceSecure = walletCore.isDeviceSecure(),
        isDevAuthBypassed = walletCore.isDevAuthBypassed,
      )
    )
  val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      walletCore.vaultState.collectLatest { vault ->
        _uiState.update { state ->
          val isImportSubScreen =
            state.subScreen == ActiveSubScreen.IMPORT_PHRASE ||
              state.subScreen == ActiveSubScreen.IMPORT_SELECT_ACCOUNT
          val shouldClearUnlockedSub =
            vault !is VaultState.Unlocked && state.subScreen != ActiveSubScreen.NONE && !isImportSubScreen
          if (shouldClearUnlockedSub) clearTransientPhrase()
          state.copy(
            vaultState = vault,
            subScreen = if (shouldClearUnlockedSub) ActiveSubScreen.NONE else state.subScreen,
            phraseWordsForDisplay =
              if (shouldClearUnlockedSub) emptyList() else state.phraseWordsForDisplay,
          )
        }
        if (vault is VaultState.Unlocked) {
          val key = vault.metadata.publicKeyHex to vault.activeNetwork
          if (lastUnlockedBalanceKey != key) {
            lastUnlockedBalanceKey = key
            refreshBalance(force = false)
            restartMonitorIfEligible()
          } else {
            restartMonitorIfEligible()
          }
        } else {
          lastUnlockedBalanceKey = null
          balanceRefreshJob?.cancel()
          stopMonitor()
        }
      }
    }
    viewModelScope.launch {
      walletCore.observeBalance().collectLatest { bal ->
        _uiState.update { it.copy(balanceState = bal) }
      }
    }
    viewModelScope.launch {
      walletCore.observeTransactions().collectLatest { txs ->
        _uiState.update { it.copy(txRecords = txs) }
      }
    }
    viewModelScope.launch {
      priceService.rates.collectLatest { rates ->
        _uiState.update { it.copy(fiatRates = rates) }
      }
    }
    viewModelScope.launch {
      appSettingsStore.settingsFlow.collectLatest { settings ->
        _uiState.update { state ->
          state.copy(
            appSettings = settings,
            monitorState = state.monitorState.copy(enabled = settings.liveMonitorEnabled),
          )
        }
        if (settings.liveMonitorEnabled) {
          restartMonitorIfEligible()
        } else {
          stopMonitor()
        }
      }
    }
    viewModelScope.launch {
      walletCore.vaultState
        .map { (it as? VaultState.Unlocked)?.activeNetwork }
        .distinctUntilChanged()
        .flatMapLatest { network ->
          if (network != null) addressBookRepository.observeEntries(network)
          else flowOf(emptyList())
        }
        .collectLatest { contacts ->
          _uiState.update { it.copy(addressBookEntries = contacts) }
        }
    }

    // In development preview mode, auto-unlock an existing DEV_UNAUTH_KEYSTORE vault on startup
    // so the user lands directly on their saved wallet across preview reloads.
    if (
      walletCore.vaultState.value is VaultState.Locked &&
        walletCore.isDevAuthBypassed &&
        walletCore.activeSecurityMode() == VaultSecurityMode.DEV_UNAUTH_KEYSTORE
    ) {
      executeUnlockWallet(appPin = null)
    }
  }

  fun setForegroundMonitoring(active: Boolean) {
    if (isForegroundActive == active) return
    isForegroundActive = active
    if (active) {
      restartMonitorIfEligible()
    } else {
      stopMonitor()
    }
  }

  fun onAppBackgrounded() {
    setForegroundMonitoring(false)
    val state = _uiState.value
    if (
      state.appSettings.autoLockOnBackground &&
        state.vaultState is VaultState.Unlocked &&
        !state.isBusy &&
        state.pinPromptMode == null
    ) {
      lockWallet()
    }
  }

  fun toggleMonitor() {
    val nextEnabled = !_uiState.value.monitorState.enabled
    setLiveMonitorEnabled(nextEnabled)
  }

  fun setLiveMonitorEnabled(enabled: Boolean) {
    _uiState.update {
      it.copy(
        appSettings = it.appSettings.copy(liveMonitorEnabled = enabled),
        monitorState = it.monitorState.copy(enabled = enabled),
      )
    }
    viewModelScope.launch { appSettingsStore.setLiveMonitorEnabled(enabled) }
    if (enabled) {
      restartMonitorIfEligible()
    } else {
      stopMonitor()
    }
  }

  fun setAutoLockOnBackground(enabled: Boolean) {
    _uiState.update { it.copy(appSettings = it.appSettings.copy(autoLockOnBackground = enabled)) }
    viewModelScope.launch { appSettingsStore.setAutoLockOnBackground(enabled) }
  }

  fun syncNow(ignoreLtShortcut: Boolean = true) {
    if (_uiState.value.vaultState !is VaultState.Unlocked) return
    viewModelScope.launch { runSingleMonitorCycle(ignoreLtShortcut = ignoreLtShortcut) }
  }

  private fun stopMonitor() {
    monitorJob?.cancel()
    monitorJob = null
    _uiState.update {
      it.copy(monitorState = it.monitorState.copy(isActive = false, isSyncing = false))
    }
  }

  private fun restartMonitorIfEligible() {
    val vault = _uiState.value.vaultState as? VaultState.Unlocked
    val enabled = _uiState.value.monitorState.enabled
    val sub = _uiState.value.subScreen
    val pauseForSend = sub == ActiveSubScreen.SEND_FORM || sub == ActiveSubScreen.SEND_CONFIRM
    if (vault == null || !isForegroundActive || !enabled || pauseForSend) {
      stopMonitor()
      return
    }
    monitorJob?.cancel()
    _uiState.update {
      it.copy(monitorState = it.monitorState.copy(isActive = true, lastError = null))
    }
    monitorJob =
      viewModelScope.launch {
        while (isActive) {
          runSingleMonitorCycle()
          delay(MONITOR_POLL_INTERVAL_MS)
        }
      }
  }

  private suspend fun runSingleMonitorCycle(ignoreLtShortcut: Boolean = false) {
    val unlocked = _uiState.value.vaultState as? VaultState.Unlocked ?: return
    val accountKey = unlocked.metadata.publicKeyHex to unlocked.activeNetwork
    _uiState.update {
      it.copy(monitorState = it.monitorState.copy(isSyncing = true))
    }
    try {
      walletCore.refreshBalance(force = true)
      priceService.refreshRatesIfNeeded(force = false)
      val hadHistoryPreviously = _uiState.value.txRecords.isNotEmpty()
      val baselineDone = initialSyncedAccounts.contains(accountKey)
      val historyResult = walletCore.syncTransactionHistory(ignoreLtShortcut = ignoreLtShortcut || !baselineDone)
      walletCore.recoverPendingTransactions()
      initialSyncedAccounts.add(accountKey)

      historyResult.fold(
        onSuccess = { summary ->
          val newIncoming = summary.newIncomingTxs
          val banner =
            if (newIncoming.isNotEmpty() && (baselineDone || !hadHistoryPreviously)) {
              val newest = newIncoming.first()
              getApplication<Application>()
                .getString(R.string.monitor_incoming_banner, newest.amount.format(), newest.assetSymbol)
            } else {
              null
            }
          _uiState.update { state ->
            state.copy(
              bannerMessage = banner ?: state.bannerMessage,
              monitorState =
                state.monitorState.copy(
                  isSyncing = false,
                  lastSyncEpochMs = System.currentTimeMillis(),
                  lastProviderName = summary.providerName,
                  lastError = null,
                ),
            )
          }
        },
        onFailure = { err ->
          _uiState.update { state ->
            state.copy(
              monitorState =
                state.monitorState.copy(
                  isSyncing = false,
                  lastError = formatUserFacingError(err),
                ),
            )
          }
        },
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      _uiState.update { state ->
        state.copy(
          monitorState =
            state.monitorState.copy(
              isSyncing = false,
              lastError = formatUserFacingError(t),
            ),
        )
      }
    }
  }

  fun refreshBalance(force: Boolean = true) {
    if (_uiState.value.vaultState !is VaultState.Unlocked) return
    balanceRefreshJob?.cancel()
    balanceRefreshJob =
      viewModelScope.launch {
        walletCore.refreshBalance(force = force)
        priceService.refreshRatesIfNeeded(force = force)
      }
  }

  fun attachActivity(activity: FragmentActivity?) {
    activityRef = WeakReference(activity)
    _uiState.update { it.copy(isDeviceSecure = walletCore.isDeviceSecure()) }
  }

  fun createWallet() {
    if (_uiState.value.isBusy) return
    val targetMode =
      when {
        walletCore.isDevAuthBypassed -> VaultSecurityMode.DEV_UNAUTH_KEYSTORE
        walletCore.isDeviceSecure() -> VaultSecurityMode.DEVICE_KEYSTORE
        else -> VaultSecurityMode.APP_PIN_WITH_KEYSTORE
      }
    if (targetMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE) {
      _uiState.update {
        it.copy(pinPromptMode = PinPromptMode.SETUP_FOR_CREATE, pinPromptError = null, errorMessage = null)
      }
      return
    }
    executeCreateWallet(appPin = null)
  }

  private fun executeCreateWallet(appPin: CharArray?) {
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    viewModelScope.launch {
      walletCore
        .createWallet(Network.MAINNET, appPin = appPin)
        .onSuccess { newWallet ->
          setTransientPhrase(newWallet.mnemonic)
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              pinPromptError = null,
              subScreen = ActiveSubScreen.RECOVERY_PHRASE,
              phraseWordsForDisplay = extractDisplayWords(newWallet.mnemonic),
              errorMessage = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              errorMessage = err.message ?: err::class.java.simpleName,
            )
          }
        }
    }
  }

  fun openImportFlow() {
    if (_uiState.value.isBusy) return
    clearTransientImportState()
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.IMPORT_PHRASE,
        importCandidates = emptyList(),
        importIsAmbiguous24 = false,
        errorMessage = null,
      )
    }
  }

  fun discoverImportAccounts(rawWordsChars: CharArray) {
    if (_uiState.value.isBusy) {
      Arrays.fill(rawWordsChars, '\u0000')
      return
    }
    val parsedPhrase =
      try {
        MnemonicService.parseSpaceSeparatedChars(rawWordsChars)
      } finally {
        Arrays.fill(rawWordsChars, '\u0000')
      }
    _uiState.update { it.copy(isBusy = true, errorMessage = null) }
    viewModelScope.launch {
      walletCore
        .discoverImportAccounts(parsedPhrase)
        .onSuccess { discovery ->
          setTransientImportPhrase(parsedPhrase)
          _uiState.update {
            it.copy(
              isBusy = false,
              subScreen = ActiveSubScreen.IMPORT_SELECT_ACCOUNT,
              importCandidates = discovery.candidates,
              importIsAmbiguous24 = discovery.isAmbiguous24,
              errorMessage = null,
            )
          }
        }
        .onFailure { err ->
          parsedPhrase.wipe()
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = err.message ?: err::class.java.simpleName,
            )
          }
        }
    }
  }

  fun selectImportedCandidate(candidate: CandidateAccount) {
    if (_uiState.value.isBusy) return
    if (transientImportPhrase == null) {
      _uiState.update { it.copy(errorMessage = "Recovery phrase session expired. Enter words again.") }
      return
    }
    val targetMode =
      when {
        walletCore.isDevAuthBypassed -> VaultSecurityMode.DEV_UNAUTH_KEYSTORE
        walletCore.isDeviceSecure() -> VaultSecurityMode.DEVICE_KEYSTORE
        else -> VaultSecurityMode.APP_PIN_WITH_KEYSTORE
      }
    if (targetMode == VaultSecurityMode.APP_PIN_WITH_KEYSTORE) {
      pendingImportCandidate = candidate
      _uiState.update {
        it.copy(pinPromptMode = PinPromptMode.SETUP_FOR_IMPORT, pinPromptError = null, errorMessage = null)
      }
      return
    }
    executeSelectImportedCandidate(candidate = candidate, appPin = null)
  }

  private fun executeSelectImportedCandidate(candidate: CandidateAccount, appPin: CharArray?) {
    val phrase = transientImportPhrase
    if (phrase == null) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      _uiState.update {
        it.copy(
          pinPromptMode = null,
          errorMessage = "Recovery phrase session expired. Enter words again.",
        )
      }
      return
    }
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    viewModelScope.launch {
      walletCore
        .selectImportedAccount(
          phrase = phrase,
          candidate = candidate,
          initialNetwork = Network.MAINNET,
          appPin = appPin,
        )
        .onSuccess {
          clearTransientImportState()
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              pinPromptError = null,
              subScreen = ActiveSubScreen.NONE,
              importCandidates = emptyList(),
              importIsAmbiguous24 = false,
              errorMessage = null,
            )
          }
        }
        .onFailure { err ->
          pendingImportCandidate = null
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              errorMessage = err.message ?: err::class.java.simpleName,
            )
          }
        }
    }
  }

  fun backFromImportSelect() {
    pendingImportCandidate = null
    transientImportPhrase?.wipe()
    transientImportPhrase = null
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.IMPORT_PHRASE,
        importCandidates = emptyList(),
        importIsAmbiguous24 = false,
        errorMessage = null,
      )
    }
  }

  fun closeImportFlow() {
    clearTransientImportState()
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.NONE,
        importCandidates = emptyList(),
        importIsAmbiguous24 = false,
        errorMessage = null,
      )
    }
  }

  fun unlockWallet() {
    if (_uiState.value.isBusy) return
    if (walletCore.activeSecurityMode() == VaultSecurityMode.APP_PIN_WITH_KEYSTORE) {
      _uiState.update {
        it.copy(pinPromptMode = PinPromptMode.ENTER_FOR_UNLOCK, pinPromptError = null, errorMessage = null)
      }
      return
    }
    executeUnlockWallet(appPin = null)
  }

  private fun executeUnlockWallet(appPin: CharArray?) {
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    viewModelScope.launch {
      val targetNetwork = walletCore.preferredNetwork()
      walletCore
        .unlockWallet(targetNetwork, appPin = appPin)
        .onSuccess {
          _uiState.update {
            it.copy(isBusy = false, pinPromptMode = null, pinPromptError = null, errorMessage = null)
          }
        }
        .onFailure { err ->
          val stayInPin = _uiState.value.pinPromptMode != null
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptError = if (stayInPin) err.message ?: err::class.java.simpleName else null,
              errorMessage = if (!stayInPin) err.message ?: err::class.java.simpleName else null,
            )
          }
        }
    }
  }

  fun openBackupFlow() {
    val unlocked = _uiState.value.vaultState as? VaultState.Unlocked ?: return
    if (_uiState.value.isBusy) return
    executeOpenBackupFlow(appPin = null, fallbackPub = unlocked.metadata.publicKeyHex)
  }

  private fun executeOpenBackupFlow(appPin: CharArray?, fallbackPub: String) {
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    viewModelScope.launch {
      walletCore
        .revealRecoveryPhrase(appPin = appPin)
        .onSuccess { phrase ->
          setTransientPhrase(phrase)
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              pinPromptError = null,
              subScreen = ActiveSubScreen.RECOVERY_PHRASE,
              phraseWordsForDisplay = extractDisplayWords(phrase),
              errorMessage = null,
            )
          }
        }
        .onFailure { err ->
          if (err is VaultAuthRequiredException) {
            _uiState.update {
              it.copy(isBusy = false, pinPromptMode = PinPromptMode.ENTER_FOR_PHRASE, pinPromptError = null)
            }
          } else {
            val stayInPin = _uiState.value.pinPromptMode != null
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptError = if (stayInPin) err.message ?: err::class.java.simpleName else null,
                bannerMessage = if (!stayInPin) err.message ?: fallbackPub else null,
              )
            }
          }
        }
    }
  }

  fun proceedFromPhraseScreen() {
    val unlocked = _uiState.value.vaultState as? VaultState.Unlocked ?: return
    if (unlocked.metadata.isBackedUp) {
      closeSubScreens()
    } else {
      val challenge = walletCore.createBackupChallenge()
      _uiState.update {
        it.copy(
          subScreen = ActiveSubScreen.BACKUP_VERIFY,
          backupChallenge = challenge,
          phraseWordsForDisplay = emptyList(),
          errorMessage = null,
        )
      }
      clearTransientPhrase()
    }
  }

  fun backToPhraseOrHome() {
    when (_uiState.value.subScreen) {
      ActiveSubScreen.BACKUP_VERIFY -> openBackupFlow()
      ActiveSubScreen.RECOVERY_PHRASE -> closeSubScreens()
      ActiveSubScreen.IMPORT_SELECT_ACCOUNT -> backFromImportSelect()
      ActiveSubScreen.IMPORT_PHRASE -> closeImportFlow()
      ActiveSubScreen.SEND_CONFIRM -> backFromSendConfirm()
      ActiveSubScreen.SEND_FORM,
      ActiveSubScreen.SEND_STATUS,
      ActiveSubScreen.TX_HISTORY,
      ActiveSubScreen.ADDRESS_BOOK -> closeSubScreens()
      ActiveSubScreen.NONE -> Unit
    }
  }

  fun openHistoryScreen() {
    if (_uiState.value.vaultState !is VaultState.Unlocked) return
    _uiState.update { it.copy(subScreen = ActiveSubScreen.TX_HISTORY, errorMessage = null) }
    syncNow(ignoreLtShortcut = true)
  }

  fun openAddressBook() {
    if (_uiState.value.vaultState !is VaultState.Unlocked) return
    _uiState.update { it.copy(subScreen = ActiveSubScreen.ADDRESS_BOOK, errorMessage = null) }
  }

  fun saveAddressBookContact(label: String, address: String) {
    val network = (_uiState.value.vaultState as? VaultState.Unlocked)?.activeNetwork ?: return
    viewModelScope.launch {
      addressBookRepository
        .saveContact(network = network, labelInput = label, addressInput = address)
        .onSuccess {
          val msg = getApplication<Application>().getString(R.string.address_book_saved_toast)
          _uiState.update { it.copy(errorMessage = null, bannerMessage = msg) }
        }
        .onFailure { err ->
          _uiState.update { it.copy(errorMessage = err.message ?: "Invalid contact details") }
        }
    }
  }

  fun deleteAddressBookContact(id: String) {
    viewModelScope.launch { addressBookRepository.deleteContact(id) }
  }

  fun startSendToContact(entry: AddressBookEntry) {
    if (_uiState.value.vaultState !is VaultState.Unlocked || _uiState.value.isBusy) return
    balanceRefreshJob?.cancel()
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.SEND_FORM,
        selectedSendAsset = TxAssetKind.GRAM,
        sendRecipientInput = entry.friendlyAddress,
        sendAmountInput = "",
        sendCommentInput = "",
        transferPreview = null,
        activeSendTxId = null,
        errorMessage = null,
      )
    }
    restartMonitorIfEligible()
  }

  fun selectAddressBookRecipient(entry: AddressBookEntry) {
    _uiState.update { it.copy(sendRecipientInput = entry.friendlyAddress, errorMessage = null) }
  }

  fun closeSubScreens() {
    clearTransientPhrase()
    clearPendingConfirmAnswers()
    clearTransientImportState()
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.NONE,
        phraseWordsForDisplay = emptyList(),
        backupChallenge = null,
        importCandidates = emptyList(),
        importIsAmbiguous24 = false,
        selectedSendAsset = TxAssetKind.GRAM,
        sendRecipientInput = "",
        sendAmountInput = "",
        sendCommentInput = "",
        transferPreview = null,
        activeSendTxId = null,
        errorMessage = null,
      )
    }
    restartMonitorIfEligible()
  }

  fun openSendFlow(initialAsset: TxAssetKind = TxAssetKind.GRAM) {
    if (_uiState.value.vaultState !is VaultState.Unlocked || _uiState.value.isBusy) return
    balanceRefreshJob?.cancel()
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.SEND_FORM,
        selectedSendAsset = initialAsset,
        sendRecipientInput = "",
        sendAmountInput = "",
        sendCommentInput = "",
        transferPreview = null,
        activeSendTxId = null,
        errorMessage = null,
      )
    }
    restartMonitorIfEligible()
  }

  fun updateSendAsset(assetKind: TxAssetKind) {
    _uiState.update { it.copy(selectedSendAsset = assetKind, errorMessage = null) }
  }

  fun updateSendRecipient(input: String) {
    val activeNetwork = (_uiState.value.vaultState as? VaultState.Unlocked)?.activeNetwork
    val parsed =
      if (input.trim().startsWith("ton://", ignoreCase = true) && activeNetwork != null) {
        walletCore.validateReceiveInput(input, activeNetwork) as? AddressCheckResult.Valid
      } else {
        null
      }
    _uiState.update { state ->
      state.copy(
        sendRecipientInput = input,
        sendAmountInput =
          if (state.sendAmountInput.isBlank() && parsed?.uriAmount != null) {
            parsed.uriAmount.format(minFractionDigits = 0, trimTrailingZeros = true)
          } else {
            state.sendAmountInput
          },
        sendCommentInput =
          if (state.sendCommentInput.isBlank() && !parsed?.uriComment.isNullOrBlank()) {
            parsed?.uriComment.orEmpty()
          } else {
            state.sendCommentInput
          },
        errorMessage = null,
      )
    }
  }

  fun updateSendAmount(input: String) {
    _uiState.update { it.copy(sendAmountInput = input, errorMessage = null) }
  }

  fun updateSendComment(input: String) {
    _uiState.update { it.copy(sendCommentInput = input, errorMessage = null) }
  }

  fun prepareSendReview() {
    val state = _uiState.value
    if (state.isBusy) return
    _uiState.update { it.copy(isBusy = true, errorMessage = null) }
    viewModelScope.launch {
      walletCore
        .prepareTransfer(
          recipientInput = state.sendRecipientInput,
          amountText = state.sendAmountInput,
          comment = state.sendCommentInput.takeIf { it.isNotBlank() },
          assetKind = state.selectedSendAsset,
        )
        .onSuccess { preview ->
          _uiState.update {
            it.copy(
              isBusy = false,
              transferPreview = preview,
              subScreen = ActiveSubScreen.SEND_CONFIRM,
              errorMessage = null,
            )
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(
              isBusy = false,
              errorMessage = formatUserFacingError(err),
            )
          }
        }
    }
  }

  fun backFromSendConfirm() {
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.SEND_FORM,
        transferPreview = null,
        errorMessage = null,
      )
    }
    restartMonitorIfEligible()
  }

  fun confirmSendTransfer() {
    executeConfirmSendTransfer(appPin = null)
  }

  private fun executeConfirmSendTransfer(appPin: CharArray?) {
    val preview = _uiState.value.transferPreview
    if (preview == null || _uiState.value.isBusy) {
      appPin?.let { Arrays.fill(it, '\u0000') }
      return
    }
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    viewModelScope.launch {
      walletCore
        .confirmAndSend(preview = preview, appPin = appPin)
        .onSuccess { txRecord ->
          _uiState.update {
            it.copy(
              isBusy = false,
              pinPromptMode = null,
              pinPromptError = null,
              transferPreview = null,
              activeSendTxId = txRecord.id,
              subScreen = ActiveSubScreen.SEND_STATUS,
              errorMessage = null,
            )
          }
          refreshBalance(force = true)
          restartMonitorIfEligible()
        }
        .onFailure { err ->
          if (err is VaultAuthRequiredException) {
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptMode = PinPromptMode.ENTER_FOR_SIGN_SEND,
                pinPromptError = null,
              )
            }
          } else {
            val stayInPin = _uiState.value.pinPromptMode != null
            val formatted = formatUserFacingError(err)
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptError = if (stayInPin) formatted else null,
                errorMessage = if (!stayInPin) formatted else null,
              )
            }
          }
        }
    }
  }

  fun openTxStatus(txId: String) {
    _uiState.update {
      it.copy(
        subScreen = ActiveSubScreen.SEND_STATUS,
        activeSendTxId = txId,
        errorMessage = null,
      )
    }
    refreshActiveTxStatus()
  }

  fun refreshActiveTxStatus() {
    val txId = _uiState.value.activeSendTxId ?: return
    viewModelScope.launch { walletCore.refreshTransactionStatus(txId) }
  }

  fun confirmBackup(answers: Map<Int, CharArray>) {
    executeConfirmBackup(answers = answers, appPin = null)
  }

  private fun executeConfirmBackup(answers: Map<Int, CharArray>, appPin: CharArray?) {
    val challenge = _uiState.value.backupChallenge ?: return
    if (_uiState.value.isBusy) {
      answers.values.forEach { Arrays.fill(it, '\u0000') }
      appPin?.let { Arrays.fill(it, '\u0000') }
      return
    }
    _uiState.update { it.copy(isBusy = true, errorMessage = null, pinPromptError = null) }
    val answersCopy = answers.mapValues { it.value.copyOf() }
    viewModelScope.launch {
      walletCore
        .confirmBackup(challenge, answers, appPin = appPin)
        .onSuccess { matched ->
          clearPendingConfirmAnswers()
          answersCopy.values.forEach { Arrays.fill(it, '\u0000') }
          if (matched) {
            clearTransientPhrase()
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptMode = null,
                pinPromptError = null,
                subScreen = ActiveSubScreen.NONE,
                backupChallenge = null,
                phraseWordsForDisplay = emptyList(),
                errorMessage = null,
                bannerMessage = getApplication<Application>().getString(R.string.backup_confirmed_toast),
              )
            }
          } else {
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptMode = null,
                errorMessage = getApplication<Application>().getString(R.string.verify_mismatch_error),
              )
            }
          }
        }
        .onFailure { err ->
          if (err is VaultAuthRequiredException) {
            clearPendingConfirmAnswers()
            pendingConfirmAnswers = answersCopy
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptMode = PinPromptMode.ENTER_FOR_BACKUP_CONFIRM,
                pinPromptError = null,
              )
            }
          } else {
            answersCopy.values.forEach { Arrays.fill(it, '\u0000') }
            val stayInPin = _uiState.value.pinPromptMode != null
            _uiState.update {
              it.copy(
                isBusy = false,
                pinPromptError = if (stayInPin) err.message ?: err::class.java.simpleName else null,
                errorMessage = if (!stayInPin) err.message ?: err::class.java.simpleName else null,
              )
            }
          }
        }
    }
  }

  fun submitPin(pin: CharArray) {
    when (_uiState.value.pinPromptMode) {
      PinPromptMode.SETUP_FOR_CREATE -> executeCreateWallet(pin)
      PinPromptMode.SETUP_FOR_IMPORT -> {
        val candidate = pendingImportCandidate
        if (candidate != null) {
          executeSelectImportedCandidate(candidate = candidate, appPin = pin)
        } else {
          Arrays.fill(pin, '\u0000')
          dismissPinPrompt()
        }
      }
      PinPromptMode.ENTER_FOR_UNLOCK -> executeUnlockWallet(pin)
      PinPromptMode.ENTER_FOR_PHRASE -> {
        val pub = (_uiState.value.vaultState as? VaultState.Unlocked)?.metadata?.publicKeyHex.orEmpty()
        executeOpenBackupFlow(appPin = pin, fallbackPub = pub)
      }
      PinPromptMode.ENTER_FOR_BACKUP_CONFIRM -> {
        val savedAnswers = pendingConfirmAnswers
        if (savedAnswers != null) {
          val copy = savedAnswers.mapValues { it.value.copyOf() }
          executeConfirmBackup(answers = copy, appPin = pin)
        } else {
          Arrays.fill(pin, '\u0000')
          dismissPinPrompt()
        }
      }
      PinPromptMode.ENTER_FOR_SIGN_SEND -> executeConfirmSendTransfer(appPin = pin)
      null -> Arrays.fill(pin, '\u0000')
    }
  }

  fun dismissPinPrompt() {
    clearPendingConfirmAnswers()
    pendingImportCandidate = null
    _uiState.update { it.copy(pinPromptMode = null, pinPromptError = null) }
  }

  fun selectNetwork(network: Network) {
    viewModelScope.launch { walletCore.switchNetwork(network) }
  }

  fun buildReceiveTransferUri(
    network: Network? = null,
    amount: com.example.core.model.Money? = null,
    comment: String? = null,
  ): String? = walletCore.buildReceiveTransferUri(network, amount, comment)

  fun validateReceiveInput(input: String, network: Network? = null): AddressCheckResult =
    walletCore.validateReceiveInput(input, network)

  fun lockWallet() {
    closeSubScreens()
    walletCore.lockWallet()
  }

  fun resetVault() {
    closeSubScreens()
    walletCore.resetVault()
  }

  fun clearBanner() {
    _uiState.update { it.copy(bannerMessage = null) }
  }

  private fun setTransientPhrase(phrase: MnemonicPhrase) {
    transientPhrase?.wipe()
    transientPhrase = phrase
  }

  private fun clearTransientPhrase() {
    transientPhrase?.wipe()
    transientPhrase = null
  }

  private fun setTransientImportPhrase(phrase: MnemonicPhrase) {
    transientImportPhrase?.wipe()
    transientImportPhrase = phrase
  }

  private fun clearTransientImportState() {
    transientImportPhrase?.wipe()
    transientImportPhrase = null
    pendingImportCandidate = null
  }

  private fun clearPendingConfirmAnswers() {
    pendingConfirmAnswers?.values?.forEach { Arrays.fill(it, '\u0000') }
    pendingConfirmAnswers = null
  }

  private fun extractDisplayWords(phrase: MnemonicPhrase): List<String> =
    (0 until phrase.wordCount).map { idx ->
      val chars = phrase.wordAt(idx)
      try {
        String(chars)
      } finally {
        Arrays.fill(chars, '\u0000')
      }
    }

  private fun formatUserFacingError(err: Throwable): String {
    val root =
      when (err) {
        is TransferRejectedException -> err.cause ?: err
        else -> err
      }
    if (root is AllProvidersFailedException) {
      val causes = root.failures
      return when {
        causes.any { it is RateLimitedException } ->
          "Network busy. Wait a moment and retry."
        causes.any { it is TransportException } ->
          "Cannot reach TON network. Check connection."
        causes.any { it is ServerErrorException || it is InvalidPayloadException } ->
          "TON node temporarily unavailable. Retry."
        else ->
          causes.firstOrNull()?.message?.let { sanitizeShortMessage(it) }
            ?: "TON network error. Please retry."
      }
    }
    return when (root) {
      is RejectedTransactionException -> formatRejectionDetail(root.message.orEmpty())
      is RateLimitedException -> "Network busy. Wait a moment and retry."
      is TransportException -> "Cannot reach TON network. Check connection."
      is ServerErrorException -> "TON node error (HTTP ${root.httpCode}). Retry."
      is InvalidPayloadException -> "Unexpected TON response. Retry."
      else ->
        root.message?.let { sanitizeShortMessage(it) }
          ?: "Operation failed. Please retry."
    }
  }

  private fun formatRejectionDetail(rawDetail: String): String {
    val stripped =
      rawDetail
        .replace(Regex("^.*?rejected transaction \\(\\d+\\):\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
    val lower = stripped.lowercase()
    return when {
      lower.contains("seqno") -> "Sequence changed. Refresh and retry."
      lower.contains("signature") || lower.contains("chksig") ->
        "Signature check failed. Review and retry."
      lower.contains("balance") || lower.contains("enough") || lower.contains("-14") ->
        "Insufficient balance for amount and fee."
      lower.contains("expired") || lower.contains("valid_until") ->
        "Transfer expired. Review and retry."
      else -> sanitizeShortMessage(stripped.ifEmpty { "Transaction rejected by network." })
    }
  }

  private fun sanitizeShortMessage(raw: String): String {
    val cleaned =
      raw
        .replace(Regex("^.*?rejected transaction \\(\\d+\\):\\s*", RegexOption.IGNORE_CASE), "")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (cleaned.length <= 90) cleaned else "${cleaned.take(87)}..."
  }

  override fun onCleared() {
    monitorJob?.cancel()
    clearTransientPhrase()
    clearTransientImportState()
    clearPendingConfirmAnswers()
    super.onCleared()
  }

  companion object {
    private const val MONITOR_POLL_INTERVAL_MS = 10_000L
  }
}

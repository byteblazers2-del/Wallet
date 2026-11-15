package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.model.VaultState
import com.example.ui.screens.AddressBookScreen
import com.example.ui.screens.BackupVerifyScreen
import com.example.ui.screens.ImportPhraseScreen
import com.example.ui.screens.ImportSelectAccountScreen
import com.example.ui.screens.LockedVaultScreen
import com.example.ui.screens.PinPromptDialog
import com.example.ui.screens.RecoveryPhraseScreen
import com.example.ui.screens.SendConfirmScreen
import com.example.ui.screens.SendFormScreen
import com.example.ui.screens.SendStatusScreen
import com.example.ui.screens.TransactionHistoryScreen
import com.example.ui.screens.WalletHomeScreen
import com.example.ui.screens.WelcomeCreateScreen
import com.example.ui.theme.GramVaultTheme

private enum class RouteKey {
  WELCOME,
  LOCKED,
  KEY_INVALIDATED,
  IMPORT_PHRASE,
  IMPORT_SELECT,
  HOME,
  RECOVERY_PHRASE,
  BACKUP_VERIFY,
  SEND_FORM,
  SEND_CONFIRM,
  SEND_STATUS,
  TX_HISTORY,
  ADDRESS_BOOK;

  val depth: Int
    get() =
      when (this) {
        WELCOME,
        LOCKED,
        KEY_INVALIDATED,
        HOME -> 0
        IMPORT_PHRASE,
        RECOVERY_PHRASE,
        SEND_FORM,
        TX_HISTORY,
        ADDRESS_BOOK -> 1
        IMPORT_SELECT,
        BACKUP_VERIFY,
        SEND_CONFIRM -> 2
        SEND_STATUS -> 3
      }
}

class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
    )
    setContent {
      GramVaultTheme(darkTheme = true) {
        val viewModel: WalletViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(this) {
          viewModel.attachActivity(this@MainActivity)
          onDispose { viewModel.attachActivity(null) }
        }
        DisposableEffect(lifecycleOwner) {
          val observer = LifecycleEventObserver { _, event ->
            when (event) {
              Lifecycle.Event.ON_START -> viewModel.setForegroundMonitoring(true)
              Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
              else -> Unit
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.setForegroundMonitoring(true)
          }
          onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForegroundMonitoring(false)
          }
        }

        uiState.pinPromptMode?.let { mode ->
          PinPromptDialog(
            isSetupMode =
              mode == PinPromptMode.SETUP_FOR_CREATE || mode == PinPromptMode.SETUP_FOR_IMPORT,
            errorMessage = uiState.pinPromptError,
            onDismiss = viewModel::dismissPinPrompt,
            onSubmitPin = viewModel::submitPin,
          )
        }

        val vault = uiState.vaultState
        val routeKey =
          when {
            vault !is VaultState.Unlocked && uiState.subScreen == ActiveSubScreen.IMPORT_PHRASE ->
              RouteKey.IMPORT_PHRASE
            vault !is VaultState.Unlocked &&
              uiState.subScreen == ActiveSubScreen.IMPORT_SELECT_ACCOUNT ->
              RouteKey.IMPORT_SELECT
            vault is VaultState.Uninitialized -> RouteKey.WELCOME
            vault is VaultState.Locked -> RouteKey.LOCKED
            vault is VaultState.KeyInvalidated -> RouteKey.KEY_INVALIDATED
            else ->
              when (uiState.subScreen) {
                ActiveSubScreen.RECOVERY_PHRASE -> RouteKey.RECOVERY_PHRASE
                ActiveSubScreen.BACKUP_VERIFY ->
                  if (uiState.backupChallenge != null) RouteKey.BACKUP_VERIFY else RouteKey.HOME
                ActiveSubScreen.SEND_FORM -> RouteKey.SEND_FORM
                ActiveSubScreen.SEND_CONFIRM ->
                  if (uiState.transferPreview != null) RouteKey.SEND_CONFIRM else RouteKey.HOME
                ActiveSubScreen.SEND_STATUS ->
                  if (uiState.txRecords.any { it.id == uiState.activeSendTxId }) {
                    RouteKey.SEND_STATUS
                  } else {
                    RouteKey.HOME
                  }
                ActiveSubScreen.TX_HISTORY -> RouteKey.TX_HISTORY
                ActiveSubScreen.ADDRESS_BOOK -> RouteKey.ADDRESS_BOOK
                ActiveSubScreen.IMPORT_PHRASE,
                ActiveSubScreen.IMPORT_SELECT_ACCOUNT,
                ActiveSubScreen.NONE -> RouteKey.HOME
              }
          }

        AnimatedContent(
          targetState = routeKey,
          transitionSpec = {
            val animSpec = tween<androidx.compose.ui.unit.IntOffset>(240, easing = FastOutSlowInEasing)
            val fadeSpec = tween<Float>(210)
            when {
              initialState == RouteKey.LOCKED || targetState == RouteKey.LOCKED -> {
                (fadeIn(fadeSpec) + scaleIn(initialScale = 0.97f, animationSpec = tween(240))) togetherWith
                  fadeOut(tween(180))
              }
              targetState.depth >= initialState.depth -> {
                (slideInHorizontally(animSpec) { it / 5 } + fadeIn(fadeSpec)) togetherWith
                  (slideOutHorizontally(animSpec) { -it / 7 } + fadeOut(tween(160)))
              }
              else -> {
                (slideInHorizontally(animSpec) { -it / 5 } + fadeIn(fadeSpec)) togetherWith
                  (slideOutHorizontally(animSpec) { it / 5 } + fadeOut(tween(160)))
              }
            }
          },
          label = "screenTransition",
        ) { targetRoute ->
          when (targetRoute) {
            RouteKey.IMPORT_PHRASE -> {
              ImportPhraseScreen(
                isBusy = uiState.isBusy,
                errorMessage = uiState.errorMessage,
                onBack = viewModel::closeImportFlow,
                onDiscoverAccounts = viewModel::discoverImportAccounts,
              )
            }

            RouteKey.IMPORT_SELECT -> {
              ImportSelectAccountScreen(
                candidates = uiState.importCandidates,
                isAmbiguous24 = uiState.importIsAmbiguous24,
                isBusy = uiState.isBusy,
                errorMessage = uiState.errorMessage,
                onBack = viewModel::backFromImportSelect,
                onConfirmAccount = viewModel::selectImportedCandidate,
              )
            }

            RouteKey.WELCOME -> {
              WelcomeCreateScreen(
                isDeviceSecure = uiState.isDeviceSecure,
                isBusy = uiState.isBusy,
                errorMessage = uiState.errorMessage,
                onCreateWallet = viewModel::createWallet,
                onImportWallet = viewModel::openImportFlow,
                isDevAuthBypassed = uiState.isDevAuthBypassed,
              )
            }

            RouteKey.LOCKED -> {
              LockedVaultScreen(
                isKeyInvalidated = false,
                isBusy = uiState.isBusy,
                errorMessage = uiState.errorMessage,
                onUnlock = viewModel::unlockWallet,
                onResetVault = viewModel::resetVault,
                showResetOption = uiState.isDevAuthBypassed,
              )
            }

            RouteKey.KEY_INVALIDATED -> {
              LockedVaultScreen(
                isKeyInvalidated = true,
                isBusy = uiState.isBusy,
                errorMessage = uiState.errorMessage,
                onUnlock = viewModel::unlockWallet,
                onResetVault = viewModel::resetVault,
                onReimportWallet = viewModel::openImportFlow,
              )
            }

            RouteKey.RECOVERY_PHRASE -> {
              val unlocked = vault as? VaultState.Unlocked
              if (unlocked != null) {
                RecoveryPhraseScreen(
                  words = uiState.phraseWordsForDisplay,
                  isAlreadyBackedUp = unlocked.metadata.isBackedUp,
                  onBack = viewModel::closeSubScreens,
                  onProceedToVerify = viewModel::proceedFromPhraseScreen,
                  onSkipBackup = viewModel::closeSubScreens,
                )
              }
            }

            RouteKey.BACKUP_VERIFY -> {
              val challenge = uiState.backupChallenge
              if (challenge != null) {
                BackupVerifyScreen(
                  challenge = challenge,
                  isBusy = uiState.isBusy,
                  errorMessage = uiState.errorMessage,
                  onBack = viewModel::backToPhraseOrHome,
                  onSubmit = viewModel::confirmBackup,
                )
              }
            }

            RouteKey.SEND_FORM -> {
              val unlocked = vault as? VaultState.Unlocked
              if (unlocked != null) {
                SendFormScreen(
                  activeNetwork = unlocked.activeNetwork,
                  balanceState = uiState.balanceState,
                  recipientInput = uiState.sendRecipientInput,
                  amountInput = uiState.sendAmountInput,
                  commentInput = uiState.sendCommentInput,
                  isBusy = uiState.isBusy,
                  errorMessage = uiState.errorMessage,
                  onRecipientChange = viewModel::updateSendRecipient,
                  onAmountChange = viewModel::updateSendAmount,
                  onCommentChange = viewModel::updateSendComment,
                  onValidateRecipient = { input ->
                    viewModel.validateReceiveInput(input, unlocked.activeNetwork)
                  },
                  onReviewTransfer = viewModel::prepareSendReview,
                  onBack = viewModel::closeSubScreens,
                  selectedAsset = uiState.selectedSendAsset,
                  onAssetChange = viewModel::updateSendAsset,
                  fiatRates = uiState.fiatRates,
                  addressBookEntries = uiState.addressBookEntries,
                  onSelectContact = viewModel::selectAddressBookRecipient,
                  onSaveContact = viewModel::saveAddressBookContact,
                )
              }
            }

            RouteKey.SEND_CONFIRM -> {
              val preview = uiState.transferPreview
              if (preview != null) {
                SendConfirmScreen(
                  preview = preview,
                  isBusy = uiState.isBusy,
                  errorMessage = uiState.errorMessage,
                  onConfirmAndSend = viewModel::confirmSendTransfer,
                  onBack = viewModel::backFromSendConfirm,
                  fiatRates = uiState.fiatRates,
                )
              }
            }

            RouteKey.SEND_STATUS -> {
              val tx = uiState.txRecords.firstOrNull { it.id == uiState.activeSendTxId }
              if (tx != null) {
                SendStatusScreen(
                  txRecord = tx,
                  onRefreshStatus = viewModel::refreshActiveTxStatus,
                  onDone = viewModel::closeSubScreens,
                )
              }
            }

            RouteKey.TX_HISTORY -> {
              val unlocked = vault as? VaultState.Unlocked
              if (unlocked != null) {
                TransactionHistoryScreen(
                  activeNetwork = unlocked.activeNetwork,
                  txRecords = uiState.txRecords,
                  isSyncing = uiState.monitorState.isSyncing,
                  onSyncNow = { viewModel.syncNow(ignoreLtShortcut = true) },
                  onSelectTx = viewModel::openTxStatus,
                  onBack = viewModel::closeSubScreens,
                )
              }
            }

            RouteKey.ADDRESS_BOOK -> {
              val unlocked = vault as? VaultState.Unlocked
              if (unlocked != null) {
                AddressBookScreen(
                  activeNetwork = unlocked.activeNetwork,
                  entries = uiState.addressBookEntries,
                  errorMessage = uiState.errorMessage,
                  onSaveContact = viewModel::saveAddressBookContact,
                  onDeleteContact = viewModel::deleteAddressBookContact,
                  onSendToContact = viewModel::startSendToContact,
                  onValidateAddress = { input ->
                    viewModel.validateReceiveInput(input, unlocked.activeNetwork)
                  },
                  onBack = viewModel::closeSubScreens,
                )
              }
            }

            RouteKey.HOME -> {
              val unlocked = vault as? VaultState.Unlocked
              if (unlocked != null) {
                WalletHomeScreen(
                  metadata = unlocked.metadata,
                  activeNetwork = unlocked.activeNetwork,
                  bannerMessage = uiState.bannerMessage,
                  onClearBanner = viewModel::clearBanner,
                  onOpenBackup = viewModel::openBackupFlow,
                  onLockWallet = viewModel::lockWallet,
                  balanceState = uiState.balanceState,
                  onRefreshBalance = { viewModel.refreshBalance(force = true) },
                  onBuildTransferUri = { amount, comment ->
                    viewModel.buildReceiveTransferUri(unlocked.activeNetwork, amount, comment)
                  },
                  onOpenSend = { viewModel.openSendFlow() },
                  onOpenAssetSend = { asset -> viewModel.openSendFlow(asset) },
                  txRecords = uiState.txRecords,
                  onOpenTxStatus = viewModel::openTxStatus,
                  monitorState = uiState.monitorState,
                  onToggleMonitor = viewModel::toggleMonitor,
                  onSyncNow = { viewModel.syncNow(ignoreLtShortcut = true) },
                  onOpenHistory = viewModel::openHistoryScreen,
                  fiatRates = uiState.fiatRates,
                  appSettings = uiState.appSettings,
                  onOpenAddressBook = viewModel::openAddressBook,
                  onToggleAutoLock = viewModel::setAutoLockOnBackground,
                  onToggleLiveMonitor = viewModel::setLiveMonitorEnabled,
                  onValidateReceiveInput = { input ->
                    viewModel.validateReceiveInput(input, unlocked.activeNetwork)
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

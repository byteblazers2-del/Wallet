package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.BalanceState
import com.example.core.model.ChainMonitorState
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.TxStatus
import com.example.core.model.VaultMetadata
import com.example.core.network.FiatRates
import com.example.core.wallet.AddressCheckResult
import com.example.core.wallet.AppSettings
import com.example.core.wallet.TonTransferUri
import com.example.ui.components.AnimatedBalanceText
import com.example.ui.components.TonkeeperActionCircle
import com.example.ui.components.VaultGlassCard
import com.example.ui.components.tactileClickable
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.TestnetBannerOnRed
import com.example.ui.theme.TestnetBannerRed
import com.example.ui.theme.UsdtGreen
import com.example.ui.theme.VaultBlue
import com.example.ui.theme.VaultDarkCard
import com.example.ui.theme.VaultDarkCardElevated
import com.example.ui.theme.VaultHairlineBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletHomeScreen(
  metadata: VaultMetadata,
  activeNetwork: Network,
  bannerMessage: String?,
  onClearBanner: () -> Unit,
  onSelectNetwork: (Network) -> Unit,
  onOpenBackup: () -> Unit,
  onLockWallet: () -> Unit,
  balanceState: BalanceState = BalanceState(),
  onRefreshBalance: () -> Unit = {},
  onBuildTransferUri: (amount: Money?, comment: String?) -> String? = { amount, comment ->
    TonTransferUri.build(
      recipient = metadata.addressFor(activeNetwork),
      bounceable = false,
      amount = amount,
      comment = comment,
    )
  },
  onOpenSend: () -> Unit = {},
  onOpenAssetSend: (TxAssetKind) -> Unit = { onOpenSend() },
  txRecords: List<TxRecord> = emptyList(),
  onOpenTxStatus: (String) -> Unit = {},
  monitorState: ChainMonitorState = ChainMonitorState(),
  onToggleMonitor: () -> Unit = {},
  onSyncNow: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  fiatRates: FiatRates = FiatRates(),
  appSettings: AppSettings = AppSettings(autoLockOnBackground = true, liveMonitorEnabled = true),
  onOpenAddressBook: () -> Unit = {},
  onToggleAutoLock: (Boolean) -> Unit = {},
  onToggleLiveMonitor: (Boolean) -> Unit = { onToggleMonitor() },
  onValidateReceiveInput: (String) -> AddressCheckResult = { input ->
    val trimmed = input.trim()
    if (trimmed.startsWith("ton://", ignoreCase = true)) {
      TonTransferUri.parse(trimmed, activeNetwork).fold(
        onSuccess = { AddressCheckResult.Valid(it.parsedRecipient, it.amount, it.comment) },
        onFailure = { AddressCheckResult.Invalid(it.message ?: "Invalid TON transfer link") },
      )
    } else {
      TonAddress.parseDetailed(trimmed, activeNetwork).fold(
        onSuccess = { AddressCheckResult.Valid(it) },
        onFailure = { AddressCheckResult.Invalid(it.message ?: "Invalid TON address") },
      )
    }
  },
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val copiedText = stringResource(R.string.address_copied)
  var showReceiveSheet by remember { mutableStateOf(false) }
  var showSettingsSheet by remember { mutableStateOf(false) }
  val pullToRefreshState = rememberPullToRefreshState()

  val tonAddress = metadata.addressFor(activeNetwork)
  val friendlyAddress = tonAddress.toFriendly(bounceable = false)
  val rawAddress = tonAddress.raw

  LaunchedEffect(bannerMessage) {
    if (!bannerMessage.isNullOrBlank()) {
      snackbarHostState.showSnackbar(bannerMessage)
      onClearBanner()
    }
  }

  val copyAddress: (String) -> Unit = { address ->
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("TON Address", address))
    scope.launch { snackbarHostState.showSnackbar(copiedText) }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      WalletTopBar(
        walletVersionLabel = stringResource(R.string.wallet_badge_format, metadata.walletVersion.displayName),
        activeNetwork = activeNetwork,
        onSelectNetwork = onSelectNetwork,
        onLockWallet = onLockWallet,
      )
    },
  ) { innerPadding ->
    PullToRefreshBox(
      isRefreshing = balanceState.isLoading || monitorState.isSyncing,
      onRefresh = {
        onRefreshBalance()
        onSyncNow()
      },
      state = pullToRefreshState,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
          modifier =
            Modifier.widthIn(max = 560.dp)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .navigationBarsPadding()
              .padding(vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          AnimatedVisibility(
            visible = !metadata.isBackedUp,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
          ) {
            Card(
              modifier =
                Modifier.fillMaxWidth()
                  .tactileClickable(onClick = onOpenBackup)
                  .testTag("backup_warning_banner"),
              shape = RoundedCornerShape(18.dp),
              colors = CardDefaults.cardColors(containerColor = Color(0xFF332612)),
              border = BorderStroke(1.dp, Color(0x33FFCA28)),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.WarningAmber,
                  contentDescription = null,
                  tint = Color(0xFFFFCA28),
                )
                Text(
                  text = stringResource(R.string.backup_needed_banner),
                  style = MaterialTheme.typography.bodySmall,
                  color = Color(0xFFFFE082),
                  fontWeight = FontWeight.Medium,
                  modifier = Modifier.weight(1f),
                )
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = null,
                  tint = Color(0xFFFFCA28),
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }

          HeroBalanceCard(
            activeNetwork = activeNetwork,
            friendlyAddress = friendlyAddress,
            isBackedUp = metadata.isBackedUp,
            balanceState = balanceState,
            fiatRates = fiatRates,
            onCopy = { copyAddress(friendlyAddress) },
            onRefreshBalance = onRefreshBalance,
          )

          ActionRow(
            onSend = onOpenSend,
            onReceive = { showReceiveSheet = true },
            onCopy = { copyAddress(friendlyAddress) },
            onBackup = onOpenBackup,
          )

          UtilityQuickRow(
            onOpenContacts = onOpenAddressBook,
            onOpenSettings = { showSettingsSheet = true },
          )

          AssetsCard(
            balanceState = balanceState,
            fiatRates = fiatRates,
            onAssetClick = onOpenAssetSend,
          )

          RecentTransfersCard(
            txRecords = txRecords,
            activeNetwork = activeNetwork,
            onOpenTxStatus = onOpenTxStatus,
            onOpenHistory = onOpenHistory,
          )

          ChainMonitorCard(
            monitorState = monitorState,
            activeNetwork = activeNetwork,
            onToggleMonitor = onToggleMonitor,
            onSyncNow = onSyncNow,
          )

          Spacer(modifier = Modifier.height(12.dp))
        }
      }
    }

    if (showReceiveSheet) {
      val receiveSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(
        onDismissRequest = { showReceiveSheet = false },
        sheetState = receiveSheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
      ) {
        ReceiveSheetContent(
          network = activeNetwork,
          friendlyAddress = friendlyAddress,
          rawAddress = rawAddress,
          onCopyAddress = { label, value, toastMsg ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            showReceiveSheet = false
            scope.launch { snackbarHostState.showSnackbar(toastMsg) }
          },
          onSharePayload = { payload ->
            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
              }
            context.startActivity(Intent.createChooser(shareIntent, null))
          },
          onBuildTransferUri = { amount, comment ->
            onBuildTransferUri(amount, comment)
              ?: TonTransferUri.build(tonAddress, bounceable = false, amount = amount, comment = comment)
          },
          onValidateInput = onValidateReceiveInput,
        )
      }
    }

    if (showSettingsSheet) {
      val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(
        onDismissRequest = { showSettingsSheet = false },
        sheetState = settingsSheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
      ) {
        SettingsSheetContent(
          activeNetwork = activeNetwork,
          securityMode = metadata.securityMode,
          appSettings = appSettings,
          onToggleAutoLock = onToggleAutoLock,
          onToggleLiveMonitor = onToggleLiveMonitor,
          onOpenAddressBook = {
            showSettingsSheet = false
            onOpenAddressBook()
          },
          onCopyUsdtMaster = { master ->
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("USDT Master", master))
            showSettingsSheet = false
            scope.launch { snackbarHostState.showSnackbar(copiedText) }
          },
        )
      }
    }
  }
}

@Composable
private fun WalletTopBar(
  walletVersionLabel: String,
  activeNetwork: Network,
  onSelectNetwork: (Network) -> Unit,
  onLockWallet: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
    AnimatedVisibility(
      visible = activeNetwork == Network.TESTNET,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .background(TestnetBannerRed)
            .padding(vertical = 6.dp)
            .testTag("testnet_banner"),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.testnet_active_warning),
          color = TestnetBannerOnRed,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier =
            Modifier.size(38.dp)
              .clip(CircleShape)
              .background(Brush.linearGradient(listOf(Color(0xFF0077B6), VaultBlue))),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Outlined.AccountBalanceWallet,
            contentDescription = stringResource(R.string.app_name),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
          )
        }
        Column {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = walletVersionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("wallet_version_badge"),
          )
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        IconButton(
          onClick = onLockWallet,
          modifier = Modifier.testTag("lock_wallet_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = stringResource(R.string.action_lock),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        NetworkTogglePill(activeNetwork = activeNetwork, onSelectNetwork = onSelectNetwork)
      }
    }
  }
}

@Composable
private fun NetworkTogglePill(activeNetwork: Network, onSelectNetwork: (Network) -> Unit) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = VaultDarkCard,
    border = BorderStroke(1.dp, VaultHairlineBorder),
    modifier = Modifier.testTag("network_toggle_pill"),
  ) {
    Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      NetworkChip(
        label = stringResource(R.string.network_mainnet),
        selected = activeNetwork == Network.MAINNET,
        selectedColor = VaultBlue,
        tag = "network_mainnet_button",
        onClick = { onSelectNetwork(Network.MAINNET) },
      )
      NetworkChip(
        label = stringResource(R.string.network_testnet),
        selected = activeNetwork == Network.TESTNET,
        selectedColor = TestnetBannerRed,
        tag = "network_testnet_button",
        onClick = { onSelectNetwork(Network.TESTNET) },
      )
    }
  }
}

@Composable
private fun NetworkChip(
  label: String,
  selected: Boolean,
  selectedColor: Color,
  tag: String,
  onClick: () -> Unit,
) {
  val bgColor by
    animateColorAsState(
      targetValue = if (selected) selectedColor else Color.Transparent,
      animationSpec = tween(220),
      label = "networkChipBg",
    )
  val textColor by
    animateColorAsState(
      targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
      animationSpec = tween(220),
      label = "networkChipText",
    )
  Box(
    modifier =
      Modifier.clip(RoundedCornerShape(999.dp))
        .background(bgColor)
        .tactileClickable(onClick = onClick)
        .minimumInteractiveComponentSize()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag(tag),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = textColor,
    )
  }
}

@Composable
private fun HeroBalanceCard(
  activeNetwork: Network,
  friendlyAddress: String,
  isBackedUp: Boolean,
  balanceState: BalanceState,
  fiatRates: FiatRates = FiatRates(),
  onCopy: () -> Unit,
  onRefreshBalance: () -> Unit,
) {
  val balanceText =
    when {
      balanceState.gramBalance != null ->
        stringResource(R.string.wallet_balance_format, balanceState.gramBalance.format())
      balanceState.isLoading -> stringResource(R.string.wallet_balance_loading)
      else -> stringResource(R.string.wallet_balance_unavailable)
    }
  val glowColor = if (activeNetwork == Network.MAINNET) VaultBlue else TestnetBannerRed

  VaultGlassCard(
    modifier = Modifier.fillMaxWidth().testTag("wallet_hero_card"),
    shape = RoundedCornerShape(28.dp),
    containerColor = VaultDarkCard,
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .drawBehind {
            drawCircle(
              brush =
                Brush.radialGradient(
                  colors = listOf(glowColor.copy(alpha = 0.22f), Color.Transparent),
                  center = Offset(size.width * 0.5f, size.height * 0.15f),
                  radius = size.width * 0.65f,
                ),
              radius = size.width * 0.65f,
              center = Offset(size.width * 0.5f, size.height * 0.15f),
            )
          }
          .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text =
                if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                else stringResource(R.string.network_testnet),
              style = MaterialTheme.typography.labelMedium,
              color = if (activeNetwork == Network.MAINNET) VaultBlue else Color(0xFFFF8A80),
              fontWeight = FontWeight.SemiBold,
            )
            if (isBackedUp) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("backed_up_badge"),
              ) {
                Icon(
                  imageVector = Icons.Filled.VerifiedUser,
                  contentDescription = null,
                  tint = UsdtGreen,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = stringResource(R.string.backup_verified_badge),
                  style = MaterialTheme.typography.labelSmall,
                  color = UsdtGreen,
                )
              }
            }
          }

          IconButton(
            onClick = onRefreshBalance,
            enabled = !balanceState.isLoading,
            modifier = Modifier.align(Alignment.CenterEnd).testTag("refresh_balance_button"),
          ) {
            if (balanceState.isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = VaultBlue,
              )
            } else {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.action_refresh_balance),
                tint = VaultBlue,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        AnimatedBalanceText(
          text = balanceText,
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("hero_balance_text"),
        )

        val totalUsdText =
          fiatRates.formatUsdValue(
            gramAmount = balanceState.gramBalance,
            usdtAmount = balanceState.usdtBalance,
          )
        AnimatedVisibility(visible = totalUsdText != null) {
          if (totalUsdText != null) {
            Surface(
              shape = RoundedCornerShape(999.dp),
              color = VaultBlue.copy(alpha = 0.12f),
            ) {
              Text(
                text = stringResource(R.string.portfolio_total_usd, totalUsdText),
                style = MaterialTheme.typography.bodySmall,
                color = VaultBlue,
                fontWeight = FontWeight.SemiBold,
                modifier =
                  Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("hero_usd_total"),
              )
            }
          }
        }

        if (balanceState.isStale) {
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xFF332612),
            modifier = Modifier.tactileClickable(onClick = onRefreshBalance).testTag("balance_stale_badge"),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFFFCA28),
                modifier = Modifier.size(14.dp),
              )
              Text(
                text = stringResource(R.string.balance_stale_badge),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFE082),
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        } else if (balanceState.providerName != null && balanceState.errorMessage == null) {
          Text(
            text = stringResource(R.string.balance_sync_provider, balanceState.providerName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("balance_provider_label"),
          )
        }

        balanceState.errorMessage?.let { errMsg ->
          Text(
            text = "$errMsg • ${stringResource(R.string.balance_retry_hint)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFF8A80),
            modifier = Modifier.tactileClickable(onClick = onRefreshBalance).testTag("balance_error_banner"),
          )
        }

        Surface(
          shape = RoundedCornerShape(999.dp),
          color = VaultDarkCardElevated,
          border = BorderStroke(1.dp, VaultHairlineBorder),
          modifier =
            Modifier.clip(RoundedCornerShape(999.dp))
              .tactileClickable(onClick = onCopy)
              .minimumInteractiveComponentSize(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = shortenAddress(friendlyAddress),
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("wallet_address_text"),
            )
            Icon(
              imageVector = Icons.Filled.ContentCopy,
              contentDescription = stringResource(R.string.action_copy),
              tint = VaultBlue,
              modifier = Modifier.size(14.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ActionRow(
  onSend: () -> Unit,
  onReceive: () -> Unit,
  onCopy: () -> Unit,
  onBackup: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    TonkeeperActionCircle(
      icon = Icons.Filled.ArrowUpward,
      label = stringResource(R.string.action_send),
      primary = true,
      tag = "send_button",
      modifier = Modifier.weight(1f),
      onClick = onSend,
    )
    TonkeeperActionCircle(
      icon = Icons.Filled.ArrowDownward,
      label = stringResource(R.string.action_receive),
      primary = true,
      tag = "receive_button",
      modifier = Modifier.weight(1f),
      onClick = onReceive,
    )
    TonkeeperActionCircle(
      icon = Icons.Filled.ContentCopy,
      label = stringResource(R.string.action_copy),
      primary = false,
      tag = "copy_address_button",
      modifier = Modifier.weight(1f),
      onClick = onCopy,
    )
    TonkeeperActionCircle(
      icon = Icons.Filled.Key,
      label = stringResource(R.string.action_backup),
      primary = false,
      tag = "backup_phrase_button",
      modifier = Modifier.weight(1f),
      onClick = onBackup,
    )
  }
}

@Composable
private fun UtilityQuickRow(
  onOpenContacts: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = VaultDarkCard,
      border = BorderStroke(1.dp, VaultHairlineBorder),
      modifier =
        Modifier.weight(1f)
          .height(50.dp)
          .clip(RoundedCornerShape(16.dp))
          .tactileClickable(onClick = onOpenContacts)
          .testTag("open_address_book_button"),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Icon(
          imageVector = Icons.Outlined.Contacts,
          contentDescription = null,
          tint = VaultBlue,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.action_contacts),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = VaultDarkCard,
      border = BorderStroke(1.dp, VaultHairlineBorder),
      modifier =
        Modifier.weight(1f)
          .height(50.dp)
          .clip(RoundedCornerShape(16.dp))
          .tactileClickable(onClick = onOpenSettings)
          .testTag("open_settings_button"),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Settings,
          contentDescription = null,
          tint = VaultBlue,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.action_settings),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun AssetsCard(
  balanceState: BalanceState,
  fiatRates: FiatRates = FiatRates(),
  onAssetClick: (TxAssetKind) -> Unit = {},
) {
  val gramBalanceText =
    balanceState.gramBalance?.let { stringResource(R.string.wallet_balance_format, it.format()) }
      ?: stringResource(R.string.asset_balance_pending)
  val gramUsdText =
    balanceState.gramBalance?.let { fiatRates.formatUsdValue(gramAmount = it, usdtAmount = null) }
  val usdtBalanceText =
    balanceState.usdtBalance?.let { stringResource(R.string.usdt_balance_format, it.format()) }
      ?: stringResource(R.string.asset_balance_pending)
  val usdtUsdText =
    balanceState.usdtBalance?.let { fiatRates.formatUsdValue(gramAmount = null, usdtAmount = it) }

  VaultGlassCard(
    modifier = Modifier.fillMaxWidth().testTag("assets_card"),
    shape = RoundedCornerShape(22.dp),
    containerColor = VaultDarkCard,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.assets_section_title),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.assets_tap_to_send),
          style = MaterialTheme.typography.labelSmall,
          color = VaultBlue.copy(alpha = 0.8f),
        )
      }
      AssetRow(
        icon = Icons.Outlined.Diamond,
        iconBg = VaultBlue,
        name = stringResource(R.string.asset_gram_name),
        subtitle = stringResource(R.string.asset_gram_subtitle),
        balance = gramBalanceText,
        usdSubtitle = gramUsdText,
        balanceTag = "asset_gram_balance",
        onClick = { onAssetClick(TxAssetKind.GRAM) },
      )
      HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 20.dp),
      )
      AssetRow(
        icon = Icons.Outlined.CurrencyExchange,
        iconBg = UsdtGreen,
        name = stringResource(R.string.asset_usdt_name),
        subtitle = stringResource(R.string.asset_usdt_subtitle),
        balance = usdtBalanceText,
        usdSubtitle = usdtUsdText,
        balanceTag = "asset_usdt_balance",
        onClick = { onAssetClick(TxAssetKind.JETTON) },
      )
    }
  }
}

@Composable
private fun AssetRow(
  icon: ImageVector,
  iconBg: Color,
  name: String,
  subtitle: String,
  balance: String,
  usdSubtitle: String? = null,
  balanceTag: String,
  onClick: () -> Unit = {},
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .tactileClickable(scaleOnPress = 0.985f, onClick = onClick)
        .minimumInteractiveComponentSize()
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.size(42.dp).clip(CircleShape).background(iconBg),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = name,
        tint = Color.White,
        modifier = Modifier.size(22.dp),
      )
    }
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(text = name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        text = balance,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(balanceTag),
      )
      usdSubtitle?.let { usd ->
        Text(
          text = usd,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(modifier = Modifier.width(4.dp))
    Icon(
      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun RecentTransfersCard(
  txRecords: List<TxRecord>,
  activeNetwork: Network,
  onOpenTxStatus: (String) -> Unit,
  onOpenHistory: () -> Unit,
) {
  VaultGlassCard(
    modifier = Modifier.fillMaxWidth().testTag("recent_txs_card"),
    shape = RoundedCornerShape(22.dp),
    containerColor = VaultDarkCard,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.recent_txs_title),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (txRecords.isNotEmpty()) {
          TextButton(
            onClick = onOpenHistory,
            modifier = Modifier.testTag("view_all_history_button"),
          ) {
            Text(
              text = stringResource(R.string.recent_txs_view_all),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = VaultBlue,
            )
          }
        }
      }
      if (txRecords.isEmpty()) {
        Text(
          text = stringResource(R.string.recent_txs_empty),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
      } else {
        txRecords.take(5).forEachIndexed { index, tx ->
          if (index > 0) {
            HorizontalDivider(
              color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
              modifier = Modifier.padding(horizontal = 20.dp),
            )
          }
          val isIncoming = tx.direction == TxDirection.IN
          val counterpartyDisplay =
            remember(tx.counterpartyRaw, activeNetwork) {
              runCatching {
                TonAddress(tx.counterpartyRaw, activeNetwork).toFriendly(bounceable = false)
              }.getOrDefault(tx.counterpartyRaw)
            }
          val timeStr =
            remember(tx.createdAtEpochMs) {
              SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(tx.createdAtEpochMs))
            }
          val statusColor =
            when (tx.state) {
              TxStatus.CONFIRMED -> UsdtGreen
              TxStatus.BROADCASTED -> VaultBlue
              TxStatus.SIGNED,
              TxStatus.EXPIRED,
              TxStatus.DRAFT -> Color(0xFFFFCA28)
              TxStatus.FAILED -> MaterialTheme.colorScheme.error
            }
          val subtitleText =
            if (isIncoming || tx.id.startsWith("chain_")) {
              "${tx.state.name} • $timeStr"
            } else {
              "${tx.state.name} • #${tx.seqno}"
            }
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .tactileClickable(scaleOnPress = 0.985f) { onOpenTxStatus(tx.id) }
                .padding(horizontal = 20.dp, vertical = 13.dp)
                .testTag("tx_row_${tx.id}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier =
                Modifier.size(38.dp)
                  .clip(CircleShape)
                  .background((if (isIncoming) UsdtGreen else VaultBlue).copy(alpha = 0.14f)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = if (isIncoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = if (isIncoming) UsdtGreen else VaultBlue,
                modifier = Modifier.size(18.dp),
              )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text =
                  (if (tx.direction == TxDirection.OUT) "To " else "From ") +
                    shortenAddress(counterpartyDisplay),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                fontWeight = FontWeight.Medium,
              )
              tx.comment?.takeIf { it.isNotBlank() }?.let { memo ->
                Text(
                  text = memo,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Text(
                text = subtitleText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
              )
            }
            Text(
              text =
                (if (tx.direction == TxDirection.OUT) "-" else "+") +
                  "${tx.amount.format()} ${tx.assetSymbol}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = if (isIncoming) UsdtGreen else MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ChainMonitorCard(
  monitorState: ChainMonitorState,
  activeNetwork: Network,
  onToggleMonitor: () -> Unit,
  onSyncNow: () -> Unit,
) {
  val isRunning = monitorState.enabled && monitorState.isActive
  val indicatorColor =
    when {
      !monitorState.enabled -> Color(0xFFFFCA28)
      monitorState.lastError != null -> Color(0xFFFF8A80)
      isRunning -> UsdtGreen
      else -> VaultBlue
    }
  val pulseAlpha =
    if (isRunning && monitorState.lastError == null) {
      val transition = rememberInfiniteTransition(label = "monitorPulse")
      val animated by
        transition.animateFloat(
          initialValue = 0.45f,
          targetValue = 1f,
          animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
          label = "pulseAlpha",
        )
      animated
    } else {
      1f
    }

  val titleText =
    if (monitorState.enabled) stringResource(R.string.monitor_title_active)
    else stringResource(R.string.monitor_title_paused)
  val subtitleText =
    if (monitorState.enabled) {
      stringResource(R.string.monitor_subtitle_active, monitorState.pollIntervalSeconds)
    } else {
      stringResource(R.string.monitor_subtitle_paused)
    }
  val formattedLastSync =
    remember(monitorState.lastSyncEpochMs) {
      monitorState.lastSyncEpochMs?.let { ts ->
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
      }
    }

  VaultGlassCard(
    modifier = Modifier.fillMaxWidth().testTag("chain_monitor_card"),
    shape = RoundedCornerShape(20.dp),
    containerColor = VaultDarkCardElevated,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("monitor_status_badge"),
        ) {
          Box(
            modifier =
              Modifier.size(10.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .clip(CircleShape)
                .background(indicatorColor)
          )
          Text(
            text = "$titleText • ${activeNetwork.name}",
            style = MaterialTheme.typography.labelLarge,
            color = indicatorColor,
            fontWeight = FontWeight.SemiBold,
          )
        }
        if (monitorState.isSyncing) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = VaultBlue,
          )
        }
      }

      Text(
        text = subtitleText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Text(
        text =
          if (formattedLastSync != null) {
            stringResource(
              R.string.monitor_last_sync,
              formattedLastSync,
              monitorState.lastProviderName ?: "TON",
            )
          } else {
            stringResource(R.string.monitor_waiting_first_sync)
          },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      monitorState.lastError?.let { err ->
        Text(
          text = err,
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFFFF8A80),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = onSyncNow,
          enabled = !monitorState.isSyncing,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.weight(1f).height(48.dp).testTag("monitor_sync_now_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.monitor_action_sync_now),
            style = MaterialTheme.typography.labelMedium,
          )
        }
        OutlinedButton(
          onClick = onToggleMonitor,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.weight(1f).height(48.dp).testTag("monitor_toggle_button"),
        ) {
          Icon(
            imageVector = if (monitorState.enabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text =
              if (monitorState.enabled) stringResource(R.string.monitor_action_pause)
              else stringResource(R.string.monitor_action_resume),
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
    }
  }
}

private fun shortenAddress(address: String): String {
  if (address.length <= 14) return address
  return "${address.take(6)}...${address.takeLast(6)}"
}

package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.AddressBookEntry
import com.example.core.model.ASSET_ID_GRAM
import com.example.core.model.ASSET_ID_USDT
import com.example.core.model.BalanceState
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.TransferPreview
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.TxStatus
import com.example.core.model.USDT_DECIMALS
import com.example.core.network.FiatRates
import com.example.core.wallet.AddressCheckResult
import com.example.core.wallet.TransferBuilder
import com.example.ui.components.BottomThumbActionBar
import com.example.ui.components.SecureScreenEffect
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
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SendFormScreen(
  activeNetwork: Network,
  balanceState: BalanceState,
  recipientInput: String,
  amountInput: String,
  commentInput: String,
  isBusy: Boolean,
  errorMessage: String?,
  onRecipientChange: (String) -> Unit,
  onAmountChange: (String) -> Unit,
  onCommentChange: (String) -> Unit,
  onValidateRecipient: (String) -> AddressCheckResult,
  onReviewTransfer: () -> Unit,
  onBack: () -> Unit,
  selectedAsset: TxAssetKind = TxAssetKind.GRAM,
  onAssetChange: (TxAssetKind) -> Unit = {},
  fiatRates: FiatRates = FiatRates(),
  addressBookEntries: List<AddressBookEntry> = emptyList(),
  onSelectContact: (AddressBookEntry) -> Unit = { onRecipientChange(it.friendlyAddress) },
  onSaveContact: (label: String, address: String) -> Unit = { _, _ -> },
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  var showSaveContactDialog by remember { mutableStateOf(false) }
  var newContactLabel by remember { mutableStateOf("") }

  val recipientCheck =
    remember(recipientInput, activeNetwork) {
      if (recipientInput.isBlank()) null else onValidateRecipient(recipientInput)
    }
  val alreadyInContacts =
    remember(recipientCheck, addressBookEntries) {
      val valid = recipientCheck as? AddressCheckResult.Valid ?: return@remember false
      addressBookEntries.any { it.address.raw.equals(valid.parsedAddress.address.raw, ignoreCase = true) }
    }
  val commentByteCount = remember(commentInput) { commentInput.trim().toByteArray(Charsets.UTF_8).size }
  val commentTooLong = commentByteCount > TransferBuilder.MAX_COMMENT_UTF8_BYTES
  val canSubmit =
    !isBusy &&
      recipientCheck is AddressCheckResult.Valid &&
      (amountInput.isNotBlank() || recipientCheck.uriAmount != null) &&
      !commentTooLong
  val activeSymbol = if (selectedAsset == TxAssetKind.JETTON) ASSET_ID_USDT else ASSET_ID_GRAM
  val maxFillableAmount =
    remember(balanceState.gramBalance, balanceState.usdtBalance, selectedAsset) {
      if (selectedAsset == TxAssetKind.JETTON) {
        balanceState.usdtBalance?.takeIf { it.units > BigInteger.ZERO }?.format()
      } else {
        val gram = balanceState.gramBalance ?: return@remember null
        val reserve = BigInteger.valueOf(TransferBuilder.LOCAL_FALLBACK_FEE_NANO * 2L)
        val spendable = gram.units - reserve
        if (spendable > BigInteger.ZERO) Money(spendable, GRAM_DECIMALS).format() else null
      }
    }
  val enteredUsdEstimate =
    remember(amountInput, selectedAsset, fiatRates) {
      val decimals = if (selectedAsset == TxAssetKind.JETTON) USDT_DECIMALS else GRAM_DECIMALS
      val parsed = runCatching { Money.fromDecimalString(amountInput.trim(), decimals) }.getOrNull()
        ?: return@remember null
      if (selectedAsset == TxAssetKind.JETTON) {
        fiatRates.formatUsdValue(gramAmount = null, usdtAmount = parsed)
      } else {
        fiatRates.formatUsdValue(gramAmount = parsed, usdtAmount = null)
      }
    }

  if (showSaveContactDialog && recipientCheck is AddressCheckResult.Valid) {
    AlertDialog(
      onDismissRequest = { showSaveContactDialog = false },
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text(stringResource(R.string.address_book_save_recipient)) },
      text = {
        OutlinedTextField(
          value = newContactLabel,
          onValueChange = { newContactLabel = it },
          label = { Text(stringResource(R.string.address_book_name_label)) },
          placeholder = { Text(stringResource(R.string.address_book_name_hint)) },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().testTag("save_recipient_name_input"),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onSaveContact(newContactLabel, recipientCheck.parsedAddress.address.toFriendly(recipientCheck.parsedAddress.isBounceable))
            newContactLabel = ""
            showSaveContactDialog = false
          },
          enabled = newContactLabel.trim().isNotEmpty(),
          modifier = Modifier.testTag("save_recipient_confirm_button"),
        ) {
          Text(stringResource(R.string.address_book_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { showSaveContactDialog = false }) {
          Text(stringResource(R.string.pin_cancel_button))
        }
      },
    )
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      SendTopBar(
        title = "Send $activeSymbol",
        activeNetwork = activeNetwork,
        onBack = onBack,
      )
    },
    bottomBar = {
      BottomThumbActionBar {
        Button(
          onClick = onReviewTransfer,
          enabled = canSubmit,
          shape = RoundedCornerShape(16.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VaultBlue),
          modifier = Modifier.fillMaxWidth().height(54.dp).testTag("send_review_button"),
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = Color(0xFF001E30),
            )
          } else {
            Text(
              text = stringResource(R.string.send_review_button),
              fontWeight = FontWeight.Bold,
              color = Color(0xFF001E30),
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        VaultGlassCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          containerColor = VaultDarkCard,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                  selected = selectedAsset == TxAssetKind.GRAM,
                  onClick = { onAssetChange(TxAssetKind.GRAM) },
                  enabled = !isBusy,
                  label = { Text(stringResource(R.string.send_asset_gram)) },
                  modifier = Modifier.testTag("send_asset_gram"),
                )
                FilterChip(
                  selected = selectedAsset == TxAssetKind.JETTON,
                  onClick = { onAssetChange(TxAssetKind.JETTON) },
                  enabled = !isBusy,
                  label = { Text(stringResource(R.string.send_asset_usdt)) },
                  modifier = Modifier.testTag("send_asset_usdt"),
                )
              }
              Surface(
                shape = RoundedCornerShape(999.dp),
                color =
                  if (activeNetwork == Network.MAINNET) VaultBlue.copy(alpha = 0.16f)
                  else TestnetBannerRed.copy(alpha = 0.25f),
              ) {
                Text(
                  text =
                    if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                    else stringResource(R.string.network_testnet),
                  style = MaterialTheme.typography.labelSmall,
                  color = if (activeNetwork == Network.MAINNET) VaultBlue else Color(0xFFFF8A80),
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
              }
            }
            Text(
              text =
                if (selectedAsset == TxAssetKind.JETTON) {
                  stringResource(
                    R.string.send_available_usdt,
                    balanceState.usdtBalance?.format() ?: "--",
                  )
                } else {
                  stringResource(
                    R.string.send_available_balance,
                    balanceState.gramBalance?.format() ?: "--",
                  )
                },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("send_available_balance_text"),
            )
            AnimatedVisibility(visible = selectedAsset == TxAssetKind.JETTON) {
              Text(
                text = stringResource(R.string.send_usdt_gas_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        AnimatedVisibility(visible = addressBookEntries.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Contacts,
                contentDescription = null,
                tint = VaultBlue,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = stringResource(R.string.address_book_pick),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              addressBookEntries.forEach { entry ->
                FilterChip(
                  selected = recipientInput.trim() == entry.friendlyAddress,
                  onClick = { onSelectContact(entry) },
                  label = { Text(entry.label) },
                  modifier = Modifier.testTag("send_contact_chip_${entry.id}"),
                )
              }
            }
          }
        }

        OutlinedTextField(
          value = recipientInput,
          onValueChange = onRecipientChange,
          enabled = !isBusy,
          label = { Text(stringResource(R.string.send_recipient_label)) },
          placeholder = { Text(stringResource(R.string.send_recipient_hint)) },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              if (recipientCheck is AddressCheckResult.Valid && !alreadyInContacts) {
                IconButton(
                  onClick = { showSaveContactDialog = true },
                  modifier = Modifier.testTag("send_save_contact_button"),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.BookmarkAdd,
                    contentDescription = stringResource(R.string.address_book_save_recipient),
                    tint = UsdtGreen,
                  )
                }
              }
              IconButton(
                onClick = {
                  val clipboard = context.getSystemService(ClipboardManager::class.java)
                  val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                  if (clipText.isNotBlank()) onRecipientChange(clipText.trim())
                },
                enabled = !isBusy,
              ) {
                Icon(
                  imageVector = Icons.Filled.ContentPaste,
                  contentDescription = stringResource(R.string.action_paste),
                  tint = VaultBlue,
                )
              }
            }
          },
          isError = recipientCheck is AddressCheckResult.Invalid,
          supportingText = {
            when (recipientCheck) {
              is AddressCheckResult.Valid -> {
                val typeLabel =
                  when {
                    recipientCheck.parsedAddress.isRaw -> stringResource(R.string.receive_checker_raw_type)
                    recipientCheck.parsedAddress.isBounceable ->
                      stringResource(R.string.receive_checker_friendly_bounceable)
                    else -> stringResource(R.string.receive_checker_friendly_nonbounceable)
                  }
                Text(
                  text =
                    stringResource(
                      R.string.receive_checker_valid_format,
                      if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                      else stringResource(R.string.network_testnet),
                      typeLabel,
                    ),
                  color = UsdtGreen,
                )
              }
              is AddressCheckResult.Invalid -> {
                Text(text = recipientCheck.reason, color = MaterialTheme.colorScheme.error)
              }
              null -> Unit
            }
          },
          modifier = Modifier.fillMaxWidth().testTag("send_recipient_input"),
        )

        OutlinedTextField(
          value = amountInput,
          onValueChange = onAmountChange,
          enabled = !isBusy,
          label = { Text(stringResource(R.string.send_amount_label_asset, activeSymbol)) },
          placeholder = { Text(stringResource(R.string.send_amount_hint)) },
          shape = RoundedCornerShape(16.dp),
          suffix = {
            Text(
              activeSymbol,
              fontWeight = FontWeight.SemiBold,
              color = if (selectedAsset == TxAssetKind.JETTON) UsdtGreen else VaultBlue,
            )
          },
          trailingIcon = {
            if (maxFillableAmount != null) {
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = VaultBlue.copy(alpha = 0.16f),
                modifier =
                  Modifier.padding(end = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .tactileClickable(enabled = !isBusy) { onAmountChange(maxFillableAmount) },
              ) {
                Text(
                  text = stringResource(R.string.action_max),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = VaultBlue,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }
            }
          },
          supportingText =
            enteredUsdEstimate?.let { usd ->
              {
                Text(
                  text = "≈ $usd USD",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("send_amount_usd_estimate"),
                )
              }
            },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth().testTag("send_amount_input"),
        )

        OutlinedTextField(
          value = commentInput,
          onValueChange = onCommentChange,
          enabled = !isBusy,
          label = { Text(stringResource(R.string.send_comment_label)) },
          placeholder = { Text(stringResource(R.string.send_comment_hint)) },
          shape = RoundedCornerShape(16.dp),
          isError = commentTooLong,
          supportingText = {
            Text(
              text =
                stringResource(
                  R.string.send_comment_bytes,
                  commentByteCount,
                  TransferBuilder.MAX_COMMENT_UTF8_BYTES,
                ),
              color =
                if (commentTooLong) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth().testTag("send_comment_input"),
        )

        AnimatedVisibility(
          visible = errorMessage != null,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          errorMessage?.let { err ->
            Card(
              modifier = Modifier.fillMaxWidth().testTag("send_error_card"),
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Filled.ErrorOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = err,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun SendConfirmScreen(
  preview: TransferPreview,
  isBusy: Boolean,
  errorMessage: String?,
  onConfirmAndSend: () -> Unit,
  onBack: () -> Unit,
  fiatRates: FiatRates = FiatRates(),
) {
  SecureScreenEffect()
  BackHandler(enabled = !isBusy, onBack = onBack)

  val isJetton = preview.assetKind == TxAssetKind.JETTON
  val destinationDisplay =
    remember(preview) { preview.destination.address.toFriendly(bounceable = preview.effectiveBounce) }
  val usdEstimate =
    remember(preview, fiatRates) {
      if (isJetton) fiatRates.formatUsdValue(gramAmount = null, usdtAmount = preview.amount)
      else fiatRates.formatUsdValue(gramAmount = preview.amount, usdtAmount = null)
    }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      SendTopBar(
        title = stringResource(R.string.send_confirm_title),
        activeNetwork = preview.network,
        onBack = { if (!isBusy) onBack() },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (isJetton) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = UsdtGreen.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, UsdtGreen.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth().testTag("confirm_verified_usdt_banner"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Verified,
                contentDescription = null,
                tint = UsdtGreen,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = stringResource(R.string.send_confirm_verified_usdt),
                style = MaterialTheme.typography.labelMedium,
                color = UsdtGreen,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }

        VaultGlassCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          containerColor = VaultDarkCard,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.send_confirm_destination_label),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = destinationDisplay,
              style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("confirm_destination_address"),
            )
            Surface(
              shape = RoundedCornerShape(999.dp),
              color =
                if (preview.effectiveBounce) VaultBlue.copy(alpha = 0.16f) else Color(0xFF332612),
              modifier =
                Modifier.testTag("confirm_bounce_badge")
                  .semantics(mergeDescendants = true) {},
            ) {
              Text(
                text =
                  if (preview.effectiveBounce) stringResource(R.string.send_bounce_bounceable)
                  else stringResource(R.string.send_bounce_nonbounceable),
                style = MaterialTheme.typography.labelSmall,
                color = if (preview.effectiveBounce) VaultBlue else Color(0xFFFFE082),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              )
            }
          }
        }

        VaultGlassCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          containerColor = VaultDarkCard,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            ConfirmDetailRow(
              label = stringResource(R.string.send_confirm_amount_label),
              value =
                if (usdEstimate != null) "${preview.amount.format()} ${preview.assetSymbol} (≈ $usdEstimate)"
                else "${preview.amount.format()} ${preview.assetSymbol}",
              highlight = true,
            )
            preview.attachedGramNano?.let { attached ->
              ConfirmDetailRow(
                label = stringResource(R.string.send_confirm_gas_label),
                value = "${attached.format()} GRAM",
              )
            }
            ConfirmDetailRow(
              label = stringResource(R.string.send_confirm_fee_label),
              value = "~${preview.estimatedFee.format()} GRAM",
            )
            if (isJetton) {
              Text(
                text = stringResource(R.string.send_confirm_gas_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (preview.feeIsEstimateFallback) {
              Text(
                text = stringResource(R.string.send_confirm_fee_fallback),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ConfirmDetailRow(
              label = stringResource(R.string.send_confirm_total_label),
              value =
                if (isJetton) {
                  val gramOut = (preview.attachedGramNano ?: Money.zero()) + preview.estimatedFee
                  "${preview.totalDebit.format()} USDT + ~${gramOut.format()} GRAM"
                } else {
                  "${preview.totalDebit.format()} GRAM"
                },
              highlight = true,
            )
            ConfirmDetailRow(
              label = stringResource(R.string.send_confirm_seqno_label),
              value = "#${preview.seqno}",
            )
            ConfirmDetailRow(
              label = stringResource(R.string.send_confirm_ttl_label),
              value = stringResource(R.string.send_confirm_ttl_value),
            )
            preview.comment?.let { memo ->
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
              Text(
                text = stringResource(R.string.send_comment_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = memo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }

        if (preview.senderNeedsStateInit) {
          VaultGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            containerColor = VaultDarkCardElevated,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = VaultBlue,
              )
              Text(
                text = stringResource(R.string.send_confirm_state_init_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        errorMessage?.let { err ->
          Card(
            modifier = Modifier.fillMaxWidth().testTag("send_confirm_error"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }

        Button(
          onClick = onConfirmAndSend,
          enabled = !isBusy,
          shape = RoundedCornerShape(18.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VaultBlue),
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag("send_confirm_button"),
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = Color(0xFF001E30),
            )
          } else {
            Text(
              text = stringResource(R.string.send_confirm_button),
              fontWeight = FontWeight.Bold,
              color = Color(0xFF001E30),
            )
          }
        }
      }
    }
  }
}

@Composable
fun SendStatusScreen(
  txRecord: TxRecord,
  onRefreshStatus: () -> Unit,
  onDone: () -> Unit,
) {
  BackHandler(onBack = onDone)
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val copiedLabel = stringResource(R.string.send_hash_copied)
  val addrCopiedLabel = stringResource(R.string.address_copied)
  val isIncoming = txRecord.direction == TxDirection.IN

  val (statusIcon, statusTint, statusText) =
    when {
      isIncoming && txRecord.state == TxStatus.CONFIRMED ->
        Triple(Icons.Filled.ArrowDownward, UsdtGreen, stringResource(R.string.tx_status_received))
      txRecord.state == TxStatus.CONFIRMED ->
        Triple(Icons.Filled.CheckCircle, UsdtGreen, stringResource(R.string.send_status_confirmed))
      txRecord.state == TxStatus.BROADCASTED ->
        Triple(Icons.Filled.HourglassTop, VaultBlue, stringResource(R.string.send_status_broadcasted))
      txRecord.state == TxStatus.SIGNED ->
        Triple(Icons.Filled.Schedule, VaultBlue, stringResource(R.string.send_status_signed))
      txRecord.state == TxStatus.EXPIRED ->
        Triple(Icons.Filled.ErrorOutline, Color(0xFFFFCA28), stringResource(R.string.send_status_expired))
      txRecord.state == TxStatus.FAILED ->
        Triple(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, stringResource(R.string.send_status_failed))
      else ->
        Triple(Icons.Filled.Schedule, VaultBlue, stringResource(R.string.send_status_draft))
    }

  val counterpartyDisplay =
    remember(txRecord.counterpartyRaw, txRecord.network) {
      runCatching {
        TonAddress(txRecord.counterpartyRaw, txRecord.network).toFriendly(bounceable = false)
      }.getOrDefault(txRecord.counterpartyRaw)
    }

  val formattedTime =
    remember(txRecord.createdAtEpochMs) {
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(txRecord.createdAtEpochMs))
    }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      SendTopBar(
        title =
          if (isIncoming || txRecord.id.startsWith("chain_")) {
            stringResource(R.string.tx_detail_title)
          } else {
            stringResource(R.string.send_status_title)
          },
        activeNetwork = txRecord.network,
        onBack = onDone,
      )
    },
    bottomBar = {
      BottomThumbActionBar {
        if (!txRecord.state.isTerminal) {
          OutlinedButton(
            onClick = onRefreshStatus,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("send_status_refresh_button"),
          ) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.send_status_refresh))
          }
        }
        Button(
          onClick = onDone,
          shape = RoundedCornerShape(16.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VaultBlue),
          modifier = Modifier.fillMaxWidth().height(54.dp).testTag("send_done_button"),
        ) {
          Text(
            text = stringResource(R.string.send_status_done),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF001E30),
          )
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          modifier =
            Modifier.size(76.dp).clip(CircleShape).background(statusTint.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = statusIcon,
            contentDescription = statusText,
            tint = statusTint,
            modifier = Modifier.size(40.dp),
          )
        }

        Text(
          text = statusText,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = statusTint,
          modifier = Modifier.testTag("send_status_badge"),
        )

        Text(
          text = (if (isIncoming) "+" else "-") + "${txRecord.amount.format()} ${txRecord.assetSymbol}",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = if (isIncoming) UsdtGreen else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("tx_status_amount"),
        )

        if (!isIncoming && !txRecord.id.startsWith("chain_")) {
          LifecycleStepStrip(state = txRecord.state)
        }

        VaultGlassCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          containerColor = VaultDarkCard,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              modifier =
                Modifier.fillMaxWidth().tactileClickable {
                  val clipboard = context.getSystemService(ClipboardManager::class.java)
                  clipboard?.setPrimaryClip(ClipData.newPlainText("Address", counterpartyDisplay))
                  scope.launch { snackbarHostState.showSnackbar(addrCopiedLabel) }
                },
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text =
                    if (isIncoming) stringResource(R.string.tx_counterparty_from)
                    else stringResource(R.string.tx_counterparty_to),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = counterpartyDisplay,
                  style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.action_copy),
                tint = VaultBlue,
                modifier = Modifier.size(18.dp),
              )
            }
            if (!txRecord.comment.isNullOrBlank()) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = stringResource(R.string.tx_comment_label),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = txRecord.comment,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ConfirmDetailRow(label = "Time", value = formattedTime)
            if (!isIncoming && txRecord.seqno > 0) {
              ConfirmDetailRow(
                label = stringResource(R.string.send_confirm_seqno_label),
                value = "#${txRecord.seqno}",
              )
            }
            txRecord.lt?.let { lt ->
              ConfirmDetailRow(label = stringResource(R.string.tx_lt_label), value = lt)
            }
            txRecord.fee?.let { fee ->
              ConfirmDetailRow(
                label = stringResource(R.string.send_confirm_fee_label),
                value = "${fee.format()} GRAM",
              )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            HashCopyRow(
              label = stringResource(R.string.send_status_msg_hash_label),
              hashHex = txRecord.messageHash,
              onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Message Hash", txRecord.messageHash))
                scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
              },
            )
            txRecord.txHash?.let { hash ->
              HashCopyRow(
                label = stringResource(R.string.send_status_tx_hash_label),
                hashHex = hash,
                onCopy = {
                  val clipboard = context.getSystemService(ClipboardManager::class.java)
                  clipboard?.setPrimaryClip(ClipData.newPlainText("Tx Hash", hash))
                  scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                },
              )
            }
            txRecord.jettonMaster?.takeIf { txRecord.kind == TxAssetKind.JETTON }?.let { masterRaw ->
              val friendlyMaster =
                remember(masterRaw, txRecord.network) {
                  runCatching { TonAddress(masterRaw, txRecord.network).toFriendly(bounceable = true) }
                    .getOrDefault(masterRaw)
                }
              HashCopyRow(
                label = stringResource(R.string.tx_asset_jetton_master_label),
                hashHex = friendlyMaster,
                onCopy = {
                  val clipboard = context.getSystemService(ClipboardManager::class.java)
                  clipboard?.setPrimaryClip(ClipData.newPlainText("USDT Master", friendlyMaster))
                  scope.launch { snackbarHostState.showSnackbar(copiedLabel) }
                },
              )
            }
            txRecord.errorDetail?.let { detail ->
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
              Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color =
                  if (txRecord.state == TxStatus.FAILED) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

private enum class HistoryFilter {
  ALL,
  IN,
  OUT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
  activeNetwork: Network,
  txRecords: List<TxRecord>,
  isSyncing: Boolean,
  onSyncNow: () -> Unit,
  onSelectTx: (String) -> Unit,
  onBack: () -> Unit,
) {
  BackHandler(onBack = onBack)
  var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
  val pullToRefreshState = rememberPullToRefreshState()
  val filtered =
    remember(txRecords, selectedFilter) {
      when (selectedFilter) {
        HistoryFilter.ALL -> txRecords
        HistoryFilter.IN -> txRecords.filter { it.direction == TxDirection.IN }
        HistoryFilter.OUT -> txRecords.filter { it.direction == TxDirection.OUT }
      }
    }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      SendTopBar(
        title = stringResource(R.string.history_title),
        activeNetwork = activeNetwork,
        onBack = onBack,
      )
    },
  ) { innerPadding ->
    PullToRefreshBox(
      isRefreshing = isSyncing,
      onRefresh = onSyncNow,
      state = pullToRefreshState,
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
          modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = selectedFilter == HistoryFilter.ALL,
                onClick = { selectedFilter = HistoryFilter.ALL },
                label = { Text(stringResource(R.string.history_filter_all)) },
                modifier = Modifier.testTag("history_filter_all"),
              )
              FilterChip(
                selected = selectedFilter == HistoryFilter.IN,
                onClick = { selectedFilter = HistoryFilter.IN },
                label = { Text(stringResource(R.string.history_filter_in)) },
                modifier = Modifier.testTag("history_filter_in"),
              )
              FilterChip(
                selected = selectedFilter == HistoryFilter.OUT,
                onClick = { selectedFilter = HistoryFilter.OUT },
                label = { Text(stringResource(R.string.history_filter_out)) },
                modifier = Modifier.testTag("history_filter_out"),
              )
            }
            IconButton(
              onClick = onSyncNow,
              enabled = !isSyncing,
              modifier = Modifier.testTag("history_sync_button"),
            ) {
              if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              } else {
                Icon(
                  imageVector = Icons.Filled.Refresh,
                  contentDescription = stringResource(R.string.monitor_action_sync_now),
                  tint = VaultBlue,
                )
              }
            }
          }

          if (filtered.isEmpty()) {
            VaultGlassCard(
              modifier = Modifier.fillMaxWidth().testTag("history_empty_card"),
              shape = RoundedCornerShape(20.dp),
              containerColor = VaultDarkCard,
            ) {
              Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize().navigationBarsPadding(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              items(filtered, key = { it.id }) { tx ->
                val isIn = tx.direction == TxDirection.IN
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
                VaultGlassCard(
                  modifier =
                    Modifier.fillMaxWidth()
                      .tactileClickable(scaleOnPress = 0.985f) { onSelectTx(tx.id) }
                      .testTag("history_row_${tx.id}"),
                  shape = RoundedCornerShape(20.dp),
                  containerColor = VaultDarkCard,
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Box(
                      modifier =
                        Modifier.size(42.dp)
                          .clip(CircleShape)
                          .background((if (isIn) UsdtGreen else VaultBlue).copy(alpha = 0.14f)),
                      contentAlignment = Alignment.Center,
                    ) {
                      Icon(
                        imageVector = if (isIn) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = null,
                        tint = if (isIn) UsdtGreen else VaultBlue,
                        modifier = Modifier.size(20.dp),
                      )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                      Text(
                        text =
                          (if (isIn) "From " else "To ") +
                            if (counterpartyDisplay.length > 14) {
                              "${counterpartyDisplay.take(6)}...${counterpartyDisplay.takeLast(6)}"
                            } else counterpartyDisplay,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                        fontWeight = FontWeight.SemiBold,
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
                        text = "${tx.state.name} • $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                      )
                    }
                    Text(
                      text = (if (isIn) "+" else "-") + "${tx.amount.format()} ${tx.assetSymbol}",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Bold,
                      color = if (isIn) UsdtGreen else MaterialTheme.colorScheme.onSurface,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LifecycleStepStrip(state: TxStatus) {
  val signedDone = true
  val broadcastDone = state == TxStatus.BROADCASTED || state == TxStatus.CONFIRMED
  val terminalLabel =
    when (state) {
      TxStatus.FAILED -> "FAILED"
      TxStatus.EXPIRED -> "EXPIRED"
      else -> "CONFIRMED"
    }
  val terminalColor =
    when (state) {
      TxStatus.CONFIRMED -> UsdtGreen
      TxStatus.FAILED -> MaterialTheme.colorScheme.error
      TxStatus.EXPIRED -> Color(0xFFFFCA28)
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StepPill("SIGNED", active = signedDone, accent = VaultBlue, modifier = Modifier.weight(1f))
    StepPill("BROADCASTED", active = broadcastDone, accent = VaultBlue, modifier = Modifier.weight(1f))
    StepPill(terminalLabel, active = state.isTerminal, accent = terminalColor, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun StepPill(
  label: String,
  active: Boolean,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  val bgColor by
    animateColorAsState(
      targetValue = if (active) accent.copy(alpha = 0.18f) else VaultDarkCard,
      animationSpec = tween(250),
      label = "stepPillBg",
    )
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = bgColor,
    border = BorderStroke(1.dp, if (active) accent.copy(alpha = 0.35f) else VaultHairlineBorder),
    modifier = modifier,
  ) {
    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ConfirmDetailRow(label: String, value: String, highlight: Boolean = false) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = if (highlight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
      fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun HashCopyRow(label: String, hashHex: String, onCopy: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().tactileClickable(onClick = onCopy),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = if (hashHex.length > 18) "${hashHex.take(10)}...${hashHex.takeLast(8)}" else hashHex,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
      )
    }
    IconButton(onClick = onCopy) {
      Icon(
        imageVector = Icons.Filled.ContentCopy,
        contentDescription = label,
        tint = VaultBlue,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun SendTopBar(
  title: String,
  activeNetwork: Network,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
    if (activeNetwork == Network.TESTNET) {
      Box(
        modifier = Modifier.fillMaxWidth().background(TestnetBannerRed).padding(vertical = 6.dp),
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
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.testTag("send_back_button")) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back",
        )
      }
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      Surface(
        shape = RoundedCornerShape(999.dp),
        color =
          if (activeNetwork == Network.MAINNET) VaultBlue.copy(alpha = 0.16f)
          else TestnetBannerRed.copy(alpha = 0.25f),
        modifier = Modifier.padding(end = 8.dp),
      ) {
        Text(
          text =
            if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
            else stringResource(R.string.network_testnet),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = if (activeNetwork == Network.MAINNET) VaultBlue else Color(0xFFFF8A80),
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
  }
}

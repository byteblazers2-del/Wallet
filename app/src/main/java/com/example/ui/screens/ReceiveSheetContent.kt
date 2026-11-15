package com.example.ui.screens

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.wallet.AddressCheckResult
import com.example.core.wallet.TonTransferUri
import com.example.ui.components.QrCodeCard
import com.example.ui.components.tactileClickable
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.UsdtGreen
import com.example.ui.theme.VaultBlue
import com.example.ui.theme.VaultDarkCard
import com.example.ui.theme.VaultDarkCardElevated
import com.example.ui.theme.VaultHairlineBorder
import java.math.BigInteger

@Composable
fun ReceiveSheetContent(
  network: Network,
  friendlyAddress: String,
  rawAddress: String,
  onCopyAddress: (label: String, value: String, toastMessage: String) -> Unit,
  onSharePayload: (String) -> Unit,
  onBuildTransferUri: (amount: Money?, comment: String?) -> String = { amount, comment ->
    TonTransferUri.build(
      recipient = TonAddress(rawAddress, network),
      bounceable = false,
      amount = amount,
      comment = comment,
    )
  },
  onValidateInput: (String) -> AddressCheckResult = { input ->
    val trimmed = input.trim()
    if (trimmed.startsWith("ton://", ignoreCase = true)) {
      TonTransferUri.parse(trimmed, network).fold(
        onSuccess = { AddressCheckResult.Valid(it.parsedRecipient, it.amount, it.comment) },
        onFailure = { AddressCheckResult.Invalid(it.message ?: "Invalid TON transfer link") },
      )
    } else {
      TonAddress.parseDetailed(trimmed, network).fold(
        onSuccess = { AddressCheckResult.Valid(it) },
        onFailure = { AddressCheckResult.Invalid(it.message ?: "Invalid TON address") },
      )
    }
  },
) {
  val context = LocalContext.current
  var qrAsTransferLink by remember { mutableStateOf(false) }
  var amountText by remember { mutableStateOf("") }
  var commentText by remember { mutableStateOf("") }
  var checkerExpanded by remember { mutableStateOf(false) }
  var checkerInput by remember { mutableStateOf("") }

  val parsedAmountResult =
    remember(amountText) {
      val trimmed = amountText.trim()
      if (trimmed.isEmpty()) {
        Result.success<Money?>(null)
      } else {
        runCatching {
          val money = Money.fromDecimalString(trimmed, GRAM_DECIMALS)
          require(money.units > BigInteger.ZERO) { "Amount must be positive" }
          money
        }
      }
    }

  val validAmount = parsedAmountResult.getOrNull()
  val hasAmountError = amountText.isNotBlank() && parsedAmountResult.isFailure
  val cleanComment = commentText.trim().take(TonTransferUri.MAX_COMMENT_LENGTH).ifEmpty { null }
  val transferUri =
    remember(friendlyAddress, validAmount, cleanComment) {
      onBuildTransferUri(validAmount, cleanComment)
    }
  val qrPayload = if (qrAsTransferLink) transferUri else friendlyAddress

  val addressCopiedMsg = stringResource(R.string.address_copied)
  val linkCopiedMsg = stringResource(R.string.receive_link_copied)

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      text = stringResource(R.string.receive_sheet_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      text = stringResource(R.string.receive_mainnet_note),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth(),
    )

    Surface(
      shape = RoundedCornerShape(999.dp),
      color = VaultDarkCard,
      border = BorderStroke(1.dp, VaultHairlineBorder),
    ) {
      Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReceiveModeChip(
          label = stringResource(R.string.qr_mode_address),
          selected = !qrAsTransferLink,
          tag = "qr_mode_address_button",
          onClick = { qrAsTransferLink = false },
        )
        ReceiveModeChip(
          label = stringResource(R.string.qr_mode_link),
          selected = qrAsTransferLink,
          tag = "qr_mode_link_button",
          onClick = { qrAsTransferLink = true },
        )
      }
    }

    QrCodeCard(
      content = qrPayload,
      contentDescriptionText = stringResource(R.string.qr_code_cd),
      modifier = Modifier.testTag("receive_qr_code"),
    )

    AnimatedVisibility(
      visible = qrAsTransferLink,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = amountText,
          onValueChange = { amountText = it },
          label = { Text(stringResource(R.string.receive_amount_label)) },
          placeholder = { Text(stringResource(R.string.receive_amount_hint)) },
          isError = hasAmountError,
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.fillMaxWidth().testTag("receive_amount_input"),
        )
        if (hasAmountError) {
          Text(
            text = stringResource(R.string.receive_amount_error),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFF8A80),
            modifier = Modifier.testTag("receive_amount_error"),
          )
        }
        OutlinedTextField(
          value = commentText,
          onValueChange = { if (it.length <= TonTransferUri.MAX_COMMENT_LENGTH) commentText = it },
          label = { Text(stringResource(R.string.receive_comment_label)) },
          placeholder = { Text(stringResource(R.string.receive_comment_hint)) },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().testTag("receive_comment_input"),
        )
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = VaultDarkCard,
          border = BorderStroke(1.dp, VaultHairlineBorder),
          modifier = Modifier.fillMaxWidth(),
        ) {
          SelectionContainer {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = stringResource(R.string.receive_uri_label),
                style = MaterialTheme.typography.labelMedium,
                color = VaultBlue,
              )
              Text(
                text = transferUri,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("receive_transfer_uri_text"),
              )
            }
          }
        }
      }
    }

    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VaultDarkCard,
      border = BorderStroke(1.dp, VaultHairlineBorder),
      modifier = Modifier.fillMaxWidth(),
    ) {
      SelectionContainer {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = stringResource(R.string.receive_base64_label),
            style = MaterialTheme.typography.labelMedium,
            color = VaultBlue,
          )
          Text(
            text = friendlyAddress,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            modifier = Modifier.testTag("receive_full_address_text"),
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
          Text(
            text = stringResource(R.string.receive_raw_label),
            style = MaterialTheme.typography.labelMedium,
            color = VaultBlue,
          )
          Text(
            text = rawAddress,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("receive_raw_address_text"),
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Button(
        onClick = {
          if (qrAsTransferLink) {
            onCopyAddress("TON Transfer Link", transferUri, linkCopiedMsg)
          } else {
            onCopyAddress("TON Address", friendlyAddress, addressCopiedMsg)
          }
        },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = VaultBlue, contentColor = Color(0xFF001E30)),
        modifier =
          Modifier.weight(1f)
            .height(52.dp)
            .testTag(if (qrAsTransferLink) "sheet_copy_link_button" else "sheet_copy_button"),
      ) {
        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text =
            if (qrAsTransferLink) stringResource(R.string.action_copy_link)
            else stringResource(R.string.action_copy),
          fontWeight = FontWeight.SemiBold,
        )
      }
      Button(
        onClick = { onCopyAddress("TON Raw Address", rawAddress, addressCopiedMsg) },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = VaultDarkCardElevated, contentColor = Color.White),
        modifier = Modifier.weight(1f).height(52.dp).testTag("sheet_copy_raw_button"),
      ) {
        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = stringResource(R.string.action_copy_raw), fontWeight = FontWeight.SemiBold)
      }
    }

    OutlinedButton(
      onClick = { onSharePayload(qrPayload) },
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth().height(50.dp).testTag("sheet_share_button"),
    ) {
      Icon(imageVector = Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = stringResource(R.string.action_share), fontWeight = FontWeight.SemiBold)
    }

    Surface(
      shape = RoundedCornerShape(18.dp),
      color = VaultDarkCardElevated,
      border = BorderStroke(1.dp, VaultHairlineBorder),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .tactileClickable(scaleOnPress = 0.99f) { checkerExpanded = !checkerExpanded }
              .padding(horizontal = 16.dp, vertical = 14.dp)
              .testTag("address_checker_toggle"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.receive_checker_toggle),
            style = MaterialTheme.typography.labelLarge,
            color = VaultBlue,
            fontWeight = FontWeight.SemiBold,
          )
          Icon(
            imageVector = if (checkerExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(R.string.receive_checker_toggle),
            tint = VaultBlue,
          )
        }
        AnimatedVisibility(
          visible = checkerExpanded,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedTextField(
              value = checkerInput,
              onValueChange = { checkerInput = it },
              label = { Text(stringResource(R.string.receive_checker_label)) },
              placeholder = { Text(stringResource(R.string.receive_checker_hint)) },
              trailingIcon = {
                IconButton(
                  onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (clipText.isNotBlank()) checkerInput = clipText.trim()
                  }
                ) {
                  Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = stringResource(R.string.action_paste),
                    tint = VaultBlue,
                  )
                }
              },
              singleLine = false,
              maxLines = 3,
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth().testTag("address_checker_input"),
            )
            if (checkerInput.isNotBlank()) {
              val checkResult = remember(checkerInput, network) { onValidateInput(checkerInput) }
              AddressCheckResultCard(result = checkResult)
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

@Composable
private fun AddressCheckResultCard(
  result: AddressCheckResult,
) {
  when (result) {
    is AddressCheckResult.Valid -> {
      val parsed = result.parsedAddress
      val kindLabel =
        when {
          parsed.isRaw -> stringResource(R.string.receive_checker_raw_type)
          parsed.isBounceable -> stringResource(R.string.receive_checker_friendly_bounceable)
          else -> stringResource(R.string.receive_checker_friendly_nonbounceable)
        }
      val netLabel = stringResource(R.string.network_mainnet)
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF132D22),
        modifier =
          Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("address_checker_result"),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = UsdtGreen,
            modifier = Modifier.size(18.dp),
          )
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = stringResource(R.string.receive_checker_valid_format, netLabel, kindLabel),
              style = MaterialTheme.typography.labelMedium,
              color = UsdtGreen,
              fontWeight = FontWeight.SemiBold,
            )
            result.uriAmount?.let { amt ->
              Text(
                text = "Amount: ${amt.formatWithSymbol()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            result.uriComment?.let { note ->
              Text(
                text = "Comment: $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            Text(
              text = parsed.address.raw,
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    is AddressCheckResult.Invalid -> {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF3A1A1A),
        modifier =
          Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("address_checker_result"),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFFF8A80),
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = result.reason,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFFCDD2),
          )
        }
      }
    }
  }
}

@Composable
private fun ReceiveModeChip(
  label: String,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  val bg by
    animateColorAsState(
      targetValue = if (selected) VaultBlue else Color.Transparent,
      animationSpec = tween(200),
      label = "receiveChipBg",
    )
  val fg by
    animateColorAsState(
      targetValue = if (selected) Color(0xFF001E30) else MaterialTheme.colorScheme.onSurfaceVariant,
      animationSpec = tween(200),
      label = "receiveChipFg",
    )
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = bg,
    modifier = Modifier.clip(RoundedCornerShape(999.dp)).tactileClickable(onClick = onClick).testTag(tag),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = fg,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

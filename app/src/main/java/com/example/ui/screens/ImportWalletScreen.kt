package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.CandidateAccount
import com.example.core.model.MnemonicScheme
import com.example.ui.components.BottomThumbActionBar
import com.example.ui.components.SecureScreenEffect
import com.example.ui.components.tactileClickable
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.VaultBlue
import com.example.ui.theme.VaultDarkCard
import com.example.ui.theme.VaultDarkCardElevated
import com.example.ui.theme.VaultHairlineBorder

@Composable
fun ImportPhraseScreen(
  isBusy: Boolean,
  errorMessage: String?,
  onBack: () -> Unit,
  onDiscoverAccounts: (CharArray) -> Unit,
) {
  SecureScreenEffect()
  BackHandler(onBack = onBack)

  var rawPhrase by remember { mutableStateOf("") }
  val wordCount =
    remember(rawPhrase) {
      rawPhrase.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }

  DisposableEffect(Unit) {
    onDispose { rawPhrase = "" }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = {
      BottomThumbActionBar {
        Button(
          onClick = { onDiscoverAccounts(rawPhrase.toCharArray()) },
          enabled = !isBusy && (wordCount == 12 || wordCount == 24),
          shape = RoundedCornerShape(18.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VaultBlue, contentColor = Color(0xFF001E30)),
          modifier = Modifier.fillMaxWidth().height(54.dp).testTag("import_discover_button"),
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = Color(0xFF001E30),
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text(text = stringResource(R.string.import_discover_button), fontWeight = FontWeight.Bold)
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(horizontal = 20.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 540.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          IconButton(onClick = onBack, modifier = Modifier.testTag("import_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.phrase_done),
            )
          }
          Text(
            text = stringResource(R.string.import_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        }

        Text(
          text = stringResource(R.string.import_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = rawPhrase,
          onValueChange = { rawPhrase = it },
          label = { Text(stringResource(R.string.import_field_label)) },
          placeholder = { Text(stringResource(R.string.import_field_hint)) },
          minLines = 4,
          maxLines = 6,
          shape = RoundedCornerShape(16.dp),
          keyboardOptions =
            KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              imeAction = ImeAction.Done,
            ),
          modifier = Modifier.fillMaxWidth().testTag("import_phrase_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.import_word_count, wordCount),
            style = MaterialTheme.typography.bodySmall,
            color =
              if (wordCount == 12 || wordCount == 24) VaultBlue
              else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("import_word_count_text"),
          )
          if (rawPhrase.isNotEmpty()) {
            TextButton(
              onClick = { rawPhrase = "" },
              modifier = Modifier.testTag("import_clear_button"),
            ) {
              Text(stringResource(R.string.import_clear_button))
            }
          }
        }

        if (!errorMessage.isNullOrBlank()) {
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("import_error_text"),
          )
        }
      }
    }
  }
}

@Composable
fun ImportSelectAccountScreen(
  candidates: List<CandidateAccount>,
  isAmbiguous24: Boolean,
  isBusy: Boolean,
  errorMessage: String?,
  onBack: () -> Unit,
  onConfirmAccount: (CandidateAccount) -> Unit,
) {
  SecureScreenEffect()
  BackHandler(onBack = onBack)

  var selectedId by remember(candidates) { mutableStateOf<String?>(null) }
  val selectedCandidate = candidates.firstOrNull { it.id == selectedId }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .windowInsetsPadding(WindowInsets.statusBars)
          .padding(horizontal = 20.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 560.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          IconButton(onClick = onBack, modifier = Modifier.testTag("import_accounts_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.phrase_done),
            )
          }
          Text(
            text = stringResource(R.string.import_accounts_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
        }

        Text(
          text = stringResource(R.string.import_accounts_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isAmbiguous24) {
          Card(
            modifier = Modifier.fillMaxWidth().testTag("import_ambiguous_24_banner"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF332612)),
            border = BorderStroke(1.dp, Color(0x33FFCA28)),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFFFCA28),
              )
              Text(
                text = stringResource(R.string.import_ambiguous_24_notice),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFE082),
              )
            }
          }
        }

        candidates.forEach { candidate ->
          val isSelected = candidate.id == selectedId
          CandidateAccountCard(
            candidate = candidate,
            selected = isSelected,
            onClick = { selectedId = candidate.id },
          )
        }

        if (!errorMessage.isNullOrBlank()) {
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("import_select_error_text"),
          )
        }

        Button(
          onClick = { selectedCandidate?.let(onConfirmAccount) },
          enabled = !isBusy && selectedCandidate != null,
          shape = RoundedCornerShape(18.dp),
          colors = ButtonDefaults.buttonColors(containerColor = VaultBlue, contentColor = Color(0xFF001E30)),
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag("import_confirm_account_button"),
        ) {
          if (isBusy) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = Color(0xFF001E30),
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text(text = stringResource(R.string.import_confirm_button), fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
private fun CandidateAccountCard(
  candidate: CandidateAccount,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val schemeText =
    when (candidate.scheme) {
      MnemonicScheme.MULTICHAIN -> stringResource(R.string.import_scheme_multichain)
      MnemonicScheme.TON_LEGACY -> stringResource(R.string.import_scheme_ton_legacy)
    }
  val containerBg by
    animateColorAsState(
      targetValue = if (selected) VaultDarkCardElevated else VaultDarkCard,
      animationSpec = tween(200),
      label = "candidateCardBg",
    )

  Card(
    modifier =
      Modifier.fillMaxWidth()
        .tactileClickable(scaleOnPress = 0.985f, onClick = onClick)
        .testTag("candidate_card_${candidate.id}"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = containerBg),
    border = if (selected) BorderStroke(1.5.dp, VaultBlue) else BorderStroke(1.dp, VaultHairlineBorder),
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
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (selected) VaultBlue else VaultDarkCardElevated,
          ) {
            Text(
              text = candidate.version.displayName,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = if (selected) Color(0xFF001E30) else VaultBlue,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
          Text(
            text = schemeText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
          imageVector =
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
          contentDescription = candidate.version.displayName,
          tint = if (selected) VaultBlue else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = "${stringResource(R.string.import_mainnet_label)} ${shortPreview(candidate.mainnetAddress.toFriendly(bounceable = false))}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "${stringResource(R.string.import_testnet_label)} ${shortPreview(candidate.testnetAddress.toFriendly(bounceable = false))}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun shortPreview(addr: String): String {
  if (addr.length <= 20) return addr
  return "${addr.take(10)}...${addr.takeLast(8)}"
}

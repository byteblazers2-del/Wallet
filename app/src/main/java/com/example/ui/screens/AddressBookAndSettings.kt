package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.data.AddressBookEntry
import com.example.core.model.JettonAllowList
import com.example.core.model.Network
import com.example.core.model.VaultSecurityMode
import com.example.core.wallet.AddressCheckResult
import com.example.core.wallet.AppSettings
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
import kotlinx.coroutines.launch

@Composable
fun AddressBookScreen(
  activeNetwork: Network,
  entries: List<AddressBookEntry>,
  errorMessage: String?,
  onSaveContact: (label: String, address: String) -> Unit,
  onDeleteContact: (id: String) -> Unit,
  onSendToContact: (AddressBookEntry) -> Unit,
  onValidateAddress: (String) -> AddressCheckResult,
  onBack: () -> Unit,
) {
  BackHandler(onBack = onBack)

  var labelInput by remember { mutableStateOf("") }
  var addressInput by remember { mutableStateOf("") }
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val copiedMsg = stringResource(R.string.address_copied)

  val addressCheck =
    remember(addressInput, activeNetwork) {
      if (addressInput.isBlank()) null else onValidateAddress(addressInput)
    }
  val canSave = labelInput.trim().isNotEmpty() && addressCheck is AddressCheckResult.Valid

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
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
          IconButton(onClick = onBack, modifier = Modifier.testTag("address_book_back_button")) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
            )
          }
          Text(
            text = stringResource(R.string.address_book_title),
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
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxSize().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        VaultGlassCard(
          modifier = Modifier.fillMaxWidth().testTag("address_book_add_card"),
          shape = RoundedCornerShape(22.dp),
          containerColor = VaultDarkCard,
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = VaultBlue)
              Text(
                text = stringResource(R.string.address_book_add),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
              )
            }
            OutlinedTextField(
              value = labelInput,
              onValueChange = { labelInput = it },
              label = { Text(stringResource(R.string.address_book_name_label)) },
              placeholder = { Text(stringResource(R.string.address_book_name_hint)) },
              singleLine = true,
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.fillMaxWidth().testTag("address_book_name_input"),
            )
            OutlinedTextField(
              value = addressInput,
              onValueChange = { addressInput = it },
              label = {
                Text(
                  stringResource(
                    R.string.address_book_address_label,
                    if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                    else stringResource(R.string.network_testnet),
                  )
                )
              },
              placeholder = { Text(stringResource(R.string.send_recipient_hint)) },
              trailingIcon = {
                IconButton(
                  onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (clipText.isNotBlank()) addressInput = clipText.trim()
                  }
                ) {
                  Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = stringResource(R.string.action_paste),
                    tint = VaultBlue,
                  )
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(14.dp),
              isError = addressCheck is AddressCheckResult.Invalid,
              supportingText = {
                when (addressCheck) {
                  is AddressCheckResult.Invalid -> {
                    Text(text = addressCheck.reason, color = MaterialTheme.colorScheme.error)
                  }
                  is AddressCheckResult.Valid -> {
                    Text(
                      text = addressCheck.parsedAddress.address.toFriendly(addressCheck.parsedAddress.isBounceable),
                      color = UsdtGreen,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  null -> Unit
                }
              },
              modifier = Modifier.fillMaxWidth().testTag("address_book_address_input"),
            )
            errorMessage?.let { err ->
              Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            Button(
              onClick = {
                onSaveContact(labelInput, addressInput)
                labelInput = ""
                addressInput = ""
              },
              enabled = canSave,
              shape = RoundedCornerShape(16.dp),
              colors = ButtonDefaults.buttonColors(containerColor = VaultBlue),
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("address_book_save_button"),
            ) {
              Text(
                text = stringResource(R.string.address_book_save),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF001E30),
              )
            }
          }
        }

        if (entries.isEmpty()) {
          VaultGlassCard(
            modifier = Modifier.fillMaxWidth().testTag("address_book_empty_card"),
            shape = RoundedCornerShape(20.dp),
            containerColor = VaultDarkCard,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(20.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Outlined.Contacts,
                contentDescription = null,
                tint = VaultBlue,
              )
              Text(
                text =
                  stringResource(
                    R.string.address_book_empty,
                    if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                    else stringResource(R.string.network_testnet),
                  ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(entries, key = { it.id }) { entry ->
              VaultGlassCard(
                modifier =
                  Modifier.fillMaxWidth()
                    .tactileClickable(scaleOnPress = 0.985f) { onSendToContact(entry) }
                    .testTag("contact_card_${entry.id}"),
                shape = RoundedCornerShape(20.dp),
                containerColor = VaultDarkCard,
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Box(
                    modifier =
                      Modifier.size(42.dp).clip(CircleShape).background(VaultBlue.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Person,
                      contentDescription = entry.label,
                      tint = VaultBlue,
                    )
                  }
                  Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      text = entry.label,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                      text =
                        if (entry.friendlyAddress.length > 18) {
                          "${entry.friendlyAddress.take(8)}...${entry.friendlyAddress.takeLast(8)}"
                        } else {
                          entry.friendlyAddress
                        },
                      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  IconButton(
                    onClick = {
                      val clipboard = context.getSystemService(ClipboardManager::class.java)
                      clipboard?.setPrimaryClip(ClipData.newPlainText(entry.label, entry.friendlyAddress))
                      scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
                    },
                    modifier = Modifier.testTag("contact_copy_${entry.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.ContentCopy,
                      contentDescription = stringResource(R.string.action_copy),
                      tint = VaultBlue,
                    )
                  }
                  IconButton(
                    onClick = { onSendToContact(entry) },
                    modifier = Modifier.testTag("contact_send_${entry.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.Send,
                      contentDescription = stringResource(R.string.action_send),
                      tint = UsdtGreen,
                    )
                  }
                  IconButton(
                    onClick = { onDeleteContact(entry.id) },
                    modifier = Modifier.testTag("contact_delete_${entry.id}"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Delete,
                      contentDescription = "Delete ${entry.label}",
                      tint = MaterialTheme.colorScheme.error,
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
fun SettingsSheetContent(
  activeNetwork: Network,
  securityMode: VaultSecurityMode,
  appSettings: AppSettings,
  onToggleAutoLock: (Boolean) -> Unit,
  onToggleLiveMonitor: (Boolean) -> Unit,
  onOpenAddressBook: () -> Unit,
  onCopyUsdtMaster: (String) -> Unit,
) {
  val usdtSpec = remember(activeNetwork) { JettonAllowList.usdtFor(activeNetwork) }
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      text = stringResource(R.string.settings_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )

    VaultGlassCard(
      shape = RoundedCornerShape(20.dp),
      containerColor = VaultDarkCardElevated,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .tactileClickable(scaleOnPress = 0.99f) {
                onToggleAutoLock(!appSettings.autoLockOnBackground)
              }
              .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.settings_auto_lock_title),
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.settings_auto_lock_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = appSettings.autoLockOnBackground,
            onCheckedChange = onToggleAutoLock,
            modifier = Modifier.testTag("settings_auto_lock_switch"),
          )
        }

        HorizontalDivider(
          color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
          modifier = Modifier.padding(horizontal = 16.dp),
        )

        Row(
          modifier =
            Modifier.fillMaxWidth()
              .tactileClickable(scaleOnPress = 0.99f) {
                onToggleLiveMonitor(!appSettings.liveMonitorEnabled)
              }
              .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.settings_live_monitor_title),
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.settings_live_monitor_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = appSettings.liveMonitorEnabled,
            onCheckedChange = onToggleLiveMonitor,
            modifier = Modifier.testTag("settings_live_monitor_switch"),
          )
        }
      }
    }

    Surface(
      shape = RoundedCornerShape(20.dp),
      color = VaultDarkCardElevated,
      border = BorderStroke(1.dp, VaultHairlineBorder),
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(20.dp))
          .tactileClickable(scaleOnPress = 0.985f, onClick = onOpenAddressBook)
          .testTag("settings_address_book_row"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Icon(imageVector = Icons.Outlined.Contacts, contentDescription = null, tint = VaultBlue)
          Text(
            text = stringResource(R.string.settings_open_address_book),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    VaultGlassCard(
      shape = RoundedCornerShape(20.dp),
      containerColor = VaultDarkCardElevated,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(imageVector = Icons.Filled.Security, contentDescription = null, tint = UsdtGreen, modifier = Modifier.size(18.dp))
          Text(
            text = stringResource(R.string.settings_security_mode_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = securityMode.name,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
          color = VaultBlue,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(imageVector = Icons.Outlined.Verified, contentDescription = null, tint = UsdtGreen, modifier = Modifier.size(18.dp))
          Text(
            text =
              stringResource(
                R.string.settings_usdt_master_label,
                if (activeNetwork == Network.MAINNET) stringResource(R.string.network_mainnet)
                else stringResource(R.string.network_testnet),
              ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .tactileClickable { onCopyUsdtMaster(usdtSpec.masterFriendly) }
              .padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = usdtSpec.masterFriendly,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.action_copy),
            tint = VaultBlue,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

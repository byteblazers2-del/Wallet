package com.example.core.wallet

import android.content.Context
import com.example.core.model.MnemonicScheme
import com.example.core.model.Network
import com.example.core.model.VaultMetadata
import com.example.core.model.VaultSecurityMode
import com.example.core.model.WalletVersion
import java.io.File
import org.json.JSONObject

class VaultMetadataStore(context: Context) {
  private val vaultDir = File(context.filesDir, "vault")
  private val metaFile = File(vaultDir, "vault_meta.json")

  fun load(): VaultMetadata? {
    if (!metaFile.exists()) return null
    return runCatching {
      val json = JSONObject(metaFile.readText())
      val modeName = json.optString("securityMode", VaultSecurityMode.DEVICE_KEYSTORE.name)
      val mode =
        runCatching { VaultSecurityMode.valueOf(modeName) }
          .getOrDefault(VaultSecurityMode.DEVICE_KEYSTORE)
      val versionName = json.optString("walletVersion", WalletVersion.V5R1.name)
      val version =
        runCatching { WalletVersion.valueOf(versionName) }
          .getOrDefault(WalletVersion.V5R1)
      val schemeName = json.optString("mnemonicScheme", MnemonicScheme.MULTICHAIN.name)
      val scheme =
        runCatching { MnemonicScheme.valueOf(schemeName) }
          .getOrDefault(MnemonicScheme.MULTICHAIN)
      val netName = json.optString("lastActiveNetwork", Network.MAINNET.name)
      val lastNet = runCatching { Network.valueOf(netName) }.getOrDefault(Network.MAINNET)
      VaultMetadata(
        publicKeyHex = json.getString("publicKeyHex"),
        mainnetRaw = json.getString("mainnetRaw"),
        testnetRaw = json.getString("testnetRaw"),
        isBackedUp = json.getBoolean("isBackedUp"),
        createdAtEpochMs = json.getLong("createdAtEpochMs"),
        securityMode = mode,
        walletVersion = version,
        mnemonicScheme = scheme,
        lastActiveNetwork = lastNet,
      )
    }.getOrNull()
  }

  fun save(metadata: VaultMetadata) {
    if (!vaultDir.exists()) vaultDir.mkdirs()
    val json =
      JSONObject()
        .put("publicKeyHex", metadata.publicKeyHex)
        .put("mainnetRaw", metadata.mainnetRaw)
        .put("testnetRaw", metadata.testnetRaw)
        .put("isBackedUp", metadata.isBackedUp)
        .put("createdAtEpochMs", metadata.createdAtEpochMs)
        .put("securityMode", metadata.securityMode.name)
        .put("walletVersion", metadata.walletVersion.name)
        .put("mnemonicScheme", metadata.mnemonicScheme.name)
        .put("lastActiveNetwork", metadata.lastActiveNetwork.name)
    val tempFile = File(vaultDir, "vault_meta.json.tmp")
    tempFile.writeText(json.toString())
    if (!tempFile.renameTo(metaFile)) {
      metaFile.writeText(json.toString())
      tempFile.delete()
    }
  }

  fun clear() {
    metaFile.delete()
  }
}

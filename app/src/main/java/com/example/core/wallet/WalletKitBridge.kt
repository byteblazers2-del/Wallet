package com.example.core.wallet

import android.content.Context
import com.example.core.keystore.SecureKeyStore
import com.example.core.model.Network
import com.example.core.model.TonAddress
import io.ton.walletkit.ITONWallet
import io.ton.walletkit.ITONWalletKit
import io.ton.walletkit.api.MAINNET
import io.ton.walletkit.api.TESTNET
import io.ton.walletkit.api.generated.TONNetwork
import io.ton.walletkit.config.TONWalletKitConfiguration
import io.ton.walletkit.model.TONHex
import io.ton.walletkit.model.WalletSigner
import io.ton.walletkit.storage.TONWalletKitStorageType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ton.kotlin.crypto.PrivateKeyEd25519

class WalletKitBridge(
  private val applicationContext: Context,
  private val secureKeyStore: SecureKeyStore,
) {
  suspend fun attachWallet(
    publicKeyHex: String,
    network: Network,
    walletVersion: com.example.core.model.WalletVersion = com.example.core.model.WalletVersion.V5R1,
    useAppPinMode: Boolean = false,
    requireUserAuth: Boolean = true,
  ): TonAddress? =
    kitMutex.withLock {
      if (walletVersion != com.example.core.model.WalletVersion.V5R1 &&
        walletVersion != com.example.core.model.WalletVersion.V4R2
      ) {
        return@withLock null
      }
      val kit = ensureInitialized(applicationContext)
      val signer =
        object : WalletSigner {
          override fun publicKey(): TONHex = TONHex("0x$publicKeyHex")

          override suspend fun sign(data: ByteArray): TONHex =
            secureKeyStore.withDecryptedSeed(
              useAppPinMode = useAppPinMode,
              requireUserAuth = requireUserAuth,
            ) { seed32 ->
              val sig = PrivateKeyEd25519(seed32).signToByteArray(data, 0, data.size)
              TONHex("0x" + AddressService.bytesToHex(sig))
            }
        }
      val signerInfo = kit.createSignerFromCustom(signer)
      val adapter =
        when (walletVersion) {
          com.example.core.model.WalletVersion.V5R1 ->
            kit.createV5R1Adapter(
              signer = signerInfo,
              network = network.toTonNetwork(),
              workchain = 0,
              walletId = network.v5r1WalletId,
            )
          com.example.core.model.WalletVersion.V4R2 ->
            kit.createV4R2Adapter(
              signer = signerInfo,
              network = network.toTonNetwork(),
              workchain = 0,
              walletId = AddressService.DEFAULT_LEGACY_WALLET_ID,
            )
          else -> return@withLock null
        }
      val wallet: ITONWallet = kit.addWallet(adapter)
      TonAddress(raw = wallet.address.toRawString(), network = network)
    }

  private fun Network.toTonNetwork(): TONNetwork =
    when (this) {
      Network.MAINNET -> TONNetwork.MAINNET
      Network.TESTNET -> TONNetwork.TESTNET
    }

  private companion object {
    val kitMutex = Mutex()
    @Volatile var sharedKit: ITONWalletKit? = null

    suspend fun ensureInitialized(appContext: Context): ITONWalletKit {
      sharedKit?.let { return it }
      val config =
        TONWalletKitConfiguration(
          networkConfigurations =
            setOf(
              TONWalletKitConfiguration.NetworkConfiguration(
                network = TONNetwork.MAINNET,
                apiClientConfiguration =
                  TONWalletKitConfiguration.APIClientConfiguration(
                    url = "https://toncenter.com",
                    key = "",
                  ),
                apiClientType = TONWalletKitConfiguration.APIClientType.TONCENTER,
              ),
              TONWalletKitConfiguration.NetworkConfiguration(
                network = TONNetwork.TESTNET,
                apiClientConfiguration =
                  TONWalletKitConfiguration.APIClientConfiguration(
                    url = "https://testnet.toncenter.com",
                    key = "",
                  ),
                apiClientType = TONWalletKitConfiguration.APIClientType.TONCENTER,
              ),
            ),
          walletManifest =
            TONWalletKitConfiguration.Manifest(
              name = "GramVault",
              appName = "gramvault",
              imageUrl = "https://gramvault.example/icon.png",
              aboutUrl = "https://gramvault.example",
              universalLink = "https://gramvault.example/ul",
              bridgeUrl = "https://bridge.tonapi.io/bridge",
            ),
          bridge = TONWalletKitConfiguration.Bridge(bridgeUrl = "https://bridge.tonapi.io/bridge"),
          features = listOf(TONWalletKitConfiguration.SendTransactionFeature()),
          storageType = TONWalletKitStorageType.Memory,
        )
      return ITONWalletKit.initialize(context = appContext, config = config).also {
        sharedKit = it
      }
    }
  }
}

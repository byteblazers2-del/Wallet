package com.example.core

import android.content.Context
import com.example.BuildConfig
import com.example.core.data.AddressBookRepository
import com.example.core.data.BalanceRepository
import com.example.core.data.GramVaultDatabase
import com.example.core.keystore.AuthGate
import com.example.core.keystore.SecureKeyStore
import com.example.core.network.PriceService
import com.example.core.network.ProviderRouter
import com.example.core.tx.TxManager
import com.example.core.wallet.AppSettingsStore
import com.example.core.wallet.VaultMetadataStore
import com.example.core.wallet.WalletCore
import com.example.core.wallet.WalletCoreImpl
import com.example.core.wallet.WalletKitBridge

data class WalletDependencies(
  val walletCore: WalletCore,
  val addressBookRepository: AddressBookRepository,
  val priceService: PriceService,
  val appSettingsStore: AppSettingsStore,
)

object WalletCoreFactory {
  fun create(applicationContext: Context, authGate: AuthGate): WalletCore =
    createDependencies(applicationContext, authGate).walletCore

  fun createDependencies(applicationContext: Context, authGate: AuthGate): WalletDependencies {
    val context = applicationContext.applicationContext
    val secureKeyStore = SecureKeyStore(context)
    val metadataStore = VaultMetadataStore(context)
    val database = GramVaultDatabase.getInstance(context)
    val okHttpClient = ProviderRouter.createDefaultHttpClient()
    val providerRouter = ProviderRouter.createDefault(okHttpClient = okHttpClient)
    val balanceRepository =
      BalanceRepository(
        balanceDao = database.balanceCacheDao(),
        balanceFetcher = providerRouter,
      )
    val txManager =
      TxManager(
        txDao = database.txDao(),
        chainGateway = providerRouter,
        balanceRepository = balanceRepository,
      )
    val walletKitBridge = WalletKitBridge(context, secureKeyStore)
    val walletCore =
      WalletCoreImpl(
        applicationContext = context,
        authGate = authGate,
        secureKeyStore = secureKeyStore,
        metadataStore = metadataStore,
        walletKitBridge = walletKitBridge,
        balanceRepository = balanceRepository,
        txManager = txManager,
        devSkipAuth = BuildConfig.DEBUG,
      )
    return WalletDependencies(
      walletCore = walletCore,
      addressBookRepository = AddressBookRepository(database.addressBookDao()),
      priceService = PriceService(okHttpClient = okHttpClient),
      appSettingsStore = AppSettingsStore(context, defaultAutoLock = !BuildConfig.DEBUG),
    )
  }
}

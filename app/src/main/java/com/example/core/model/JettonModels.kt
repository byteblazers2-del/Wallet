package com.example.core.model

import com.example.core.network.OnChainMessageStatus

const val ASSET_ID_USDT = "USDT"
const val USDT_DECIMALS = 6

data class AllowedJettonSpec(
  val assetId: String,
  val symbol: String,
  val name: String,
  val network: Network,
  val masterFriendly: String,
  val decimals: Int,
) {
  val masterAddress: TonAddress by lazy {
    TonAddress.parse(masterFriendly, network).getOrThrow()
  }
}

object JettonAllowList {
  // Official Tether USD (USDt) TON Mainnet Jetton Master contract
  const val MAINNET_USDT_MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"

  // Standard TON Testnet USDt Jetton Master contract for development testing
  const val TESTNET_USDT_MASTER = "kQD0GKBM8ZbryVk2aESmzfU6b9b_8era_IkvBSELujFZPsyy"

  val MAINNET_USDT =
    AllowedJettonSpec(
      assetId = ASSET_ID_USDT,
      symbol = "USDT",
      name = "Tether USD",
      network = Network.MAINNET,
      masterFriendly = MAINNET_USDT_MASTER,
      decimals = USDT_DECIMALS,
    )

  val TESTNET_USDT =
    AllowedJettonSpec(
      assetId = ASSET_ID_USDT,
      symbol = "USDT",
      name = "Tether USD",
      network = Network.TESTNET,
      masterFriendly = TESTNET_USDT_MASTER,
      decimals = USDT_DECIMALS,
    )

  fun usdtFor(network: Network): AllowedJettonSpec =
    when (network) {
      Network.MAINNET -> MAINNET_USDT
      Network.TESTNET -> TESTNET_USDT
    }

  fun allFor(network: Network): List<AllowedJettonSpec> = listOf(usdtFor(network))

  fun findVerified(
    masterAddress: TonAddress,
    reportedDecimals: Int? = null,
  ): AllowedJettonSpec? {
    val candidate =
      allFor(masterAddress.network).firstOrNull { spec ->
        spec.masterAddress.raw.equals(masterAddress.raw, ignoreCase = true)
      } ?: return null

    if (reportedDecimals != null && reportedDecimals != candidate.decimals) {
      return null
    }
    return candidate
  }
}

data class RemoteJettonBalance(
  val ownerAddress: TonAddress,
  val jettonMaster: TonAddress,
  val jettonWalletAddress: TonAddress?,
  val balance: Money,
  val providerName: String,
  val fetchedAtEpochMs: Long,
)

data class RemoteJettonTransfer(
  val txHash: String,
  val lt: String,
  val utimeEpochSec: Long,
  val direction: TxDirection,
  val jettonMasterRaw: String,
  val amount: Money,
  val counterpartyRaw: String,
  val comment: String?,
  val status: OnChainMessageStatus,
)

data class RemoteJettonHistoryPage(
  val transfers: List<RemoteJettonTransfer>,
  val providerName: String,
)

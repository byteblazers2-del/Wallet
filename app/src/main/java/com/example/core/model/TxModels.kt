package com.example.core.model

enum class TxStatus {
  DRAFT,
  SIGNED,
  BROADCASTED,
  CONFIRMED,
  FAILED,
  EXPIRED;

  val isTerminal: Boolean
    get() = this == CONFIRMED || this == FAILED || this == EXPIRED
}

enum class TxDirection {
  OUT,
  IN,
}

enum class TxAssetKind {
  GRAM,
  JETTON,
}

data class TransferPreview(
  val network: Network,
  val fromAddress: TonAddress,
  val destination: ParsedAddress,
  val destinationStatus: AccountOnChainStatus,
  val effectiveBounce: Boolean,
  val amount: Money,
  val estimatedFee: Money,
  val totalDebit: Money,
  val comment: String?,
  val seqno: Int,
  val senderNeedsStateInit: Boolean,
  val validUntilEpochSec: Long,
  val feeIsEstimateFallback: Boolean = false,
  val assetKind: TxAssetKind = TxAssetKind.GRAM,
  val jettonMasterRaw: String? = null,
  val senderJettonWallet: TonAddress? = null,
  val attachedGramNano: Money? = null,
) {
  val assetSymbol: String
    get() = if (assetKind == TxAssetKind.JETTON) ASSET_ID_USDT else ASSET_ID_GRAM
}

data class TxRecord(
  val id: String,
  val network: Network,
  val accountRaw: String,
  val messageHash: String,
  val normalizedHash: String,
  val txHash: String?,
  val seqno: Int,
  val direction: TxDirection,
  val kind: TxAssetKind,
  val amount: Money,
  val fee: Money?,
  val counterpartyRaw: String,
  val comment: String?,
  val state: TxStatus,
  val validUntilEpochSec: Long,
  val lt: String?,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val errorDetail: String? = null,
  val jettonMaster: String? = null,
) {
  val assetSymbol: String
    get() = if (kind == TxAssetKind.JETTON) ASSET_ID_USDT else ASSET_ID_GRAM
}

data class HistorySyncSummary(
  val newIncomingTxs: List<TxRecord>,
  val reconciledCount: Int,
  val providerName: String,
)

data class ChainMonitorState(
  val enabled: Boolean = true,
  val isActive: Boolean = false,
  val isSyncing: Boolean = false,
  val lastSyncEpochMs: Long? = null,
  val lastProviderName: String? = null,
  val lastError: String? = null,
  val pollIntervalSeconds: Int = 10,
)

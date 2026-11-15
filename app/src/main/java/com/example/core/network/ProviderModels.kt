package com.example.core.network

import com.example.core.model.AccountOnChainStatus
import com.example.core.model.AllowedJettonSpec
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.RemoteAccountBalance
import com.example.core.model.RemoteJettonBalance
import com.example.core.model.RemoteJettonHistoryPage
import com.example.core.model.TonAddress
import com.example.core.model.TxDirection

data class RemoteWalletInfo(
  val address: TonAddress,
  val balance: Money,
  val status: AccountOnChainStatus,
  val seqno: Int,
  val providerName: String,
  val fetchedAtEpochMs: Long,
)

data class RemoteFeeEstimate(
  val estimatedFee: Money,
  val providerName: String,
)

data class BroadcastResult(
  val messageHashHex: String,
  val providerName: String,
)

enum class OnChainMessageStatus {
  PENDING,
  CONFIRMED,
  FAILED,
}

data class RemoteTxLookup(
  val status: OnChainMessageStatus,
  val txHash: String?,
  val lt: String?,
  val fee: Money?,
  val providerName: String,
  val failureReason: String? = null,
)

data class RemoteChainTransfer(
  val txHash: String,
  val lt: String,
  val utimeEpochSec: Long,
  val direction: TxDirection,
  val amount: Money,
  val fee: Money?,
  val counterpartyRaw: String,
  val comment: String?,
  val status: OnChainMessageStatus,
  val inMsgHash: String?,
  val inMsgNormalizedHash: String?,
)

data class RemoteTxHistoryPage(
  val transfers: List<RemoteChainTransfer>,
  val providerName: String,
)

interface BalanceFetcher {
  suspend fun fetchAccountBalance(address: TonAddress): Result<RemoteAccountBalance>

  suspend fun fetchJettonBalance(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): Result<RemoteJettonBalance>
}

interface ChainGateway : BalanceFetcher {
  suspend fun fetchWalletInfo(address: TonAddress): Result<RemoteWalletInfo>

  suspend fun estimateExternalMessageFee(
    address: TonAddress,
    bocBase64: String,
  ): Result<RemoteFeeEstimate>

  suspend fun sendBoc(network: Network, bocBase64: String): Result<BroadcastResult>

  suspend fun lookupMessageStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): Result<RemoteTxLookup>

  suspend fun lookupJettonTraceStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): Result<RemoteTxLookup>

  suspend fun fetchAccountHistory(
    address: TonAddress,
    afterLt: String? = null,
    limit: Int = 25,
  ): Result<RemoteTxHistoryPage>

  suspend fun fetchJettonHistory(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    limit: Int = 25,
  ): Result<RemoteJettonHistoryPage>
}

interface TonNetworkProvider {
  val name: String

  suspend fun fetchAccountBalance(address: TonAddress): RemoteAccountBalance

  suspend fun fetchJettonBalance(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): RemoteJettonBalance

  suspend fun fetchWalletInfo(address: TonAddress): RemoteWalletInfo

  suspend fun estimateExternalMessageFee(address: TonAddress, bocBase64: String): RemoteFeeEstimate

  suspend fun sendBoc(network: Network, bocBase64: String): BroadcastResult

  suspend fun lookupMessageStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): RemoteTxLookup

  suspend fun lookupJettonTraceStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): RemoteTxLookup

  suspend fun fetchAccountHistory(
    address: TonAddress,
    afterLt: String? = null,
    limit: Int = 25,
  ): RemoteTxHistoryPage

  suspend fun fetchJettonHistory(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    limit: Int = 25,
  ): RemoteJettonHistoryPage
}

sealed class ProviderException(
  message: String,
  val providerName: String,
  val isRetryable: Boolean,
  cause: Throwable? = null,
) : Exception(message, cause)

class RateLimitedException(
  providerName: String,
  val retryAfterMs: Long? = null,
) : ProviderException("$providerName rate limited (HTTP 429)", providerName, isRetryable = true)

class ServerErrorException(
  providerName: String,
  val httpCode: Int,
) : ProviderException("$providerName HTTP error $httpCode", providerName, isRetryable = true)

class RejectedTransactionException(
  providerName: String,
  val httpCode: Int,
  detail: String,
) : ProviderException("$providerName rejected transaction ($httpCode): $detail", providerName, isRetryable = false)

class TransportException(
  providerName: String,
  cause: Throwable,
) : ProviderException("$providerName transport error: ${cause.message}", providerName, isRetryable = true, cause = cause)

class InvalidPayloadException(
  providerName: String,
  detail: String,
  cause: Throwable? = null,
) : ProviderException("$providerName invalid payload: $detail", providerName, isRetryable = true, cause = cause)

class AllProvidersFailedException(
  val failures: List<Throwable>,
) : Exception(
    failures.lastOrNull()?.message ?: "All TON network providers failed",
    failures.lastOrNull(),
  )

data class NetworkEndpoints(
  val mainnetBaseUrl: String,
  val testnetBaseUrl: String,
) {
  fun baseUrlFor(network: Network): String =
    when (network) {
      Network.MAINNET -> mainnetBaseUrl
      Network.TESTNET -> testnetBaseUrl
    }
}

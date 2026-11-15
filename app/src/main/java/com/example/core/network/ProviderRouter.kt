package com.example.core.network

import com.example.core.model.AllowedJettonSpec
import com.example.core.model.Network
import com.example.core.model.RemoteAccountBalance
import com.example.core.model.RemoteJettonBalance
import com.example.core.model.RemoteJettonHistoryPage
import com.example.core.model.TonAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

class ProviderRouter(
  private val providers: List<TonNetworkProvider>,
  private val rateLimiter: ProviderRateLimiter = ProviderRateLimiter(),
) : ChainGateway {

  init {
    require(providers.isNotEmpty()) { "ProviderRouter requires at least one provider" }
  }

  override suspend fun fetchAccountBalance(address: TonAddress): Result<RemoteAccountBalance> =
    executeWithFailover { provider -> provider.fetchAccountBalance(address) }

  override suspend fun fetchJettonBalance(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): Result<RemoteJettonBalance> =
    executeWithFailover { provider -> provider.fetchJettonBalance(ownerAddress, spec) }

  override suspend fun fetchWalletInfo(address: TonAddress): Result<RemoteWalletInfo> =
    executeWithFailover { provider -> provider.fetchWalletInfo(address) }

  override suspend fun estimateExternalMessageFee(
    address: TonAddress,
    bocBase64: String,
  ): Result<RemoteFeeEstimate> =
    executeWithFailover(openCircuitOnNonRateLimit = false) { provider ->
      provider.estimateExternalMessageFee(address, bocBase64)
    }

  override suspend fun sendBoc(network: Network, bocBase64: String): Result<BroadcastResult> =
    executeWithFailover(failoverOnRejection = true) { provider ->
      provider.sendBoc(network, bocBase64)
    }

  override suspend fun lookupMessageStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): Result<RemoteTxLookup> =
    executeWithFailover { provider ->
      provider.lookupMessageStatus(network, messageHashHex, normalizedHashHex)
    }

  override suspend fun lookupJettonTraceStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): Result<RemoteTxLookup> =
    executeWithFailover { provider ->
      provider.lookupJettonTraceStatus(network, messageHashHex, normalizedHashHex)
    }

  override suspend fun fetchAccountHistory(
    address: TonAddress,
    afterLt: String?,
    limit: Int,
  ): Result<RemoteTxHistoryPage> =
    executeWithFailover { provider ->
      provider.fetchAccountHistory(address, afterLt, limit)
    }

  override suspend fun fetchJettonHistory(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    limit: Int,
  ): Result<RemoteJettonHistoryPage> =
    executeWithFailover { provider ->
      provider.fetchJettonHistory(ownerAddress, spec, limit)
    }

  private suspend fun <T> executeWithFailover(
    failoverOnRejection: Boolean = false,
    openCircuitOnNonRateLimit: Boolean = true,
    call: suspend (TonNetworkProvider) -> T,
  ): Result<T> {
    val activeProviders = providers.filterNot { rateLimiter.isCircuitOpen(it.name) }
    val candidates =
      if (activeProviders.isNotEmpty()) {
        activeProviders
      } else {
        // Half-open fallback: probe the provider whose cooldown expires soonest.
        listOf(providers.minBy { rateLimiter.openUntilEpochMs(it.name) })
      }

    val failures = mutableListOf<Throwable>()
    for (provider in candidates) {
      try {
        rateLimiter.awaitRequestSlot(provider.name)
        val result = call(provider)
        rateLimiter.recordSuccess(provider.name)
        return Result.success(result)
      } catch (ce: CancellationException) {
        throw ce
      } catch (rejected: RejectedTransactionException) {
        if (!failoverOnRejection) {
          return Result.failure(rejected)
        }
        failures.add(rejected)
      } catch (t: Throwable) {
        failures.add(t)
        rateLimiter.recordFailureAndBackoff(
          providerName = provider.name,
          error = t,
          allowCircuitOpen = openCircuitOnNonRateLimit,
        )
      }
    }

    val rejections = failures.filterIsInstance<RejectedTransactionException>()
    if (rejections.isNotEmpty() && rejections.size == failures.size) {
      return Result.failure(rejections.first())
    }
    return Result.failure(AllProvidersFailedException(failures))
  }

  companion object {
    fun createDefaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun createDefault(okHttpClient: OkHttpClient = createDefaultHttpClient()): ProviderRouter {
      val rateLimiter =
        ProviderRateLimiter(
          config =
            ProviderRateLimitConfig(
              minIntervalMs = 1_150L,
              baseBackoffMs = 400L,
              maxBackoffMs = 8_000L,
              failureThresholdToOpenCircuit = 2,
              openCircuitBaseMs = 5_000L,
              perProviderMinIntervalMs =
                mapOf(
                  "TON Center" to 1_150L,
                  "TonAPI" to 4_250L,
                ),
            ),
        )
      val pacer: suspend (String) -> Unit = { providerName ->
        rateLimiter.awaitRequestSlot(providerName)
      }
      return ProviderRouter(
        providers =
          listOf(
            TonCenterClient(okHttpClient = okHttpClient, requestPacer = pacer),
            TonApiClient(okHttpClient = okHttpClient, requestPacer = pacer),
          ),
        rateLimiter = rateLimiter,
      )
    }
  }
}

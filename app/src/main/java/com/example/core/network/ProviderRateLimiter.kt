package com.example.core.network

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

data class ProviderRateLimitConfig(
  val minIntervalMs: Long = 1_150L,
  val baseBackoffMs: Long = 350L,
  val maxBackoffMs: Long = 8_000L,
  val failureThresholdToOpenCircuit: Int = 2,
  val openCircuitBaseMs: Long = 4_000L,
  val perProviderMinIntervalMs: Map<String, Long> = emptyMap(),
)

class ProviderRateLimiter(
  private val config: ProviderRateLimitConfig = ProviderRateLimitConfig(),
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
  private val jitterProvider: () -> Double = { Random.Default.nextDouble() },
) {
  private data class ProviderState(
    var lastRequestEpochMs: Long = 0L,
    var consecutiveFailures: Int = 0,
    var openUntilEpochMs: Long = 0L,
    var lastBackoffMs: Long = 0L,
  )

  private val states = ConcurrentHashMap<String, ProviderState>()

  @Synchronized
  private fun getOrCreateState(providerName: String): ProviderState =
    states.getOrPut(providerName) { ProviderState() }

  @Synchronized
  fun isCircuitOpen(providerName: String): Boolean {
    val state = getOrCreateState(providerName)
    return clockMs() < state.openUntilEpochMs
  }

  @Synchronized
  fun openUntilEpochMs(providerName: String): Long = getOrCreateState(providerName).openUntilEpochMs

  @Synchronized
  fun consecutiveFailures(providerName: String): Int =
    getOrCreateState(providerName).consecutiveFailures

  @Synchronized
  fun lastAppliedBackoffMs(providerName: String): Long = getOrCreateState(providerName).lastBackoffMs

  suspend fun awaitRequestSlot(providerName: String) {
    val waitMs =
      synchronized(this) {
        val now = clockMs()
        val state = getOrCreateState(providerName)
        val effectiveMinInterval = config.perProviderMinIntervalMs[providerName] ?: config.minIntervalMs
        val elapsed = now - state.lastRequestEpochMs
        val needed =
          if (state.lastRequestEpochMs > 0L && elapsed < effectiveMinInterval) {
            effectiveMinInterval - elapsed
          } else {
            0L
          }
        state.lastRequestEpochMs = now + needed
        needed
      }
    if (waitMs > 0L) {
      sleeper(waitMs)
    }
  }

  @Synchronized
  fun recordSuccess(providerName: String) {
    val state = getOrCreateState(providerName)
    state.consecutiveFailures = 0
    state.openUntilEpochMs = 0L
    state.lastBackoffMs = 0L
  }

  suspend fun recordFailureAndBackoff(
    providerName: String,
    error: Throwable,
    allowCircuitOpen: Boolean = true,
  ): Long {
    val delayMs =
      synchronized(this) {
        val now = clockMs()
        val state = getOrCreateState(providerName)
        val retryAfter = (error as? RateLimitedException)?.retryAfterMs ?: 0L
        if (!allowCircuitOpen && error !is RateLimitedException) {
          state.lastBackoffMs = config.baseBackoffMs
          return@synchronized config.baseBackoffMs
        }

        state.consecutiveFailures += 1

        val exponent = (state.consecutiveFailures - 1).coerceIn(0, 6)
        val rawExp = config.baseBackoffMs * (1L shl exponent)
        val cappedExp = min(rawExp, config.maxBackoffMs)
        val jitterFraction = 0.75 + 0.5 * jitterProvider().coerceIn(0.0, 1.0)
        val jittered = (cappedExp * jitterFraction).toLong().coerceAtLeast(1L)

        val computedDelay = maxOf(jittered, retryAfter).coerceAtMost(config.maxBackoffMs)
        state.lastBackoffMs = computedDelay

        if (error is RateLimitedException || state.consecutiveFailures >= config.failureThresholdToOpenCircuit) {
          val openDuration =
            maxOf(
                config.openCircuitBaseMs * (1L shl (state.consecutiveFailures - 1).coerceIn(0, 4)),
                retryAfter,
              )
              .coerceAtMost(config.maxBackoffMs * 6L)
          state.openUntilEpochMs = now + openDuration
        }
        computedDelay
      }
    if (delayMs > 0L) {
      sleeper(delayMs)
    }
    return delayMs
  }
}

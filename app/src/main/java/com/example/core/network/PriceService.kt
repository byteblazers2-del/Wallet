package com.example.core.network

import com.example.core.model.JettonAllowList
import com.example.core.model.Money
import com.squareup.moshi.JsonReader
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Display-only fiat price feed for GRAM (TON) and USDT.
 * Never used for signing, balance authority, or security decisions.
 */
data class FiatRates(
  val gramUsdPrice: BigDecimal? = null,
  val usdtUsdPrice: BigDecimal? = null,
  val fetchedAtEpochMs: Long? = null,
  val sourceName: String? = null,
) {
  fun formatUsdValue(gramAmount: Money? = null, usdtAmount: Money? = null): String? {
    var totalUsd = BigDecimal.ZERO
    var hasAny = false
    if (gramAmount != null && gramUsdPrice != null) {
      val gramCoins = BigDecimal(gramAmount.units).movePointLeft(gramAmount.decimals)
      totalUsd = totalUsd.add(gramCoins.multiply(gramUsdPrice))
      hasAny = true
    }
    if (usdtAmount != null) {
      val usdtRate = usdtUsdPrice ?: BigDecimal.ONE
      val usdtCoins = BigDecimal(usdtAmount.units).movePointLeft(usdtAmount.decimals)
      totalUsd = totalUsd.add(usdtCoins.multiply(usdtRate))
      hasAny = true
    }
    if (!hasAny) return null
    val scaled = totalUsd.setScale(2, RoundingMode.HALF_UP)
    return "\$${scaled.toPlainString()}"
  }
}

class PriceService(
  private val okHttpClient: OkHttpClient,
  private val tonApiRatesUrl: String =
    "https://tonapi.io/v2/rates?tokens=ton,${JettonAllowList.MAINNET_USDT_MASTER}&currencies=usd",
  private val coinGeckoRatesUrl: String =
    "https://api.coingecko.com/api/v3/simple/price?ids=the-open-network,tether&vs_currencies=usd",
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val cacheTtlMs: Long = 90_000L,
) {
  private val _rates = MutableStateFlow(FiatRates())
  val rates: StateFlow<FiatRates> = _rates.asStateFlow()

  suspend fun refreshRatesIfNeeded(force: Boolean = false): FiatRates {
    val current = _rates.value
    val now = clockMs()
    val fetchedAt = current.fetchedAtEpochMs
    if (!force && fetchedAt != null && (now - fetchedAt) < cacheTtlMs) {
      return current
    }

    val fetched =
      withContext(Dispatchers.IO) {
        fetchFromTonApi()?.let { return@withContext it }
        fetchFromCoinGecko()
      }
    if (fetched != null) {
      _rates.value = fetched
      return fetched
    }
    return current
  }

  internal fun parseTonApiRatesJson(bodyBytes: ByteArray): FiatRates? {
    if (bodyBytes.isEmpty()) return null
    return runCatching {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var gramUsd: BigDecimal? = null
      var usdtUsd: BigDecimal? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "rates" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
              reader.beginObject()
              while (reader.hasNext()) {
                val tokenKey = reader.nextName()
                val usdVal = readTonApiUsdPrice(reader)
                if (tokenKey.equals("TON", ignoreCase = true)) {
                  gramUsd = usdVal
                } else if (
                  tokenKey.equals(JettonAllowList.MAINNET_USDT_MASTER, ignoreCase = true) ||
                    tokenKey.equals(JettonAllowList.MAINNET_USDT.masterAddress.raw, ignoreCase = true)
                ) {
                  usdtUsd = usdVal
                }
              }
              reader.endObject()
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (gramUsd == null && usdtUsd == null) null
      else FiatRates(
        gramUsdPrice = gramUsd,
        usdtUsdPrice = usdtUsd ?: BigDecimal.ONE,
        fetchedAtEpochMs = clockMs(),
        sourceName = "TonAPI",
      )
    }.getOrNull()
  }

  internal fun parseCoinGeckoRatesJson(bodyBytes: ByteArray): FiatRates? {
    if (bodyBytes.isEmpty()) return null
    return runCatching {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var gramUsd: BigDecimal? = null
      var usdtUsd: BigDecimal? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "the-open-network" -> gramUsd = readCoinGeckoUsd(reader)
          "tether" -> usdtUsd = readCoinGeckoUsd(reader)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (gramUsd == null && usdtUsd == null) null
      else FiatRates(
        gramUsdPrice = gramUsd,
        usdtUsdPrice = usdtUsd ?: BigDecimal.ONE,
        fetchedAtEpochMs = clockMs(),
        sourceName = "CoinGecko",
      )
    }.getOrNull()
  }

  private fun fetchFromTonApi(): FiatRates? =
    runCatching {
      val req = Request.Builder().url(tonApiRatesUrl).get().header("Accept", "application/json").build()
      okHttpClient.newCall(req).execute().use { res ->
        if (!res.isSuccessful) return@runCatching null
        val bytes = res.body?.bytes() ?: return@runCatching null
        parseTonApiRatesJson(bytes)
      }
    }.getOrNull()

  private fun fetchFromCoinGecko(): FiatRates? =
    runCatching {
      val req = Request.Builder().url(coinGeckoRatesUrl).get().header("Accept", "application/json").build()
      okHttpClient.newCall(req).execute().use { res ->
        if (!res.isSuccessful) return@runCatching null
        val bytes = res.body?.bytes() ?: return@runCatching null
        parseCoinGeckoRatesJson(bytes)
      }
    }.getOrNull()

  private fun readTonApiUsdPrice(reader: JsonReader): BigDecimal? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var usd: BigDecimal? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "prices" -> usd = readCoinGeckoUsd(reader)
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return usd
  }

  private fun readCoinGeckoUsd(reader: JsonReader): BigDecimal? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var usd: BigDecimal? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName().lowercase()) {
        "usd" -> {
          val str =
            when (reader.peek()) {
              JsonReader.Token.NUMBER, JsonReader.Token.STRING -> reader.nextString()
              else -> {
                reader.skipValue()
                null
              }
            }
          if (!str.isNullOrBlank()) {
            usd = runCatching { BigDecimal(str.trim()).takeIf { it > BigDecimal.ZERO } }.getOrNull()
          }
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return usd
  }
}

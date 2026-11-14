package com.example.core.network

import com.example.core.model.AccountOnChainStatus
import com.example.core.model.AllowedJettonSpec
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.JettonAllowList
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.RemoteAccountBalance
import com.example.core.model.RemoteJettonBalance
import com.example.core.model.RemoteJettonHistoryPage
import com.example.core.model.RemoteJettonTransfer
import com.example.core.model.TonAddress
import com.example.core.model.TxDirection
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class TonApiClient(
  private val okHttpClient: OkHttpClient,
  private val endpoints: NetworkEndpoints =
    NetworkEndpoints(
      mainnetBaseUrl = "https://tonapi.io",
      testnetBaseUrl = "https://testnet.tonapi.io",
    ),
  private val apiKeyProvider: () -> String? = { null },
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val requestPacer: suspend (String) -> Unit = {},
) : TonNetworkProvider {

  override val name: String = "TonAPI"

  override suspend fun fetchAccountBalance(address: TonAddress): RemoteAccountBalance =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val bodyBytes =
        executeGetAllow404("$baseUrl/v2/accounts/${address.raw}")
          ?: return@withContext RemoteAccountBalance(
            address = address,
            balance = Money.zero(),
            status = AccountOnChainStatus.NONEXIST,
            providerName = name,
            fetchedAtEpochMs = clockMs(),
          )
      parseAccountJson(bodyBytes, requestedAddress = address, fetchedAt = clockMs())
    }

  override suspend fun fetchJettonBalance(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): RemoteJettonBalance =
    withContext(Dispatchers.IO) {
      require(ownerAddress.network == spec.network) {
        "Jetton balance query network mismatch: ${ownerAddress.network} vs ${spec.network}"
      }
      val verifiedSpec =
        JettonAllowList.findVerified(spec.masterAddress)
          ?: throw InvalidPayloadException(name, "Unverified Jetton master rejected")
      val baseUrl = endpoints.baseUrlFor(ownerAddress.network).trimEnd('/')
      val bodyBytes =
        executeGetAllow404("$baseUrl/v2/accounts/${ownerAddress.raw}/jettons")
          ?: return@withContext RemoteJettonBalance(
            ownerAddress = ownerAddress,
            jettonMaster = verifiedSpec.masterAddress,
            jettonWalletAddress = null,
            balance = Money.zero(verifiedSpec.decimals),
            providerName = name,
            fetchedAtEpochMs = clockMs(),
          )
      parseTonApiJettonsJson(bodyBytes, ownerAddress, verifiedSpec, fetchedAt = clockMs())
    }

  override suspend fun fetchWalletInfo(address: TonAddress): RemoteWalletInfo =
    withContext(Dispatchers.IO) {
      val account = fetchAccountBalance(address)
      if (account.status == AccountOnChainStatus.NONEXIST ||
        account.status == AccountOnChainStatus.UNINIT
      ) {
        return@withContext RemoteWalletInfo(
          address = account.address,
          balance = account.balance,
          status = account.status,
          seqno = 0,
          providerName = name,
          fetchedAtEpochMs = account.fetchedAtEpochMs,
        )
      }
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val seqno = fetchSeqnoFromTonApi(baseUrl, address.raw)
      RemoteWalletInfo(
        address = account.address,
        balance = account.balance,
        status = account.status,
        seqno = seqno,
        providerName = name,
        fetchedAtEpochMs = account.fetchedAtEpochMs,
      )
    }

  override suspend fun estimateExternalMessageFee(
    address: TonAddress,
    bocBase64: String,
  ): RemoteFeeEstimate =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val payload = buildJsonObject { w -> w.name("boc").value(bocBase64) }
      val responseBytes =
        executePostJson("$baseUrl/v2/traces/emulate?ignore_signature_check=true", payload)
      val totalFees = parseEmulatedFeesJson(responseBytes)
      RemoteFeeEstimate(
        estimatedFee = Money(totalFees, GRAM_DECIMALS),
        providerName = name,
      )
    }

  override suspend fun sendBoc(network: Network, bocBase64: String): BroadcastResult =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(network).trimEnd('/')
      val payload = buildJsonObject { w -> w.name("boc").value(bocBase64) }
      executePostJson("$baseUrl/v2/blockchain/message", payload, treat4xxAsRejection = true)
      BroadcastResult(messageHashHex = "", providerName = name)
    }

  override suspend fun lookupMessageStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): RemoteTxLookup =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(network).trimEnd('/')
      val firstBytes =
        executeGetAllow404("$baseUrl/v2/blockchain/messages/$normalizedHashHex/transaction")
      val txBytes =
        if (firstBytes == null && messageHashHex.isNotBlank() && messageHashHex != normalizedHashHex) {
          requestPacer(name)
          executeGetAllow404("$baseUrl/v2/blockchain/messages/$messageHashHex/transaction")
        } else {
          firstBytes
        }
      if (txBytes == null) {
        return@withContext RemoteTxLookup(
          status = OnChainMessageStatus.PENDING,
          txHash = null,
          lt = null,
          fee = null,
          providerName = name,
        )
      }
      parseTransactionLookupJson(txBytes)
    }

  override suspend fun fetchAccountHistory(
    address: TonAddress,
    afterLt: String?,
    limit: Int,
  ): RemoteTxHistoryPage =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val clampedLimit = limit.coerceIn(1, 50)
      val afterParam =
        afterLt?.trim()?.takeIf { it.isNotEmpty() }?.let { "&after_lt=$it" }.orEmpty()
      val bytes =
        executeGetAllow404(
          "$baseUrl/v2/blockchain/accounts/${address.raw}/transactions?limit=$clampedLimit$afterParam"
        )
          ?: return@withContext RemoteTxHistoryPage(emptyList(), name)
      parseAccountHistoryJson(bytes, address)
    }

  override suspend fun fetchJettonHistory(
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    limit: Int,
  ): RemoteJettonHistoryPage =
    withContext(Dispatchers.IO) {
      require(ownerAddress.network == spec.network) {
        "Jetton history query network mismatch: ${ownerAddress.network} vs ${spec.network}"
      }
      val verifiedSpec =
        JettonAllowList.findVerified(spec.masterAddress)
          ?: throw InvalidPayloadException(name, "Unverified Jetton master rejected")
      val baseUrl = endpoints.baseUrlFor(ownerAddress.network).trimEnd('/')
      val clampedLimit = limit.coerceIn(1, 50)
      val bytes =
        executeGetAllow404(
          "$baseUrl/v2/accounts/${ownerAddress.raw}/jettons/history?limit=$clampedLimit"
        )
          ?: return@withContext RemoteJettonHistoryPage(emptyList(), name)
      parseTonApiJettonHistoryJson(bytes, ownerAddress, verifiedSpec)
    }

  private suspend fun fetchSeqnoFromTonApi(baseUrl: String, rawAddress: String): Int {
    requestPacer(name)
    val walletSeqnoBytes = executeGetAllow404("$baseUrl/v2/wallet/$rawAddress/seqno")
    if (walletSeqnoBytes != null) {
      val parsed = parseWalletSeqnoJson(walletSeqnoBytes)
      if (parsed != null) return parsed
    }
    requestPacer(name)
    val methodBytes =
      executeGetAllow404("$baseUrl/v2/blockchain/accounts/$rawAddress/methods/seqno")
        ?: return 0
    return parseMethodSeqnoJson(methodBytes)
  }

  private fun executeGet(url: String): ByteArray {
    val requestBuilder = Request.Builder().url(url).get().header("Accept", "application/json")
    applyAuthHeader(requestBuilder)
    val response =
      try {
        okHttpClient.newCall(requestBuilder.build()).execute()
      } catch (e: IOException) {
        throw TransportException(name, e)
      }
    return response.use { res ->
      handleErrorStatus(res.code, res.header("Retry-After"), null, treat4xxAsRejection = false)
      readBodyBytes(res)
    }
  }

  private fun executeGetAllow404(url: String): ByteArray? {
    val requestBuilder = Request.Builder().url(url).get().header("Accept", "application/json")
    applyAuthHeader(requestBuilder)
    val response =
      try {
        okHttpClient.newCall(requestBuilder.build()).execute()
      } catch (e: IOException) {
        throw TransportException(name, e)
      }
    return response.use { res ->
      if (res.code == 404) return@use null
      handleErrorStatus(res.code, res.header("Retry-After"), null, treat4xxAsRejection = false)
      readBodyBytes(res)
    }
  }

  private fun executePostJson(
    url: String,
    jsonBody: String,
    treat4xxAsRejection: Boolean = false,
  ): ByteArray {
    val requestBuilder =
      Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        .header("Accept", "application/json")
    applyAuthHeader(requestBuilder)
    val response =
      try {
        okHttpClient.newCall(requestBuilder.build()).execute()
      } catch (e: IOException) {
        throw TransportException(name, e)
      }
    return response.use { res ->
      if (!res.isSuccessful) {
        val errBody = runCatching { res.body?.string() }.getOrNull()
        handleErrorStatus(res.code, res.header("Retry-After"), errBody, treat4xxAsRejection)
      }
      readBodyBytesAllowEmpty(res)
    }
  }

  private fun applyAuthHeader(builder: Request.Builder) {
    apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
      builder.header("Authorization", "Bearer $key")
    }
  }

  private fun handleErrorStatus(
    code: Int,
    retryAfterHeader: String?,
    errorBody: String?,
    treat4xxAsRejection: Boolean,
  ) {
    when (code) {
      in 200..299 -> return
      429 -> throw RateLimitedException(name, parseRetryAfterMs(retryAfterHeader))
      in 500..599 -> throw ServerErrorException(name, code)
      else -> {
        if (treat4xxAsRejection && (code == 400 || code == 422)) {
          val detail = extractErrorMessage(errorBody) ?: "HTTP $code"
          throw RejectedTransactionException(name, code, detail)
        }
        throw ServerErrorException(name, code)
      }
    }
  }

  private fun readBodyBytes(res: okhttp3.Response): ByteArray =
    try {
      res.body?.bytes() ?: throw InvalidPayloadException(name, "Empty response body")
    } catch (e: IOException) {
      throw TransportException(name, e)
    }

  private fun readBodyBytesAllowEmpty(res: okhttp3.Response): ByteArray =
    try {
      res.body?.bytes() ?: ByteArray(0)
    } catch (e: IOException) {
      throw TransportException(name, e)
    }

  private fun extractErrorMessage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parsed =
      runCatching {
        val reader = JsonReader.of(Buffer().writeUtf8(raw))
        var errMsg: String? = null
        if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
          reader.beginObject()
          while (reader.hasNext()) {
            when (reader.nextName()) {
              "error" -> errMsg = readScalarAsString(reader)
              else -> reader.skipValue()
            }
          }
          reader.endObject()
        }
        errMsg?.takeIf { it.isNotBlank() }
      }.getOrNull()
    return parsed ?: raw.take(160)
  }

  private fun parseAccountJson(
    bodyBytes: ByteArray,
    requestedAddress: TonAddress,
    fetchedAt: Long,
  ): RemoteAccountBalance {
    if (bodyBytes.isEmpty()) {
      throw InvalidPayloadException(name, "Empty JSON response")
    }
    var rawAddress: String? = null
    var rawBalance: String? = null
    var rawStatus: String? = null

    try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "address" -> rawAddress = readScalarAsString(reader)
          "balance" -> rawBalance = readScalarAsString(reader)
          "status" -> rawStatus = readScalarAsString(reader)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
    } catch (e: ProviderException) {
      throw e
    } catch (e: IOException) {
      throw InvalidPayloadException(name, "Malformed JSON", e)
    } catch (e: JsonDataException) {
      throw InvalidPayloadException(name, "Unexpected JSON token", e)
    }

    val returnedAddrText =
      rawAddress?.takeIf { it.isNotBlank() }
        ?: throw InvalidPayloadException(name, "Missing account address")
    val parsedAddress =
      TonAddress.parse(returnedAddrText, requestedAddress.network).getOrElse { err ->
        throw InvalidPayloadException(name, "Address network/format mismatch", err)
      }
    if (parsedAddress.raw != requestedAddress.raw) {
      throw InvalidPayloadException(name, "Response address does not match request")
    }

    val balanceText =
      rawBalance?.takeIf { it.isNotBlank() }
        ?: throw InvalidPayloadException(name, "Missing balance field")
    val money =
      try {
        Money.parseUnits(balanceText, GRAM_DECIMALS)
      } catch (e: IllegalArgumentException) {
        throw InvalidPayloadException(name, "Invalid balance units: $balanceText", e)
      }

    return RemoteAccountBalance(
      address = requestedAddress,
      balance = money,
      status = AccountOnChainStatus.fromWire(rawStatus),
      providerName = name,
      fetchedAtEpochMs = fetchedAt,
    )
  }

  private fun parseTonApiJettonsJson(
    bodyBytes: ByteArray,
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    fetchedAt: Long,
  ): RemoteJettonBalance {
    if (bodyBytes.isEmpty()) {
      return RemoteJettonBalance(
        ownerAddress = ownerAddress,
        jettonMaster = spec.masterAddress,
        jettonWalletAddress = null,
        balance = Money.zero(spec.decimals),
        providerName = name,
        fetchedAtEpochMs = fetchedAt,
      )
    }
    var matchedWalletAddr: TonAddress? = null
    var matchedBalance: Money? = null

    try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "balances" -> {
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
              reader.skipValue()
            } else {
              reader.beginArray()
              while (reader.hasNext()) {
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                  reader.skipValue()
                  continue
                }
                var balanceStr: String? = null
                var walletRaw: String? = null
                var masterRaw: String? = null
                var decimals: Int? = null
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "balance" -> balanceStr = readScalarAsStringOrSkip(reader)
                    "wallet_address" -> walletRaw = readTonApiAccountAddress(reader, ownerAddress.network)
                    "jetton" -> {
                      if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                          when (reader.nextName()) {
                            "address" -> masterRaw = readScalarAsStringOrSkip(reader)
                            "decimals" -> decimals = readScalarAsStringOrSkip(reader)?.trim()?.toIntOrNull()
                            else -> reader.skipValue()
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

                val parsedMaster =
                  masterRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() }
                if (parsedMaster != null && parsedMaster.raw.equals(spec.masterAddress.raw, ignoreCase = true)) {
                  if (decimals != null && decimals != spec.decimals) {
                    throw InvalidPayloadException(
                      name,
                      "Jetton decimals mismatch: expected ${spec.decimals}, got $decimals",
                    )
                  }
                  matchedWalletAddr =
                    walletRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() }
                  val balText = balanceStr?.trim().orEmpty()
                  matchedBalance =
                    if (balText.isEmpty()) Money.zero(spec.decimals)
                    else runCatching { Money.parseUnits(balText, spec.decimals) }
                      .getOrElse { throw InvalidPayloadException(name, "Invalid Jetton balance: $balText", it) }
                }
              }
              reader.endArray()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed TonAPI jettons JSON", e)
    }

    return RemoteJettonBalance(
      ownerAddress = ownerAddress,
      jettonMaster = spec.masterAddress,
      jettonWalletAddress = matchedWalletAddr,
      balance = matchedBalance ?: Money.zero(spec.decimals),
      providerName = name,
      fetchedAtEpochMs = fetchedAt,
    )
  }

  private fun parseTonApiJettonHistoryJson(
    bodyBytes: ByteArray,
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): RemoteJettonHistoryPage {
    if (bodyBytes.isEmpty()) return RemoteJettonHistoryPage(emptyList(), name)
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      val list = mutableListOf<RemoteJettonTransfer>()
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "events" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              while (reader.hasNext()) {
                parseTonApiJettonEvent(reader, ownerAddress, spec, list)
              }
              reader.endArray()
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      RemoteJettonHistoryPage(transfers = list, providerName = name)
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed TonAPI jetton history JSON", e)
    }
  }

  private fun parseTonApiJettonEvent(
    reader: JsonReader,
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
    outList: MutableList<RemoteJettonTransfer>,
  ) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    var eventId = ""
    var lt = ""
    var timestamp = 0L
    var isScamEvent = false
    var inProgress = false
    val pendingActions = mutableListOf<RemoteJettonTransfer>()

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "event_id" -> eventId = TonCommentDecoder.normalizeHashToHex(readScalarAsStringOrSkip(reader)).orEmpty()
        "lt" -> lt = readScalarAsStringOrSkip(reader)?.trim().orEmpty()
        "timestamp" -> timestamp = readScalarAsStringOrSkip(reader)?.trim()?.toLongOrNull() ?: 0L
        "is_scam" -> isScamEvent = readBooleanOrNull(reader) ?: false
        "in_progress" -> inProgress = readBooleanOrNull(reader) ?: false
        "actions" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
              parseTonApiJettonAction(reader, ownerAddress, spec)?.let { pendingActions.add(it) }
            }
            reader.endArray()
          } else {
            reader.skipValue()
          }
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    if (isScamEvent || inProgress || eventId.isEmpty()) return
    val effectiveLt = lt.ifEmpty { timestamp.toString() }
    for (act in pendingActions) {
      outList.add(act.copy(txHash = eventId, lt = effectiveLt, utimeEpochSec = timestamp))
    }
  }

  private fun parseTonApiJettonAction(
    reader: JsonReader,
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): RemoteJettonTransfer? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var actionType: String? = null
    var statusStr: String? = null
    var senderRaw: String? = null
    var recipientRaw: String? = null
    var amountStr: String? = null
    var comment: String? = null
    var masterRaw: String? = null
    var decimals: Int? = null

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "type" -> actionType = readScalarAsStringOrSkip(reader)
        "status" -> statusStr = readScalarAsStringOrSkip(reader)
        "JettonTransfer",
        "jetton_transfer" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "sender" -> senderRaw = readTonApiAccountAddress(reader, ownerAddress.network)
                "recipient" -> recipientRaw = readTonApiAccountAddress(reader, ownerAddress.network)
                "amount" -> amountStr = readScalarAsStringOrSkip(reader)?.trim()
                "comment" -> comment = readScalarAsStringOrSkip(reader)?.trim()?.takeIf { it.isNotEmpty() }
                "jetton" -> {
                  if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                      when (reader.nextName()) {
                        "address" -> masterRaw = readScalarAsStringOrSkip(reader)
                        "decimals" -> decimals = readScalarAsStringOrSkip(reader)?.trim()?.toIntOrNull()
                        else -> reader.skipValue()
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
          } else {
            reader.skipValue()
          }
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    if (!actionType.equals("JettonTransfer", ignoreCase = true)) return null
    val parsedMaster =
      masterRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() } ?: return null
    if (!parsedMaster.raw.equals(spec.masterAddress.raw, ignoreCase = true)) return null
    if (decimals != null && decimals != spec.decimals) {
      throw InvalidPayloadException(name, "Jetton decimals mismatch: expected ${spec.decimals}, got $decimals")
    }
    val amount = amountStr?.let { runCatching { Money.parseUnits(it, spec.decimals) }.getOrNull() } ?: return null
    if (amount.units <= BigInteger.ZERO) return null

    val isIncoming =
      recipientRaw.equals(ownerAddress.raw, ignoreCase = true) &&
        !senderRaw.equals(ownerAddress.raw, ignoreCase = true)
    val counterparty = (if (isIncoming) senderRaw else recipientRaw) ?: return null
    val msgStatus =
      if (statusStr.equals("failed", ignoreCase = true)) OnChainMessageStatus.FAILED
      else OnChainMessageStatus.CONFIRMED

    return RemoteJettonTransfer(
      txHash = "",
      lt = "",
      utimeEpochSec = 0L,
      direction = if (isIncoming) TxDirection.IN else TxDirection.OUT,
      jettonMasterRaw = spec.masterAddress.raw,
      amount = amount,
      counterpartyRaw = counterparty,
      comment = comment,
      status = msgStatus,
    )
  }

  private fun parseWalletSeqnoJson(bodyBytes: ByteArray): Int? {
    if (bodyBytes.isEmpty()) return null
    return runCatching {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var seqno: Int? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "seqno" -> seqno = readScalarAsString(reader)?.toIntOrNull()?.coerceAtLeast(0)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      seqno
    }.getOrNull()
  }

  private fun parseMethodSeqnoJson(bodyBytes: ByteArray): Int {
    if (bodyBytes.isEmpty()) return 0
    return runCatching {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var exitCode = -1
      var decodedSeqno: Int? = null
      var stackSeqno: Int? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "exit_code" -> exitCode = readScalarAsString(reader)?.toIntOrNull() ?: -1
          "decoded" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
              reader.beginObject()
              while (reader.hasNext()) {
                when (reader.nextName()) {
                  "seqno" -> decodedSeqno = readScalarAsString(reader)?.toIntOrNull()?.coerceAtLeast(0)
                  else -> reader.skipValue()
                }
              }
              reader.endObject()
            } else {
              reader.skipValue()
            }
          }
          "stack" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              if (reader.hasNext() && reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "num" -> stackSeqno = parseStackInt(readScalarAsString(reader).orEmpty())
                    else -> reader.skipValue()
                  }
                }
                reader.endObject()
              }
              while (reader.hasNext()) reader.skipValue()
              reader.endArray()
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (exitCode != 0) 0 else (decodedSeqno ?: stackSeqno ?: 0)
    }.getOrDefault(0)
  }

  private fun parseEmulatedFeesJson(bodyBytes: ByteArray): BigInteger {
    if (bodyBytes.isEmpty()) throw InvalidPayloadException(name, "Empty emulation response")
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      var total = BigInteger.ZERO
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "trace" -> total = total.add(sumTraceFees(reader))
          "transaction" -> total = total.add(readTransactionFees(reader))
          "children" -> total = total.add(sumTraceChildrenFees(reader))
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      total.coerceAtLeast(BigInteger.ZERO)
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed emulation JSON", e)
    }
  }

  private fun sumTraceFees(reader: JsonReader): BigInteger {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return BigInteger.ZERO
    }
    var total = BigInteger.ZERO
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "transaction" -> total = total.add(readTransactionFees(reader))
        "children" -> total = total.add(sumTraceChildrenFees(reader))
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return total
  }

  private fun sumTraceChildrenFees(reader: JsonReader): BigInteger {
    if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
      reader.skipValue()
      return BigInteger.ZERO
    }
    var total = BigInteger.ZERO
    reader.beginArray()
    while (reader.hasNext()) {
      total = total.add(sumTraceFees(reader))
    }
    reader.endArray()
    return total
  }

  private fun readTransactionFees(reader: JsonReader): BigInteger {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return BigInteger.ZERO
    }
    var fee = BigInteger.ZERO
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "total_fees" -> {
          val str = readScalarAsString(reader)?.trim()
          if (!str.isNullOrEmpty()) fee = BigInteger(str)
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return fee
  }

  private fun parseTransactionLookupJson(bodyBytes: ByteArray): RemoteTxLookup {
    if (bodyBytes.isEmpty()) {
      throw InvalidPayloadException(name, "Empty transaction response")
    }
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      var hash: String? = null
      var lt: String? = null
      var totalFees: Money? = null
      var aborted = false
      var successField: Boolean? = null

      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "hash" -> hash = readScalarAsString(reader)?.takeIf { it.isNotBlank() }
          "lt" -> lt = readScalarAsString(reader)?.takeIf { it.isNotBlank() }
          "total_fees" ->
            totalFees =
              readScalarAsString(reader)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Money.parseUnits(it, GRAM_DECIMALS) }
          "aborted" -> aborted = readBooleanOrNull(reader) ?: false
          "success" -> successField = readBooleanOrNull(reader)
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      val success = successField ?: !aborted
      val status =
        if (!aborted && success) OnChainMessageStatus.CONFIRMED else OnChainMessageStatus.FAILED
      RemoteTxLookup(
        status = status,
        txHash = hash,
        lt = lt,
        fee = totalFees,
        providerName = name,
        failureReason = if (status == OnChainMessageStatus.FAILED) "Transaction aborted on-chain" else null,
      )
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed transaction JSON", e)
    }
  }

  private fun parseAccountHistoryJson(
    bodyBytes: ByteArray,
    accountAddress: TonAddress,
  ): RemoteTxHistoryPage {
    if (bodyBytes.isEmpty()) return RemoteTxHistoryPage(emptyList(), name)
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      val transfers = mutableListOf<RemoteChainTransfer>()
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "transactions" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              while (reader.hasNext()) {
                parseTonApiHistoryTransaction(reader, accountAddress, transfers)
              }
              reader.endArray()
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      RemoteTxHistoryPage(transfers = transfers, providerName = name)
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed account history JSON", e)
    }
  }

  private data class TonApiParsedMsg(
    val hash: String? = null,
    val hashNorm: String? = null,
    val msgType: String? = null,
    val sourceRaw: String? = null,
    val destRaw: String? = null,
    val valueNano: BigInteger = BigInteger.ZERO,
    val bounced: Boolean = false,
    val bounce: Boolean = false,
    val comment: String? = null,
  )

  private fun parseTonApiHistoryTransaction(
    reader: JsonReader,
    accountAddress: TonAddress,
    outList: MutableList<RemoteChainTransfer>,
  ) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    var txHash = ""
    var lt = ""
    var utime = 0L
    var totalFees: Money? = null
    var aborted = false
    var successField: Boolean? = null
    var creditNano: BigInteger? = null
    var computeSkippedNoState = false
    var bouncePhaseExecuted = false
    var inMsg: TonApiParsedMsg? = null
    val outMsgs = mutableListOf<TonApiParsedMsg>()

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> txHash = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader)).orEmpty()
        "lt" -> lt = readScalarAsString(reader)?.trim().orEmpty()
        "utime" -> utime = readScalarAsString(reader)?.trim()?.toLongOrNull() ?: 0L
        "total_fees" ->
          totalFees =
            readScalarAsString(reader)?.trim()?.takeIf { it.isNotEmpty() }
              ?.let { runCatching { Money.parseUnits(it, GRAM_DECIMALS) }.getOrNull() }
        "aborted" -> aborted = readBooleanOrNull(reader) ?: false
        "success" -> successField = readBooleanOrNull(reader)
        "credit_phase" -> creditNano = readTonApiCreditPhaseNano(reader)
        "compute_phase" -> computeSkippedNoState = readTonApiComputeSkippedNoState(reader)
        "bounce_phase" -> {
          val bp = readScalarAsStringOrSkip(reader)?.lowercase().orEmpty()
          if (bp.contains("ok") || bp.contains("executed")) {
            bouncePhaseExecuted = true
          }
        }
        "in_msg" -> inMsg = parseTonApiMessageObject(reader, accountAddress.network)
        "out_msgs" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
              parseTonApiMessageObject(reader, accountAddress.network)?.let { outMsgs.add(it) }
            }
            reader.endArray()
          } else {
            reader.skipValue()
          }
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    if (txHash.isEmpty() || lt.isEmpty()) return
    val success = successField ?: !aborted
    val txStatus =
      if (!aborted && success) OnChainMessageStatus.CONFIRMED else OnChainMessageStatus.FAILED
    val bounceExecuted = bouncePhaseExecuted || outMsgs.any { it.bounced }

    val incoming = inMsg
    if (
      incoming != null &&
        incoming.sourceRaw != null &&
        incoming.msgType != "ext_in_msg" &&
        (incoming.valueNano > BigInteger.ZERO || !incoming.comment.isNullOrBlank())
    ) {
      val incomingStatus =
        when {
          bounceExecuted -> OnChainMessageStatus.FAILED
          creditNano != null && creditNano > BigInteger.ZERO -> OnChainMessageStatus.CONFIRMED
          (computeSkippedNoState || aborted) && !incoming.bounce -> OnChainMessageStatus.CONFIRMED
          incoming.bounced -> OnChainMessageStatus.CONFIRMED
          else -> txStatus
        }
      outList.add(
        RemoteChainTransfer(
          txHash = txHash,
          lt = lt,
          utimeEpochSec = utime,
          direction = TxDirection.IN,
          amount = Money(incoming.valueNano, GRAM_DECIMALS),
          fee = totalFees,
          counterpartyRaw = incoming.sourceRaw,
          comment = incoming.comment,
          status = incomingStatus,
          inMsgHash = incoming.hash,
          inMsgNormalizedHash = incoming.hashNorm,
        )
      )
    }

    var emittedOut = false
    for (outMsg in outMsgs) {
      val dest = outMsg.destRaw ?: continue
      if (outMsg.valueNano <= BigInteger.ZERO && outMsg.comment.isNullOrBlank()) continue
      emittedOut = true
      outList.add(
        RemoteChainTransfer(
          txHash = txHash,
          lt = lt,
          utimeEpochSec = utime,
          direction = TxDirection.OUT,
          amount = Money(outMsg.valueNano, GRAM_DECIMALS),
          fee = totalFees,
          counterpartyRaw = dest,
          comment = outMsg.comment,
          status = txStatus,
          inMsgHash = incoming?.hash,
          inMsgNormalizedHash = incoming?.hashNorm,
        )
      )
    }

    if (
      !emittedOut &&
        incoming?.msgType == "ext_in_msg" &&
        incoming.sourceRaw == null &&
        txStatus == OnChainMessageStatus.FAILED
    ) {
      outList.add(
        RemoteChainTransfer(
          txHash = txHash,
          lt = lt,
          utimeEpochSec = utime,
          direction = TxDirection.OUT,
          amount = Money.zero(),
          fee = totalFees,
          counterpartyRaw = accountAddress.raw,
          comment = null,
          status = OnChainMessageStatus.FAILED,
          inMsgHash = incoming.hash,
          inMsgNormalizedHash = incoming.hashNorm,
        )
      )
    }
  }

  private fun readTonApiCreditPhaseNano(reader: JsonReader): BigInteger? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var credit: BigInteger? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "credit" -> {
          val str = readScalarAsStringOrSkip(reader)?.trim()
          if (!str.isNullOrEmpty()) credit = runCatching { BigInteger(str) }.getOrNull()
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return credit
  }

  private fun readTonApiComputeSkippedNoState(reader: JsonReader): Boolean {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return false
    }
    var skipped = false
    var skipReason: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "skipped" -> skipped = readBooleanOrNull(reader) ?: false
        "skip_reason" -> skipReason = readScalarAsStringOrSkip(reader)?.lowercase()
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return skipped && (skipReason == null || skipReason.contains("no_state"))
  }

  private fun parseTonApiMessageObject(reader: JsonReader, network: Network): TonApiParsedMsg? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var hash: String? = null
    var hashNorm: String? = null
    var msgType: String? = null
    var sourceRaw: String? = null
    var destRaw: String? = null
    var valueNano = BigInteger.ZERO
    var bounced = false
    var bounce = false
    var decodedComment: String? = null
    var rawBody: String? = null

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> hash = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader))
        "hash_norm" -> hashNorm = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader))
        "msg_type" -> msgType = readScalarAsString(reader)
        "value" -> {
          val str = readScalarAsString(reader)?.trim()
          if (!str.isNullOrEmpty()) {
            valueNano = runCatching { BigInteger(str) }.getOrDefault(BigInteger.ZERO)
          }
        }
        "bounced" -> bounced = readBooleanOrNull(reader) ?: false
        "bounce" -> bounce = readBooleanOrNull(reader) ?: false
        "source" -> sourceRaw = readTonApiAccountAddress(reader, network)
        "destination" -> destRaw = readTonApiAccountAddress(reader, network)
        "decoded_body" -> decodedComment = readTonApiDecodedComment(reader)
        "raw_body" -> rawBody = readScalarAsString(reader)
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    val comment = decodedComment ?: TonCommentDecoder.decodeTextCommentFromBoc(rawBody)
    return TonApiParsedMsg(
      hash = hash,
      hashNorm = hashNorm,
      msgType = msgType,
      sourceRaw = sourceRaw,
      destRaw = destRaw,
      valueNano = valueNano,
      bounced = bounced,
      bounce = bounce,
      comment = comment,
    )
  }

  private fun readTonApiAccountAddress(reader: JsonReader, network: Network): String? {
    return when (reader.peek()) {
      JsonReader.Token.BEGIN_OBJECT -> {
        var addr: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "address" -> {
              val raw = readScalarAsString(reader)
              if (!raw.isNullOrBlank()) {
                addr = TonAddress.parse(raw, network).getOrNull()?.raw
              }
            }
            else -> reader.skipValue()
          }
        }
        reader.endObject()
        addr
      }
      JsonReader.Token.STRING -> {
        val raw = reader.nextString()
        TonAddress.parse(raw, network).getOrNull()?.raw
      }
      else -> {
        reader.skipValue()
        null
      }
    }
  }

  private fun readTonApiDecodedComment(reader: JsonReader): String? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var text: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "text",
        "comment" -> text = readScalarAsStringOrSkip(reader)?.trim()?.takeIf { it.isNotEmpty() }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return text
  }

  private fun readScalarAsStringOrSkip(reader: JsonReader): String? {
    return when (reader.peek()) {
      JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
      JsonReader.Token.NULL -> {
        reader.nextNull<Unit>()
        null
      }
      else -> {
        reader.skipValue()
        null
      }
    }
  }

  private fun parseStackInt(raw: String): Int? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    return if (clean.startsWith("0x", ignoreCase = true)) {
      clean.substring(2).toIntOrNull(16)
    } else {
      clean.toIntOrNull()
    }
  }

  private fun readScalarAsString(reader: JsonReader): String? {
    return when (reader.peek()) {
      JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
      JsonReader.Token.NULL -> {
        reader.nextNull<Unit>()
        null
      }
      else -> throw InvalidPayloadException(name, "Expected string or number token")
    }
  }

  private fun readBooleanOrNull(reader: JsonReader): Boolean? {
    return when (reader.peek()) {
      JsonReader.Token.BOOLEAN -> reader.nextBoolean()
      JsonReader.Token.NULL -> {
        reader.nextNull<Unit>()
        null
      }
      else -> {
        reader.skipValue()
        null
      }
    }
  }

  private fun buildJsonObject(block: (JsonWriter) -> Unit): String {
    val buffer = Buffer()
    val writer = JsonWriter.of(buffer)
    writer.beginObject()
    block(writer)
    writer.endObject()
    writer.flush()
    return buffer.readUtf8()
  }

  private fun parseRetryAfterMs(headerValue: String?): Long? {
    val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
    return if (seconds > 0L) seconds * 1_000L else null
  }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

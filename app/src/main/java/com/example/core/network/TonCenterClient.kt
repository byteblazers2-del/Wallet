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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class TonCenterClient(
  private val okHttpClient: OkHttpClient,
  private val endpoints: NetworkEndpoints =
    NetworkEndpoints(
      mainnetBaseUrl = "https://toncenter.com",
      testnetBaseUrl = "https://testnet.toncenter.com",
    ),
  private val apiKeyProvider: () -> String? = { null },
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val requestPacer: suspend (String) -> Unit = {},
) : TonNetworkProvider {

  override val name: String = "TON Center"

  override suspend fun fetchAccountBalance(address: TonAddress): RemoteAccountBalance =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val encodedAddr = URLEncoder.encode(address.raw, StandardCharsets.UTF_8.name())
      val bodyBytes =
        executeGetAllowCodes(
          url = "$baseUrl/api/v3/accountStates?address=$encodedAddr&include_boc=false",
          allowedNullCodes = setOf(404),
        )
          ?: return@withContext RemoteAccountBalance(
            address = address,
            balance = Money.zero(),
            status = AccountOnChainStatus.NONEXIST,
            providerName = name,
            fetchedAtEpochMs = clockMs(),
          )
      parseAccountStatesJson(bodyBytes, requestedAddress = address, fetchedAt = clockMs())
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
      val encodedOwner = URLEncoder.encode(ownerAddress.raw, StandardCharsets.UTF_8.name())
      val encodedMaster = URLEncoder.encode(verifiedSpec.masterAddress.raw, StandardCharsets.UTF_8.name())
      val bodyBytes =
        executeGetAllowCodes(
          url = "$baseUrl/api/v3/jetton/wallets?owner_address=$encodedOwner&jetton_address=$encodedMaster&limit=10",
          allowedNullCodes = setOf(404),
        )
          ?: return@withContext RemoteJettonBalance(
            ownerAddress = ownerAddress,
            jettonMaster = verifiedSpec.masterAddress,
            jettonWalletAddress = null,
            balance = Money.zero(verifiedSpec.decimals),
            providerName = name,
            fetchedAtEpochMs = clockMs(),
          )
      parseJettonWalletsJson(bodyBytes, ownerAddress, verifiedSpec, fetchedAt = clockMs())
    }

  override suspend fun fetchWalletInfo(address: TonAddress): RemoteWalletInfo =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val encodedAddr = URLEncoder.encode(address.raw, StandardCharsets.UTF_8.name())
      val walletInfoBytes =
        executeGetAllowCodes(
          url = "$baseUrl/api/v3/walletInformation?address=$encodedAddr",
          allowedNullCodes = setOf(404, 409),
        )
      if (walletInfoBytes != null) {
        return@withContext parseWalletInformationJson(
          bodyBytes = walletInfoBytes,
          requestedAddress = address,
          fetchedAt = clockMs(),
        )
      }

      requestPacer(name)
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
      requestPacer(name)
      val payload =
        buildJsonObject { w ->
          w.name("address").value(address.raw)
          w.name("method").value("seqno")
          w.name("stack").beginArray().endArray()
        }
      val responseBytes = executePostJson("$baseUrl/api/v3/runGetMethod", payload)
      val seqno = parseRunGetMethodSeqnoJson(responseBytes)
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
      val payload =
        buildJsonObject { w ->
          w.name("boc").value(bocBase64)
          w.name("ignore_chksig").value(true)
          w.name("include_code_data").value(false)
          w.name("with_actions").value(false)
        }
      val responseBytes = executePostJson("$baseUrl/api/emulate/v1/emulateTrace", payload)
      val totalFees = parseEmulateTraceFeesJson(responseBytes)
      RemoteFeeEstimate(
        estimatedFee = Money(totalFees, GRAM_DECIMALS),
        providerName = name,
      )
    }

  override suspend fun sendBoc(network: Network, bocBase64: String): BroadcastResult =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(network).trimEnd('/')
      val payload = buildJsonObject { w -> w.name("boc").value(bocBase64) }
      try {
        val responseBytes =
          executePostJson("$baseUrl/api/v3/message", payload, treat4xxAsRejection = true)
        val msgHash = parseSendMessageHashJson(responseBytes)
        return@withContext BroadcastResult(messageHashHex = msgHash, providerName = name)
      } catch (rejected: RejectedTransactionException) {
        throw rejected
      } catch (primaryErr: ProviderException) {
        if (primaryErr is RateLimitedException) throw primaryErr
        requestPacer(name)
        val v2Bytes =
          executePostJson(
            "$baseUrl/api/v2/sendBocReturnHash",
            payload,
            treat4xxAsRejection = true,
          )
        val msgHash = parseSendBocV2HashJson(v2Bytes)
        return@withContext BroadcastResult(messageHashHex = msgHash, providerName = name)
      }
    }

  override suspend fun lookupMessageStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): RemoteTxLookup =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(network).trimEnd('/')
      val firstLookup = queryTransactionsByMessage(baseUrl, normalizedHashHex)
      if (firstLookup != null) return@withContext firstLookup
      if (messageHashHex.isNotBlank() && messageHashHex != normalizedHashHex) {
        requestPacer(name)
        val secondLookup = queryTransactionsByMessage(baseUrl, messageHashHex)
        if (secondLookup != null) return@withContext secondLookup
      }
      RemoteTxLookup(
        status = OnChainMessageStatus.PENDING,
        txHash = null,
        lt = null,
        fee = null,
        providerName = name,
      )
    }

  override suspend fun lookupJettonTraceStatus(
    network: Network,
    messageHashHex: String,
    normalizedHashHex: String,
  ): RemoteTxLookup =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(network).trimEnd('/')
      val firstTrace = queryJettonTraceByMessage(baseUrl, normalizedHashHex)
      if (firstTrace != null) return@withContext firstTrace
      if (messageHashHex.isNotBlank() && messageHashHex != normalizedHashHex) {
        requestPacer(name)
        val secondTrace = queryJettonTraceByMessage(baseUrl, messageHashHex)
        if (secondTrace != null) return@withContext secondTrace
      }
      RemoteTxLookup(
        status = OnChainMessageStatus.PENDING,
        txHash = null,
        lt = null,
        fee = null,
        providerName = name,
      )
    }

  override suspend fun fetchAccountHistory(
    address: TonAddress,
    afterLt: String?,
    limit: Int,
  ): RemoteTxHistoryPage =
    withContext(Dispatchers.IO) {
      val baseUrl = endpoints.baseUrlFor(address.network).trimEnd('/')
      val encodedAddr = URLEncoder.encode(address.raw, StandardCharsets.UTF_8.name())
      val clampedLimit = limit.coerceIn(1, 50)
      val startLtParam =
        afterLt?.trim()?.toBigIntegerOrNull()?.let { ltVal ->
          "&start_lt=${ltVal.add(BigInteger.ONE)}"
        }.orEmpty()
      val bytes =
        executeGetAllow404(
          "$baseUrl/api/v3/transactions?account=$encodedAddr&limit=$clampedLimit&sort=desc$startLtParam"
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
      val encodedOwner = URLEncoder.encode(ownerAddress.raw, StandardCharsets.UTF_8.name())
      val encodedMaster = URLEncoder.encode(verifiedSpec.masterAddress.raw, StandardCharsets.UTF_8.name())
      val clampedLimit = limit.coerceIn(1, 50)
      val bytes =
        executeGetAllow404(
          "$baseUrl/api/v3/jetton/transfers?owner_address=$encodedOwner&jetton_master=$encodedMaster&limit=$clampedLimit&sort=desc"
        )
          ?: return@withContext RemoteJettonHistoryPage(emptyList(), name)
      parseJettonTransfersJson(bytes, ownerAddress, verifiedSpec)
    }

  private fun queryTransactionsByMessage(baseUrl: String, hashHex: String): RemoteTxLookup? {
    val cleanHex = hashHex.removePrefix("0x").trim()
    if (cleanHex.isEmpty()) return null
    val encodedHash = URLEncoder.encode(cleanHex, StandardCharsets.UTF_8.name())
    val bodyBytes =
      executeGetAllow404(
        "$baseUrl/api/v3/transactionsByMessage?msg_hash=$encodedHash&direction=in&limit=1"
      )
        ?: return null
    if (bodyBytes.isEmpty()) return null
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      var result: RemoteTxLookup? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "transactions" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              if (reader.hasNext()) {
                result = parseSingleTransactionObject(reader)
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
      result
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed transactionsByMessage JSON", e)
    }
  }

  private fun parseSingleTransactionObject(reader: JsonReader): RemoteTxLookup? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var txHash: String? = null
    var lt: String? = null
    var totalFees: Money? = null
    var aborted = false
    var computeSuccess = true

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> txHash = readScalarAsString(reader)?.takeIf { it.isNotBlank() }
        "lt" -> lt = readScalarAsString(reader)?.takeIf { it.isNotBlank() }
        "total_fees",
        "total_fee" ->
          totalFees =
            readScalarAsString(reader)?.trim()?.takeIf { it.isNotEmpty() }
              ?.let { Money.parseUnits(it, GRAM_DECIMALS) }
        "aborted" -> aborted = readBooleanOrNull(reader) ?: false
        "compute_ph" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "success" -> computeSuccess = readBooleanOrNull(reader) ?: true
                "exit_code" -> {
                  val code = readScalarAsString(reader)?.toIntOrNull()
                  if (code != null && code != 0) computeSuccess = false
                }
                else -> reader.skipValue()
              }
            }
            reader.endObject()
          } else {
            reader.skipValue()
          }
        }
        "description" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "aborted" -> aborted = readBooleanOrNull(reader) ?: false
                "compute_ph" -> {
                  if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                      when (reader.nextName()) {
                        "success" -> computeSuccess = readBooleanOrNull(reader) ?: true
                        "exit_code" -> {
                          val code = readScalarAsString(reader)?.toIntOrNull()
                          if (code != null && code != 0) computeSuccess = false
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
          } else {
            reader.skipValue()
          }
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    val status =
      if (!aborted && computeSuccess) OnChainMessageStatus.CONFIRMED else OnChainMessageStatus.FAILED
    return RemoteTxLookup(
      status = status,
      txHash = txHash,
      lt = lt,
      fee = totalFees,
      providerName = name,
      failureReason = if (status == OnChainMessageStatus.FAILED) "Transaction aborted on-chain" else null,
    )
  }

  private class TonCenterTraceEval {
    var rootTxHash: String? = null
    var rootLt: String? = null
    var startLt: String? = null
    var isIncomplete: Boolean = false
    var hasFailureOrBounce: Boolean = false
    var hasActionFailure: Boolean = false
    var hasSuccessfulJettonAction: Boolean = false
    var totalFeesNano: BigInteger = BigInteger.ZERO
    var hasFee: Boolean = false
    val seenTxKeys = mutableSetOf<String>()
  }

  private fun queryJettonTraceByMessage(baseUrl: String, hashHex: String): RemoteTxLookup? {
    val cleanHex = hashHex.removePrefix("0x").trim()
    if (cleanHex.isEmpty()) return null
    val encodedHash = URLEncoder.encode(cleanHex, StandardCharsets.UTF_8.name())
    val bodyBytes =
      executeGetAllow404(
        "$baseUrl/api/v3/traces?msg_hash=$encodedHash&include_actions=true&limit=1"
      )
        ?: return null
    if (bodyBytes.isEmpty()) return null
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      val eval = TonCenterTraceEval()
      var foundTrace = false
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "traces" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              if (reader.hasNext()) {
                foundTrace = true
                parseTonCenterTraceEntry(reader, eval)
              }
              while (reader.hasNext()) reader.skipValue()
              reader.endArray()
            } else {
              reader.skipValue()
            }
          }
          "transactions" -> readTonCenterTraceTransactionsMap(reader, eval)
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      if (!foundTrace) return null
      val effectiveFee = if (eval.hasFee) Money(eval.totalFeesNano, GRAM_DECIMALS) else null
      val effectiveLt = eval.rootLt ?: eval.startLt
      when {
        eval.hasFailureOrBounce || eval.hasActionFailure ->
          RemoteTxLookup(
            status = OnChainMessageStatus.FAILED,
            txHash = eval.rootTxHash,
            lt = effectiveLt,
            fee = effectiveFee,
            providerName = name,
            failureReason = "Jetton transfer failed or bounced back",
          )
        eval.isIncomplete || (eval.seenTxKeys.size < 2 && !eval.hasSuccessfulJettonAction) ->
          RemoteTxLookup(
            status = OnChainMessageStatus.PENDING,
            txHash = eval.rootTxHash,
            lt = effectiveLt,
            fee = null,
            providerName = name,
          )
        else ->
          RemoteTxLookup(
            status = OnChainMessageStatus.CONFIRMED,
            txHash = eval.rootTxHash,
            lt = effectiveLt,
            fee = effectiveFee,
            providerName = name,
          )
      }
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed traces JSON", e)
    }
  }

  private fun parseTonCenterTraceEntry(reader: JsonReader, eval: TonCenterTraceEval) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "trace_id" -> {
          val tid = readScalarAsStringOrSkip(reader)?.takeIf { it.isNotBlank() }
          if (eval.rootTxHash == null) eval.rootTxHash = tid
        }
        "start_lt" -> eval.startLt = readScalarAsStringOrSkip(reader)?.takeIf { it.isNotBlank() }
        "is_incomplete" -> eval.isIncomplete = readBooleanOrNull(reader) ?: false
        "transactions" -> readTonCenterTraceTransactionsMap(reader, eval)
        "actions" -> readTonCenterTraceActions(reader, eval)
        else -> reader.skipValue()
      }
    }
    reader.endObject()
  }

  private fun readTonCenterTraceTransactionsMap(reader: JsonReader, eval: TonCenterTraceEval) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    reader.beginObject()
    while (reader.hasNext()) {
      val mapKey = reader.nextName()
      readTonCenterTraceTransaction(reader, mapKey, eval)
    }
    reader.endObject()
  }

  private fun readTonCenterTraceTransaction(
    reader: JsonReader,
    mapKey: String,
    eval: TonCenterTraceEval,
  ) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    var txHash: String? = mapKey.takeIf { it.isNotBlank() }
    var lt: String? = null
    var feeNano: BigInteger? = null
    var aborted = false
    var computeFailed = false
    var actionFailed = false
    var bounceExecuted = false
    var inBounced = false
    var outBounced = false

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> txHash = readScalarAsStringOrSkip(reader)?.takeIf { it.isNotBlank() } ?: txHash
        "lt" -> lt = readScalarAsStringOrSkip(reader)?.takeIf { it.isNotBlank() }
        "total_fees",
        "total_fee" ->
          feeNano = readScalarAsStringOrSkip(reader)?.trim()?.toBigIntegerOrNull() ?: feeNano
        "aborted" -> if (readBooleanOrNull(reader) == true) aborted = true
        "bounce" -> if (readTonCenterBounceType(reader) == "ok") bounceExecuted = true
        "compute_ph" ->
          applyTonCenterComputePhase(
            reader = reader,
            onSkippedNoState = {},
            onFailure = { computeFailed = true },
          )
        "action" -> if (readTonCenterActionPhaseFailed(reader)) actionFailed = true
        "description" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "aborted" -> if (readBooleanOrNull(reader) == true) aborted = true
                "bounce" -> if (readTonCenterBounceType(reader) == "ok") bounceExecuted = true
                "compute_ph" ->
                  applyTonCenterComputePhase(
                    reader = reader,
                    onSkippedNoState = {},
                    onFailure = { computeFailed = true },
                  )
                "action" -> if (readTonCenterActionPhaseFailed(reader)) actionFailed = true
                else -> reader.skipValue()
              }
            }
            reader.endObject()
          } else {
            reader.skipValue()
          }
        }
        "in_msg" -> if (readTonCenterMsgBounced(reader)) inBounced = true
        "out_msgs" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
              if (readTonCenterMsgBounced(reader)) outBounced = true
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

    val dedupKey = txHash ?: mapKey
    if (!eval.seenTxKeys.add(dedupKey)) return
    if (eval.seenTxKeys.size == 1) {
      if (txHash != null) eval.rootTxHash = txHash
      if (lt != null) eval.rootLt = lt
    }
    if (feeNano != null) {
      eval.totalFeesNano = eval.totalFeesNano.add(feeNano)
      eval.hasFee = true
    }
    if (aborted || computeFailed || actionFailed || bounceExecuted || inBounced || outBounced) {
      eval.hasFailureOrBounce = true
    }
  }

  private fun readTonCenterActionPhaseFailed(reader: JsonReader): Boolean {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return false
    }
    var failed = false
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "success" -> if (readBooleanOrNull(reader) == false) failed = true
        "result_code" -> {
          val code = readScalarAsStringOrSkip(reader)?.toIntOrNull()
          if (code != null && code != 0) failed = true
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return failed
  }

  private fun readTonCenterMsgBounced(reader: JsonReader): Boolean {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return false
    }
    var bounced = false
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "bounced" -> if (readBooleanOrNull(reader) == true) bounced = true
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    return bounced
  }

  private fun readTonCenterTraceActions(reader: JsonReader, eval: TonCenterTraceEval) {
    if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
      reader.skipValue()
      return
    }
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var actionType: String? = null
      var actionSuccess: Boolean? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "type" -> actionType = readScalarAsStringOrSkip(reader)
          "success" -> actionSuccess = readBooleanOrNull(reader)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (actionSuccess == false) {
        eval.hasActionFailure = true
      } else if (
        actionSuccess == true &&
          actionType.equals("jetton_transfer", ignoreCase = true)
      ) {
        eval.hasSuccessfulJettonAction = true
      }
    }
    reader.endArray()
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
                parseTonCenterHistoryTransaction(reader, accountAddress, transfers)
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
      throw InvalidPayloadException(name, "Malformed account transactions JSON", e)
    }
  }

  private data class TonCenterParsedMsg(
    val hash: String? = null,
    val hashNorm: String? = null,
    val sourceRaw: String? = null,
    val destRaw: String? = null,
    val valueNano: BigInteger = BigInteger.ZERO,
    val bounced: Boolean = false,
    val bounce: Boolean = false,
    val comment: String? = null,
  )

  private fun parseTonCenterHistoryTransaction(
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
    var nowSec = 0L
    var totalFees: Money? = null
    var aborted = false
    var computeSuccess = true
    var computeSkippedNoState = false
    var creditNano: BigInteger? = null
    var bounceType: String? = null
    var inMsg: TonCenterParsedMsg? = null
    val outMsgs = mutableListOf<TonCenterParsedMsg>()

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> txHash = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader)).orEmpty()
        "lt" -> lt = readScalarAsString(reader)?.trim().orEmpty()
        "now" -> nowSec = readScalarAsString(reader)?.trim()?.toLongOrNull() ?: 0L
        "total_fees",
        "total_fee" ->
          totalFees =
            readScalarAsString(reader)?.trim()?.takeIf { it.isNotEmpty() }
              ?.let { runCatching { Money.parseUnits(it, GRAM_DECIMALS) }.getOrNull() }
        "aborted" -> aborted = readBooleanOrNull(reader) ?: false
        "credit_ph" -> creditNano = readTonCenterCreditNano(reader)
        "bounce" -> bounceType = readTonCenterBounceType(reader)
        "compute_ph" ->
          applyTonCenterComputePhase(
            reader = reader,
            onSkippedNoState = { computeSkippedNoState = true },
            onFailure = { computeSuccess = false },
          )
        "description" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "aborted" -> aborted = readBooleanOrNull(reader) ?: false
                "credit_ph" -> creditNano = readTonCenterCreditNano(reader)
                "bounce" -> bounceType = readTonCenterBounceType(reader)
                "compute_ph" ->
                  applyTonCenterComputePhase(
                    reader = reader,
                    onSkippedNoState = { computeSkippedNoState = true },
                    onFailure = { computeSuccess = false },
                  )
                else -> reader.skipValue()
              }
            }
            reader.endObject()
          } else {
            reader.skipValue()
          }
        }
        "in_msg" -> inMsg = parseTonCenterMessageObject(reader, accountAddress.network)
        "out_msgs" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
              parseTonCenterMessageObject(reader, accountAddress.network)?.let { outMsgs.add(it) }
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
    val txStatus =
      if (!aborted && computeSuccess) OnChainMessageStatus.CONFIRMED else OnChainMessageStatus.FAILED
    val bounceExecuted = (bounceType == "ok") || outMsgs.any { it.bounced }

    val incoming = inMsg
    if (
      incoming != null &&
        incoming.sourceRaw != null &&
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
          utimeEpochSec = nowSec,
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
          utimeEpochSec = nowSec,
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

    if (!emittedOut && incoming?.sourceRaw == null && txStatus == OnChainMessageStatus.FAILED) {
      outList.add(
        RemoteChainTransfer(
          txHash = txHash,
          lt = lt,
          utimeEpochSec = nowSec,
          direction = TxDirection.OUT,
          amount = Money.zero(),
          fee = totalFees,
          counterpartyRaw = accountAddress.raw,
          comment = null,
          status = OnChainMessageStatus.FAILED,
          inMsgHash = incoming?.hash,
          inMsgNormalizedHash = incoming?.hashNorm,
        )
      )
    }
  }

  private fun readTonCenterCreditNano(reader: JsonReader): BigInteger? {
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

  private fun applyTonCenterComputePhase(
    reader: JsonReader,
    onSkippedNoState: () -> Unit,
    onFailure: () -> Unit,
  ) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    var phType: String? = null
    var skipReason: String? = null
    var skippedFlag = false
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "type",
        "compute_type" -> phType = readScalarAsStringOrSkip(reader)?.lowercase()
        "skipped" -> skippedFlag = readBooleanOrNull(reader) ?: false
        "reason",
        "skip_reason" -> skipReason = readScalarAsStringOrSkip(reader)?.lowercase()
        "success" -> if (readBooleanOrNull(reader) == false) onFailure()
        "exit_code" -> {
          val code = readScalarAsString(reader)?.toIntOrNull()
          if (code != null && code != 0) onFailure()
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()
    if ((phType == "skipped" || skippedFlag) && (skipReason == null || skipReason.contains("no_state"))) {
      onSkippedNoState()
    }
  }

  private fun readTonCenterBounceType(reader: JsonReader): String? {
    return when (reader.peek()) {
      JsonReader.Token.STRING -> reader.nextString().lowercase()
      JsonReader.Token.BEGIN_OBJECT -> {
        var type: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "type",
            "bounce_type" -> type = readScalarAsStringOrSkip(reader)?.lowercase()
            else -> reader.skipValue()
          }
        }
        reader.endObject()
        type
      }
      else -> {
        reader.skipValue()
        null
      }
    }
  }

  private fun parseTonCenterMessageObject(
    reader: JsonReader,
    network: Network,
  ): TonCenterParsedMsg? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var hash: String? = null
    var hashNorm: String? = null
    var sourceRaw: String? = null
    var destRaw: String? = null
    var valueNano = BigInteger.ZERO
    var bounced = false
    var bounce = false
    var decodedComment: String? = null
    var rawBodyBoc: String? = null

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "hash" -> hash = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader))
        "hash_norm" -> hashNorm = TonCommentDecoder.normalizeHashToHex(readScalarAsString(reader))
        "bounced" -> bounced = readBooleanOrNull(reader) ?: false
        "bounce" -> bounce = readBooleanOrNull(reader) ?: false
        "source" -> {
          val raw = readScalarAsString(reader)
          if (!raw.isNullOrBlank()) {
            sourceRaw = TonAddress.parse(raw, network).getOrNull()?.raw
          }
        }
        "destination" -> {
          val raw = readScalarAsString(reader)
          if (!raw.isNullOrBlank()) {
            destRaw = TonAddress.parse(raw, network).getOrNull()?.raw
          }
        }
        "value" -> {
          val str = readScalarAsString(reader)?.trim()
          if (!str.isNullOrEmpty()) {
            valueNano = runCatching { BigInteger(str) }.getOrDefault(BigInteger.ZERO)
          }
        }
        "message_content" -> {
          val (decoded, body) = parseTonCenterMessageContent(reader)
          if (decodedComment == null) decodedComment = decoded
          if (rawBodyBoc == null) rawBodyBoc = body
        }
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    val comment = decodedComment ?: TonCommentDecoder.decodeTextCommentFromBoc(rawBodyBoc)
    return TonCenterParsedMsg(
      hash = hash,
      hashNorm = hashNorm,
      sourceRaw = sourceRaw,
      destRaw = destRaw,
      valueNano = valueNano,
      bounced = bounced,
      bounce = bounce,
      comment = comment,
    )
  }

  private fun parseTonCenterMessageContent(reader: JsonReader): Pair<String?, String?> {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null to null
    }
    var decodedComment: String? = null
    var bodyBoc: String? = null
    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "body" -> bodyBoc = readScalarAsString(reader)
        "decoded" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "comment",
                "text" ->
                  decodedComment =
                    readScalarAsStringOrSkip(reader)?.trim()?.takeIf { it.isNotEmpty() }
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
    return decodedComment to bodyBoc
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

  private fun executeGetAllow404(url: String): ByteArray? =
    executeGetAllowCodes(url, setOf(404))

  private fun executeGetAllowCodes(url: String, allowedNullCodes: Set<Int>): ByteArray? {
    val requestBuilder = Request.Builder().url(url).get().header("Accept", "application/json")
    applyAuthHeader(requestBuilder)
    val response =
      try {
        okHttpClient.newCall(requestBuilder.build()).execute()
      } catch (e: IOException) {
        throw TransportException(name, e)
      }
    return response.use { res ->
      if (res.code in allowedNullCodes) return@use null
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
      readBodyBytes(res)
    }
  }

  private fun applyAuthHeader(builder: Request.Builder) {
    apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
      builder.header("X-API-Key", key)
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
              "error",
              "detail" -> errMsg = readScalarAsStringOrSkip(reader)
              else -> reader.skipValue()
            }
          }
          reader.endObject()
        }
        errMsg?.takeIf { it.isNotBlank() }
      }.getOrNull()
    return parsed ?: raw.take(160)
  }

  private fun parseAccountStatesJson(
    bodyBytes: ByteArray,
    requestedAddress: TonAddress,
    fetchedAt: Long,
  ): RemoteAccountBalance {
    if (bodyBytes.isEmpty()) {
      throw InvalidPayloadException(name, "Empty JSON response")
    }
    var hasAccountsArray = false
    var foundAccount = false
    var rawAddress: String? = null
    var rawBalance: String? = null
    var rawStatus: String? = null
    var rawLastTxLt: String? = null

    try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "accounts" -> {
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
              throw InvalidPayloadException(name, "Expected accounts array")
            }
            hasAccountsArray = true
            reader.beginArray()
            if (reader.hasNext()) {
              if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                throw InvalidPayloadException(name, "Expected account object")
              }
              foundAccount = true
              reader.beginObject()
              while (reader.hasNext()) {
                when (reader.nextName()) {
                  "address" -> rawAddress = readScalarAsString(reader)
                  "balance" -> rawBalance = readScalarAsString(reader)
                  "status" -> rawStatus = readScalarAsString(reader)
                  "last_transaction_lt" -> rawLastTxLt = readScalarAsStringOrSkip(reader)?.trim()
                  else -> reader.skipValue()
                }
              }
              reader.endObject()
            }
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
          }
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

    if (!hasAccountsArray) {
      throw InvalidPayloadException(name, "Missing accounts field")
    }
    if (!foundAccount) {
      return RemoteAccountBalance(
        address = requestedAddress,
        balance = Money.zero(),
        status = AccountOnChainStatus.NONEXIST,
        providerName = name,
        fetchedAtEpochMs = fetchedAt,
        lastTxLt = null,
      )
    }

    rawAddress?.takeIf { it.isNotBlank() }?.let { returnedAddr ->
      val parsedAddr =
        TonAddress.parse(returnedAddr, requestedAddress.network).getOrElse { err ->
          throw InvalidPayloadException(name, "Address network/format mismatch", err)
        }
      if (parsedAddr.raw != requestedAddress.raw) {
        throw InvalidPayloadException(name, "Response address does not match request")
      }
    }

    val status = AccountOnChainStatus.fromWire(rawStatus)
    val money =
      if (rawBalance.isNullOrBlank() && status == AccountOnChainStatus.NONEXIST) {
        Money.zero()
      } else {
        val balanceText =
          rawBalance?.takeIf { it.isNotBlank() }
            ?: throw InvalidPayloadException(name, "Missing balance field")
        try {
          Money.parseUnits(balanceText, GRAM_DECIMALS)
        } catch (e: IllegalArgumentException) {
          throw InvalidPayloadException(name, "Invalid balance units: $balanceText", e)
        }
      }

    return RemoteAccountBalance(
      address = requestedAddress,
      balance = money,
      status = status,
      providerName = name,
      fetchedAtEpochMs = fetchedAt,
      lastTxLt = rawLastTxLt?.takeIf { it.isNotEmpty() },
    )
  }

  private fun parseJettonWalletsJson(
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
          "jetton_wallets" -> {
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
              reader.skipValue()
            } else {
              reader.beginArray()
              while (reader.hasNext()) {
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                  reader.skipValue()
                  continue
                }
                var wAddrRaw: String? = null
                var wBalanceRaw: String? = null
                var wOwnerRaw: String? = null
                var wMasterRaw: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "address" -> wAddrRaw = readScalarAsStringOrSkip(reader)
                    "balance" -> wBalanceRaw = readScalarAsStringOrSkip(reader)
                    "owner" -> wOwnerRaw = readScalarAsStringOrSkip(reader)
                    "jetton" -> wMasterRaw = readScalarAsStringOrSkip(reader)
                    else -> reader.skipValue()
                  }
                }
                reader.endObject()

                val parsedMaster =
                  wMasterRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() }
                val parsedOwner =
                  wOwnerRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() }
                val verified =
                  parsedMaster != null &&
                    parsedMaster.raw.equals(spec.masterAddress.raw, ignoreCase = true) &&
                    (parsedOwner == null || parsedOwner.raw.equals(ownerAddress.raw, ignoreCase = true))
                if (verified && matchedBalance == null) {
                  val parsedWallet =
                    wAddrRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() }
                  val balText = wBalanceRaw?.trim().orEmpty()
                  val money =
                    if (balText.isEmpty()) Money.zero(spec.decimals)
                    else runCatching { Money.parseUnits(balText, spec.decimals) }
                      .getOrElse { throw InvalidPayloadException(name, "Invalid Jetton balance: $balText", it) }
                  matchedWalletAddr = parsedWallet
                  matchedBalance = money
                }
              }
              reader.endArray()
            }
          }
          "metadata" -> validateTonCenterJettonMetadataDecimals(reader, ownerAddress.network, spec)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed jetton/wallets JSON", e)
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

  private fun validateTonCenterJettonMetadataDecimals(
    reader: JsonReader,
    network: Network,
    spec: AllowedJettonSpec,
  ) {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return
    }
    reader.beginObject()
    while (reader.hasNext()) {
      val key = reader.nextName()
      val keyMaster = TonAddress.parse(key, network).getOrNull()
      if (keyMaster != null && keyMaster.raw.equals(spec.masterAddress.raw, ignoreCase = true)) {
        if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
          reader.beginObject()
          while (reader.hasNext()) {
            when (reader.nextName()) {
              "token_info" -> {
                if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                  reader.beginArray()
                  while (reader.hasNext()) {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                      reader.beginObject()
                      while (reader.hasNext()) {
                        when (reader.nextName()) {
                          "extra" -> {
                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                              reader.beginObject()
                              while (reader.hasNext()) {
                                when (reader.nextName()) {
                                  "decimals" -> {
                                    val dec = readScalarAsStringOrSkip(reader)?.trim()?.toIntOrNull()
                                    if (dec != null && dec != spec.decimals) {
                                      throw InvalidPayloadException(
                                        name,
                                        "Jetton decimals mismatch: expected ${spec.decimals}, got $dec",
                                      )
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
                    } else {
                      reader.skipValue()
                    }
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
        } else {
          reader.skipValue()
        }
      } else {
        reader.skipValue()
      }
    }
    reader.endObject()
  }

  private fun parseJettonTransfersJson(
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
          "jetton_transfers",
          "transfers" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              while (reader.hasNext()) {
                parseTonCenterJettonTransferItem(reader, ownerAddress, spec)?.let { list.add(it) }
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
      throw InvalidPayloadException(name, "Malformed jetton transfers JSON", e)
    }
  }

  private fun parseTonCenterJettonTransferItem(
    reader: JsonReader,
    ownerAddress: TonAddress,
    spec: AllowedJettonSpec,
  ): RemoteJettonTransfer? {
    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
      reader.skipValue()
      return null
    }
    var sourceRaw: String? = null
    var destRaw: String? = null
    var amountStr: String? = null
    var txHash = ""
    var lt = ""
    var nowSec = 0L
    var masterRaw: String? = null
    var forwardPayloadBoc: String? = null
    var decodedComment: String? = null
    var txAborted = false

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "source" -> sourceRaw = readScalarAsStringOrSkip(reader)?.let { TonAddress.parse(it, ownerAddress.network).getOrNull()?.raw }
        "destination" -> destRaw = readScalarAsStringOrSkip(reader)?.let { TonAddress.parse(it, ownerAddress.network).getOrNull()?.raw }
        "amount" -> amountStr = readScalarAsStringOrSkip(reader)?.trim()
        "transaction_hash" -> txHash = TonCommentDecoder.normalizeHashToHex(readScalarAsStringOrSkip(reader)).orEmpty()
        "transaction_lt",
        "lt" -> {
          val candidate = readScalarAsStringOrSkip(reader)?.trim().orEmpty()
          if (candidate.isNotEmpty() && lt.isEmpty()) lt = candidate
        }
        "transaction_now" -> nowSec = readScalarAsStringOrSkip(reader)?.trim()?.toLongOrNull() ?: 0L
        "jetton_master" -> masterRaw = readScalarAsStringOrSkip(reader)
        "forward_payload" -> forwardPayloadBoc = readScalarAsStringOrSkip(reader)
        "tx_aborted" -> txAborted = readBooleanOrNull(reader) ?: false
        "decoded_forward_payload" -> {
          if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            reader.beginObject()
            while (reader.hasNext()) {
              when (reader.nextName()) {
                "comment",
                "text" -> decodedComment = readScalarAsStringOrSkip(reader)?.trim()?.takeIf { it.isNotEmpty() }
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

    // Anti-spoofing gate: reject transfers from any non-allow-listed Jetton master
    val parsedMaster = masterRaw?.let { TonAddress.parse(it, ownerAddress.network).getOrNull() } ?: return null
    if (!parsedMaster.raw.equals(spec.masterAddress.raw, ignoreCase = true)) return null
    if (txHash.isEmpty() || lt.isEmpty() || amountStr.isNullOrEmpty()) return null
    val amount = runCatching { Money.parseUnits(amountStr, spec.decimals) }.getOrNull() ?: return null
    if (amount.units <= BigInteger.ZERO) return null

    val isIncoming =
      destRaw.equals(ownerAddress.raw, ignoreCase = true) &&
        !sourceRaw.equals(ownerAddress.raw, ignoreCase = true)
    val direction = if (isIncoming) TxDirection.IN else TxDirection.OUT
    val counterparty = (if (isIncoming) sourceRaw else destRaw) ?: return null
    val comment = decodedComment ?: TonCommentDecoder.decodeTextCommentFromBoc(forwardPayloadBoc)

    return RemoteJettonTransfer(
      txHash = txHash,
      lt = lt,
      utimeEpochSec = nowSec,
      direction = direction,
      jettonMasterRaw = spec.masterAddress.raw,
      amount = amount,
      counterpartyRaw = counterparty,
      comment = comment,
      status = if (txAborted) OnChainMessageStatus.FAILED else OnChainMessageStatus.CONFIRMED,
    )
  }

  private fun parseWalletInformationJson(
    bodyBytes: ByteArray,
    requestedAddress: TonAddress,
    fetchedAt: Long,
  ): RemoteWalletInfo {
    if (bodyBytes.isEmpty()) {
      throw InvalidPayloadException(name, "Empty JSON response")
    }
    var rawBalance: String? = null
    var rawStatus: String? = null
    var seqno: Int? = null

    try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "balance" -> rawBalance = readScalarAsString(reader)
          "status" -> rawStatus = readScalarAsString(reader)
          "seqno" -> seqno = readScalarAsString(reader)?.trim()?.toIntOrNull()?.coerceAtLeast(0)
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

    val balanceText =
      rawBalance?.takeIf { it.isNotBlank() }
        ?: throw InvalidPayloadException(name, "Missing balance field")
    val money =
      try {
        Money.parseUnits(balanceText, GRAM_DECIMALS)
      } catch (e: IllegalArgumentException) {
        throw InvalidPayloadException(name, "Invalid balance units: $balanceText", e)
      }

    val parsedStatus = AccountOnChainStatus.fromWire(rawStatus)
    return RemoteWalletInfo(
      address = requestedAddress,
      balance = money,
      status = parsedStatus,
      seqno = seqno ?: 0,
      providerName = name,
      fetchedAtEpochMs = fetchedAt,
    )
  }

  private fun parseRunGetMethodSeqnoJson(bodyBytes: ByteArray): Int {
    if (bodyBytes.isEmpty()) return 0
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var exitCode = -1
      var seqnoValue = 0
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "exit_code" -> exitCode = readScalarAsString(reader)?.toIntOrNull() ?: -1
          "stack" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              if (reader.hasNext() && reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "value" -> {
                      val valueStr = readScalarAsString(reader).orEmpty().trim()
                      seqnoValue =
                        if (valueStr.startsWith("0x", ignoreCase = true)) {
                          valueStr.substring(2).toIntOrNull(16) ?: 0
                        } else {
                          valueStr.toIntOrNull() ?: 0
                        }
                    }
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
      if (exitCode != 0) 0 else seqnoValue
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed runGetMethod JSON", e)
    }
  }

  private fun parseEmulateTraceFeesJson(bodyBytes: ByteArray): BigInteger {
    if (bodyBytes.isEmpty()) throw InvalidPayloadException(name, "Empty emulateTrace response")
    return try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      var hasTransactions = false
      var total = BigInteger.ZERO
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "transactions" -> {
            hasTransactions = true
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
              throw InvalidPayloadException(name, "Expected transactions object")
            }
            reader.beginObject()
            while (reader.hasNext()) {
              reader.nextName()
              if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                  when (reader.nextName()) {
                    "total_fees",
                    "total_fee" -> {
                      val feeStr = readScalarAsString(reader)?.trim()
                      if (!feeStr.isNullOrEmpty()) total = total.add(BigInteger(feeStr))
                    }
                    else -> reader.skipValue()
                  }
                }
                reader.endObject()
              } else {
                reader.skipValue()
              }
            }
            reader.endObject()
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (!hasTransactions) throw InvalidPayloadException(name, "Missing emulated transactions map")
      total.coerceAtLeast(BigInteger.ZERO)
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed emulateTrace JSON", e)
    }
  }

  private fun parseSendMessageHashJson(bodyBytes: ByteArray): String {
    if (bodyBytes.isEmpty()) return ""
    return runCatching {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      var hashNorm: String? = null
      var hashRaw: String? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "message_hash_norm" -> hashNorm = readScalarAsString(reader)
          "message_hash" -> hashRaw = readScalarAsString(reader)
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      val candidate =
        hashNorm?.takeIf { it.isNotBlank() } ?: hashRaw?.takeIf { it.isNotBlank() } ?: return@runCatching ""
      normalizeHashCandidate(candidate)
    }.getOrDefault("")
  }

  private fun parseSendBocV2HashJson(bodyBytes: ByteArray): String {
    if (bodyBytes.isEmpty()) throw InvalidPayloadException(name, "Empty sendBocReturnHash response")
    var ok: Boolean? = null
    var hash: String? = null
    var errorMsg: String? = null
    try {
      val reader = JsonReader.of(Buffer().write(bodyBytes))
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw InvalidPayloadException(name, "Expected JSON object")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "ok" -> ok = readBooleanOrNull(reader)
          "error" -> errorMsg = readScalarAsStringOrSkip(reader)
          "result" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
              reader.beginObject()
              while (reader.hasNext()) {
                when (reader.nextName()) {
                  "hash" -> hash = readScalarAsStringOrSkip(reader)
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
    } catch (e: ProviderException) {
      throw e
    } catch (e: Exception) {
      throw InvalidPayloadException(name, "Malformed sendBocReturnHash JSON", e)
    }

    if (ok == false) {
      throw RejectedTransactionException(name, 400, errorMsg?.takeIf { it.isNotBlank() } ?: "Rejected by TON node")
    }
    return hash?.takeIf { it.isNotBlank() }?.let(::normalizeHashCandidate).orEmpty()
  }

  private fun normalizeHashCandidate(candidate: String): String {
    val clean = candidate.trim()
    if (clean.matches(Regex("^[0-9a-fA-F]+$"))) {
      return clean.lowercase()
    }
    return runCatching {
      val decoded = Base64.getDecoder().decode(clean)
      if (decoded.size == 32) decoded.joinToString("") { "%02x".format(it) } else clean
    }.getOrDefault(clean)
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

package com.example.core.wallet

import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.ParsedAddress
import com.example.core.model.TonAddress
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedTransferUri(
  val parsedRecipient: ParsedAddress,
  val amount: Money?,
  val comment: String?,
  val jettonMaster: TonAddress?,
  val expSeconds: Long?,
) {
  val recipient: TonAddress
    get() = parsedRecipient.address

  val isBounceable: Boolean
    get() = parsedRecipient.isBounceable
}

object TonTransferUri {
  const val MAX_COMMENT_LENGTH = 500

  fun build(
    recipient: TonAddress,
    bounceable: Boolean = false,
    amount: Money? = null,
    comment: String? = null,
  ): String {
    val addressText = recipient.toFriendly(bounceable = bounceable)
    val params = mutableListOf<String>()

    if (amount != null) {
      require(amount.decimals == GRAM_DECIMALS) {
        "Native GRAM transfer URI requires $GRAM_DECIMALS decimals"
      }
      require(amount.units > BigInteger.ZERO) { "Transfer URI amount must be greater than zero" }
      params.add("amount=${amount.units}")
    }

    val cleanComment = comment?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanComment != null) {
      require(cleanComment.length <= MAX_COMMENT_LENGTH) {
        "Comment exceeds $MAX_COMMENT_LENGTH characters"
      }
      params.add("text=${percentEncode(cleanComment)}")
    }

    return if (params.isEmpty()) {
      "ton://transfer/$addressText"
    } else {
      "ton://transfer/$addressText?${params.joinToString("&")}"
    }
  }

  fun parse(uriText: String, expectedNetwork: Network): Result<ParsedTransferUri> = runCatching {
    val trimmed = uriText.trim()
    require(trimmed.isNotEmpty()) { "Transfer link cannot be empty" }

    val uri =
      try {
        URI(trimmed)
      } catch (e: Exception) {
        throw IllegalArgumentException("Malformed TON transfer URI", e)
      }

    require(uri.scheme.equals("ton", ignoreCase = true)) {
      "URI scheme must be ton://"
    }
    require(uri.host.equals("transfer", ignoreCase = true)) {
      "Unsupported TON URI action: ${uri.host ?: "missing"}"
    }

    val rawPath = uri.rawPath?.trim('/') ?: ""
    require(rawPath.isNotEmpty() && !rawPath.contains('/')) {
      "Transfer URI must contain a single recipient address"
    }

    val parsedRecipient = TonAddress.parseDetailed(rawPath, expectedNetwork).getOrThrow()

    val queryParams = parseQuery(uri.rawQuery)
    val amount =
      queryParams["amount"]?.let { rawAmount ->
        val money = Money.parseUnits(rawAmount, GRAM_DECIMALS)
        require(money.units > BigInteger.ZERO) { "Transfer URI amount must be positive" }
        money
      }
    val comment = queryParams["text"]?.takeIf { it.isNotEmpty() }
    val jettonMaster =
      queryParams["jetton"]?.let { rawJetton ->
        TonAddress.parse(rawJetton, expectedNetwork).getOrThrow()
      }
    val expSeconds =
      queryParams["exp"]?.let { rawExp ->
        val parsed = rawExp.toLongOrNull()
        require(parsed != null && parsed > 0L) { "Invalid expiry timestamp in transfer URI" }
        parsed
      }

    ParsedTransferUri(
      parsedRecipient = parsedRecipient,
      amount = amount,
      comment = comment,
      jettonMaster = jettonMaster,
      expSeconds = expSeconds,
    )
  }

  private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (pair in rawQuery.split('&')) {
      if (pair.isBlank()) continue
      val eqIndex = pair.indexOf('=')
      val key =
        if (eqIndex >= 0) percentDecode(pair.substring(0, eqIndex)) else percentDecode(pair)
      val value = if (eqIndex >= 0) percentDecode(pair.substring(eqIndex + 1)) else ""
      require(!result.containsKey(key)) { "Duplicate query parameter: $key" }
      result[key] = value
    }
    return result
  }

  private fun percentEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

  private fun percentDecode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}

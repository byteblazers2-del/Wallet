package com.example.core.model

import java.math.BigDecimal
import java.math.BigInteger

const val ASSET_ID_GRAM = "GRAM"
const val GRAM_DECIMALS = 9

data class Money(
  val units: BigInteger,
  val decimals: Int = GRAM_DECIMALS,
) {
  init {
    require(units >= BigInteger.ZERO) { "Money units cannot be negative" }
    require(decimals in 0..36) { "Money decimals must be between 0 and 36" }
  }

  fun format(
    minFractionDigits: Int = 2,
    maxFractionDigits: Int = decimals,
    trimTrailingZeros: Boolean = true,
  ): String {
    require(minFractionDigits in 0..decimals) {
      "minFractionDigits ($minFractionDigits) exceeds decimals ($decimals)"
    }
    require(maxFractionDigits in minFractionDigits..decimals) {
      "maxFractionDigits ($maxFractionDigits) must be in $minFractionDigits..$decimals"
    }

    if (decimals == 0) return units.toString()

    val divisor = BigInteger.TEN.pow(decimals)
    val (whole, frac) = units.divideAndRemainder(divisor)
    val paddedFrac = frac.toString().padStart(decimals, '0')
    val cappedFrac =
      if (maxFractionDigits < decimals) paddedFrac.substring(0, maxFractionDigits) else paddedFrac

    val displayFrac =
      if (trimTrailingZeros) {
        val trimmed = cappedFrac.trimEnd('0')
        if (trimmed.length < minFractionDigits) {
          cappedFrac.substring(0, minFractionDigits)
        } else {
          trimmed
        }
      } else {
        cappedFrac
      }

    return if (displayFrac.isEmpty()) whole.toString() else "$whole.$displayFrac"
  }

  fun formatWithSymbol(
    symbol: String = ASSET_ID_GRAM,
    minFractionDigits: Int = 2,
    maxFractionDigits: Int = decimals,
    trimTrailingZeros: Boolean = true,
  ): String = "${format(minFractionDigits, maxFractionDigits, trimTrailingZeros)} $symbol"

  operator fun plus(other: Money): Money {
    require(decimals == other.decimals) {
      "Cannot add Money with mismatched decimals: $decimals vs ${other.decimals}"
    }
    return Money(units = units.add(other.units), decimals = decimals)
  }

  operator fun minus(other: Money): Money {
    require(decimals == other.decimals) {
      "Cannot subtract Money with mismatched decimals: $decimals vs ${other.decimals}"
    }
    require(units >= other.units) { "Resulting Money units cannot be negative" }
    return Money(units = units.subtract(other.units), decimals = decimals)
  }

  companion object {
    private val UNSIGNED_INT_REGEX = Regex("^\\d+$")

    fun zero(decimals: Int = GRAM_DECIMALS): Money = Money(BigInteger.ZERO, decimals)

    fun ofNano(units: Long): Money {
      require(units >= 0L) { "Nano units cannot be negative" }
      return Money(BigInteger.valueOf(units), GRAM_DECIMALS)
    }

    fun parseUnits(rawUnits: String, decimals: Int = GRAM_DECIMALS): Money {
      val trimmed = rawUnits.trim()
      require(UNSIGNED_INT_REGEX.matches(trimmed)) {
        "Units must be a non-negative integer string"
      }
      return Money(BigInteger(trimmed), decimals)
    }

    fun fromDecimalString(decimalText: String, decimals: Int = GRAM_DECIMALS): Money {
      val trimmed = decimalText.trim()
      require(trimmed.isNotEmpty()) { "Decimal amount cannot be empty" }
      require(!trimmed.startsWith("-") && !trimmed.startsWith("+")) {
        "Signed decimal strings are not accepted"
      }
      val parsed =
        try {
          BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
          throw IllegalArgumentException("Invalid decimal amount: $trimmed", e)
        }
      require(parsed >= BigDecimal.ZERO) { "Amount cannot be negative" }
      val scaled = parsed.movePointRight(decimals)
      val exactUnits =
        try {
          scaled.toBigIntegerExact()
        } catch (e: ArithmeticException) {
          throw IllegalArgumentException(
            "Amount has more than $decimals fractional digits: $trimmed",
            e,
          )
        }
      return Money(exactUnits, decimals)
    }
  }
}

enum class AccountOnChainStatus(val wireValue: String) {
  NONEXIST("nonexist"),
  UNINIT("uninit"),
  ACTIVE("active"),
  FROZEN("frozen"),
  UNKNOWN("unknown");

  companion object {
    fun fromWire(value: String?): AccountOnChainStatus {
      return when (value?.trim()?.lowercase()) {
        "nonexist", "non_exist", "nonexistent" -> NONEXIST
        "uninit", "uninitialized" -> UNINIT
        "active" -> ACTIVE
        "frozen" -> FROZEN
        else -> UNKNOWN
      }
    }
  }
}

data class RemoteAccountBalance(
  val address: TonAddress,
  val balance: Money,
  val status: AccountOnChainStatus,
  val providerName: String,
  val fetchedAtEpochMs: Long,
  val lastTxLt: String? = null,
)

data class BalanceState(
  val network: Network? = null,
  val address: TonAddress? = null,
  val gramBalance: Money? = null,
  val usdtBalance: Money? = null,
  val usdtJettonWallet: TonAddress? = null,
  val lastTxLt: String? = null,
  val accountStatus: AccountOnChainStatus? = null,
  val isLoading: Boolean = false,
  val isStale: Boolean = false,
  val fetchedAtEpochMs: Long? = null,
  val providerName: String? = null,
  val errorMessage: String? = null,
)

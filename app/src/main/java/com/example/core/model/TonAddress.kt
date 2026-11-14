package com.example.core.model

import java.util.Base64
import org.ton.block.AddrStd

data class ParsedAddress(
  val address: TonAddress,
  val isRaw: Boolean,
  val isBounceable: Boolean,
  val isTestnetOnly: Boolean,
)

data class TonAddress(
  val raw: String,
  val network: Network,
) {
  fun toFriendly(bounceable: Boolean = false): String {
    val std = AddrStd(raw)
    return std.toString(
      userFriendly = true,
      urlSafe = true,
      testOnly = (network == Network.TESTNET),
      bounceable = bounceable,
    )
  }

  companion object {
    private val RAW_ADDRESS_REGEX = Regex("^(0|-1):[0-9a-fA-F]{64}$")

    fun parse(input: String, expectedNetwork: Network): Result<TonAddress> =
      parseDetailed(input, expectedNetwork).map { it.address }

    fun parseDetailed(input: String, expectedNetwork: Network): Result<ParsedAddress> = runCatching {
      val trimmed = input.trim()
      require(trimmed.isNotEmpty()) { "Address cannot be empty" }
      if (trimmed.contains(':')) {
        require(RAW_ADDRESS_REGEX.matches(trimmed)) {
          "Raw address must be workchain:64-hex (workchain 0 or -1)"
        }
        val std = AddrStd.parseRaw(trimmed)
        require(std.workchainId == 0 || std.workchainId == -1) {
          "Unsupported workchain: ${std.workchainId}"
        }
        val normalizedRaw = std.toString(userFriendly = false)
        ParsedAddress(
          address = TonAddress(raw = normalizedRaw, network = expectedNetwork),
          isRaw = true,
          isBounceable = false,
          isTestnetOnly = (expectedNetwork == Network.TESTNET),
        )
      } else {
        require(trimmed.length == 48) { "Friendly TON address must be 48 characters" }
        val normalizedB64 = trimmed.replace('-', '+').replace('_', '/')
        val decoded =
          try {
            Base64.getDecoder().decode(normalizedB64)
          } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 TON address encoding", e)
          }
        require(decoded.size == 36) { "Invalid friendly address byte length: ${decoded.size}" }

        val expectedCrc = crc16Xmodem(decoded, 0, 34)
        val actualCrc = ((decoded[34].toInt() and 0xFF) shl 8) or (decoded[35].toInt() and 0xFF)
        require(expectedCrc == actualCrc) { "Invalid TON address checksum" }

        val tag = decoded[0].toInt() and 0xFF
        require((tag and 0x3F) == 0x11) { "Unsupported TON address tag byte: 0x${tag.toString(16)}" }

        val addressIsTestnet = (tag and 0x80) != 0
        val isNonBounceable = (tag and 0x40) != 0
        val expectTestnet = (expectedNetwork == Network.TESTNET)
        require(addressIsTestnet == expectTestnet) {
          if (expectTestnet) "Mainnet address is not allowed on Testnet"
          else "Testnet address is not allowed on Mainnet"
        }

        val workchain = decoded[1].toInt()
        require(workchain == 0 || workchain == -1) { "Unsupported workchain: $workchain" }
        val hashHex = decoded.copyOfRange(2, 34).joinToString("") { "%02x".format(it) }
        val canonicalRaw = "$workchain:$hashHex"
        val std = AddrStd.parseRaw(canonicalRaw)

        ParsedAddress(
          address = TonAddress(raw = std.toString(userFriendly = false), network = expectedNetwork),
          isRaw = false,
          isBounceable = !isNonBounceable,
          isTestnetOnly = addressIsTestnet,
        )
      }
    }

    private fun crc16Xmodem(data: ByteArray, offset: Int, length: Int): Int {
      var crc = 0
      for (i in offset until (offset + length)) {
        crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
        for (bit in 0 until 8) {
          crc =
            if ((crc and 0x8000) != 0) {
              ((crc shl 1) xor 0x1021) and 0xFFFF
            } else {
              (crc shl 1) and 0xFFFF
            }
        }
      }
      return crc
    }
  }
}

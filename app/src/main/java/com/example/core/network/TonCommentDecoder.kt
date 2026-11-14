package com.example.core.network

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.ton.boc.BagOfCells
import org.ton.cell.Cell

internal object TonCommentDecoder {
  private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

  fun normalizeHashToHex(raw: String?): String? {
    val clean = raw?.trim()?.removePrefix("0x")?.takeIf { it.isNotEmpty() } ?: return null
    if (clean.matches(HEX_REGEX)) {
      return clean.lowercase()
    }
    return runCatching {
      val decoded = Base64.getDecoder().decode(clean)
      if (decoded.size == 32) {
        decoded.joinToString("") { "%02x".format(it) }
      } else {
        clean
      }
    }.getOrDefault(clean)
  }

  fun decodeTextCommentFromBoc(bocEncoded: String?): String? {
    val raw = bocEncoded?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val bocBytes =
      if (raw.matches(HEX_REGEX) && raw.length % 2 == 0) {
        ByteArray(raw.length / 2) { i -> raw.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
      } else {
        runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return null
      }
    if (bocBytes.isEmpty()) return null
    val root = runCatching { BagOfCells(bocBytes).roots.firstOrNull() }.getOrNull() ?: return null
    if (root.bits.size < 40 || root.bits.size % 8 != 0) return null
    val isZeroOpcode = (0 until 32).all { idx -> !root.bits[idx] }
    if (!isZeroOpcode) return null

    val out = ByteArrayOutputStream()
    appendCellBytes(root, startBit = 32, out = out)
    var nextCell: Cell? = root.refs.firstOrNull()
    var hops = 0
    while (nextCell != null && hops < 4) {
      if (nextCell.bits.size % 8 != 0) break
      appendCellBytes(nextCell, startBit = 0, out = out)
      nextCell = nextCell.refs.firstOrNull()
      hops++
    }

    val bytes = out.toByteArray()
    if (bytes.isEmpty()) return null
    val decoded = String(bytes, Charsets.UTF_8).trim()
    if (decoded.isEmpty()) return null
    val hasBadControl = decoded.any { ch -> ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' }
    return if (hasBadControl) null else decoded.take(240)
  }

  private fun appendCellBytes(cell: Cell, startBit: Int, out: ByteArrayOutputStream) {
    var bitIdx = startBit
    while (bitIdx + 8 <= cell.bits.size) {
      var byteVal = 0
      for (offset in 0 until 8) {
        byteVal = (byteVal shl 1) or if (cell.bits[bitIdx + offset]) 1 else 0
      }
      out.write(byteVal)
      bitIdx += 8
    }
  }
}

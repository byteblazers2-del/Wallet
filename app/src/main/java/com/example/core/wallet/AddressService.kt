package com.example.core.wallet

import com.example.core.model.CandidateAccount
import com.example.core.model.MnemonicScheme
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.WalletVersion
import java.util.Base64
import org.ton.block.StateInit
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder

object AddressService {
  const val DEFAULT_LEGACY_WALLET_ID = 698983191L

  // Wallet V5R1 compiled code BOC verified from org.ton:walletkit:0.0.6
  private const val WALLET_V5R1_CODE_BOC_HEX =
    "b5ee9c7201021401000281000114ff00f4a413f4bcf2c80b01020120020302014804050102f20e02dcd020d749c120" +
      "915b8f6320d70b1f2082106578746ebd21821073696e74bdb0925f03e082106578746eba8eb48020d72101d074d721" +
      "fa4030fa44f828fa443058bd915be0ed44d0810141d721f4058307f40e6fa1319130e18040d721707fdb3ce03120d74" +
      "9810280b99130e070e2100f020120060702012008090019be5f0f6a2684080a0eb90fa02c02016e0a0b0201480c0d00" +
      "19adce76a2684020eb90eb85ffc00019af1df6a2684010eb90eb858fc00017b325fb51341c75c875c2c7e00011b262f" +
      "b513435c28020011e20d70b1f82107369676ebaf2e08a7f0f01e68ef0eda2edfb218308d722028308d723208020d721" +
      "d31fd31fd31fed44d0d200d31f20d31fd3ffd70a000af90140ccf9109a28945f0adb31e1f2c087df02b35007b0f2d08" +
      "45125baf2e0855036baf2e086f823bbf2d0882292f800de01a47fc8ca00cb1f01cf16c9ed542092f80fde70db3cd810" +
      "03f6eda2edfb02f404216e926c218e4c0221d73930709421c700b38e2d01d72820761e436c20d749c008f2e09320d74" +
      "ac002f2e09320d71d06c712c2005230b0f2d089d74cd7393001a4e86c128407bbf2e093d74ac000f2e093ed55e2d200" +
      "01c000915be0ebd72c08142091709601d72c081c12e25210b1e30f20d74a111213009601fa4001fa44f828fa443058ba" +
      "f2e091ed44d0810141d718f405049d7fc8ca0040048307f453f2e08b8e14038307f45bf2e08c22d70a00216e01b3b0f2" +
      "d090e2c85003cf1612f400c9ed54007230d72c08248e2d21f2e092d200ed44d0d2005113baf2d08f54503091319c0181" +
      "0140d721d70a00f2e08ee2c8ca0058cf16c9ed5493f2c08de20010935bdb31e1d74cd0"

  // Wallet V4R2 compiled code BOC verified from org.ton:walletkit:0.0.6
  private const val WALLET_V4R2_CODE_BOC_BASE64 =
    "te6ccgECFAEAAtQAART/APSkE/S88sgLAQIBIAIDAgFIBAUE+PKDCNcYINMf0x/THwL4I7vyZO1E0NMf0x/T//QE0VFD" +
      "uvKhUVG68qIF+QFUEGT5EPKj+AAkpMjLH1JAyx9SMMv/UhD0AMntVPgPAdMHIcAAn2xRkyDXSpbTB9QC+wDoMOAhwAHj" +
      "ACHAAuMAAcADkTDjDQOkyMsfEssfy/8QERITAubQAdDTAyFxsJJfBOAi10nBIJJfBOAC0x8hghBwbHVnvSKCEGRzdHK9" +
      "sJJfBeAD+kAwIPpEAcjKB8v/ydDtRNCBAUDXIfQEMFyBAQj0Cm+hMbOSXwfgBdM/yCWCEHBsdWe6kjgw4w0DghBkc3Ry" +
      "upJfBuMNBgcCASAICQB4AfoA9AQw+CdvIjBQCqEhvvLgUIIQcGx1Z4MesXCAGFAEywUmzxZY+gIZ9ADLaRfLH1Jgyz8g" +
      "yYBA+wAGAIpQBIEBCPRZMO1E0IEBQNcgyAHPFvQAye1UAXKwjiOCEGRzdHKDHrFwgBhQBcsFUAPPFiP6AhPLassfyz/J" +
      "gED7AJJfA+ICASAKCwBZvSQrb2omhAgKBrkPoCGEcNQICEekk30pkQzmkD6f+YN4EoAbeBAUiYcVnzGEAgFYDA0AEbjJ" +
      "ftRNDXCx+AA9sp37UTQgQFA1yH0BDACyMoHy//J0AGBAQj0Cm+hMYAIBIA4PABmtznaiaEAga5Drhf/AABmvHfaiaEAQ" +
      "a5DrhY/AAG7SB/oA1NQi+QAFyMoHFcv/ydB3dIAYyMsFywIizxZQBfoCFMtrEszMyXP7AMhAFIEBCPRR8qcCAHCBAQjX" +
      "GPoA0z/IVCBHgQEI9FHyp4IQbm90ZXB0gBjIywXLAlAGzxZQBPoCFMtqEssfyz/Jc/sAAgBsgQEI1xj6ANM/MFIkgQEI" +
      "9Fnyp4IQZHN0cnB0gBjIywXLAlAFzxZQA/oCE8tqyx8Syz/Jc/sAAAr0AMntVA=="

  // Wallet V3R2 compiled code BOC verified against official TON contract source
  private const val WALLET_V3R2_CODE_BOC_BASE64 =
    "te6cckEBAQEAcQAA3v8AIN0gggFMl7ohggEznLqxn3Gw7UTQ0x/THzHXC//jBOCk8mCDCNcYINMf0x/TH/gjE7vyY+1E" +
      "0NMf0x/T/9FRMrryoVFEuvKiBPkBVBBV+RDyo/gAkyDXSpbTB9QC+wDo0QGkyMsfyx/L/8ntVBC9ba0="

  // Wallet V3R1 compiled code BOC verified against official TON contract source
  private const val WALLET_V3R1_CODE_BOC_BASE64 =
    "te6cckEBAQEAYgAAwP8AIN0gggFMl7qXMO1E0NcLH+Ck8mCDCNcYINMf0x/TH/gjE7vyY+1E0NMf0x/T/9FRMrryoVFE" +
      "uvKiBPkBVBBV+RDyo/gAkyDXSpbTB9QC+wDo0QGkyMsfyx/L/8ntVD++buA="

  private val v5r1CodeCell: Cell by lazy {
    BagOfCells(hexToBytes(WALLET_V5R1_CODE_BOC_HEX)).roots.first()
  }

  private val v4r2CodeCell: Cell by lazy {
    BagOfCells(Base64.getDecoder().decode(WALLET_V4R2_CODE_BOC_BASE64)).roots.first()
  }

  private val v3r2CodeCell: Cell by lazy {
    BagOfCells(Base64.getDecoder().decode(WALLET_V3R2_CODE_BOC_BASE64)).roots.first()
  }

  private val v3r1CodeCell: Cell by lazy {
    BagOfCells(Base64.getDecoder().decode(WALLET_V3R1_CODE_BOC_BASE64)).roots.first()
  }

  internal fun codeHashHex(version: WalletVersion): String {
    val cell =
      when (version) {
        WalletVersion.V5R1 -> v5r1CodeCell
        WalletVersion.V4R2 -> v4r2CodeCell
        WalletVersion.V3R2 -> v3r2CodeCell
        WalletVersion.V3R1 -> v3r1CodeCell
      }
    return bytesToHex(cell.hash().toByteArray())
  }

  fun deriveAddress(
    publicKey32: ByteArray,
    version: WalletVersion,
    network: Network,
    workchain: Int = 0,
  ): TonAddress =
    when (version) {
      WalletVersion.V5R1 -> deriveV5R1Address(publicKey32, network, workchain)
      WalletVersion.V4R2 -> deriveV4R2Address(publicKey32, network, workchain)
      WalletVersion.V3R2 -> deriveV3R2Address(publicKey32, network, workchain)
      WalletVersion.V3R1 -> deriveV3R1Address(publicKey32, network, workchain)
    }

  fun buildStateInit(
    publicKey32: ByteArray,
    version: WalletVersion,
    network: Network,
    workchain: Int = 0,
  ): StateInit {
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    return when (version) {
      WalletVersion.V5R1 -> {
        val dataCell =
          CellBuilder.createCell {
            storeBoolean(true) // signatureAllowed
            storeUInt(0, 32) // initial seqno
            storeUInt(network.v5r1WalletId, 32) // TEP-3 network-specific wallet_id
            storeBits(publicKey32, 256) // 256-bit Ed25519 public key
            storeBoolean(false) // empty extensions dictionary
          }
        StateInit(code = v5r1CodeCell, data = dataCell)
      }
      WalletVersion.V4R2 -> {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32) // initial seqno
            storeUInt(DEFAULT_LEGACY_WALLET_ID, 32)
            storeBits(publicKey32, 256)
            storeBoolean(false) // empty plugins dictionary
          }
        StateInit(code = v4r2CodeCell, data = dataCell)
      }
      WalletVersion.V3R2 -> {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32) // initial seqno
            storeUInt(DEFAULT_LEGACY_WALLET_ID + workchain, 32)
            storeBits(publicKey32, 256)
          }
        StateInit(code = v3r2CodeCell, data = dataCell)
      }
      WalletVersion.V3R1 -> {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32) // initial seqno
            storeUInt(DEFAULT_LEGACY_WALLET_ID + workchain, 32)
            storeBits(publicKey32, 256)
          }
        StateInit(code = v3r1CodeCell, data = dataCell)
      }
    }
  }

  fun deriveV5R1Address(
    publicKey32: ByteArray,
    network: Network,
    workchain: Int = 0,
  ): TonAddress {
    val stateInit = buildStateInit(publicKey32, WalletVersion.V5R1, network, workchain)
    val addrStd = stateInit.address(workchain)
    return TonAddress(raw = addrStd.toString(userFriendly = false), network = network)
  }

  fun deriveV4R2Address(
    publicKey32: ByteArray,
    network: Network,
    workchain: Int = 0,
    subwalletId: Long = DEFAULT_LEGACY_WALLET_ID,
  ): TonAddress {
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    val stateInit =
      if (subwalletId == DEFAULT_LEGACY_WALLET_ID) {
        buildStateInit(publicKey32, WalletVersion.V4R2, network, workchain)
      } else {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32)
            storeUInt(subwalletId, 32)
            storeBits(publicKey32, 256)
            storeBoolean(false)
          }
        StateInit(code = v4r2CodeCell, data = dataCell)
      }
    val addrStd = stateInit.address(workchain)
    return TonAddress(raw = addrStd.toString(userFriendly = false), network = network)
  }

  fun deriveV3R2Address(
    publicKey32: ByteArray,
    network: Network,
    workchain: Int = 0,
    walletId: Long = DEFAULT_LEGACY_WALLET_ID + workchain,
  ): TonAddress {
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    val stateInit =
      if (walletId == DEFAULT_LEGACY_WALLET_ID + workchain) {
        buildStateInit(publicKey32, WalletVersion.V3R2, network, workchain)
      } else {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32)
            storeUInt(walletId, 32)
            storeBits(publicKey32, 256)
          }
        StateInit(code = v3r2CodeCell, data = dataCell)
      }
    val addrStd = stateInit.address(workchain)
    return TonAddress(raw = addrStd.toString(userFriendly = false), network = network)
  }

  fun deriveV3R1Address(
    publicKey32: ByteArray,
    network: Network,
    workchain: Int = 0,
    walletId: Long = DEFAULT_LEGACY_WALLET_ID + workchain,
  ): TonAddress {
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    val stateInit =
      if (walletId == DEFAULT_LEGACY_WALLET_ID + workchain) {
        buildStateInit(publicKey32, WalletVersion.V3R1, network, workchain)
      } else {
        val dataCell =
          CellBuilder.createCell {
            storeUInt(0, 32)
            storeUInt(walletId, 32)
            storeBits(publicKey32, 256)
          }
        StateInit(code = v3r1CodeCell, data = dataCell)
      }
    val addrStd = stateInit.address(workchain)
    return TonAddress(raw = addrStd.toString(userFriendly = false), network = network)
  }

  fun discoverCandidateAccounts(
    publicKey32: ByteArray,
    scheme: MnemonicScheme,
    workchain: Int = 0,
  ): List<CandidateAccount> {
    val pubHex = bytesToHex(publicKey32)
    return listOf(
        WalletVersion.V5R1,
        WalletVersion.V4R2,
        WalletVersion.V3R2,
        WalletVersion.V3R1,
      )
      .map { version ->
        CandidateAccount(
          id = "${scheme.name}_${version.name}",
          scheme = scheme,
          version = version,
          publicKeyHex = pubHex,
          mainnetAddress = deriveAddress(publicKey32, version, Network.MAINNET, workchain),
          testnetAddress = deriveAddress(publicKey32, version, Network.TESTNET, workchain),
        )
      }
  }

  fun formatFriendly(address: TonAddress, bounceable: Boolean = false): String =
    address.toFriendly(bounceable = bounceable)

  fun parseAddress(input: String, expectedNetwork: Network): Result<TonAddress> =
    TonAddress.parse(input, expectedNetwork)

  fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString(separator = "") { "%02x".format(it) }
}

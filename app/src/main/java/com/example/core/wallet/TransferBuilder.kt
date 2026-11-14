package com.example.core.wallet

import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.WalletVersion
import java.math.BigInteger
import java.util.Base64
import org.ton.bigint.BigInt
import org.ton.block.AddrNone
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.Either
import org.ton.block.ExtInMsgInfo
import org.ton.block.Just
import org.ton.block.Maybe
import org.ton.block.Message
import org.ton.block.MessageRelaxed
import org.ton.block.MsgAddressInt
import org.ton.block.Nothing
import org.ton.block.StateInit
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.CellSlice
import org.ton.kotlin.cell.CellContext
import org.ton.kotlin.crypto.PrivateKeyEd25519
import org.ton.tlb.CellRef
import org.ton.tlb.TlbCodec

data class SignedExternalTransfer(
  val externalBocBase64: String,
  val externalBocBytes: ByteArray,
  val messageHashHex: String,
  val normalizedHashHex: String,
  val bodyHashHex: String,
  val seqno: Int,
  val validUntilEpochSec: Long,
  val bounce: Boolean,
  val needsStateInit: Boolean,
)

object TransferBuilder {
  const val DEFAULT_VALID_UNTIL_TTL_SECONDS = 120L
  const val DEFAULT_SEND_MODE = 3 // PAY_GAS_SEPARATELY (1) + IGNORE_ERRORS (2)
  const val MAX_COMMENT_UTF8_BYTES = 120
  const val LOCAL_FALLBACK_FEE_NANO = 15_000_000L // 0.015 GRAM conservative fallback
  const val LOCAL_JETTON_FALLBACK_FEE_NANO = 20_000_000L // 0.02 GRAM conservative fallback
  const val JETTON_TRANSFER_OPCODE = 0x0f8a7ea5L
  const val DEFAULT_JETTON_ATTACHED_GRAM_NANO = 55_000_000L // 0.055 GRAM attached gas; excess returned
  const val DEFAULT_JETTON_FORWARD_TON_NANO = 1L // 1 nanoton notification per TEP-74

  private const val V5_AUTH_SIGNED_EXTERNAL_OPCODE = 0x7369676eL
  private const val OUT_ACTION_SEND_MSG_TAG = 0x0ec3c86dL

  private val rawCellCodec: TlbCodec<Cell> =
    object : TlbCodec<Cell> {
      override fun storeTlb(cellBuilder: CellBuilder, value: Cell, context: CellContext) {
        cellBuilder.storeBits(value.bits)
        value.refs.forEach { ref -> cellBuilder.storeRef(ref) }
      }

      override fun loadTlb(cellSlice: CellSlice, context: CellContext): Cell {
        val remainingBits = cellSlice.bits.drop(cellSlice.bitsPosition).toBooleanArray()
        val remainingRefs = cellSlice.refs.drop(cellSlice.refsPosition)
        return CellBuilder.createCell {
          remainingBits.forEach { bit -> storeBit(bit) }
          remainingRefs.forEach { ref -> storeRef(ref) }
        }
      }
    }

  private val messageRelaxedCellCodec = MessageRelaxed.tlbCodec(rawCellCodec)

  fun buildCommentBodyCell(comment: String?): Cell {
    val trimmed = comment?.trim().orEmpty()
    if (trimmed.isEmpty()) return Cell.empty()
    val utf8Bytes = trimmed.toByteArray(Charsets.UTF_8)
    require(utf8Bytes.size <= MAX_COMMENT_UTF8_BYTES) {
      "Comment is too long (${utf8Bytes.size} bytes; max $MAX_COMMENT_UTF8_BYTES)"
    }
    return CellBuilder.createCell {
      storeUInt(0, 32) // standard TON text comment opcode
      storeBits(utf8Bytes, utf8Bytes.size * 8)
    }
  }

  /**
   * Builds a standard TEP-74 `transfer#0f8a7ea5` internal message body targeted at the sender's
   * Jetton Wallet smart contract.
   */
  fun buildJettonTransferBodyCell(
    jettonAmountUnits: BigInteger,
    destination: TonAddress,
    responseDestination: TonAddress,
    forwardTonNano: Long = DEFAULT_JETTON_FORWARD_TON_NANO,
    comment: String? = null,
    queryId: Long = 0L,
  ): Cell {
    require(jettonAmountUnits > BigInteger.ZERO) { "Jetton amount must be positive" }
    require(forwardTonNano >= 0L) { "Forward TON amount cannot be negative" }
    require(destination.network == responseDestination.network) {
      "Jetton transfer destination and response destination network mismatch"
    }
    require(queryId >= 0L) { "Query ID must be non-negative" }

    val forwardCommentCell = buildCommentBodyCell(comment)
    val jettonAmountCoins = Coins.ofNano(BigInt(jettonAmountUnits.toString()))
    val forwardCoins = Coins.ofNano(BigInt(forwardTonNano.toString()))
    val destAddr = AddrStd(destination.raw)
    val respAddr = AddrStd(responseDestination.raw)

    return CellBuilder.createCell {
      storeUInt(JETTON_TRANSFER_OPCODE, 32)
      storeUInt(queryId, 64)
      Coins.tlbCodec().storeTlb(this, jettonAmountCoins)
      MsgAddressInt.tlbCodec().storeTlb(this, destAddr)
      MsgAddressInt.tlbCodec().storeTlb(this, respAddr)
      storeBoolean(false) // custom_payload:(Maybe ^Cell) = nothing
      Coins.tlbCodec().storeTlb(this, forwardCoins)
      if (forwardCommentCell.isEmpty()) {
        storeBoolean(false) // forward_payload:(Either Cell ^Cell) inline empty
      } else {
        storeBoolean(true) // forward_payload stored as ^Cell reference
        storeRef(forwardCommentCell)
      }
    }
  }

  fun buildDummyTransferBocForEstimation(
    senderAddress: TonAddress,
    publicKey32: ByteArray,
    walletVersion: WalletVersion,
    destination: TonAddress,
    amount: Money,
    bounce: Boolean,
    seqno: Int,
    validUntilEpochSec: Long,
    needsStateInit: Boolean,
    comment: String? = null,
    sendMode: Int = DEFAULT_SEND_MODE,
  ): String {
    require(senderAddress.network == destination.network) {
      "Sender and destination network mismatch"
    }
    val payloadToSign =
      buildUnsignedWalletBody(
        walletVersion = walletVersion,
        network = senderAddress.network,
        internalMsgCell =
          buildInternalTransferCell(
            destination = destination,
            amount = amount,
            bounce = bounce,
            bodyCell = buildCommentBodyCell(comment),
          ),
        seqno = seqno,
        validUntilEpochSec = validUntilEpochSec,
        sendMode = sendMode,
      )
    val dummySignature = ByteArray(64)
    val signedBody = packSignedWalletBody(walletVersion, payloadToSign, dummySignature)
    val msgCell =
      packExternalMessageCell(
        senderAddress = senderAddress,
        publicKey32 = publicKey32,
        walletVersion = walletVersion,
        signedBody = signedBody,
        needsStateInit = needsStateInit,
      )
    val bocBytes = BagOfCells(msgCell).toByteArray()
    return Base64.getEncoder().encodeToString(bocBytes)
  }

  fun buildDummyJettonTransferBocForEstimation(
    senderAddress: TonAddress,
    senderJettonWallet: TonAddress,
    publicKey32: ByteArray,
    walletVersion: WalletVersion,
    destination: TonAddress,
    jettonAmount: Money,
    seqno: Int,
    validUntilEpochSec: Long,
    needsStateInit: Boolean,
    comment: String? = null,
    attachedGramNano: Long = DEFAULT_JETTON_ATTACHED_GRAM_NANO,
    sendMode: Int = DEFAULT_SEND_MODE,
  ): String {
    require(senderAddress.network == senderJettonWallet.network) {
      "Sender and Jetton wallet network mismatch"
    }
    require(senderAddress.network == destination.network) {
      "Sender and destination network mismatch"
    }
    val jettonBody =
      buildJettonTransferBodyCell(
        jettonAmountUnits = jettonAmount.units,
        destination = destination,
        responseDestination = senderAddress,
        comment = comment,
      )
    val internalMsgCell =
      buildInternalTransferCell(
        destination = senderJettonWallet,
        amount = Money.ofNano(attachedGramNano),
        bounce = true, // User's Jetton Wallet is an active contract
        bodyCell = jettonBody,
      )
    val payloadToSign =
      buildUnsignedWalletBody(
        walletVersion = walletVersion,
        network = senderAddress.network,
        internalMsgCell = internalMsgCell,
        seqno = seqno,
        validUntilEpochSec = validUntilEpochSec,
        sendMode = sendMode,
      )
    val signedBody = packSignedWalletBody(walletVersion, payloadToSign, ByteArray(64))
    val msgCell =
      packExternalMessageCell(
        senderAddress = senderAddress,
        publicKey32 = publicKey32,
        walletVersion = walletVersion,
        signedBody = signedBody,
        needsStateInit = needsStateInit,
      )
    return Base64.getEncoder().encodeToString(BagOfCells(msgCell).toByteArray())
  }

  fun buildSignedTransfer(
    seed32: ByteArray,
    publicKey32: ByteArray,
    senderAddress: TonAddress,
    walletVersion: WalletVersion,
    destination: TonAddress,
    amount: Money,
    bounce: Boolean,
    seqno: Int,
    validUntilEpochSec: Long,
    needsStateInit: Boolean,
    comment: String? = null,
    sendMode: Int = DEFAULT_SEND_MODE,
  ): SignedExternalTransfer {
    require(seed32.size == 32) { "Ed25519 seed must be 32 bytes" }
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    require(senderAddress.network == destination.network) {
      "Sender and destination network mismatch"
    }
    require(amount.decimals == GRAM_DECIMALS && amount.units > BigInteger.ZERO) {
      "Transfer amount must be positive GRAM nano units"
    }
    require(seqno >= 0) { "Seqno must be non-negative" }

    val unsignedBody =
      buildUnsignedWalletBody(
        walletVersion = walletVersion,
        network = senderAddress.network,
        internalMsgCell =
          buildInternalTransferCell(
            destination = destination,
            amount = amount,
            bounce = bounce,
            bodyCell = buildCommentBodyCell(comment),
          ),
        seqno = seqno,
        validUntilEpochSec = validUntilEpochSec,
        sendMode = sendMode,
      )
    return signAndPackExternalTransfer(
      seed32 = seed32,
      publicKey32 = publicKey32,
      senderAddress = senderAddress,
      walletVersion = walletVersion,
      unsignedBody = unsignedBody,
      seqno = seqno,
      validUntilEpochSec = validUntilEpochSec,
      bounce = bounce,
      needsStateInit = needsStateInit,
    )
  }

  fun buildSignedJettonTransfer(
    seed32: ByteArray,
    publicKey32: ByteArray,
    senderAddress: TonAddress,
    senderJettonWallet: TonAddress,
    walletVersion: WalletVersion,
    destination: TonAddress,
    jettonAmount: Money,
    seqno: Int,
    validUntilEpochSec: Long,
    needsStateInit: Boolean,
    comment: String? = null,
    attachedGramNano: Long = DEFAULT_JETTON_ATTACHED_GRAM_NANO,
    queryId: Long = 0L,
    sendMode: Int = DEFAULT_SEND_MODE,
  ): SignedExternalTransfer {
    require(seed32.size == 32) { "Ed25519 seed must be 32 bytes" }
    require(publicKey32.size == 32) { "Ed25519 public key must be 32 bytes" }
    require(senderAddress.network == senderJettonWallet.network) {
      "Sender and Jetton wallet network mismatch"
    }
    require(senderAddress.network == destination.network) {
      "Sender and destination network mismatch"
    }
    require(jettonAmount.units > BigInteger.ZERO) { "Jetton amount must be positive" }
    require(seqno >= 0) { "Seqno must be non-negative" }

    val jettonBody =
      buildJettonTransferBodyCell(
        jettonAmountUnits = jettonAmount.units,
        destination = destination,
        responseDestination = senderAddress,
        comment = comment,
        queryId = queryId,
      )
    val internalMsgCell =
      buildInternalTransferCell(
        destination = senderJettonWallet,
        amount = Money.ofNano(attachedGramNano),
        bounce = true,
        bodyCell = jettonBody,
      )
    val unsignedBody =
      buildUnsignedWalletBody(
        walletVersion = walletVersion,
        network = senderAddress.network,
        internalMsgCell = internalMsgCell,
        seqno = seqno,
        validUntilEpochSec = validUntilEpochSec,
        sendMode = sendMode,
      )
    return signAndPackExternalTransfer(
      seed32 = seed32,
      publicKey32 = publicKey32,
      senderAddress = senderAddress,
      walletVersion = walletVersion,
      unsignedBody = unsignedBody,
      seqno = seqno,
      validUntilEpochSec = validUntilEpochSec,
      bounce = true,
      needsStateInit = needsStateInit,
    )
  }

  private fun signAndPackExternalTransfer(
    seed32: ByteArray,
    publicKey32: ByteArray,
    senderAddress: TonAddress,
    walletVersion: WalletVersion,
    unsignedBody: Cell,
    seqno: Int,
    validUntilEpochSec: Long,
    bounce: Boolean,
    needsStateInit: Boolean,
  ): SignedExternalTransfer {
    val signingHash = unsignedBody.hash().toByteArray()
    val signature = PrivateKeyEd25519(seed32).signToByteArray(signingHash, 0, signingHash.size)
    val signedBody = packSignedWalletBody(walletVersion, unsignedBody, signature)

    val msgCell =
      packExternalMessageCell(
        senderAddress = senderAddress,
        publicKey32 = publicKey32,
        walletVersion = walletVersion,
        signedBody = signedBody,
        needsStateInit = needsStateInit,
      )
    val normalizedCell =
      packExternalMessageCell(
        senderAddress = senderAddress,
        publicKey32 = publicKey32,
        walletVersion = walletVersion,
        signedBody = signedBody,
        needsStateInit = false,
      )

    val bocBytes = BagOfCells(msgCell).toByteArray()
    val bocBase64 = Base64.getEncoder().encodeToString(bocBytes)
    return SignedExternalTransfer(
      externalBocBase64 = bocBase64,
      externalBocBytes = bocBytes,
      messageHashHex = AddressService.bytesToHex(msgCell.hash().toByteArray()),
      normalizedHashHex = AddressService.bytesToHex(normalizedCell.hash().toByteArray()),
      bodyHashHex = AddressService.bytesToHex(signedBody.hash().toByteArray()),
      seqno = seqno,
      validUntilEpochSec = validUntilEpochSec,
      bounce = bounce,
      needsStateInit = needsStateInit,
    )
  }

  private fun buildInternalTransferCell(
    destination: TonAddress,
    amount: Money,
    bounce: Boolean,
    bodyCell: Cell,
  ): Cell {
    val destAddrStd = AddrStd(destination.raw)
    val msgInfo =
      CommonMsgInfoRelaxed.IntMsgInfoRelaxed(
        dest = destAddrStd,
        bounce = bounce,
        value = Coins.ofNano(BigInt(amount.units.toString())),
      )
    val relaxedBody: Either<Cell, CellRef<Cell>> =
      if (bodyCell.isEmpty()) {
        Either.Left(bodyCell)
      } else {
        Either.Right(CellRef.valueOf(cell = bodyCell, codec = rawCellCodec))
      }
    val internalMessage =
      MessageRelaxed(
        info = msgInfo,
        init = Nothing(),
        body = relaxedBody,
      )
    return messageRelaxedCellCodec.createCell(internalMessage)
  }

  private fun buildUnsignedWalletBody(
    walletVersion: WalletVersion,
    network: Network,
    internalMsgCell: Cell,
    seqno: Int,
    validUntilEpochSec: Long,
    sendMode: Int,
  ): Cell {
    return when (walletVersion) {
      WalletVersion.V5R1 -> {
        val outActionSendMsg =
          CellBuilder.createCell {
            storeUInt(OUT_ACTION_SEND_MSG_TAG, 32)
            storeUInt(sendMode, 8)
            storeRef(internalMsgCell)
          }
        val outList =
          CellBuilder.createCell {
            storeRef(Cell.empty())
            storeSlice(outActionSendMsg.beginParse())
          }
        val actionsList =
          CellBuilder.createCell {
            storeBoolean(true) // maybe outActions present
            storeRef(outList)
            storeBoolean(false) // no extended actions
          }
        CellBuilder.createCell {
          storeUInt(V5_AUTH_SIGNED_EXTERNAL_OPCODE, 32)
          storeUInt(network.v5r1WalletId, 32)
          storeUInt(validUntilEpochSec, 32)
          storeUInt(seqno.toLong(), 32)
          storeSlice(actionsList.beginParse())
        }
      }
      WalletVersion.V4R2 -> {
        CellBuilder.createCell {
          storeUInt(AddressService.DEFAULT_LEGACY_WALLET_ID, 32)
          storeUInt(validUntilEpochSec, 32)
          storeUInt(seqno.toLong(), 32)
          storeUInt(0, 8) // op = simple transfer
          storeUInt(sendMode, 8)
          storeRef(internalMsgCell)
        }
      }
      WalletVersion.V3R2,
      WalletVersion.V3R1 -> {
        CellBuilder.createCell {
          storeUInt(AddressService.DEFAULT_LEGACY_WALLET_ID, 32)
          storeUInt(validUntilEpochSec, 32)
          storeUInt(seqno.toLong(), 32)
          storeUInt(sendMode, 8)
          storeRef(internalMsgCell)
        }
      }
    }
  }

  private fun packSignedWalletBody(
    walletVersion: WalletVersion,
    unsignedPayload: Cell,
    signature64: ByteArray,
  ): Cell {
    require(signature64.size == 64) { "Ed25519 signature must be 64 bytes" }
    return when (walletVersion) {
      // Wallet V5R1 places the 512-bit signature at the end of the signed cell
      WalletVersion.V5R1 ->
        CellBuilder.createCell {
          storeSlice(unsignedPayload.beginParse())
          storeBits(signature64, signature64.size * 8)
        }
      // Wallet V3/V4 place the 512-bit signature before the payload slice
      WalletVersion.V4R2,
      WalletVersion.V3R2,
      WalletVersion.V3R1 ->
        CellBuilder.createCell {
          storeBits(signature64, signature64.size * 8)
          storeSlice(unsignedPayload.beginParse())
        }
    }
  }

  private fun packExternalMessageCell(
    senderAddress: TonAddress,
    publicKey32: ByteArray,
    walletVersion: WalletVersion,
    signedBody: Cell,
    needsStateInit: Boolean,
  ): Cell {
    val senderStd = AddrStd(senderAddress.raw)
    val info = ExtInMsgInfo(src = AddrNone, dest = senderStd, importFee = Coins.ZERO)
    val init: Maybe<Either<StateInit, CellRef<StateInit>>> =
      if (needsStateInit) {
        val stateInit =
          AddressService.buildStateInit(
            publicKey32 = publicKey32,
            version = walletVersion,
            network = senderAddress.network,
            workchain = senderStd.workchainId,
          )
        Just(Either.Right(CellRef.valueOf(stateInit, StateInit.tlbCodec())))
      } else {
        Nothing()
      }
    val body: Either<Cell, CellRef<Cell>> =
      Either.Right(CellRef.valueOf(cell = signedBody, codec = rawCellCodec))
    val externalMessage = Message(info = info, init = init, body = body)
    return Message.tlbCodec(rawCellCodec).createCell(externalMessage)
  }
}

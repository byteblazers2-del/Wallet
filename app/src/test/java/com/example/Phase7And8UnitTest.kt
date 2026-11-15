package com.example

import com.example.core.model.JettonAllowList
import com.example.core.model.MnemonicPhrase
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.TxDirection
import com.example.core.model.USDT_DECIMALS
import com.example.core.model.WalletVersion
import com.example.core.network.FiatRates
import com.example.core.network.InvalidPayloadException
import com.example.core.network.OnChainMessageStatus
import com.example.core.network.PriceService
import com.example.core.network.TonApiClient
import com.example.core.network.TonCenterClient
import com.example.core.wallet.AddressService
import com.example.core.wallet.MnemonicService
import com.example.core.wallet.TransferBuilder
import java.math.BigDecimal
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.Message
import org.ton.block.MessageRelaxed
import org.ton.block.MsgAddressInt
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.CellSlice
import org.ton.kotlin.cell.CellContext
import org.ton.tlb.TlbCodec

class Phase7And8UnitTest {

  @Test
  fun jetton_allowlist_validates_master_address_network_and_decimals() {
    val mainnetSpec = JettonAllowList.usdtFor(Network.MAINNET)!!

    assertEquals(null, JettonAllowList.usdtFor(Network.TESTNET))
    assertTrue(JettonAllowList.allFor(Network.TESTNET).isEmpty())
    assertNotNull(JettonAllowList.findVerified(mainnetSpec.masterAddress, reportedDecimals = 6))
    assertNull(JettonAllowList.findVerified(mainnetSpec.masterAddress, reportedDecimals = 9))
    assertNull(
      JettonAllowList.findVerified(
        TonAddress("0:" + "aa".repeat(32), Network.MAINNET),
        reportedDecimals = 6,
      )
    )
    assertNull(
      JettonAllowList.findVerified(
        TonAddress(mainnetSpec.masterAddress.raw, Network.TESTNET),
        reportedDecimals = 6,
      )
    )
    assertNull(
      JettonAllowList.findVerified(
        TonAddress.parse("kQD0GKBM8ZbryVk2aESmzfU6b9b_8era_IkvBSELujFZPsyy", Network.TESTNET).getOrThrow(),
        reportedDecimals = 6,
      )
    )
  }

  @Test
  fun transfer_builder_builds_valid_tep74_jetton_transfer_body_and_signed_external_boc() {
    val words =
      listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
      ).map { it.toCharArray() }
    val phrase = MnemonicPhrase(words)
    val derived = MnemonicService.deriveMultichainKey(phrase)
    try {
      val sender = AddressService.deriveV5R1Address(derived.publicKey32, Network.MAINNET)
      val senderJettonWallet = TonAddress("0:" + "22".repeat(32), Network.MAINNET)
      val destOwner = TonAddress("0:" + "77".repeat(32), Network.MAINNET)
      val amount = Money.fromDecimalString("15.25", USDT_DECIMALS)

      val rawCodec =
        object : TlbCodec<Cell> {
          override fun storeTlb(cellBuilder: CellBuilder, value: Cell, context: CellContext) {
            cellBuilder.storeBits(value.bits)
            value.refs.forEach { cellBuilder.storeRef(it) }
          }

          override fun loadTlb(cellSlice: CellSlice, context: CellContext): Cell {
            val bits = cellSlice.bits.drop(cellSlice.bitsPosition).toBooleanArray()
            val refs = cellSlice.refs.drop(cellSlice.refsPosition)
            return CellBuilder.createCell {
              bits.forEach { storeBit(it) }
              refs.forEach { storeRef(it) }
            }
          }
        }

      for (version in listOf(WalletVersion.V5R1, WalletVersion.V4R2)) {
        val signed =
          TransferBuilder.buildSignedJettonTransfer(
            seed32 = derived.seed32,
            publicKey32 = derived.publicKey32,
            senderAddress = sender,
            senderJettonWallet = senderJettonWallet,
            walletVersion = version,
            destination = destOwner,
            jettonAmount = amount,
            seqno = 3,
            validUntilEpochSec = 1_800_000_120L,
            needsStateInit = false,
            comment = "USDT tea",
          )
        assertTrue(signed.externalBocBytes.isNotEmpty())

        val root = BagOfCells(signed.externalBocBytes).roots.first()
        val extMsg = Message.tlbCodec(rawCodec).loadTlb(root.beginParse())
        val bodyCell =
          when (val b = extMsg.body) {
            is org.ton.block.Either.Left -> b.value
            is org.ton.block.Either.Right -> b.value.value
          }
        val internalMsgCell =
          if (version == WalletVersion.V5R1) {
            bodyCell.beginParse().loadRef().refs.last()
          } else {
            bodyCell.beginParse().loadRef()
          }
        val parsedInternal = MessageRelaxed.tlbCodec(rawCodec).loadTlb(internalMsgCell.beginParse())
        val intInfo = parsedInternal.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
        assertTrue("Internal msg to jetton wallet must be bounceable", intInfo.bounce)
        assertEquals(
          TransferBuilder.DEFAULT_JETTON_ATTACHED_GRAM_NANO.toString(),
          intInfo.value.coins.amount.value.toString(10),
        )
        val jettonBody =
          when (val jb = parsedInternal.body) {
            is org.ton.block.Either.Left -> jb.value
            is org.ton.block.Either.Right -> jb.value.value
          }
        val slice = jettonBody.beginParse()
        assertEquals(TransferBuilder.JETTON_TRANSFER_OPCODE, slice.loadUInt(32).toLong())
        assertEquals(0L, slice.loadUInt(64).toLong())
        val decodedAmount = Coins.tlbCodec().loadTlb(slice).amount.value
        assertEquals("15250000", decodedAmount.toString(10))
        MsgAddressInt.tlbCodec().loadTlb(slice) // destination owner
        MsgAddressInt.tlbCodec().loadTlb(slice) // response destination
        assertEquals(false, slice.loadBit()) // null custom_payload
        val forwardTon = Coins.tlbCodec().loadTlb(slice).amount.value
        assertEquals("1", forwardTon.toString(10))
      }
    } finally {
      phrase.wipe()
      derived.wipe()
    }
  }

  @Test
  fun toncenter_and_tonapi_clients_parse_jetton_balance_history_and_last_tx_lt() = runTest {
    val owner = TonAddress("0:" + "11".repeat(32), Network.MAINNET)
    val jettonWallet = "0:" + "aa".repeat(32)
    val usdtMasterRaw = JettonAllowList.MAINNET_USDT.masterAddress.raw
    val counterpartyRaw = "0:" + "33".repeat(32)
    val commentCell =
      CellBuilder.createCell {
        storeUInt(0, 32)
        storeBytes("Order #77".toByteArray(Charsets.UTF_8))
      }
    val commentBocB64 = Base64.getEncoder().encodeToString(BagOfCells(commentCell).toByteArray())

    val tonCenter =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            val path = request().url.encodedPath
            when (path) {
              "/api/v3/accountStates" ->
                jsonResponse(
                  200,
                  """{"accounts":[{"address":"${owner.raw}","balance":"1000000000","status":"active","last_transaction_lt":"987654"}]}""",
                )
              "/api/v3/jetton/wallets" ->
                jsonResponse(
                  200,
                  """
                  {
                    "jetton_wallets": [
                      {
                        "address": "$jettonWallet",
                        "owner": "${owner.raw}",
                        "jetton": "$usdtMasterRaw",
                        "balance": "12500000"
                      }
                    ],
                    "metadata": {
                      "$usdtMasterRaw": {
                        "token_info": [{"valid": true, "type": "jetton_masters", "extra": {"decimals": "6"}}]
                      }
                    }
                  }
                  """.trimIndent(),
                )
              "/api/v3/jetton/transfers" ->
                jsonResponse(
                  200,
                  """
                  {
                    "jetton_transfers": [
                      {
                        "transaction_hash": "hash123",
                        "transaction_lt": "987654",
                        "transaction_now": 1710000300,
                        "transaction_aborted": false,
                        "amount": "2500000",
                        "source": "$counterpartyRaw",
                        "destination": "${owner.raw}",
                        "jetton_master": "$usdtMasterRaw",
                        "forward_payload": "$commentBocB64"
                      }
                    ]
                  }
                  """.trimIndent(),
                )
              else -> jsonResponse(404, """{}""")
            }
          }
      )

    val bal = tonCenter.fetchAccountBalance(owner)
    assertEquals("987654", bal.lastTxLt)

    val tcJetton = tonCenter.fetchJettonBalance(owner, JettonAllowList.MAINNET_USDT)
    assertEquals("12.50", tcJetton.balance.format())
    assertEquals(
      TonAddress.parse(jettonWallet, Network.MAINNET).getOrThrow(),
      tcJetton.jettonWalletAddress,
    )

    val tcHistory = tonCenter.fetchJettonHistory(owner, JettonAllowList.MAINNET_USDT, limit = 10)
    assertEquals(1, tcHistory.transfers.size)
    assertEquals(TxDirection.IN, tcHistory.transfers[0].direction)
    assertEquals("2.50", tcHistory.transfers[0].amount.format())
    assertEquals("Order #77", tcHistory.transfers[0].comment)
    assertEquals(OnChainMessageStatus.CONFIRMED, tcHistory.transfers[0].status)

    // Wrong decimals from server must trigger InvalidPayloadException
    val spoofDecimalsCenter =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            jsonResponse(
              200,
              """
              {
                "jetton_wallets": [{"address":"$jettonWallet","owner":"${owner.raw}","jetton":"$usdtMasterRaw","balance":"12500000"}],
                "metadata": {"$usdtMasterRaw":{"token_info":[{"extra":{"decimals":"9"}}]}}
              }
              """.trimIndent(),
            )
          }
      )
    val badDecimalsErr =
      runCatching { spoofDecimalsCenter.fetchJettonBalance(owner, JettonAllowList.MAINNET_USDT) }
        .exceptionOrNull()
    assertTrue(badDecimalsErr is InvalidPayloadException)

    // TonAPI jetton balance + history (filters is_scam and unverified masters)
    val tonApi =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            val path = request().url.encodedPath
            when {
              path.endsWith("/jettons") ->
                jsonResponse(
                  200,
                  """
                  {
                    "balances": [
                      {
                        "balance": "8400000",
                        "wallet_address": {"address": "$jettonWallet"},
                        "jetton": {"address": "$usdtMasterRaw", "symbol": "USDt", "decimals": 6}
                      }
                    ]
                  }
                  """.trimIndent(),
                )
              path.endsWith("/jettons/history") ->
                jsonResponse(
                  200,
                  """
                  {
                    "events": [
                      {
                        "event_id": "evt1",
                        "lt": 888001,
                        "timestamp": 1710000400,
                        "in_progress": false,
                        "is_scam": false,
                        "actions": [
                          {
                            "type": "JettonTransfer",
                            "status": "ok",
                            "JettonTransfer": {
                              "sender": {"address": "${owner.raw}"},
                              "recipient": {"address": "$counterpartyRaw"},
                              "amount": "3100000",
                              "comment": "Sent USDT",
                              "jetton": {"address": "$usdtMasterRaw", "decimals": 6}
                            }
                          }
                        ]
                      },
                      {
                        "event_id": "evt2",
                        "lt": 888002,
                        "timestamp": 1710000405,
                        "in_progress": false,
                        "is_scam": true,
                        "actions": [
                          {
                            "type": "JettonTransfer",
                            "status": "ok",
                            "JettonTransfer": {
                              "sender": {"address": "$counterpartyRaw"},
                              "recipient": {"address": "${owner.raw}"},
                              "amount": "999000000",
                              "jetton": {"address": "$usdtMasterRaw", "decimals": 6}
                            }
                          }
                        ]
                      }
                    ]
                  }
                  """.trimIndent(),
                )
              else -> jsonResponse(404, """{}""")
            }
          }
      )

    val taJetton = tonApi.fetchJettonBalance(owner, JettonAllowList.MAINNET_USDT)
    assertEquals("8.40", taJetton.balance.format())

    val taHistory = tonApi.fetchJettonHistory(owner, JettonAllowList.MAINNET_USDT, limit = 10)
    assertEquals(1, taHistory.transfers.size)
    assertEquals(TxDirection.OUT, taHistory.transfers[0].direction)
    assertEquals("3.10", taHistory.transfers[0].amount.format())
    assertEquals("Sent USDT", taHistory.transfers[0].comment)
  }

  @Test
  fun price_service_parses_tonapi_and_coingecko_and_caches_with_ttl() = runTest {
    var nowMs = 100_000L
    val calls = AtomicInteger(0)
    val service =
      PriceService(
        okHttpClient =
          mockHttpClient {
            calls.incrementAndGet()
            val url = request().url.toString()
            when {
              url.contains("tonapi") && calls.get() == 1 ->
                jsonResponse(
                  200,
                  """{"rates":{"TON":{"prices":{"USD":5.40}},"${JettonAllowList.MAINNET_USDT_MASTER}":{"prices":{"USD":1.00}}}}""",
                )
              url.contains("tonapi") -> jsonResponse(503, """{"error":"unavailable"}""")
              url.contains("coingecko") ->
                jsonResponse(200, """{"the-open-network":{"usd":5.10},"tether":{"usd":1.00}}""")
              else -> jsonResponse(404, """{}""")
            }
          },
        clockMs = { nowMs },
        cacheTtlMs = 90_000L,
      )

    val first = service.refreshRatesIfNeeded()
    assertEquals("TonAPI", first.sourceName)
    assertEquals(BigDecimal("5.40"), first.gramUsdPrice)

    // Within TTL -> returns cached without network hit
    val cached = service.refreshRatesIfNeeded()
    assertEquals(1, calls.get())
    assertEquals(
      "$15.80",
      cached.formatUsdValue(
        gramAmount = Money.ofNano(2_000_000_000L),
        usdtAmount = Money.fromDecimalString("5.00", USDT_DECIMALS),
      ),
    )

    // After TTL and TonAPI 503 -> falls back to CoinGecko
    nowMs += 95_000L
    val fallback = service.refreshRatesIfNeeded()
    assertEquals("CoinGecko", fallback.sourceName)
    assertEquals(BigDecimal("5.10"), fallback.gramUsdPrice)
    assertEquals(
      "$2.55",
      FiatRates(gramUsdPrice = BigDecimal("5.10"), usdtUsdPrice = BigDecimal.ONE)
        .formatUsdValue(gramAmount = Money.ofNano(500_000_000L)),
    )
  }

  private fun mockHttpClient(handler: Interceptor.Chain.() -> Response): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain -> chain.handler() })
      .build()

  private fun Interceptor.Chain.jsonResponse(code: Int, body: String): Response =
    Response.Builder()
      .request(request())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("HTTP $code")
      .body(body.toResponseBody("application/json".toMediaType()))
      .build()
}

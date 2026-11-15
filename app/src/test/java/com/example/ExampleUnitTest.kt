package com.example

import com.example.core.model.AccountOnChainStatus
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.MnemonicPhrase
import com.example.core.model.MnemonicScheme
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.TonAddress
import com.example.core.model.TxDirection
import com.example.core.model.WalletVersion
import com.example.core.network.AllProvidersFailedException
import com.example.core.network.InvalidPayloadException
import com.example.core.network.NetworkEndpoints
import com.example.core.network.OnChainMessageStatus
import com.example.core.network.ProviderRateLimitConfig
import com.example.core.network.ProviderRateLimiter
import com.example.core.network.ProviderRouter
import com.example.core.network.RejectedTransactionException
import com.example.core.network.TonApiClient
import com.example.core.network.TonCenterClient
import com.example.core.network.TonCommentDecoder
import com.example.core.wallet.AddressService
import com.example.core.wallet.Bip39Wordlist
import com.example.core.wallet.MnemonicService
import com.example.core.wallet.TonTransferUri
import com.example.core.wallet.TransferBuilder
import java.util.Base64
import com.example.ui.components.encodeQrMatrix
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.ExtInMsgInfo
import org.ton.block.Just
import org.ton.block.Message
import org.ton.block.MessageRelaxed
import org.ton.block.Nothing
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.CellSlice
import org.ton.kotlin.cell.CellContext
import org.ton.kotlin.crypto.PrivateKeyEd25519
import org.ton.tlb.CellRef
import org.ton.tlb.TlbCodec
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun network_separation_holds() {
    assertNotEquals(Network.MAINNET, Network.TESTNET)
    assertNotEquals(Network.MAINNET.v5r1WalletId, Network.TESTNET.v5r1WalletId)
    assertEquals(0x7FFFFF11L, Network.MAINNET.v5r1WalletId)
    assertEquals(0x7FFFFFFDL, Network.TESTNET.v5r1WalletId)
  }

  @Test
  fun address_resolution_and_wrong_network_rejection() {
    val rawHex = "0:0000000000000000000000000000000000000000000000000000000000000000"
    val testnetFriendly = TonAddress(rawHex, Network.TESTNET).toFriendly(bounceable = false)
    val mainnetFriendly = TonAddress(rawHex, Network.MAINNET).toFriendly(bounceable = false)
    assertTrue(testnetFriendly.startsWith("0Q"))
    assertTrue(mainnetFriendly.startsWith("UQ"))

    val parsedMain = TonAddress.parse(mainnetFriendly, Network.MAINNET).getOrThrow()
    assertEquals(mainnetFriendly, parsedMain.toFriendly(bounceable = false))
    assertEquals(rawHex, parsedMain.raw)

    assertTrue(TonAddress.parse(testnetFriendly, Network.MAINNET).isFailure)
    assertTrue(TonAddress.parse(mainnetFriendly, Network.TESTNET).isFailure)
    assertTrue(TonAddress.parse("${testnetFriendly.dropLast(1)}A", Network.TESTNET).isFailure)
  }

  @Test
  fun bip39_wordlist_and_mnemonic_generation_valid() {
    assertEquals(2048, Bip39Wordlist.WORDS.size)
    assertEquals("abandon", Bip39Wordlist.WORDS.first())
    assertEquals("zoo", Bip39Wordlist.WORDS.last())

    val phrase = MnemonicService.generate12WordMnemonic()
    try {
      assertEquals(12, phrase.wordCount)
      assertTrue(MnemonicService.validateBip39Mnemonic(phrase))
    } finally {
      phrase.wipe()
    }

    val invalidChecksum =
      MnemonicPhrase(List(12) { "abandon".toCharArray() })
    assertFalse(MnemonicService.validateBip39Mnemonic(invalidChecksum))
    invalidChecksum.wipe()
  }

  @Test
  fun slip0010_known_vector_derives_distinct_mainnet_and_testnet_v5r1_addresses() {
    val words =
      listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
      ).map { it.toCharArray() }
    val phrase = MnemonicPhrase(words)
    val derived1 = MnemonicService.deriveMultichainKey(phrase)
    val derived2 = MnemonicService.deriveMultichainKey(phrase)
    try {
      assertArrayEquals(derived1.seed32, derived2.seed32)
      assertArrayEquals(derived1.publicKey32, derived2.publicKey32)

      val mainnetAddr = AddressService.deriveV5R1Address(derived1.publicKey32, Network.MAINNET)
      val testnetAddr = AddressService.deriveV5R1Address(derived1.publicKey32, Network.TESTNET)

      // Both friendly and raw V5R1 addresses must differ for Mainnet (0x7FFFFF11) vs Testnet (0x7FFFFFFD)
      assertNotEquals(mainnetAddr.raw, testnetAddr.raw)
      val mainnetFriendly = AddressService.formatFriendly(mainnetAddr)
      val testnetFriendly = AddressService.formatFriendly(testnetAddr)
      assertTrue(mainnetFriendly.startsWith("UQ"))
      assertTrue(testnetFriendly.startsWith("0Q"))
      assertNotEquals(mainnetFriendly, testnetFriendly)

      // Round-trip validation
      assertEquals(
        mainnetFriendly,
        AddressService.parseAddress(mainnetFriendly, Network.MAINNET).getOrThrow().toFriendly(),
      )
      assertEquals(
        testnetFriendly,
        AddressService.parseAddress(testnetFriendly, Network.TESTNET).getOrThrow().toFriendly(),
      )
    } finally {
      phrase.wipe()
      derived1.wipe()
      derived2.wipe()
    }
  }

  @Test
  fun backup_challenge_verification_handles_match_and_mismatch() {
    val phrase = MnemonicService.generate12WordMnemonic()
    try {
      val challenge = MnemonicService.createBackupChallenge(wordCount = 12, sampleCount = 3)
      assertEquals(3, challenge.indicesOneBased.distinct().size)
      assertTrue(challenge.indicesOneBased.all { it in 1..12 })

      val correctAnswers =
        challenge.indicesOneBased.associateWith { idx -> phrase.wordAt(idx - 1) }
      assertTrue(MnemonicService.verifyBackupChallenge(phrase, challenge, correctAnswers))
      correctAnswers.values.forEach { it.fill('\u0000') }

      val wrongAnswers =
        challenge.indicesOneBased.associateWith { "invalidword".toCharArray() }
      assertFalse(MnemonicService.verifyBackupChallenge(phrase, challenge, wrongAnswers))
      wrongAnswers.values.forEach { it.fill('\u0000') }
    } finally {
      phrase.wipe()
    }
  }

  @Test
  fun qr_encoding_produces_valid_matrix() {
    val matrix = encodeQrMatrix("0QCSES0TZYqcVkgoguhIb8iMEo4cvaEwmIrU5qbQgnN8fo2A")
    assertTrue(matrix.size >= 21)
    assertEquals(matrix.size * matrix.size, matrix.modules.size)
    assertTrue(matrix.modules.any { it })
  }

  @Test
  fun wallet_contract_code_hashes_match_official_ton_specs() {
    assertEquals(
      "b61041a58a7980b946e8fb9e198e3c904d24799ffa36574ea4251c41a566f581",
      AddressService.codeHashHex(WalletVersion.V3R1),
    )
    assertEquals(
      "84dafa449f98a6987789ba232358072bc0f76dc4524002a5d0918b9a75d2d599",
      AddressService.codeHashHex(WalletVersion.V3R2),
    )
    assertEquals(
      "feb5ff6820e2ff0d9483e7e0d62c817d846789fb4ae580c878866d959dabd5c0",
      AddressService.codeHashHex(WalletVersion.V4R2),
    )
  }

  @Test
  fun ton_24_word_mnemonic_validation_and_derivation_matches_sdk() {
    val sdkMnemonic = org.ton.kotlin.crypto.mnemonic.Mnemonic.generate()
    val phraseChars = sdkMnemonic.words.joinToString(" ").toCharArray()
    val phrase = MnemonicService.parseSpaceSeparatedChars(phraseChars)
    phraseChars.fill('\u0000')
    try {
      assertEquals(24, phrase.wordCount)
      assertTrue(MnemonicService.validateTon24Mnemonic(phrase))
      val schemes = MnemonicService.detectValidSchemes(phrase)
      assertTrue(schemes.contains(MnemonicScheme.TON_LEGACY))

      val derived = MnemonicService.deriveTon24Key(phrase)
      try {
        val sdkSeed32 = sdkMnemonic.toSeed().copyOf(32)
        val sdkKey = org.ton.kotlin.crypto.PrivateKeyEd25519(sdkSeed32)
        assertArrayEquals(sdkSeed32, derived.seed32)
        assertArrayEquals(sdkKey.publicKey().key.toByteArray(), derived.publicKey32)

        val candidates = AddressService.discoverCandidateAccounts(derived.publicKey32, MnemonicScheme.TON_LEGACY)
        assertEquals(4, candidates.size)
        assertEquals(
          setOf(WalletVersion.V5R1, WalletVersion.V4R2, WalletVersion.V3R2, WalletVersion.V3R1),
          candidates.map { it.version }.toSet(),
        )
        assertEquals(4, candidates.map { it.mainnetAddress.raw }.distinct().size)
      } finally {
        derived.wipe()
      }
    } finally {
      phrase.wipe()
    }
  }

  @Test
  fun bip39_24_word_multichain_derives_all_contract_versions() {
    val words24 =
      ("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art")
        .toCharArray()
    val phrase = MnemonicService.parseSpaceSeparatedChars(words24)
    words24.fill('\u0000')
    try {
      assertEquals(24, phrase.wordCount)
      assertTrue(MnemonicService.validateBip39Mnemonic(phrase))
      val schemes = MnemonicService.detectValidSchemes(phrase)
      assertTrue(schemes.contains(MnemonicScheme.MULTICHAIN))

      val derived = MnemonicService.deriveMultichainKey(phrase)
      try {
        val candidates = AddressService.discoverCandidateAccounts(derived.publicKey32, MnemonicScheme.MULTICHAIN)
        assertEquals(4, candidates.size)
        assertEquals(4, candidates.map { it.mainnetAddress.toFriendly(false) }.distinct().size)
        assertEquals(4, candidates.map { it.testnetAddress.toFriendly(false) }.distinct().size)
      } finally {
        derived.wipe()
      }
    } finally {
      phrase.wipe()
    }
  }

  @Test
  fun ambiguous_24_word_mnemonic_detects_both_schemes_without_auto_picking() {
    val ambiguousWords =
      ("bunker present derive member useless upset corn measure obey void problem desk " +
        "crack jelly large caught vendor mean purpose library secret deliver patient frame")
        .toCharArray()
    val phrase = MnemonicService.parseSpaceSeparatedChars(ambiguousWords)
    ambiguousWords.fill('\u0000')
    try {
      assertEquals(24, phrase.wordCount)
      assertTrue(MnemonicService.validateTon24Mnemonic(phrase))
      assertTrue(MnemonicService.validateBip39Mnemonic(phrase))

      val schemes = MnemonicService.detectValidSchemes(phrase)
      assertEquals(listOf(MnemonicScheme.TON_LEGACY, MnemonicScheme.MULTICHAIN), schemes)

      val tonKey = MnemonicService.deriveKeyForScheme(phrase, MnemonicScheme.TON_LEGACY)
      val multiKey = MnemonicService.deriveKeyForScheme(phrase, MnemonicScheme.MULTICHAIN)
      try {
        assertFalse(tonKey.publicKey32.contentEquals(multiKey.publicKey32))
        val allCandidates =
          schemes.flatMap { scheme ->
            val key = MnemonicService.deriveKeyForScheme(phrase, scheme)
            try {
              AddressService.discoverCandidateAccounts(key.publicKey32, scheme)
            } finally {
              key.wipe()
            }
          }
        assertEquals(8, allCandidates.size)
        assertEquals(8, allCandidates.map { it.mainnetAddress.raw }.distinct().size)
      } finally {
        tonKey.wipe()
        multiKey.wipe()
      }
    } finally {
      phrase.wipe()
    }
  }

  @Test
  fun invalid_checksums_and_bad_word_counts_rejected() {
    val bad12 = MnemonicPhrase(List(12) { "abandon".toCharArray() })
    assertTrue(MnemonicService.detectValidSchemes(bad12).isEmpty())
    bad12.wipe()

    // 24 identical "about" words fail BIP-39 checksum and TON seed version check
    val bad24 = MnemonicPhrase(List(24) { "about".toCharArray() })
    assertFalse(MnemonicService.validateBip39Mnemonic(bad24))
    assertFalse(MnemonicService.validateTon24Mnemonic(bad24))
    assertTrue(MnemonicService.detectValidSchemes(bad24).isEmpty())
    bad24.wipe()

    val badCount = MnemonicPhrase(List(15) { "abandon".toCharArray() })
    assertTrue(MnemonicService.detectValidSchemes(badCount).isEmpty())
    badCount.wipe()

    val unknownWord = MnemonicPhrase(List(11) { "abandon".toCharArray() } + listOf("notaword".toCharArray()))
    assertTrue(MnemonicService.detectValidSchemes(unknownWord).isEmpty())
    unknownWord.wipe()
  }

  @Test
  fun money_formatting_and_validation_preserve_exact_biginteger_precision() {
    assertEquals("0.00", Money.zero().format())
    assertEquals("1.00", Money.ofNano(1_000_000_000L).format())
    assertEquals("1.50", Money.ofNano(1_500_000_000L).format())
    assertEquals("0.000000001", Money.ofNano(1L).format())
    assertEquals("1.23456789", Money.ofNano(1_234_567_890L).format())

    // Exceeds IEEE-754 Double 53-bit precision — must remain exact
    val huge = Money.parseUnits("1234567890123456789012345", GRAM_DECIMALS)
    assertEquals("1234567890123456.789012345", huge.format())

    // Jetton decimals != 9 (e.g., USDT 6 decimals)
    val usdt = Money.fromDecimalString("42.5", decimals = 6)
    assertEquals(BigInteger.valueOf(42_500_000L), usdt.units)
    assertEquals("42.50", usdt.format())

    // Reject negative, malformed, and over-precision amounts
    assertThrowsArg { Money(BigInteger.valueOf(-1L), GRAM_DECIMALS) }
    assertThrowsArg { Money.parseUnits("-500", GRAM_DECIMALS) }
    assertThrowsArg { Money.parseUnits("1.5e3", GRAM_DECIMALS) }
    assertThrowsArg { Money.fromDecimalString("0.0000000001", GRAM_DECIMALS) }
    assertThrowsArg { Money.fromDecimalString("-1.0", GRAM_DECIMALS) }
  }

  @Test
  fun tonapi_and_toncenter_clients_validate_payloads_and_reject_mismatches() = runTest {
    val testAddr = TonAddress("0:" + "11".repeat(32), Network.TESTNET)
    val mainnetFriendly = TonAddress(testAddr.raw, Network.MAINNET).toFriendly(bounceable = false)

    val tonApiOk =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            jsonResponse(
              code = 200,
              body = """{"address":"${testAddr.raw}","balance":10728693628195,"status":"active"}""",
            )
          },
        clockMs = { 1_000L },
      )
    val parsedTonApi = tonApiOk.fetchAccountBalance(testAddr)
    assertEquals(BigInteger("10728693628195"), parsedTonApi.balance.units)
    assertEquals(AccountOnChainStatus.ACTIVE, parsedTonApi.status)
    assertEquals("TonAPI", parsedTonApi.providerName)

    val tonApiWrongNet =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            jsonResponse(
              code = 200,
              body = """{"address":"$mainnetFriendly","balance":100,"status":"active"}""",
            )
          }
      )
    val mismatchErr = runCatching { tonApiWrongNet.fetchAccountBalance(testAddr) }.exceptionOrNull()
    assertTrue(mismatchErr is InvalidPayloadException)

    var capturedTcUrl = ""
    val tonCenterOk =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            capturedTcUrl = request().url.toString()
            jsonResponse(
              code = 200,
              body = """{"accounts":[{"address":"${testAddr.raw}","balance":"881888727694","status":"uninit"}]}""",
            )
          },
        clockMs = { 2_000L },
      )
    val parsedCenter = tonCenterOk.fetchAccountBalance(testAddr)
    assertTrue(capturedTcUrl.contains("/api/v3/accountStates"))
    assertEquals(BigInteger("881888727694"), parsedCenter.balance.units)
    assertEquals(AccountOnChainStatus.UNINIT, parsedCenter.status)

    val tonCenterEmpty =
      TonCenterClient(
        okHttpClient = mockHttpClient { jsonResponse(code = 200, body = """{"accounts":[]}""") },
        clockMs = { 2_000L },
      )
    val parsedEmpty = tonCenterEmpty.fetchAccountBalance(testAddr)
    assertEquals(AccountOnChainStatus.NONEXIST, parsedEmpty.status)
    assertEquals(BigInteger.ZERO, parsedEmpty.balance.units)

    val tonCenterNegative =
      TonCenterClient(
        okHttpClient =
          mockHttpClient { jsonResponse(code = 200, body = """{"accounts":[{"balance":"-5","status":"uninit"}]}""") }
      )
    assertTrue(
      runCatching { tonCenterNegative.fetchAccountBalance(testAddr) }.exceptionOrNull()
        is InvalidPayloadException
    )
  }

  @Test
  fun provider_router_falls_back_and_backs_off_on_429_500_timeouts_and_garbage_json() = runTest {
    val testAddr = TonAddress("0:" + "22".repeat(32), Network.TESTNET)
    var nowMs = 10_000L
    val recordedSleeps = mutableListOf<Long>()
    val limiter =
      ProviderRateLimiter(
        config =
          ProviderRateLimitConfig(
            minIntervalMs = 0L,
            baseBackoffMs = 100L,
            maxBackoffMs = 5_000L,
            failureThresholdToOpenCircuit = 2,
            openCircuitBaseMs = 2_000L,
          ),
        clockMs = { nowMs },
        sleeper = { ms ->
          recordedSleeps.add(ms)
          nowMs += ms
        },
        jitterProvider = { 0.5 }, // multiplier 1.0 -> deterministic backoff
      )

    val primaryCalls = AtomicInteger(0)
    val secondaryCalls = AtomicInteger(0)

    // Scenario 1: Primary returns HTTP 429 -> backs off -> falls back to secondary -> succeeds
    val primary429 =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            primaryCalls.incrementAndGet()
            jsonResponse(
              code = 429,
              body = """{"error":"rate limit"}""",
              headers = mapOf("Retry-After" to "1"),
            )
          },
        endpoints = NetworkEndpoints("https://primary.test", "https://primary.test"),
        clockMs = { nowMs },
      )
    val secondaryOk =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            secondaryCalls.incrementAndGet()
            jsonResponse(code = 200, body = """{"accounts":[{"balance":"5000000000","status":"active"}]}""")
          },
        endpoints = NetworkEndpoints("https://secondary.test", "https://secondary.test"),
        clockMs = { nowMs },
      )

    val router1 = ProviderRouter(providers = listOf(primary429, secondaryOk), rateLimiter = limiter)
    val firstResult = router1.fetchAccountBalance(testAddr).getOrThrow()
    assertEquals(BigInteger("5000000000"), firstResult.balance.units)
    assertEquals("TON Center", firstResult.providerName)
    assertEquals(1, primaryCalls.get())
    assertEquals(1, secondaryCalls.get())
    assertTrue(recordedSleeps.isNotEmpty())
    assertTrue(recordedSleeps.first() >= 1_000L) // respected Retry-After: 1s

    // Scenario 2: While primary circuit is open, router skips primary immediately
    assertTrue(limiter.isCircuitOpen("TonAPI"))
    val secondResult = router1.fetchAccountBalance(testAddr).getOrThrow()
    assertEquals("TON Center", secondResult.providerName)
    assertEquals(1, primaryCalls.get()) // not called while circuit is open
    assertEquals(2, secondaryCalls.get())

    // Scenario 3: Primary returns HTTP 500, secondary throws SocketTimeoutException -> clean failure, no crash
    val failingLimiter =
      ProviderRateLimiter(
        config = ProviderRateLimitConfig(minIntervalMs = 0L, baseBackoffMs = 50L),
        clockMs = { nowMs },
        sleeper = { nowMs += it },
        jitterProvider = { 0.5 },
      )
    val primary500 =
      TonApiClient(
        okHttpClient = mockHttpClient { jsonResponse(code = 500, body = "internal error") },
        clockMs = { nowMs },
      )
    val secondaryTimeout =
      TonCenterClient(
        okHttpClient = mockHttpClient { throw SocketTimeoutException("read timed out") },
        clockMs = { nowMs },
      )
    val routerAllFail =
      ProviderRouter(providers = listOf(primary500, secondaryTimeout), rateLimiter = failingLimiter)
    val failureResult = routerAllFail.fetchAccountBalance(testAddr)
    assertTrue(failureResult.isFailure)
    val combinedErr = failureResult.exceptionOrNull() as AllProvidersFailedException
    assertEquals(2, combinedErr.failures.size)

    // Scenario 4: Primary returns garbage JSON -> falls back to secondary and succeeds
    val garbageLimiter =
      ProviderRateLimiter(
        config = ProviderRateLimitConfig(minIntervalMs = 0L, baseBackoffMs = 25L),
        clockMs = { nowMs },
        sleeper = { nowMs += it },
        jitterProvider = { 0.5 },
      )
    val primaryGarbage =
      TonApiClient(
        okHttpClient = mockHttpClient { jsonResponse(code = 200, body = "{not-valid-json") },
        clockMs = { nowMs },
      )
    val routerGarbage =
      ProviderRouter(providers = listOf(primaryGarbage, secondaryOk), rateLimiter = garbageLimiter)
    val garbageFallback = routerGarbage.fetchAccountBalance(testAddr).getOrThrow()
    assertEquals("TON Center", garbageFallback.providerName)
  }

  private fun assertThrowsArg(block: () -> Unit) {
    val thrown = runCatching(block).exceptionOrNull()
    assertTrue("Expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
  }

  private fun mockHttpClient(handler: Interceptor.Chain.() -> Response): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain -> chain.handler() })
      .build()

  private fun Interceptor.Chain.jsonResponse(
    code: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): Response {
    val builder =
      Response.Builder()
        .request(request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("HTTP $code")
        .body(body.toResponseBody("application/json".toMediaType()))
    headers.forEach { (k, v) -> builder.header(k, v) }
    return builder.build()
  }

  @Test
  fun address_parsing_validates_raw_friendly_bounceable_flags_network_and_checksum() {
    val rawZero = "0:" + "AB".repeat(32)
    val rawMaster = "-1:" + "CD".repeat(32)

    val mainnetAddr = TonAddress(rawZero, Network.MAINNET)
    val mainnetBounce = mainnetAddr.toFriendly(bounceable = true)
    val mainnetNonBounce = mainnetAddr.toFriendly(bounceable = false)
    assertTrue(mainnetBounce.startsWith("EQ"))
    assertTrue(mainnetNonBounce.startsWith("UQ"))

    val parsedMainBounce = TonAddress.parseDetailed(mainnetBounce, Network.MAINNET).getOrThrow()
    assertFalse(parsedMainBounce.isRaw)
    assertTrue(parsedMainBounce.isBounceable)
    assertFalse(parsedMainBounce.isTestnetOnly)
    assertEquals(rawZero, parsedMainBounce.address.raw)

    val parsedMainNonBounce = TonAddress.parseDetailed(mainnetNonBounce, Network.MAINNET).getOrThrow()
    assertFalse(parsedMainNonBounce.isRaw)
    assertFalse(parsedMainNonBounce.isBounceable)

    val testnetAddr = TonAddress(rawZero, Network.TESTNET)
    val testnetBounce = testnetAddr.toFriendly(bounceable = true)
    val testnetNonBounce = testnetAddr.toFriendly(bounceable = false)
    assertTrue(testnetBounce.startsWith("kQ"))
    assertTrue(testnetNonBounce.startsWith("0Q"))

    val parsedTestBounce = TonAddress.parseDetailed(testnetBounce, Network.TESTNET).getOrThrow()
    assertTrue(parsedTestBounce.isBounceable)
    assertTrue(parsedTestBounce.isTestnetOnly)

    val parsedRawMaster = TonAddress.parseDetailed(rawMaster, Network.MAINNET).getOrThrow()
    assertTrue(parsedRawMaster.isRaw)
    assertEquals(rawMaster, parsedRawMaster.address.raw)

    // Standard Base64 (+/) as well as URL-safe (-_) accepted
    val stdB64 = mainnetBounce.replace('-', '+').replace('_', '/')
    assertEquals(rawZero, TonAddress.parse(stdB64, Network.MAINNET).getOrThrow().raw)

    // Wrong network, bad checksum, bad workchain, and invalid raw hex rejected
    assertTrue(TonAddress.parseDetailed(testnetNonBounce, Network.MAINNET).isFailure)
    assertTrue(TonAddress.parseDetailed(mainnetBounce, Network.TESTNET).isFailure)
    val corruptedChecksum = mainnetNonBounce.dropLast(1) + if (mainnetNonBounce.last() == 'A') 'B' else 'A'
    assertTrue(TonAddress.parseDetailed(corruptedChecksum, Network.MAINNET).isFailure)
    assertTrue(TonAddress.parseDetailed("1:" + "00".repeat(32), Network.MAINNET).isFailure)
    assertTrue(TonAddress.parseDetailed("0:1234", Network.MAINNET).isFailure)
  }

  @Test
  fun ton_transfer_uri_builds_and_parses_with_amount_and_encoded_comment() {
    val recipient = TonAddress("0:" + "44".repeat(32), Network.TESTNET)
    val baseUri = TonTransferUri.build(recipient = recipient, bounceable = false)
    val expectedFriendly = recipient.toFriendly(bounceable = false)
    assertEquals("ton://transfer/$expectedFriendly", baseUri)

    val parsedBase = TonTransferUri.parse(baseUri, Network.TESTNET).getOrThrow()
    assertEquals(recipient.raw, parsedBase.recipient.raw)
    assertFalse(parsedBase.isBounceable)
    assertEquals(null, parsedBase.amount)
    assertEquals(null, parsedBase.comment)

    val richUri =
      TonTransferUri.build(
        recipient = recipient,
        bounceable = false,
        amount = Money.fromDecimalString("1.25", GRAM_DECIMALS),
        comment = "Invoice #42 & tips",
      )
    assertTrue(richUri.startsWith("ton://transfer/$expectedFriendly?"))
    assertTrue(richUri.contains("amount=1250000000"))
    assertTrue(richUri.contains("text=Invoice%20%2342%20%26%20tips"))
    assertFalse(richUri.contains("+"))

    val parsedRich = TonTransferUri.parse(richUri, Network.TESTNET).getOrThrow()
    assertEquals(BigInteger.valueOf(1_250_000_000L), parsedRich.amount?.units)
    assertEquals("Invoice #42 & tips", parsedRich.comment)

    // Wrong network, unsupported scheme, invalid amount, and bad checksum rejected
    assertTrue(TonTransferUri.parse(richUri, Network.MAINNET).isFailure)
    assertTrue(TonTransferUri.parse("https://ton.org/transfer/$expectedFriendly", Network.TESTNET).isFailure)
    assertTrue(TonTransferUri.parse("ton://transfer/$expectedFriendly?amount=0", Network.TESTNET).isFailure)
    assertTrue(TonTransferUri.parse("ton://transfer/$expectedFriendly?amount=-5", Network.TESTNET).isFailure)
    val badAddrUri = "ton://transfer/${expectedFriendly.dropLast(1)}Z"
    assertTrue(TonTransferUri.parse(badAddrUri, Network.TESTNET).isFailure)
  }

  @Test
  fun transfer_builder_serializes_signed_bocs_for_v5r1_v4r2_v3r2_v3r1_and_validates_bounds() {
    val words =
      listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
      ).map { it.toCharArray() }
    val phrase = MnemonicPhrase(words)
    val derived = MnemonicService.deriveMultichainKey(phrase)
    try {
      val recipientMainnet = TonAddress("0:" + "77".repeat(32), Network.MAINNET)
      val recipientTestnet = TonAddress("0:" + "77".repeat(32), Network.TESTNET)
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

      for (version in WalletVersion.entries) {
        val sender = AddressService.deriveAddress(derived.publicKey32, version, Network.TESTNET)
        val expectBounce = version == WalletVersion.V4R2
        val expectInit = version == WalletVersion.V5R1 || version == WalletVersion.V3R1
        val signed =
          TransferBuilder.buildSignedTransfer(
            seed32 = derived.seed32,
            publicKey32 = derived.publicKey32,
            senderAddress = sender,
            walletVersion = version,
            destination = recipientTestnet,
            amount = Money.ofNano(1_500_000_000L),
            bounce = expectBounce,
            seqno = 7,
            validUntilEpochSec = 1_800_000_120L,
            needsStateInit = expectInit,
            comment = "Phase 5 test",
          )

        assertTrue(signed.externalBocBytes.isNotEmpty())
        assertEquals(64, signed.messageHashHex.length)
        assertEquals(64, signed.normalizedHashHex.length)
        if (expectInit) {
          assertNotEquals(signed.messageHashHex, signed.normalizedHashHex)
        } else {
          assertEquals(signed.messageHashHex, signed.normalizedHashHex)
        }

        val parsedBoc = BagOfCells(signed.externalBocBytes)
        val root = parsedBoc.roots.first()
        val parsedMsg = Message.tlbCodec(rawCodec).loadTlb(root.beginParse())
        assertTrue(parsedMsg.info is ExtInMsgInfo)
        assertEquals(expectInit, parsedMsg.init is Just)
        if (!expectInit) {
          assertTrue(parsedMsg.init is Nothing)
        }

        val bodyCell =
          when (val b = parsedMsg.body) {
            is org.ton.block.Either.Left -> b.value
            is org.ton.block.Either.Right -> b.value.value
          }
        val bodySlice = bodyCell.beginParse()
        val internalMsgCell =
          if (version == WalletVersion.V5R1) {
            val outListCell = bodySlice.loadRef()
            val actionRef = outListCell.beginParse().loadRef() // previous list or msg ref
            // V5R1 outList has ref(empty) and ref(internalMsgCell) inside outAction
            outListCell.refs.last()
          } else {
            bodySlice.loadRef()
          }
        val parsedInternal = MessageRelaxed.tlbCodec(rawCodec).loadTlb(internalMsgCell.beginParse())
        val intInfo = parsedInternal.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
        assertEquals(expectBounce, intInfo.bounce)
        assertEquals("1500000000", intInfo.value.coins.amount.value.toString(10))
      }

      // Mainnet vs Testnet V5R1 transfers use distinct wallet IDs -> different hashes
      val senderMain = AddressService.deriveV5R1Address(derived.publicKey32, Network.MAINNET)
      val senderTest = AddressService.deriveV5R1Address(derived.publicKey32, Network.TESTNET)
      val signedMain =
        TransferBuilder.buildSignedTransfer(
          seed32 = derived.seed32,
          publicKey32 = derived.publicKey32,
          senderAddress = senderMain,
          walletVersion = WalletVersion.V5R1,
          destination = recipientMainnet,
          amount = Money.ofNano(500_000_000L),
          bounce = false,
          seqno = 0,
          validUntilEpochSec = 1_800_000_120L,
          needsStateInit = true,
        )
      val signedTest =
        TransferBuilder.buildSignedTransfer(
          seed32 = derived.seed32,
          publicKey32 = derived.publicKey32,
          senderAddress = senderTest,
          walletVersion = WalletVersion.V5R1,
          destination = recipientTestnet,
          amount = Money.ofNano(500_000_000L),
          bounce = false,
          seqno = 0,
          validUntilEpochSec = 1_800_000_120L,
          needsStateInit = true,
        )
      assertNotEquals(signedMain.bodyHashHex, signedTest.bodyHashHex)
      assertNotEquals(signedMain.messageHashHex, signedTest.messageHashHex)

      // Oversized UTF-8 comment & zero amount rejected
      assertThrowsArg {
        TransferBuilder.buildSignedTransfer(
          seed32 = derived.seed32,
          publicKey32 = derived.publicKey32,
          senderAddress = senderTest,
          walletVersion = WalletVersion.V5R1,
          destination = recipientTestnet,
          amount = Money.ofNano(100_000_000L),
          bounce = false,
          seqno = 0,
          validUntilEpochSec = 1_800_000_120L,
          needsStateInit = false,
          comment = "a".repeat(121),
        )
      }
      assertThrowsArg {
        TransferBuilder.buildSignedTransfer(
          seed32 = derived.seed32,
          publicKey32 = derived.publicKey32,
          senderAddress = senderTest,
          walletVersion = WalletVersion.V5R1,
          destination = recipientTestnet,
          amount = Money.zero(),
          bounce = false,
          seqno = 0,
          validUntilEpochSec = 1_800_000_120L,
          needsStateInit = false,
        )
      }
    } finally {
      phrase.wipe()
      derived.wipe()
    }
  }

  @Test
  fun tonapi_and_toncenter_send_endpoints_parse_seqno_fee_sendboc_and_lookup() = runTest {
    val testAddr = TonAddress("0:" + "88".repeat(32), Network.TESTNET)

    val tonApiClient =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            val url = request().url.toString()
            when {
              url.contains("/v2/accounts/") ->
                jsonResponse(200, """{"address":"${testAddr.raw}","balance":9000000000,"status":"active"}""")
              url.contains("/v2/wallet/") && url.endsWith("/seqno") ->
                jsonResponse(200, """{"seqno":4}""")
              url.contains("/v2/traces/emulate") ->
                jsonResponse(200, """{"trace":{"transaction":{"total_fees":4200000}}}""")
              request().url.encodedPath == "/v2/blockchain/message" ->
                jsonResponse(400, """{"error":"cannot apply external message: seqno mismatch"}""")
              url.contains("/v2/blockchain/messages/") ->
                jsonResponse(200, """{"hash":"abc123","lt":555001,"total_fees":3500000,"aborted":false,"compute_phase":{"exit_code":0}}""")
              else -> jsonResponse(404, """{"error":"not found"}""")
            }
          },
      )

    val walletInfo = tonApiClient.fetchWalletInfo(testAddr)
    assertEquals(4, walletInfo.seqno)
    assertEquals(AccountOnChainStatus.ACTIVE, walletInfo.status)
    assertEquals(BigInteger.valueOf(9_000_000_000L), walletInfo.balance.units)

    val fee = tonApiClient.estimateExternalMessageFee(testAddr, "dGVzdA==")
    assertEquals(BigInteger.valueOf(4_200_000L), fee.estimatedFee.units)

    val sendErr = runCatching { tonApiClient.sendBoc(Network.TESTNET, "dGVzdA==") }.exceptionOrNull()
    assertTrue(sendErr is RejectedTransactionException)

    val lookup = tonApiClient.lookupMessageStatus(Network.TESTNET, "00".repeat(32), "00".repeat(32))
    assertEquals(OnChainMessageStatus.CONFIRMED, lookup.status)
    assertEquals("abc123", lookup.txHash)
    assertEquals("555001", lookup.lt)

    val tonCenterDirectWalletInfo =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            val url = request().url.toString()
            when {
              url.contains("/api/v3/walletInformation") ->
                jsonResponse(200, """{"balance":"7000000000","status":"active","seqno":5}""")
              else -> jsonResponse(404, """{}""")
            }
          },
      )
    val directInfo = tonCenterDirectWalletInfo.fetchWalletInfo(testAddr)
    assertEquals(5, directInfo.seqno)
    assertEquals(AccountOnChainStatus.ACTIVE, directInfo.status)
    assertEquals(BigInteger.valueOf(7_000_000_000L), directInfo.balance.units)

    var capturedTxByMsgUrl = ""
    val tonCenterClient =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            val url = request().url.toString()
            when {
              url.contains("/api/v3/walletInformation") ->
                jsonResponse(409, """{"error":"fallback to accountStates + runGetMethod"}""")
              url.contains("/api/v3/accountStates") ->
                jsonResponse(200, """{"accounts":[{"balance":"7000000000","status":"active"}]}""")
              url.contains("/api/v3/runGetMethod") ->
                jsonResponse(200, """{"exit_code":0,"stack":[{"type":"num","value":"0x3"}]}""")
              url.contains("/api/emulate/v1/emulateTrace") ->
                jsonResponse(200, """{"transactions":{"tx1":{"total_fees":"5100000"}}}""")
              url.contains("/api/v3/message") ->
                jsonResponse(200, """{"message_hash":"deadbeef"}""")
              url.contains("/api/v3/transactionsByMessage") -> {
                capturedTxByMsgUrl = url
                jsonResponse(200, """{"transactions":[{"hash":"tx99","lt":"900","total_fee":"4100000","description":{"aborted":true,"compute_ph":{"exit_code":33}}}]}""")
              }
              else -> jsonResponse(404, """{}""")
            }
          },
      )

    val tcInfo = tonCenterClient.fetchWalletInfo(testAddr)
    assertEquals(3, tcInfo.seqno)
    assertEquals(AccountOnChainStatus.ACTIVE, tcInfo.status)

    val uninitClient =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            val url = request().url.toString()
            when {
              url.contains("/api/v3/walletInformation") -> jsonResponse(409, """{"error":"uninit"}""")
              url.contains("/api/v3/accountStates") -> jsonResponse(200, """{"accounts":[{"balance":"0","status":"uninit"}]}""")
              else -> jsonResponse(404, """{}""")
            }
          },
      )
    val uninitInfo = uninitClient.fetchWalletInfo(testAddr)
    assertEquals(0, uninitInfo.seqno)
    assertEquals(AccountOnChainStatus.UNINIT, uninitInfo.status)

    val tcFee = tonCenterClient.estimateExternalMessageFee(testAddr, "dGVzdA==")
    assertEquals(BigInteger.valueOf(5_100_000L), tcFee.estimatedFee.units)

    val tcSent = tonCenterClient.sendBoc(Network.TESTNET, "dGVzdA==")
    assertEquals("deadbeef", tcSent.messageHashHex)

    val tcLookup = tonCenterClient.lookupMessageStatus(Network.TESTNET, "00".repeat(32), "00".repeat(32))
    assertTrue(capturedTxByMsgUrl.contains("direction=in"))
    assertEquals(OnChainMessageStatus.FAILED, tcLookup.status)
    assertEquals("tx99", tcLookup.txHash)
  }

  @Test
  fun ton_comment_decoder_and_account_history_endpoints_parse_incoming_and_outgoing_transfers() = runTest {
    val testAddr = TonAddress("0:" + "88".repeat(32), Network.TESTNET)
    val senderRaw = "0:" + "11".repeat(32)
    val recipientRaw = "0:" + "22".repeat(32)

    val commentCell =
      CellBuilder.createCell {
        storeUInt(0, 32)
        storeBytes("Hello TON!".toByteArray(Charsets.UTF_8))
      }
    val commentBocBase64 = Base64.getEncoder().encodeToString(BagOfCells(commentCell).toByteArray())
    assertEquals("Hello TON!", TonCommentDecoder.decodeTextCommentFromBoc(commentBocBase64))

    val nonCommentCell =
      CellBuilder.createCell {
        storeUInt(0x12345678, 32)
        storeBytes("ignored".toByteArray(Charsets.UTF_8))
      }
    val nonCommentBocBase64 = Base64.getEncoder().encodeToString(BagOfCells(nonCommentCell).toByteArray())
    assertEquals(null, TonCommentDecoder.decodeTextCommentFromBoc(nonCommentBocBase64))

    val rawDigest32 = ByteArray(32) { 0xab.toByte() }
    val digestBase64 = Base64.getEncoder().encodeToString(rawDigest32)
    assertEquals("ab".repeat(32), TonCommentDecoder.normalizeHashToHex(digestBase64))
    assertEquals("deadbeef", TonCommentDecoder.normalizeHashToHex("DEADBEEF"))

    var capturedTonApiUrl = ""
    val tonApiHistoryClient =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            capturedTonApiUrl = request().url.toString()
            jsonResponse(
              200,
              """
              {
                "transactions": [
                  {
                    "hash": "${"cc".repeat(32)}",
                    "lt": 900100,
                    "utime": 1710000100,
                    "aborted": false,
                    "total_fees": 1200000,
                    "in_msg": {
                      "hash": "${"d1".repeat(32)}",
                      "msg_type": "int_msg",
                      "value": 500000000,
                      "source": {"address": "$senderRaw"},
                      "destination": {"address": "${testAddr.raw}"},
                      "decoded_op_name": "text_comment",
                      "decoded_body": {"text": "Coffee"}
                    },
                    "out_msgs": []
                  },
                  {
                    "hash": "${"dd".repeat(32)}",
                    "lt": 900050,
                    "utime": 1710000050,
                    "aborted": false,
                    "total_fees": 3500000,
                    "in_msg": {
                      "hash": "${"e1".repeat(32)}",
                      "msg_type": "ext_in_msg",
                      "value": 0
                    },
                    "out_msgs": [
                      {
                        "hash": "${"f1".repeat(32)}",
                        "msg_type": "int_msg",
                        "value": 250000000,
                        "destination": {"address": "$recipientRaw"},
                        "raw_body": "$commentBocBase64"
                      }
                    ]
                  }
                ]
              }
              """.trimIndent(),
            )
          },
      )

    val tonApiPage = tonApiHistoryClient.fetchAccountHistory(testAddr, afterLt = "900000", limit = 20)
    assertTrue(capturedTonApiUrl.contains("after_lt=900000"))
    assertEquals("TonAPI", tonApiPage.providerName)
    assertEquals(2, tonApiPage.transfers.size)
    assertEquals(TxDirection.IN, tonApiPage.transfers[0].direction)
    assertEquals(BigInteger.valueOf(500_000_000L), tonApiPage.transfers[0].amount.units)
    assertEquals("Coffee", tonApiPage.transfers[0].comment)
    assertEquals(senderRaw, tonApiPage.transfers[0].counterpartyRaw)
    assertEquals(TxDirection.OUT, tonApiPage.transfers[1].direction)
    assertEquals(BigInteger.valueOf(250_000_000L), tonApiPage.transfers[1].amount.units)
    assertEquals("Hello TON!", tonApiPage.transfers[1].comment)
    assertEquals("e1".repeat(32), tonApiPage.transfers[1].inMsgHash)

    var capturedTonCenterUrl = ""
    val tonCenterHistoryClient =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            capturedTonCenterUrl = request().url.toString()
            jsonResponse(
              200,
              """
              {
                "transactions": [
                  {
                    "hash": "$digestBase64",
                    "lt": "900200",
                    "now": 1710000200,
                    "total_fees": "2100000",
                    "description": {"aborted": false, "compute_ph": {"exit_code": 0}},
                    "in_msg": {
                      "hash": "$digestBase64",
                      "source": "$senderRaw",
                      "destination": "${testAddr.raw}",
                      "value": "750000000",
                      "message_content": {
                        "body": "$commentBocBase64",
                        "decoded": {"type": "text_comment", "comment": "Hello TON!"}
                      }
                    },
                    "out_msgs": []
                  }
                ]
              }
              """.trimIndent(),
            )
          },
      )

    val tcPage = tonCenterHistoryClient.fetchAccountHistory(testAddr, afterLt = "900000", limit = 15)
    assertTrue(capturedTonCenterUrl.contains("start_lt=900001"))
    assertEquals("TON Center", tcPage.providerName)
    assertEquals(1, tcPage.transfers.size)
    assertEquals(TxDirection.IN, tcPage.transfers[0].direction)
    assertEquals("ab".repeat(32), tcPage.transfers[0].txHash)
    assertEquals(BigInteger.valueOf(750_000_000L), tcPage.transfers[0].amount.units)
    assertEquals("Hello TON!", tcPage.transfers[0].comment)
    assertEquals(OnChainMessageStatus.CONFIRMED, tcPage.transfers[0].status)
  }

  @Test
  fun uninit_incoming_deposit_is_confirmed_even_when_aborted_and_bounced_remains_failed() = runTest {
    val testAddr = TonAddress("0:" + "88".repeat(32), Network.MAINNET)
    val senderRaw = "0:" + "11".repeat(32)

    val tonApiClient =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            val url = request().url.toString()
            when {
              url.contains("/v2/accounts/") ->
                jsonResponse(404, """{"error":"account not found"}""")
              url.contains("/transactions") ->
                jsonResponse(
                  200,
                  """
                  {
                    "transactions": [
                      {
                        "hash": "${"aa".repeat(32)}",
                        "lt": 100200,
                        "utime": 1710000300,
                        "aborted": true,
                        "success": false,
                        "total_fees": 500000,
                        "credit_phase": {"credit": 500000000},
                        "compute_phase": {"skipped": true, "skip_reason": "no_state"},
                        "in_msg": {
                          "hash": "${"bb".repeat(32)}",
                          "msg_type": "int_msg",
                          "value": 500000000,
                          "bounce": false,
                          "bounced": false,
                          "source": {"address": "$senderRaw"},
                          "destination": {"address": "${testAddr.raw}"}
                        },
                        "out_msgs": []
                      },
                      {
                        "hash": "${"cc".repeat(32)}",
                        "lt": 100100,
                        "utime": 1710000200,
                        "aborted": true,
                        "success": false,
                        "total_fees": 500000,
                        "bounce_phase": "tr_phase_bounce_ok",
                        "compute_phase": {"skipped": true, "skip_reason": "no_state"},
                        "in_msg": {
                          "hash": "${"dd".repeat(32)}",
                          "msg_type": "int_msg",
                          "value": 200000000,
                          "bounce": true,
                          "bounced": false,
                          "source": {"address": "$senderRaw"},
                          "destination": {"address": "${testAddr.raw}"}
                        },
                        "out_msgs": []
                      }
                    ]
                  }
                  """.trimIndent(),
                )
              else -> jsonResponse(404, """{}""")
            }
          },
      )

    val nonExistBal = tonApiClient.fetchAccountBalance(testAddr)
    assertEquals(AccountOnChainStatus.NONEXIST, nonExistBal.status)
    assertEquals(BigInteger.ZERO, nonExistBal.balance.units)

    val taPage = tonApiClient.fetchAccountHistory(testAddr, afterLt = null, limit = 10)
    assertEquals(2, taPage.transfers.size)
    assertEquals(OnChainMessageStatus.CONFIRMED, taPage.transfers[0].status)
    assertEquals(OnChainMessageStatus.FAILED, taPage.transfers[1].status)

    val tonCenterClient =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            jsonResponse(
              200,
              """
              {
                "transactions": [
                  {
                    "hash": "${"ee".repeat(32)}",
                    "lt": "200200",
                    "now": 1710000400,
                    "total_fees": "400000",
                    "description": {
                      "aborted": true,
                      "credit_ph": {"credit": "300000000"},
                      "compute_ph": {"type": "skipped", "reason": "no_state"}
                    },
                    "in_msg": {
                      "hash": "${"ff".repeat(32)}",
                      "source": "$senderRaw",
                      "destination": "${testAddr.raw}",
                      "value": "300000000",
                      "bounce": false,
                      "bounced": false
                    },
                    "out_msgs": []
                  }
                ]
              }
              """.trimIndent(),
            )
          },
      )

    val tcPage = tonCenterClient.fetchAccountHistory(testAddr, afterLt = null, limit = 10)
    assertEquals(1, tcPage.transfers.size)
    assertEquals(OnChainMessageStatus.CONFIRMED, tcPage.transfers[0].status)
  }

  @Test
  fun tonapi_404_sendboc_fails_over_to_toncenter_and_v5r1_signature_verifies() = runTest {
    val words =
      listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about",
      ).map { it.toCharArray() }
    val phrase = MnemonicPhrase(words)
    val derived = MnemonicService.deriveMultichainKey(phrase)
    try {
      val sender = AddressService.deriveV5R1Address(derived.publicKey32, Network.MAINNET)
      val recipient = TonAddress("0:" + "77".repeat(32), Network.MAINNET)
      val signed =
        TransferBuilder.buildSignedTransfer(
          seed32 = derived.seed32,
          publicKey32 = derived.publicKey32,
          senderAddress = sender,
          walletVersion = WalletVersion.V5R1,
          destination = recipient,
          amount = Money.ofNano(100_000_000L),
          bounce = false,
          seqno = 0,
          validUntilEpochSec = 1_800_000_120L,
          needsStateInit = true,
          comment = "hi",
        )

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
      val root = BagOfCells(signed.externalBocBytes).roots.first()
      val msg = Message.tlbCodec(rawCodec).loadTlb(root.beginParse())
      val bodyCell =
        when (val b = msg.body) {
          is org.ton.block.Either.Left -> b.value
          is org.ton.block.Either.Right -> b.value.value
        }
      // Verify comment cell has 32 + 2*8 = 48 bits for "hi"
      assertEquals(48, TransferBuilder.buildCommentBodyCell("hi").bits.size)

      // V5R1 signed body puts 512-bit (64 byte) signature at the very end of bodyCell bits:
      // 32 (opcode) + 32 (walletId) + 32 (validUntil) + 32 (seqno) + 1 (actions=true) + 1 (ext=false) + 512 (sig) = 642 bits
      val totalBits = bodyCell.bits.size
      assertEquals(642, totalBits)
      val unsignedBitCount = totalBits - 512
      val unsignedRebuilt =
        CellBuilder.createCell {
          for (i in 0 until unsignedBitCount) {
            storeBoolean(bodyCell.bits[i])
          }
          bodyCell.refs.forEach { storeRef(it) }
        }
      val sigBytes =
        ByteArray(64) { byteIdx ->
          var acc = 0
          for (bit in 0 until 8) {
            if (bodyCell.bits[unsignedBitCount + byteIdx * 8 + bit]) {
              acc = acc or (1 shl (7 - bit))
            }
          }
          acc.toByte()
        }
      val unsignedHash = unsignedRebuilt.hash().toByteArray()
      val expectedSig = PrivateKeyEd25519(derived.seed32).signToByteArray(unsignedHash, 0, unsignedHash.size)
      assertArrayEquals(expectedSig, sigBytes)

      var nowMs = 10_000L
      val limiter =
        ProviderRateLimiter(
          config = ProviderRateLimitConfig(minIntervalMs = 0L, baseBackoffMs = 10L),
          clockMs = { nowMs },
          sleeper = { nowMs += it },
          jitterProvider = { 0.5 },
        )
      var tonApiPath = ""
      val tonApi404 =
        TonApiClient(
          okHttpClient =
            mockHttpClient {
              tonApiPath = request().url.encodedPath
              jsonResponse(404, """{"error":"endpoint not found"}""")
            },
          clockMs = { nowMs },
        )
      val tonCenterOk =
        TonCenterClient(
          okHttpClient =
            mockHttpClient {
              jsonResponse(200, """{"message_hash":"feedface"}""")
            },
          clockMs = { nowMs },
        )
      val router = ProviderRouter(providers = listOf(tonApi404, tonCenterOk), rateLimiter = limiter)
      val broadcast = router.sendBoc(Network.MAINNET, signed.externalBocBase64).getOrThrow()
      assertEquals("/v2/blockchain/message", tonApiPath)
      assertEquals("TON Center", broadcast.providerName)
      assertEquals("feedface", broadcast.messageHashHex)
    } finally {
      phrase.wipe()
      derived.wipe()
    }
  }

  @Test
  fun v5r1_code_hash_rfc8032_and_external_message_layout_are_valid() {
    assertEquals(
      "20834b7b72b112147e1b2fb457b84e74d1a30f04f737d4f62a668e9552d2b72f",
      AddressService.codeHashHex(WalletVersion.V5R1),
    )

    // RFC 8032 Ed25519 Test Vector 1 (empty message)
    val seed1 = AddressService.hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val expectedPub1 = AddressService.hexToBytes("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val expectedSig1 =
      AddressService.hexToBytes(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
          "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
      )
    val priv1 = PrivateKeyEd25519(seed1)
    assertArrayEquals(expectedPub1, priv1.publicKey().key.toByteArray())
    val emptyMsg = ByteArray(0)
    assertArrayEquals(expectedSig1, priv1.signToByteArray(emptyMsg, 0, 0))

    // RFC 8032 Ed25519 Test Vector 3 (2-byte message 0xaf82)
    val seed3 = AddressService.hexToBytes("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
    val expectedPub3 = AddressService.hexToBytes("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    val msg3 = AddressService.hexToBytes("af82")
    val expectedSig3 =
      AddressService.hexToBytes(
        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
          "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
      )
    val priv3 = PrivateKeyEd25519(seed3)
    assertArrayEquals(expectedPub3, priv3.publicKey().key.toByteArray())
    assertArrayEquals(expectedSig3, priv3.signToByteArray(msg3, 0, msg3.size))

    // Verify IntMsgInfoRelaxed defaults (ihrDisabled must be true)
    val sender = AddressService.deriveV5R1Address(expectedPub1, Network.MAINNET)
    val dest = TonAddress("0:" + "77".repeat(32), Network.MAINNET)
    val signed =
      TransferBuilder.buildSignedTransfer(
        seed32 = seed1,
        publicKey32 = expectedPub1,
        senderAddress = sender,
        walletVersion = WalletVersion.V5R1,
        destination = dest,
        amount = Money.ofNano(10_000_000L),
        bounce = false,
        seqno = 0,
        validUntilEpochSec = 1_800_000_000L,
        needsStateInit = true,
        comment = null,
      )
    val root = BagOfCells(signed.externalBocBytes).roots.first()
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
    val extMsg = Message.tlbCodec(rawCodec).loadTlb(root.beginParse())
    val stateInit = (extMsg.init as Just).value.let { (it as org.ton.block.Either.Right).value.value }
    assertEquals(sender.raw.lowercase(), "0:" + AddressService.bytesToHex(stateInit.address(0).address.toByteArray()))

    val bodyCell = (extMsg.body as org.ton.block.Either.Right).value.value
    val outList = bodyCell.refs.first()
    val internalCell = outList.refs.last()
    val intMsg = MessageRelaxed.tlbCodec(rawCodec).loadTlb(internalCell.beginParse())
    val intInfo = intMsg.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
    assertTrue("ihrDisabled should be true", intInfo.ihrDisabled)
    assertFalse("bounce should be false", intInfo.bounce)
    assertFalse("bounced should be false", intInfo.bounced)
    assertTrue("extMsg.body should be ref when StateInit is present", extMsg.body is org.ton.block.Either.Right)
    assertEquals("outList must have 2 refs (prev Empty + internalMsg)", 2, outList.refs.size)
  }

  @Test
  fun toncenter_sendboc_falls_back_to_v2_and_history_handles_top_level_aborted_deposit() = runTest {
    val testAddr = TonAddress("0:" + "88".repeat(32), Network.MAINNET)
    val senderRaw = "0:" + "11".repeat(32)
    val expectedHashBytes = ByteArray(32) { (it + 1).toByte() }
    val hashBase64 = Base64.getEncoder().encodeToString(expectedHashBytes)
    val expectedHashHex = expectedHashBytes.joinToString("") { "%02x".format(it) }

    val pacedCalls = AtomicInteger(0)
    val client =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            val path = request().url.encodedPath
            when (path) {
              "/api/v3/message" -> jsonResponse(502, """{"error":"bad gateway"}""")
              "/api/v2/sendBocReturnHash" ->
                jsonResponse(200, """{"ok":true,"result":{"hash":"$hashBase64"}}""")
              "/api/v3/transactions" ->
                jsonResponse(
                  200,
                  """
                  {
                    "transactions": [
                      {
                        "hash": "${"aa".repeat(32)}",
                        "lt": "400100",
                        "now": 1710000500,
                        "total_fees": "450000",
                        "aborted": true,
                        "compute_ph": {"compute_type": "skipped", "skipped": true, "reason": "no_state"},
                        "in_msg": {
                          "hash": "${"bb".repeat(32)}",
                          "source": "$senderRaw",
                          "destination": "${testAddr.raw}",
                          "value": "250000000",
                          "bounce": false,
                          "bounced": false
                        },
                        "out_msgs": []
                      }
                    ]
                  }
                  """.trimIndent(),
                )
              else -> jsonResponse(404, """{}""")
            }
          },
        requestPacer = { pacedCalls.incrementAndGet() },
      )

    val broadcast = client.sendBoc(Network.MAINNET, "dGVzdA==")
    assertEquals(expectedHashHex, broadcast.messageHashHex)
    assertEquals(1, pacedCalls.get())

    val history = client.fetchAccountHistory(testAddr, afterLt = null, limit = 10)
    assertEquals(1, history.transfers.size)
    assertEquals(OnChainMessageStatus.CONFIRMED, history.transfers[0].status)
  }

  @Test
  fun provider_rate_limiter_enforces_per_provider_intervals_and_subcall_pacing() = runTest {
    var nowMs = 1_000L
    val recordedSleeps = mutableListOf<Long>()
    val limiter =
      ProviderRateLimiter(
        config =
          ProviderRateLimitConfig(
            minIntervalMs = 1_000L,
            baseBackoffMs = 100L,
            perProviderMinIntervalMs = mapOf("TON Center" to 1_150L, "TonAPI" to 4_250L),
          ),
        clockMs = { nowMs },
        sleeper = { ms ->
          recordedSleeps.add(ms)
          nowMs += ms
        },
      )

    limiter.awaitRequestSlot("TON Center")
    limiter.awaitRequestSlot("TON Center")
    limiter.awaitRequestSlot("TonAPI")
    limiter.awaitRequestSlot("TonAPI")

    assertEquals(listOf(1_150L, 4_250L), recordedSleeps)
  }

  @Test
  fun provider_router_sendboc_fails_over_on_rejection_and_emulation_keeps_circuit_closed() = runTest {
    val testAddr = TonAddress("0:" + "55".repeat(32), Network.TESTNET)
    var nowMs = 10_000L
    val limiter =
      ProviderRateLimiter(
        config =
          ProviderRateLimitConfig(
            minIntervalMs = 0L,
            baseBackoffMs = 10L,
            failureThresholdToOpenCircuit = 1,
            openCircuitBaseMs = 30_000L,
          ),
        clockMs = { nowMs },
        sleeper = { nowMs += it },
        jitterProvider = { 0.5 },
      )

    val tonCenterPrimary =
      TonCenterClient(
        okHttpClient =
          mockHttpClient {
            when (request().url.encodedPath) {
              "/api/emulate/v1/emulateTrace" -> jsonResponse(500, """{"error":"trace failed"}""")
              "/api/v3/message",
              "/api/v2/sendBocReturnHash" ->
                jsonResponse(400, """{"error":"temporary v3 validator rejection"}""")
              else -> jsonResponse(404, """{}""")
            }
          },
        clockMs = { nowMs },
      )
    val tonApiSecondary =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            when (request().url.encodedPath) {
              "/v2/traces/emulate" -> jsonResponse(200, """{"trace":{"transaction":{"total_fees":1000000}}}""")
              "/v2/blockchain/message" -> jsonResponse(200, """{}""")
              else -> jsonResponse(404, """{}""")
            }
          },
        clockMs = { nowMs },
      )

    val router = ProviderRouter(providers = listOf(tonCenterPrimary, tonApiSecondary), rateLimiter = limiter)
    val est = router.estimateExternalMessageFee(testAddr, "dGVzdA==").getOrThrow()
    assertEquals("TonAPI", est.providerName)
    assertFalse("Emulation failure must not open TON Center circuit", limiter.isCircuitOpen("TON Center"))

    val sent = router.sendBoc(Network.TESTNET, "dGVzdA==").getOrThrow()
    assertEquals("TonAPI", sent.providerName)
  }

  @Test
  fun toncenter_lookup_jetton_trace_requires_complete_non_bounced_trace() = runTest {
    var payload = ""
    val client =
      TonCenterClient(
        okHttpClient = mockHttpClient { jsonResponse(200, payload) },
      )
    val hash = "aa".repeat(32)

    // 1. Incomplete trace -> PENDING
    payload =
      """
      {
        "traces": [
          {
            "trace_id": "t1",
            "is_incomplete": true,
            "transactions": {
              "h1": {"hash": "h1", "lt": "100", "total_fees": "2000000", "aborted": false}
            },
            "actions": []
          }
        ]
      }
      """.trimIndent()
    assertEquals(OnChainMessageStatus.PENDING, client.lookupJettonTraceStatus(Network.MAINNET, hash, hash).status)

    // 2. Single-hop only without Jetton completion -> PENDING
    payload =
      """
      {
        "traces": [
          {
            "trace_id": "t1",
            "is_incomplete": false,
            "transactions": {
              "h1": {"hash": "h1", "lt": "100", "total_fees": "2000000", "aborted": false}
            },
            "actions": []
          }
        ]
      }
      """.trimIndent()
    assertEquals(OnChainMessageStatus.PENDING, client.lookupJettonTraceStatus(Network.MAINNET, hash, hash).status)

    // 3. Downstream bounce on child Jetton wallet -> FAILED
    payload =
      """
      {
        "traces": [
          {
            "trace_id": "t1",
            "is_incomplete": false,
            "transactions": {
              "h1": {"hash": "h1", "lt": "100", "total_fees": "2000000", "aborted": false},
              "h2": {
                "hash": "h2",
                "lt": "101",
                "total_fees": "3000000",
                "description": {"aborted": false, "bounce": {"type": "ok"}},
                "out_msgs": [{"bounced": true}]
              }
            },
            "actions": [{"type": "jetton_transfer", "success": false}]
          }
        ]
      }
      """.trimIndent()
    val bounced = client.lookupJettonTraceStatus(Network.MAINNET, hash, hash)
    assertEquals(OnChainMessageStatus.FAILED, bounced.status)

    // 4. Complete multi-hop non-bounced trace -> CONFIRMED with summed fees
    payload =
      """
      {
        "traces": [
          {
            "trace_id": "t1",
            "is_incomplete": false,
            "transactions": {
              "h1": {"hash": "h1", "lt": "100", "total_fees": "2000000", "aborted": false, "out_msgs": [{"bounced": false}]},
              "h2": {"hash": "h2", "lt": "101", "total_fees": "3000000", "aborted": false, "out_msgs": [{"bounced": false}]},
              "h3": {"hash": "h3", "lt": "102", "total_fees": "2500000", "aborted": false, "out_msgs": []}
            },
            "actions": [{"type": "jetton_transfer", "success": true}]
          }
        ]
      }
      """.trimIndent()
    val confirmed = client.lookupJettonTraceStatus(Network.MAINNET, hash, hash)
    assertEquals(OnChainMessageStatus.CONFIRMED, confirmed.status)
    assertEquals("h1", confirmed.txHash)
    assertEquals(7_500_000L, confirmed.fee?.units?.toLong())
  }

  @Test
  fun tonapi_lookup_jetton_trace_detects_child_bounce_and_in_progress_event() = runTest {
    val rootHash = "root_tx_01"
    var traceJson = ""
    var eventJson = """{"in_progress":false,"actions":[{"type":"JettonTransfer","status":"ok"}]}"""
    val client =
      TonApiClient(
        okHttpClient =
          mockHttpClient {
            val path = request().url.encodedPath
            when {
              path.endsWith("/transaction") ->
                jsonResponse(200, """{"hash":"$rootHash","lt":"500","total_fees":2000000,"aborted":false,"success":true}""")
              path == "/v2/traces/$rootHash" -> jsonResponse(200, traceJson)
              path == "/v2/events/$rootHash" -> jsonResponse(200, eventJson)
              else -> jsonResponse(404, """{}""")
            }
          },
      )
    val msgHash = "bb".repeat(32)

    // 1. Only wallet node so far (no children) -> PENDING
    traceJson = """{"in_progress":false,"transaction":{"hash":"$rootHash","lt":"500","total_fees":2000000,"aborted":false,"success":true},"children":[]}"""
    assertEquals(OnChainMessageStatus.PENDING, client.lookupJettonTraceStatus(Network.MAINNET, msgHash, msgHash).status)

    // 2. Child bounced internal transfer -> FAILED
    traceJson =
      """
      {
        "in_progress": false,
        "transaction": {"hash": "$rootHash", "lt": "500", "total_fees": 2000000, "aborted": false, "success": true},
        "children": [
          {
            "transaction": {
              "hash": "jw_tx_02",
              "lt": "501",
              "total_fees": 3500000,
              "aborted": true,
              "compute_phase": {"skipped": false, "success": false, "exit_code": 706},
              "out_msgs": [{"bounced": true}]
            },
            "children": []
          }
        ]
      }
      """.trimIndent()
    assertEquals(OnChainMessageStatus.FAILED, client.lookupJettonTraceStatus(Network.MAINNET, msgHash, msgHash).status)

    // 3. Multi-hop trace ready, event still in_progress -> PENDING
    traceJson =
      """
      {
        "in_progress": false,
        "transaction": {"hash": "$rootHash", "lt": "500", "total_fees": 2000000, "aborted": false, "success": true},
        "children": [
          {
            "transaction": {
              "hash": "jw_tx_02",
              "lt": "501",
              "total_fees": 3500000,
              "aborted": false,
              "success": true,
              "compute_phase": {"skipped": false, "success": true, "exit_code": 0},
              "action_phase": {"success": true, "result_code": 0}
            },
            "children": []
          }
        ]
      }
      """.trimIndent()
    eventJson = """{"in_progress":true,"actions":[{"type":"JettonTransfer","status":"ok"}]}"""
    assertEquals(OnChainMessageStatus.PENDING, client.lookupJettonTraceStatus(Network.MAINNET, msgHash, msgHash).status)

    // 4. Both trace and event finalized with no bounce/abort -> CONFIRMED
    eventJson = """{"in_progress":false,"actions":[{"type":"JettonTransfer","status":"ok"}]}"""
    val confirmed = client.lookupJettonTraceStatus(Network.MAINNET, msgHash, msgHash)
    assertEquals(OnChainMessageStatus.CONFIRMED, confirmed.status)
    assertEquals(5_500_000L, confirmed.fee?.units?.toLong())
  }
}

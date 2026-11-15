package com.example.core.model

import java.util.Arrays

class MnemonicPhrase(words: List<CharArray>) : AutoCloseable {
  private val internalWords: List<CharArray> = words.map { it.copyOf() }

  val wordCount: Int
    get() = internalWords.size

  fun wordsCopy(): List<CharArray> = internalWords.map { it.copyOf() }

  fun wordAt(indexZeroBased: Int): CharArray = internalWords[indexZeroBased].copyOf()

  fun toSpaceSeparatedCharArray(): CharArray {
    if (internalWords.isEmpty()) return CharArray(0)
    val totalLength = internalWords.sumOf { it.size } + (internalWords.size - 1)
    val result = CharArray(totalLength)
    var pos = 0
    internalWords.forEachIndexed { idx, word ->
      System.arraycopy(word, 0, result, pos, word.size)
      pos += word.size
      if (idx < internalWords.lastIndex) {
        result[pos++] = ' '
      }
    }
    return result
  }

  fun wipe() {
    internalWords.forEach { Arrays.fill(it, '\u0000') }
  }

  override fun close() {
    wipe()
  }
}

enum class VaultSecurityMode {
  DEVICE_KEYSTORE,
  APP_PIN_WITH_KEYSTORE,
  DEV_UNAUTH_KEYSTORE,
}

enum class WalletVersion(val displayName: String) {
  V5R1("V5R1"),
  V4R2("V4R2"),
  V3R2("V3R2"),
  V3R1("V3R1"),
}

enum class MnemonicScheme(val displayName: String) {
  MULTICHAIN("Multichain (BIP-39)"),
  TON_LEGACY("TON Standard (24-word)"),
}

data class CandidateAccount(
  val id: String,
  val scheme: MnemonicScheme,
  val version: WalletVersion,
  val publicKeyHex: String,
  val mainnetAddress: TonAddress,
  val testnetAddress: TonAddress,
) {
  fun addressFor(network: Network): TonAddress =
    when (network) {
      Network.MAINNET -> mainnetAddress
      Network.TESTNET -> testnetAddress
    }
}

data class ImportDiscoveryResult(
  val candidates: List<CandidateAccount>,
  val isAmbiguous24: Boolean,
)

data class VaultMetadata(
  val publicKeyHex: String,
  val mainnetRaw: String,
  val testnetRaw: String,
  val isBackedUp: Boolean,
  val createdAtEpochMs: Long,
  val securityMode: VaultSecurityMode = VaultSecurityMode.DEVICE_KEYSTORE,
  val walletVersion: WalletVersion = WalletVersion.V5R1,
  val mnemonicScheme: MnemonicScheme = MnemonicScheme.MULTICHAIN,
  val lastActiveNetwork: Network = Network.MAINNET,
) {
  fun addressFor(network: Network): TonAddress =
    when (network) {
      Network.MAINNET -> TonAddress(mainnetRaw, Network.MAINNET)
      Network.TESTNET -> TonAddress(testnetRaw, Network.TESTNET)
    }
}

data class NewWallet(
  val mainnetAddress: TonAddress,
  val testnetAddress: TonAddress,
  val publicKeyHex: String,
  val mnemonic: MnemonicPhrase,
)

data class BackupChallenge(val indicesOneBased: List<Int>)

enum class AuthReason {
  CREATE_WALLET,
  IMPORT_WALLET,
  UNLOCK_WALLET,
  VIEW_RECOVERY_PHRASE,
  SIGN_ACTION,
}

sealed interface AuthResult {
  data object Authenticated : AuthResult
  data object Cancelled : AuthResult
  data object NoDeviceSecurity : AuthResult
  data class Failed(val message: String) : AuthResult
}

sealed interface VaultState {
  data object Uninitialized : VaultState
  data class Locked(val metadata: VaultMetadata) : VaultState
  data class KeyInvalidated(val metadata: VaultMetadata) : VaultState
  data class Unlocked(
    val metadata: VaultMetadata,
    val activeNetwork: Network,
  ) : VaultState
}

package com.example.core.tx

import com.example.core.data.BalanceRepository
import com.example.core.data.TxDao
import com.example.core.data.TxEntity
import com.example.core.model.AccountOnChainStatus
import com.example.core.model.GRAM_DECIMALS
import com.example.core.model.HistorySyncSummary
import com.example.core.model.JettonAllowList
import com.example.core.model.Money
import com.example.core.model.Network
import com.example.core.model.RemoteJettonBalance
import com.example.core.model.TonAddress
import com.example.core.model.TxAssetKind
import com.example.core.model.TxDirection
import com.example.core.model.TxRecord
import com.example.core.model.TxStatus
import com.example.core.model.USDT_DECIMALS
import com.example.core.network.AllProvidersFailedException
import com.example.core.network.ChainGateway
import com.example.core.network.OnChainMessageStatus
import com.example.core.network.RateLimitedException
import com.example.core.network.RejectedTransactionException
import com.example.core.network.ServerErrorException
import com.example.core.network.TransportException
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TransferChainContext(
  val senderBalance: Money,
  val senderStatus: AccountOnChainStatus,
  val senderSeqno: Int,
  val destinationStatus: AccountOnChainStatus,
  val providerName: String,
)

class PendingTransferConflictException(message: String) : Exception(message)

class TransferRejectedException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

class TxManager(
  private val txDao: TxDao,
  private val chainGateway: ChainGateway,
  private val balanceRepository: BalanceRepository? = null,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val txMutex = Mutex()
  private val trackingJobs = mutableMapOf<String, Job>()

  fun observeTransactions(accountRaw: String, network: Network): Flow<List<TxRecord>> =
    txDao.observeByAccount(accountRaw, network.name).map { list -> list.map { it.toDomain() } }

  suspend fun ensureNoActivePendingTx(accountAddress: TonAddress) {
    val active =
      txMutex.withLock {
        val pending = txDao.getNonTerminalForAccount(accountAddress.raw, accountAddress.network.name)
        for (entity in pending) {
          reconcileEntityLocked(entity, allowRebroadcast = false)
        }
        txDao.getNonTerminalForAccount(accountAddress.raw, accountAddress.network.name)
      }
    if (active.isNotEmpty()) {
      val blocker = active.first()
      throw PendingTransferConflictException(
        "Transfer #${blocker.seqno} is already in progress. Wait for confirmation or expiry before sending again."
      )
    }
  }

  suspend fun fetchTransferChainContext(
    senderAddress: TonAddress,
    destination: TonAddress,
  ): Result<TransferChainContext> {
    val senderResult = chainGateway.fetchWalletInfo(senderAddress)
    val senderInfo =
      senderResult.getOrElse { err ->
        return Result.failure(err)
      }

    val destStatus =
      if (destination.raw == senderAddress.raw && destination.network == senderAddress.network) {
        senderInfo.status
      } else {
        chainGateway
          .fetchAccountBalance(destination)
          .fold(
            onSuccess = { it.status },
            onFailure = { err -> return Result.failure(err) },
          )
      }

    return Result.success(
      TransferChainContext(
        senderBalance = senderInfo.balance,
        senderStatus = senderInfo.status,
        senderSeqno = senderInfo.seqno,
        destinationStatus = destStatus,
        providerName = senderInfo.providerName,
      )
    )
  }

  suspend fun fetchJettonChainContext(
    senderAddress: TonAddress,
  ): Result<RemoteJettonBalance> {
    val spec =
      JettonAllowList.usdtFor(senderAddress.network)
        ?: return Result.failure(IllegalArgumentException("USDT is only supported on Mainnet"))
    return chainGateway.fetchJettonBalance(senderAddress, spec)
  }

  suspend fun estimateTransferFee(
    senderAddress: TonAddress,
    dummyBocBase64: String,
  ): Money? =
    chainGateway
      .estimateExternalMessageFee(senderAddress, dummyBocBase64)
      .getOrNull()
      ?.estimatedFee

  suspend fun persistSignedAndBroadcast(
    record: TxRecord,
    bocBase64: String,
  ): Result<TxRecord> {
    val accountAddress = TonAddress(record.accountRaw, record.network)
    val persistedResult =
      txMutex.withLock {
        val existingPending =
          txDao.getNonTerminalForAccount(record.accountRaw, record.network.name)
        for (entity in existingPending) {
          reconcileEntityLocked(entity, allowRebroadcast = false)
        }
        val stillPending =
          txDao.getNonTerminalForAccount(record.accountRaw, record.network.name)
        if (stillPending.isNotEmpty()) {
          return@withLock Result.failure(
            PendingTransferConflictException(
              "Transfer #${stillPending.first().seqno} is already active. Duplicate send blocked."
            )
          )
        }

        val signedRecord = record.copy(state = TxStatus.SIGNED)
        // Persist SIGNED state + BOC atomically BEFORE attempting network broadcast.
        txDao.persistSignedAtomic(signedRecord.toEntity(bocBase64 = bocBase64))

        val broadcastResult = chainGateway.sendBoc(record.network, bocBase64)
        val nowMs = clockMs()
        broadcastResult.fold(
          onSuccess = {
            txDao.updateLifecycleState(
              id = signedRecord.id,
              newState = TxStatus.BROADCASTED.name,
              bocBase64 = null,
              updatedAtEpochMs = nowMs,
              errorDetail = null,
            )
            Result.success(
              signedRecord.copy(
                state = TxStatus.BROADCASTED,
                updatedAtEpochMs = nowMs,
                errorDetail = null,
              )
            )
          },
          onFailure = { err ->
            if (err is RejectedTransactionException) {
              val detail = err.message ?: "Transaction rejected by TON network"
              txDao.markTerminal(
                id = signedRecord.id,
                finalState = TxStatus.FAILED.name,
                txHash = null,
                lt = null,
                feeUnits = null,
                updatedAtEpochMs = nowMs,
                errorDetail = detail,
              )
              Result.failure(TransferRejectedException(detail, err))
            } else {
              // Transient network/timeout failure: keep SIGNED + BOC in Room so recovery can reconcile
              // or safely re-broadcast the identical BOC without double-spending.
              val queuedNote =
                if (
                  err is AllProvidersFailedException ||
                    err is RateLimitedException ||
                    err is TransportException ||
                    err is ServerErrorException
                ) {
                  "Broadcast queued — retrying automatically"
                } else {
                  err.message?.lineSequence()?.firstOrNull()?.take(120)
                    ?: "Broadcast queued — retrying automatically"
                }
              txDao.updateLifecycleState(
                id = signedRecord.id,
                newState = TxStatus.SIGNED.name,
                bocBase64 = bocBase64,
                updatedAtEpochMs = nowMs,
                errorDetail = queuedNote,
              )
              Result.success(
                signedRecord.copy(
                  state = TxStatus.SIGNED,
                  updatedAtEpochMs = nowMs,
                  errorDetail = queuedNote,
                )
              )
            }
          },
        )
      }

    persistedResult.onSuccess { updated ->
      if (!updated.state.isTerminal) {
        startTracking(updated.id, accountAddress)
      }
    }
    return persistedResult
  }

  suspend fun recoverPendingTransactions(accountAddress: TonAddress) {
    val remaining =
      txMutex.withLock {
        val pending = txDao.getNonTerminalForAccount(accountAddress.raw, accountAddress.network.name)
        for (entity in pending) {
          reconcileEntityLocked(entity, allowRebroadcast = true)
        }
        txDao.getNonTerminalForAccount(accountAddress.raw, accountAddress.network.name)
      }
    for (entity in remaining) {
      startTracking(entity.id, accountAddress)
    }
  }

  suspend fun refreshTransactionStatus(txId: String): TxRecord? {
    val updated =
      txMutex.withLock {
        val entity = txDao.getById(txId) ?: return@withLock null
        reconcileEntityLocked(entity, allowRebroadcast = true)
      }
    return updated?.toDomain()
  }

  suspend fun syncAccountHistory(
    accountAddress: TonAddress,
    limit: Int = 25,
    knownAccountLt: String? = null,
  ): Result<HistorySyncSummary> {
    val syncMeta =
      txMutex.withLock {
        val syncedCount = txDao.countChainSyncedTxs(accountAddress.raw, accountAddress.network.name)
        val failedIncoming = txDao.countFailedIncoming(accountAddress.raw, accountAddress.network.name)
        val pendingCount =
          txDao.getNonTerminalForAccount(accountAddress.raw, accountAddress.network.name).size
        val latestLt = txDao.getLatestLt(accountAddress.raw, accountAddress.network.name)
        Triple(syncedCount > 0 && failedIncoming == 0, pendingCount == 0, latestLt)
      }
    val (canUseIncremental, noPending, latestLocalLt) = syncMeta

    // Rate-limit optimization: if chain reports same or older last_transaction_lt and there are no
    // pending local transactions or failed incoming repairs needed, skip redundant history calls.
    if (canUseIncremental && noPending && !knownAccountLt.isNullOrBlank() && !latestLocalLt.isNullOrBlank()) {
      val remoteLtNum = knownAccountLt.trim().toBigIntegerOrNull()
      val localLtNum = latestLocalLt.trim().toBigIntegerOrNull()
      if (remoteLtNum != null && localLtNum != null && remoteLtNum <= localLtNum) {
        return Result.success(
          HistorySyncSummary(
            newIncomingTxs = emptyList(),
            reconciledCount = 0,
            providerName = "Cache",
          )
        )
      }
    }

    val afterLt = if (canUseIncremental) latestLocalLt else null
    val page =
      chainGateway
        .fetchAccountHistory(accountAddress, afterLt = afterLt, limit = limit)
        .getOrElse { err -> return Result.failure(err) }

    val usdtSpec = JettonAllowList.usdtFor(accountAddress.network)
    val jettonPage =
      usdtSpec?.let { spec ->
        chainGateway
          .fetchJettonHistory(accountAddress, spec, limit = limit)
          .getOrNull()
      }

    if (page.transfers.isEmpty() && (jettonPage == null || jettonPage.transfers.isEmpty())) {
      return Result.success(
        HistorySyncSummary(
          newIncomingTxs = emptyList(),
          reconciledCount = 0,
          providerName = page.providerName,
        )
      )
    }

    val nowMs = clockMs()
    val newIncoming = mutableListOf<TxRecord>()
    var reconciledCount = 0

    txMutex.withLock {
      val toUpsert = mutableListOf<TxEntity>()
      val perTxCounters = mutableMapOf<String, Int>()
      for (transfer in page.transfers) {
        val hash1 = transfer.inMsgHash.orEmpty()
        val hash2 = transfer.inMsgNormalizedHash.orEmpty()
        val existingByMsgHash =
          if (hash1.isNotEmpty() || hash2.isNotEmpty()) {
            txDao.findByMessageHashes(
              accountRaw = accountAddress.raw,
              network = accountAddress.network.name,
              hash1 = hash1,
              hash2 = hash2,
            )
          } else {
            null
          }

        val terminalStatus =
          if (transfer.status == OnChainMessageStatus.FAILED) TxStatus.FAILED else TxStatus.CONFIRMED

        if (existingByMsgHash != null) {
          if (
            existingByMsgHash.direction == TxDirection.OUT.name &&
              transfer.direction == TxDirection.OUT
          ) {
            if (existingByMsgHash.kind == TxAssetKind.JETTON.name) {
              // Wallet-contract history only proves the first hop left the sender wallet;
              // never mark a Jetton transfer CONFIRMED from wallet history alone.
              if (terminalStatus == TxStatus.FAILED) {
                val updatedEntity =
                  existingByMsgHash.copy(
                    txHash = transfer.txHash,
                    lt = transfer.lt,
                    feeUnits = transfer.fee?.units?.toString() ?: existingByMsgHash.feeUnits,
                    state = TxStatus.FAILED.name,
                    bocBase64 = null,
                    updatedAtEpochMs = nowMs,
                    errorDetail = existingByMsgHash.errorDetail ?: "Transaction aborted on-chain",
                  )
                toUpsert.add(updatedEntity)
                reconciledCount++
              } else if (
                existingByMsgHash.state == TxStatus.SIGNED.name ||
                  existingByMsgHash.txHash == null ||
                  existingByMsgHash.lt == null
              ) {
                val nextState =
                  if (existingByMsgHash.state == TxStatus.SIGNED.name) TxStatus.BROADCASTED.name
                  else existingByMsgHash.state
                toUpsert.add(
                  existingByMsgHash.copy(
                    txHash = existingByMsgHash.txHash ?: transfer.txHash,
                    lt = existingByMsgHash.lt ?: transfer.lt,
                    feeUnits = transfer.fee?.units?.toString() ?: existingByMsgHash.feeUnits,
                    state = nextState,
                    bocBase64 = null,
                    updatedAtEpochMs = nowMs,
                  )
                )
              }
            } else {
              val updatedEntity =
                existingByMsgHash.copy(
                  txHash = transfer.txHash,
                  lt = transfer.lt,
                  feeUnits = transfer.fee?.units?.toString() ?: existingByMsgHash.feeUnits,
                  state = terminalStatus.name,
                  bocBase64 = null,
                  updatedAtEpochMs = nowMs,
                  errorDetail =
                    if (terminalStatus == TxStatus.FAILED) {
                      existingByMsgHash.errorDetail ?: "Transaction aborted on-chain"
                    } else {
                      null
                    },
                )
              toUpsert.add(updatedEntity)
              reconciledCount++
            }
          } else if (
            existingByMsgHash.direction == TxDirection.IN.name &&
              transfer.direction == TxDirection.IN &&
              existingByMsgHash.state != terminalStatus.name
          ) {
            toUpsert.add(
              existingByMsgHash.copy(
                state = terminalStatus.name,
                updatedAtEpochMs = nowMs,
                errorDetail = if (terminalStatus == TxStatus.FAILED) "Transaction aborted on-chain" else null,
              )
            )
            reconciledCount++
          }
        } else {
          val txHashPrefix = transfer.txHash.take(16).ifEmpty { "notx" }
          val baseKey = "${transfer.lt}_${txHashPrefix}_${transfer.direction.name}"
          val withinTxSeq = perTxCounters[baseKey] ?: 0
          perTxCounters[baseKey] = withinTxSeq + 1
          val deterministicId = "chain_${accountAddress.network.name}_${baseKey}_$withinTxSeq"
          val existingById = txDao.getById(deterministicId)
          val createdMs =
            if (transfer.utimeEpochSec > 0L) transfer.utimeEpochSec * 1000L else nowMs
          val entity =
            TxEntity(
              id = deterministicId,
              network = accountAddress.network.name,
              accountRaw = accountAddress.raw,
              messageHash = transfer.inMsgHash ?: transfer.txHash,
              normalizedHash = transfer.inMsgNormalizedHash ?: transfer.inMsgHash ?: transfer.txHash,
              txHash = transfer.txHash,
              seqno = 0,
              direction = transfer.direction.name,
              kind = TxAssetKind.GRAM.name,
              jettonMaster = null,
              amountUnits = transfer.amount.units.toString(),
              feeUnits = transfer.fee?.units?.toString(),
              counterpartyRaw = transfer.counterpartyRaw,
              comment = transfer.comment,
              state = terminalStatus.name,
              validUntilEpochSec = 0L,
              lt = transfer.lt,
              bocBase64 = null,
              createdAtEpochMs = existingById?.createdAtEpochMs ?: createdMs,
              updatedAtEpochMs = nowMs,
              errorDetail =
                if (terminalStatus == TxStatus.FAILED) "Transaction aborted on-chain" else null,
            )
          toUpsert.add(entity)
          if (
            existingById == null &&
              transfer.direction == TxDirection.IN &&
              terminalStatus == TxStatus.CONFIRMED
          ) {
            newIncoming.add(entity.toDomain())
          }
        }
      }

      if (jettonPage != null) {
        val jettonCounters = mutableMapOf<String, Int>()
        for (jt in jettonPage.transfers) {
          val verified =
            JettonAllowList.findVerified(TonAddress(jt.jettonMasterRaw, accountAddress.network))
              ?: continue
          val createdMs = if (jt.utimeEpochSec > 0L) jt.utimeEpochSec * 1000L else nowMs
          if (jt.direction == TxDirection.OUT) {
            val matchedInBatch =
              toUpsert.any { existing ->
                existing.kind == TxAssetKind.JETTON.name &&
                  existing.direction == TxDirection.OUT.name &&
                  (
                    (jt.txHash.isNotEmpty() && existing.txHash == jt.txHash) ||
                      (
                        existing.counterpartyRaw == jt.counterpartyRaw &&
                          existing.amountUnits == jt.amount.units.toString() &&
                          kotlin.math.abs(existing.createdAtEpochMs - createdMs) <= 180_000L
                      )
                  )
              }
            val matchedInDb =
              if (!matchedInBatch) {
                txDao.countMatchingOutgoingJetton(
                  accountRaw = accountAddress.raw,
                  network = accountAddress.network.name,
                  txHash = jt.txHash,
                  counterpartyRaw = jt.counterpartyRaw,
                  amountUnits = jt.amount.units.toString(),
                  minTimeMs = createdMs - 180_000L,
                  maxTimeMs = createdMs + 180_000L,
                ) > 0
              } else {
                true
              }
            if (matchedInBatch || matchedInDb) {
              continue
            }
          }
          val jtStatus =
            if (jt.status == OnChainMessageStatus.FAILED) TxStatus.FAILED else TxStatus.CONFIRMED
          val txHashPrefix = jt.txHash.take(16).ifEmpty { "notx" }
          val baseKey = "${jt.lt}_${txHashPrefix}_${jt.direction.name}"
          val seqIdx = jettonCounters[baseKey] ?: 0
          jettonCounters[baseKey] = seqIdx + 1
          val deterministicId = "jetton_${accountAddress.network.name}_${baseKey}_$seqIdx"
          val existingById = txDao.getById(deterministicId)
          val entity =
            TxEntity(
              id = deterministicId,
              network = accountAddress.network.name,
              accountRaw = accountAddress.raw,
              messageHash = jt.txHash,
              normalizedHash = jt.txHash,
              txHash = jt.txHash,
              seqno = 0,
              direction = jt.direction.name,
              kind = TxAssetKind.JETTON.name,
              jettonMaster = verified.masterAddress.raw,
              amountUnits = jt.amount.units.toString(),
              feeUnits = null,
              counterpartyRaw = jt.counterpartyRaw,
              comment = jt.comment,
              state = jtStatus.name,
              validUntilEpochSec = 0L,
              lt = jt.lt,
              bocBase64 = null,
              createdAtEpochMs = existingById?.createdAtEpochMs ?: createdMs,
              updatedAtEpochMs = nowMs,
              errorDetail = if (jtStatus == TxStatus.FAILED) "Jetton transfer failed" else null,
            )
          toUpsert.add(entity)
          if (existingById == null && jt.direction == TxDirection.IN && jtStatus == TxStatus.CONFIRMED) {
            newIncoming.add(entity.toDomain())
          }
        }
      }

      txDao.applyHistorySyncAtomic(toUpsert)
    }

    return Result.success(
      HistorySyncSummary(
        newIncomingTxs = newIncoming,
        reconciledCount = reconciledCount,
        providerName = page.providerName,
      )
    )
  }

  suspend fun clearAll() {
    txMutex.withLock {
      trackingJobs.values.forEach { it.cancel() }
      trackingJobs.clear()
      txDao.clearAll()
    }
  }

  private fun startTracking(txId: String, accountAddress: TonAddress) {
    synchronized(trackingJobs) {
      if (trackingJobs[txId]?.isActive == true) return
      val job =
        scope.launch {
          try {
            var attempts = 0
            while (attempts < MAX_POLL_ATTEMPTS) {
              attempts++
              val current =
                txMutex.withLock {
                  val entity = txDao.getById(txId) ?: return@withLock null
                  reconcileEntityLocked(entity, allowRebroadcast = true)
                }
              if (current == null || TxStatus.valueOf(current.state).isTerminal) {
                break
              }
              delay(POLL_INTERVAL_MS)
            }
          } finally {
            synchronized(trackingJobs) { trackingJobs.remove(txId) }
          }
        }
      trackingJobs[txId] = job
    }
  }

  private suspend fun reconcileEntityLocked(
    entity: TxEntity,
    allowRebroadcast: Boolean,
  ): TxEntity {
    val currentStatus = runCatching { TxStatus.valueOf(entity.state) }.getOrDefault(TxStatus.FAILED)
    if (currentStatus.isTerminal) return entity

    val network = runCatching { Network.valueOf(entity.network) }.getOrNull() ?: return entity
    val accountAddress = TonAddress(entity.accountRaw, network)
    val nowMs = clockMs()
    val nowEpochSec = nowMs / 1000L

    // 1. Check message/trace inclusion and execution status on-chain first.
    val lookup =
      if (entity.kind == TxAssetKind.JETTON.name) {
        chainGateway
          .lookupJettonTraceStatus(
            network = network,
            messageHashHex = entity.messageHash,
            normalizedHashHex = entity.normalizedHash,
          )
          .getOrNull()
      } else {
        chainGateway
          .lookupMessageStatus(
            network = network,
            messageHashHex = entity.messageHash,
            normalizedHashHex = entity.normalizedHash,
          )
          .getOrNull()
      }

    if (lookup != null) {
      when (lookup.status) {
        OnChainMessageStatus.CONFIRMED -> {
          txDao.markTerminal(
            id = entity.id,
            finalState = TxStatus.CONFIRMED.name,
            txHash = lookup.txHash,
            lt = lookup.lt,
            feeUnits = lookup.fee?.units?.toString(),
            updatedAtEpochMs = nowMs,
            errorDetail = null,
          )
          balanceRepository?.refreshBalance(accountAddress, force = true)
          return txDao.getById(entity.id) ?: entity
        }
        OnChainMessageStatus.FAILED -> {
          txDao.markTerminal(
            id = entity.id,
            finalState = TxStatus.FAILED.name,
            txHash = lookup.txHash,
            lt = lookup.lt,
            feeUnits = lookup.fee?.units?.toString(),
            updatedAtEpochMs = nowMs,
            errorDetail = lookup.failureReason ?: "Transaction failed on-chain",
          )
          balanceRepository?.refreshBalance(accountAddress, force = true)
          return txDao.getById(entity.id) ?: entity
        }
        OnChainMessageStatus.PENDING -> Unit
      }
    }

    // 2. Check wallet seqno to determine if seqno was already consumed or validity window expired.
    val walletInfo = chainGateway.fetchWalletInfo(accountAddress).getOrNull()
    if (walletInfo != null) {
      if (walletInfo.seqno > entity.seqno) {
        // Seqno advanced without on-chain hash confirmation: never mark CONFIRMED or EXPIRED
        // from seqno alone. Move to UNCERTAIN, clear any stored BOC so it never rebroadcasts,
        // and keep checking by message hash.
        if (currentStatus != TxStatus.UNCERTAIN || entity.bocBase64 != null) {
          txDao.updateLifecycleState(
            id = entity.id,
            newState = TxStatus.UNCERTAIN.name,
            bocBase64 = null,
            updatedAtEpochMs = nowMs,
            errorDetail = "Sequence number advanced before message hash confirmation; verifying on TON",
          )
          return txDao.getById(entity.id) ?: entity
        }
        return entity
      }

      // walletInfo.seqno <= entity.seqno
      if (nowEpochSec > entity.validUntilEpochSec + EXPIRY_GRACE_SECONDS) {
        if (currentStatus == TxStatus.BROADCASTED || currentStatus == TxStatus.UNCERTAIN) {
          if (currentStatus != TxStatus.UNCERTAIN || entity.bocBase64 != null) {
            txDao.updateLifecycleState(
              id = entity.id,
              newState = TxStatus.UNCERTAIN.name,
              bocBase64 = null,
              updatedAtEpochMs = nowMs,
              errorDetail = "Validity window passed while awaiting message hash confirmation; verifying on TON",
            )
            return txDao.getById(entity.id) ?: entity
          }
          return entity
        }
        txDao.markTerminal(
          id = entity.id,
          finalState = TxStatus.EXPIRED.name,
          txHash = null,
          lt = null,
          feeUnits = null,
          updatedAtEpochMs = nowMs,
          errorDetail = "Transaction validity window expired before broadcast",
        )
        return txDao.getById(entity.id) ?: entity
      }

      if (
        allowRebroadcast &&
          currentStatus == TxStatus.SIGNED &&
          !entity.bocBase64.isNullOrBlank() &&
          nowEpochSec < entity.validUntilEpochSec
      ) {
        chainGateway.sendBoc(network, entity.bocBase64).fold(
          onSuccess = {
            txDao.updateLifecycleState(
              id = entity.id,
              newState = TxStatus.BROADCASTED.name,
              bocBase64 = null,
              updatedAtEpochMs = nowMs,
              errorDetail = null,
            )
          },
          onFailure = { err ->
            if (err is RejectedTransactionException) {
              txDao.markTerminal(
                id = entity.id,
                finalState = TxStatus.FAILED.name,
                txHash = null,
                lt = null,
                feeUnits = null,
                updatedAtEpochMs = nowMs,
                errorDetail = err.message ?: "Rejected during broadcast recovery",
              )
            }
          },
        )
        return txDao.getById(entity.id) ?: entity
      }
    }

    return entity
  }

  private fun TxRecord.toEntity(bocBase64: String?): TxEntity =
    TxEntity(
      id = id,
      network = network.name,
      accountRaw = accountRaw,
      messageHash = messageHash,
      normalizedHash = normalizedHash,
      txHash = txHash,
      seqno = seqno,
      direction = direction.name,
      kind = kind.name,
      jettonMaster = jettonMaster,
      amountUnits = amount.units.toString(),
      feeUnits = fee?.units?.toString(),
      counterpartyRaw = counterpartyRaw,
      comment = comment,
      state = state.name,
      validUntilEpochSec = validUntilEpochSec,
      lt = lt,
      bocBase64 = bocBase64,
      createdAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
      errorDetail = errorDetail,
    )

  private fun TxEntity.toDomain(): TxRecord {
    val txKind = TxAssetKind.valueOf(kind)
    val amountDecimals = if (txKind == TxAssetKind.JETTON) USDT_DECIMALS else GRAM_DECIMALS
    return TxRecord(
      id = id,
      network = Network.valueOf(network),
      accountRaw = accountRaw,
      messageHash = messageHash,
      normalizedHash = normalizedHash,
      txHash = txHash,
      seqno = seqno,
      direction = TxDirection.valueOf(direction),
      kind = txKind,
      amount = Money.parseUnits(amountUnits, amountDecimals),
      fee = feeUnits?.let { Money.parseUnits(it, GRAM_DECIMALS) },
      counterpartyRaw = counterpartyRaw,
      comment = comment,
      state = TxStatus.valueOf(state),
      validUntilEpochSec = validUntilEpochSec,
      lt = lt,
      createdAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
      errorDetail = errorDetail,
      jettonMaster = jettonMaster,
    )
  }

  companion object {
    const val EXPIRY_GRACE_SECONDS = 15L
    private const val POLL_INTERVAL_MS = 3500L
    private const val MAX_POLL_ATTEMPTS = 45
  }
}

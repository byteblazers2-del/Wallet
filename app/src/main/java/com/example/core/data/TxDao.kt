package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TxDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: TxEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(entities: List<TxEntity>)

  @Query(
    """
    SELECT * FROM tx
    WHERE accountRaw = :accountRaw AND network = :network
    ORDER BY createdAtEpochMs DESC, CAST(COALESCE(lt, '0') AS INTEGER) DESC
    """
  )
  fun observeByAccount(accountRaw: String, network: String): Flow<List<TxEntity>>

  @Query(
    """
    SELECT * FROM tx
    WHERE accountRaw = :accountRaw AND network = :network
      AND state IN ('DRAFT', 'SIGNED', 'BROADCASTED', 'UNCERTAIN')
    ORDER BY createdAtEpochMs ASC
    """
  )
  suspend fun getNonTerminalForAccount(accountRaw: String, network: String): List<TxEntity>

  @Query("SELECT * FROM tx WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): TxEntity?

  @Query(
    """
    SELECT lt FROM tx
    WHERE accountRaw = :accountRaw AND network = :network AND lt IS NOT NULL
    ORDER BY CAST(lt AS INTEGER) DESC
    LIMIT 1
    """
  )
  suspend fun getLatestLt(accountRaw: String, network: String): String?

  @Query(
    """
    SELECT COUNT(*) FROM tx
    WHERE accountRaw = :accountRaw AND network = :network AND lt IS NOT NULL
    """
  )
  suspend fun countChainSyncedTxs(accountRaw: String, network: String): Int

  @Query(
    """
    SELECT COUNT(*) FROM tx
    WHERE accountRaw = :accountRaw AND network = :network
      AND direction = 'IN' AND state = 'FAILED'
    """
  )
  suspend fun countFailedIncoming(accountRaw: String, network: String): Int

  @Query(
    """
    SELECT * FROM tx
    WHERE accountRaw = :accountRaw AND network = :network
      AND (
        (:hash1 != '' AND (messageHash = :hash1 OR normalizedHash = :hash1)) OR
        (:hash2 != '' AND (messageHash = :hash2 OR normalizedHash = :hash2))
      )
    LIMIT 1
    """
  )
  suspend fun findByMessageHashes(
    accountRaw: String,
    network: String,
    hash1: String,
    hash2: String,
  ): TxEntity?

  @Query(
    """
    SELECT COUNT(*) FROM tx
    WHERE accountRaw = :accountRaw AND network = :network
      AND kind = 'JETTON' AND direction = 'OUT'
      AND (
        (:txHash != '' AND txHash = :txHash) OR
        (
          counterpartyRaw = :counterpartyRaw AND amountUnits = :amountUnits
          AND createdAtEpochMs BETWEEN :minTimeMs AND :maxTimeMs
          AND id NOT LIKE 'jetton_%'
        )
      )
    """
  )
  suspend fun countMatchingOutgoingJetton(
    accountRaw: String,
    network: String,
    txHash: String,
    counterpartyRaw: String,
    amountUnits: String,
    minTimeMs: Long,
    maxTimeMs: Long,
  ): Int

  @Query(
    """
    UPDATE tx
    SET state = :newState,
        bocBase64 = :bocBase64,
        updatedAtEpochMs = :updatedAtEpochMs,
        errorDetail = :errorDetail
    WHERE id = :id
    """
  )
  suspend fun updateLifecycleState(
    id: String,
    newState: String,
    bocBase64: String?,
    updatedAtEpochMs: Long,
    errorDetail: String?,
  )

  @Query(
    """
    UPDATE tx
    SET state = :finalState,
        txHash = :txHash,
        lt = :lt,
        feeUnits = COALESCE(:feeUnits, feeUnits),
        bocBase64 = NULL,
        updatedAtEpochMs = :updatedAtEpochMs,
        errorDetail = :errorDetail
    WHERE id = :id
    """
  )
  suspend fun markTerminal(
    id: String,
    finalState: String,
    txHash: String?,
    lt: String?,
    feeUnits: String?,
    updatedAtEpochMs: Long,
    errorDetail: String?,
  )

  @Transaction
  suspend fun persistSignedAtomic(entity: TxEntity) {
    upsert(entity)
  }

  @Transaction
  suspend fun applyHistorySyncAtomic(entities: List<TxEntity>) {
    if (entities.isNotEmpty()) {
      upsertAll(entities)
    }
  }

  @Query("DELETE FROM tx")
  suspend fun clearAll()
}

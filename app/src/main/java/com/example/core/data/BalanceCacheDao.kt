package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceCacheDao {
  @Query(
    "SELECT * FROM balance_cache WHERE accountAddr = :accountAddr AND network = :network AND assetId = :assetId LIMIT 1"
  )
  fun observeBalance(
    accountAddr: String,
    network: String,
    assetId: String,
  ): Flow<BalanceCacheEntity?>

  @Query(
    "SELECT * FROM balance_cache WHERE accountAddr = :accountAddr AND network = :network AND assetId = :assetId LIMIT 1"
  )
  suspend fun getBalance(
    accountAddr: String,
    network: String,
    assetId: String,
  ): BalanceCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: BalanceCacheEntity)

  @Query("DELETE FROM balance_cache")
  suspend fun clearAll()
}

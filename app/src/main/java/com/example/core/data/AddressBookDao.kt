package com.example.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AddressBookDao {
  @Query("SELECT * FROM address_book WHERE network = :network ORDER BY createdAtEpochMs DESC")
  fun observeByNetwork(network: String): Flow<List<AddressBookEntity>>

  @Query("SELECT * FROM address_book WHERE network = :network AND rawAddress = :rawAddress LIMIT 1")
  suspend fun findByRawAddress(network: String, rawAddress: String): AddressBookEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entity: AddressBookEntity)

  @Query("DELETE FROM address_book WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM address_book")
  suspend fun clearAll()
}

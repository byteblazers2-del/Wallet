package com.example.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "address_book",
  indices = [
    Index(value = ["network", "rawAddress"], unique = true),
  ],
)
data class AddressBookEntity(
  @PrimaryKey val id: String,
  val network: String,
  val label: String,
  val rawAddress: String,
  val friendlyAddress: String,
  val createdAtEpochMs: Long,
)

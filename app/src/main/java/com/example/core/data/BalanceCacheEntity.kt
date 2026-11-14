package com.example.core.data

import androidx.room.Entity

@Entity(
  tableName = "balance_cache",
  primaryKeys = ["accountAddr", "network", "assetId"],
)
data class BalanceCacheEntity(
  val accountAddr: String,
  val network: String,
  val assetId: String,
  val amountUnits: String,
  val decimals: Int,
  val accountStatus: String,
  val providerName: String,
  val fetchedAt: Long,
  val extraAddress: String? = null,
  val lastTxLt: String? = null,
)

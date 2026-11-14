package com.example.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "tx",
  indices = [
    Index(value = ["accountRaw", "network", "createdAtEpochMs"]),
    Index(value = ["accountRaw", "network", "state"]),
    Index(value = ["accountRaw", "network", "seqno"]),
  ],
)
data class TxEntity(
  @PrimaryKey val id: String,
  val network: String,
  val accountRaw: String,
  val messageHash: String,
  val normalizedHash: String,
  val txHash: String?,
  val seqno: Int,
  val direction: String,
  val kind: String,
  val jettonMaster: String?,
  val amountUnits: String,
  val feeUnits: String?,
  val counterpartyRaw: String,
  val comment: String?,
  val state: String,
  val validUntilEpochSec: Long,
  val lt: String?,
  val bocBase64: String?,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val errorDetail: String?,
)

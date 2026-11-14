package com.example.core.data

import com.example.core.model.Network
import com.example.core.model.TonAddress
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AddressBookEntry(
  val id: String,
  val network: Network,
  val label: String,
  val address: TonAddress,
  val friendlyAddress: String,
  val createdAtEpochMs: Long,
)

class AddressBookRepository(
  private val dao: AddressBookDao,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
  fun observeEntries(network: Network): Flow<List<AddressBookEntry>> =
    dao.observeByNetwork(network.name).map { list ->
      list.map { entity ->
        val net = Network.valueOf(entity.network)
        AddressBookEntry(
          id = entity.id,
          network = net,
          label = entity.label,
          address = TonAddress(entity.rawAddress, net),
          friendlyAddress = entity.friendlyAddress,
          createdAtEpochMs = entity.createdAtEpochMs,
        )
      }
    }

  suspend fun saveContact(
    network: Network,
    labelInput: String,
    addressInput: String,
  ): Result<AddressBookEntry> = runCatching {
    val label = labelInput.trim()
    require(label.isNotEmpty()) { "Enter a contact name" }
    require(label.length <= 40) { "Contact name must be 40 characters or fewer" }

    val parsed = TonAddress.parseDetailed(addressInput.trim(), network).getOrThrow()
    val friendly = parsed.address.toFriendly(bounceable = parsed.isBounceable)
    val existing = dao.findByRawAddress(network.name, parsed.address.raw)
    val now = clockMs()
    val entity =
      AddressBookEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        network = network.name,
        label = label,
        rawAddress = parsed.address.raw,
        friendlyAddress = friendly,
        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
      )
    dao.upsert(entity)
    AddressBookEntry(
      id = entity.id,
      network = network,
      label = entity.label,
      address = parsed.address,
      friendlyAddress = entity.friendlyAddress,
      createdAtEpochMs = entity.createdAtEpochMs,
    )
  }

  suspend fun deleteContact(id: String) {
    dao.deleteById(id)
  }
}

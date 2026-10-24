package com.pennypilot.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Deleted event/operation identities survive removal; delayed work cannot resurrect a bill. */
@Entity(tableName = "capture_tombstones")
data class CaptureTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long
)

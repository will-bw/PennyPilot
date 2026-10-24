package com.pennypilot.app.data.db.entity

import androidx.room.*
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType

@Entity(
    tableName = "transactions",
    indices = [
        Index("occurred_at"),
        Index("category_id", "occurred_at"),
        Index("status"),
        Index(value = ["capture_event_key"], unique = true),
        Index(value = ["operation_key"], unique = true)
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "type") val type: TxType,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "merchant") val merchant: String? = null,
    @ColumnInfo(name = "account_tag") val accountTag: String? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "source") val source: TxSource = TxSource.MANUAL,
    @ColumnInfo(name = "status") val status: TxStatus = TxStatus.CONFIRMED,
    @ColumnInfo(name = "raw_payload") val rawPayload: String? = null,
    @ColumnInfo(name = "capture_event_key") val captureEventKey: String? = null,
    /** 仅表示需要用户核对的关联候选；不自动忽略、不覆盖原账目。 */
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long? = null,
    @ColumnInfo(name = "business_kind", defaultValue = "'standard'") val businessKind: String = "standard",
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "operation_key") val operationKey: String? = null,
    @ColumnInfo(name = "edit_version", defaultValue = "0") val editVersion: Long = 0
)

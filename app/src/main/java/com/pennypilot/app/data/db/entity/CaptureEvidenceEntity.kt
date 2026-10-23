package com.pennypilot.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxType

/** 原始捕获证据与事件别名，用户编辑账目不会改写证据或复活已忽略的事件。 */
@Entity(
    tableName = "capture_events",
    foreignKeys = [ForeignKey(
        entity = TransactionEntity::class, parentColumns = ["id"],
        childColumns = ["transaction_id"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("transaction_id"), Index("observed_at"),
        Index("order_domain", "order_id"), Index("package_name", "notification_key")]
)
data class CaptureEvidenceEntity(
    @PrimaryKey @ColumnInfo(name = "event_key") val eventKey: String,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "source") val source: TxSource,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    /** 防止不断更新通知把短时间窗口延长成无限滑动窗口。 */
    @ColumnInfo(name = "first_observed_at") val firstObservedAt: Long,
    @ColumnInfo(name = "notification_key") val notificationKey: String?,
    @ColumnInfo(name = "text_fingerprint") val textFingerprint: String,
    @ColumnInfo(name = "order_id") val orderId: String?,
    @ColumnInfo(name = "order_domain") val orderDomain: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "type") val type: TxType,
    @ColumnInfo(name = "merchant_fingerprint") val merchantFingerprint: String?
)

package com.pennypilot.app.service

import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.db.entity.CaptureEvidenceEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus

data class CaptureEvidence(
    val packageName: String,
    val eventKey: String,
    val observedAt: Long,
    val text: String,
    // 通知更新身份必须由系统 key + 上游 notification.when 构成；when 缺失时传 null。
    val notificationKey: String? = null,
    val orderId: String? = null,
    val orderDomain: String? = null,
    val metadataJson: String? = null
)

sealed interface CaptureResult {
    data class Inserted(val transactionId: Long, val duplicateOf: Long? = null) : CaptureResult
    data class Duplicate(val transactionId: Long) : CaptureResult
    data class Conflict(val transactionId: Long) : CaptureResult
    data object StaleRevision : CaptureResult
    data object StaleEvidence : CaptureResult
}

object CapturePersistence {
    internal const val NOTIFICATION_UPDATE_WINDOW_MS = 15_000L
    internal const val POSSIBLE_DUPLICATE_WINDOW_MS = 120_000L

    /**
     * 检查证据、保存别名及账目在同一事务执行，两个捕获服务并发时也只写入一次。
     * 自动合并仅接受同事件、同域可靠订单号，或 15 秒内同通知 key/when + 完全相同文本。
     * 商户同额等弱证据只关联新待确认账目；不修改已有账目和用户确认/忽略结果。
     */
    suspend fun capture(
        database: AppDatabase,
        expectedRevision: Long,
        transaction: TransactionEntity,
        evidence: CaptureEvidence,
        isCurrent: () -> Boolean = { true }
    ): CaptureResult = database.withTransaction {
        if (LedgerRevision.current != expectedRevision) return@withTransaction CaptureResult.StaleRevision
        if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
        require(transaction.id == 0L && evidence.eventKey.isNotBlank() && evidence.packageName.isNotBlank())
        require(transaction.source == TxSource.NOTIFICATION || transaction.source == TxSource.ACCESSIBILITY)
        val dao = database.transactionDao()
        dao.tombstone(evidence.eventKey)?.let {
            return@withTransaction CaptureResult.Duplicate(it.transactionId)
        }
        val incoming = CaptureEvidenceEntity(
            eventKey = evidence.eventKey, transactionId = 0,
            packageName = evidence.packageName, source = transaction.source,
            observedAt = evidence.observedAt, firstObservedAt = evidence.observedAt,
            notificationKey = evidence.notificationKey,
            textFingerprint = CaptureEventIdentity.fingerprint(evidence.text),
            orderId = CaptureEventIdentity.reliableOrderId(evidence.orderId),
            orderDomain = evidence.orderDomain?.takeIf { it.isNotBlank() }
                ?: CaptureEventIdentity.domainFor(evidence.packageName),
            amount = transaction.amount, type = transaction.type,
            merchantFingerprint = CaptureEventIdentity.merchantFingerprint(transaction.merchant),
            metadataJson = evidence.metadataJson ?: transaction.metadataJson
        )
        incoming.orderId?.let { order ->
            dao.tombstone("order:${incoming.orderDomain}:$order:${incoming.type.name}:${incoming.amount}")?.let {
                return@withTransaction CaptureResult.Duplicate(it.transactionId)
            }
        }
        dao.captureEvidenceByKey(evidence.eventKey)?.let { previous ->
            if (!sameFinancials(previous, incoming) || conflictingOrders(previous, incoming)) {
                return@withTransaction CaptureResult.Conflict(previous.transactionId)
            }
            // Late loading enriches source evidence only. Never replace user-edited ledger fields.
            if (sameFinancials(previous, incoming) && !conflictingOrders(previous, incoming)) {
                if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
                dao.updateCaptureEvidence(previous.copy(
                    observedAt = maxOf(previous.observedAt, incoming.observedAt),
                    orderId = previous.orderId ?: incoming.orderId,
                    merchantFingerprint = previous.merchantFingerprint ?: incoming.merchantFingerprint,
                    metadataJson = mergeMetadata(previous.metadataJson, incoming.metadataJson)
                ))
                val existing = dao.getById(previous.transactionId)
                if (existing != null && existing.editVersion == 0L && existing.status == TxStatus.PENDING) {
                    val enriched = existing.copy(
                        merchant = existing.merchant ?: transaction.merchant,
                        accountTag = existing.accountTag ?: transaction.accountTag,
                        metadataJson = mergeMetadata(existing.metadataJson, transaction.metadataJson),
                        businessKind = if (existing.businessKind == "unknown") transaction.businessKind else existing.businessKind
                    )
                    if (enriched != existing) dao.update(enriched)
                }
            }
            return@withTransaction CaptureResult.Duplicate(previous.transactionId)
        }
        // v4 的事件没有独立证据行；首次回放补齐元数据但保留账目及状态。
        val legacy = CaptureEventIdentity.legacyNotificationKey(evidence.eventKey)?.let { oldKey ->
            dao.getByCaptureEvent(oldKey)?.takeIf {
                it.amount == incoming.amount && it.type == incoming.type &&
                    it.rawPayload?.let(CaptureEventIdentity::fingerprint) == incoming.textFingerprint
            }
        }
        (dao.getByCaptureEvent(evidence.eventKey) ?: legacy)?.let {
            if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
            dao.insertCaptureEvidence(incoming.copy(transactionId = it.id))
            return@withTransaction CaptureResult.Duplicate(it.id)
        }
        val orderEvidence = incoming.orderId?.let { orderId -> dao.captureEvidenceByOrder(incoming.orderDomain, orderId) }.orEmpty()
        val sameOrder = orderEvidence.firstOrNull { sameFinancials(it, incoming) }
        val orderConflict = orderEvidence.firstOrNull { it.type == incoming.type && it.amount != incoming.amount }
        val notificationUpdate = if (incoming.source == TxSource.NOTIFICATION && incoming.notificationKey != null) {
            dao.captureEvidenceByNotification(
                incoming.packageName, incoming.notificationKey,
                incoming.observedAt - NOTIFICATION_UPDATE_WINDOW_MS,
                incoming.observedAt + NOTIFICATION_UPDATE_WINDOW_MS
            ).firstOrNull { isNotificationUpdate(it, incoming) }
        } else null
        val duplicate = sameOrder ?: notificationUpdate
        if (duplicate != null) {
            if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
            dao.insertCaptureEvidence(incoming.copy(
                transactionId = duplicate.transactionId, firstObservedAt = duplicate.firstObservedAt
            ))
            return@withTransaction CaptureResult.Duplicate(duplicate.transactionId)
        }
        val possibleDuplicate = if (incoming.merchantFingerprint != null) {
            dao.captureEvidenceNear(
                incoming.observedAt - POSSIBLE_DUPLICATE_WINDOW_MS,
                incoming.observedAt + POSSIBLE_DUPLICATE_WINDOW_MS, incoming.amount
            ).firstOrNull { isPossibleDuplicate(it, incoming) }
        } else null
        // 用户可能已先手记/AI 补记，再收到迟到通知；仅用具体商户完全匹配给出弱提示。
        val manuallyRecorded = if (possibleDuplicate == null && incoming.merchantFingerprint != null) {
            dao.nonCapturedTransactionsNear(
                incoming.observedAt - POSSIBLE_DUPLICATE_WINDOW_MS,
                incoming.observedAt + POSSIBLE_DUPLICATE_WINDOW_MS, incoming.amount, incoming.type
            ).firstOrNull {
                CaptureEventIdentity.merchantFingerprint(it.merchant) == incoming.merchantFingerprint
            }
        } else null
        val duplicateOf = orderConflict?.transactionId ?: possibleDuplicate?.transactionId ?: manuallyRecorded?.id
        if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
        val id = dao.insert(transaction.copy(
            captureEventKey = incoming.eventKey, duplicateOf = duplicateOf,
            metadataJson = if (orderConflict == null) transaction.metadataJson else kotlinx.serialization.json.buildJsonObject {
                transaction.metadataJson?.let { raw -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject }.getOrNull()?.forEach { (key, value) -> put(key, value) } }
                put("conflictingTransactionId", kotlinx.serialization.json.JsonPrimitive(orderConflict.transactionId))
                put("questions", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("同一订单出现不同金额，请核对原账目后再确认。"))))
            }.toString(),
            // Explicit auto-record policy can confirm verified rules; weak duplicate evidence never can.
            status = if (duplicateOf != null) TxStatus.PENDING else transaction.status
        ))
        dao.insertCaptureEvidence(incoming.copy(transactionId = id))
        CaptureResult.Inserted(id, duplicateOf)
    }

    private fun mergeMetadata(previous: String?, incoming: String?): String? {
        if (incoming == null) return previous
        if (previous == null) return incoming
        return runCatching {
            val old = kotlinx.serialization.json.Json.parseToJsonElement(previous) as kotlinx.serialization.json.JsonObject
            val fresh = kotlinx.serialization.json.Json.parseToJsonElement(incoming) as kotlinx.serialization.json.JsonObject
            kotlinx.serialization.json.JsonObject(fresh + old).toString()
        }.getOrDefault(previous)
    }

    internal fun isNotificationUpdate(previous: CaptureEvidenceEntity, incoming: CaptureEvidenceEntity): Boolean =
        previous.source == TxSource.NOTIFICATION && incoming.source == TxSource.NOTIFICATION &&
            previous.packageName == incoming.packageName &&
            previous.notificationKey != null && previous.notificationKey == incoming.notificationKey &&
            previous.textFingerprint == incoming.textFingerprint && sameFinancials(previous, incoming) &&
            !conflictingOrders(previous, incoming) &&
            incoming.observedAt - previous.firstObservedAt in 0..NOTIFICATION_UPDATE_WINDOW_MS

    internal fun isPossibleDuplicate(previous: CaptureEvidenceEntity, incoming: CaptureEvidenceEntity): Boolean =
        sameFinancials(previous, incoming) && incoming.merchantFingerprint != null &&
            previous.merchantFingerprint == incoming.merchantFingerprint &&
            incoming.observedAt - previous.observedAt in -POSSIBLE_DUPLICATE_WINDOW_MS..POSSIBLE_DUPLICATE_WINDOW_MS &&
            !conflictingOrders(previous, incoming) &&
            // 页面重新进入/进程重建可能换会话身份，只提示核对，不能按文本静默吞单。
            (previous.packageName != incoming.packageName || previous.source != incoming.source ||
                incoming.source == TxSource.ACCESSIBILITY)

    private fun sameFinancials(a: CaptureEvidenceEntity, b: CaptureEvidenceEntity): Boolean =
        a.amount == b.amount && a.type == b.type

    private fun conflictingOrders(a: CaptureEvidenceEntity, b: CaptureEvidenceEntity): Boolean =
        a.orderDomain == b.orderDomain && a.orderId != null && b.orderId != null && a.orderId != b.orderId

    /** 恢复与捕获写入使用同一数据库事务队列，避免旧解析跨恢复写入新账本。 */
    suspend fun insertIfCurrent(
        database: AppDatabase,
        expectedRevision: Long,
        transaction: TransactionEntity
    ): Long = database.withTransaction {
        if (LedgerRevision.current != expectedRevision) return@withTransaction -1L
        database.transactionDao().insertCaptured(transaction)
    }
}

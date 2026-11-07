package com.pennypilot.app.service

import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.db.entity.CaptureEvidenceEntity
import com.pennypilot.app.data.recognition.CaptureSemanticResolver
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString

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
    data class Enriched(val transactionId: Long) : CaptureResult
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
        isCurrent: () -> Boolean = { true },
        enrichSemantics: Boolean = false
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
            if (!sameFinancials(previous, incoming) || conflictingOrders(previous, incoming) ||
                previous.orderId != null && incoming.orderId != null && previous.orderDomain != incoming.orderDomain) {
                return@withTransaction CaptureResult.Conflict(previous.transactionId)
            }
            // The event identity fixes financial facts; semantic enrichment is only
            // allowed while the user has never edited, confirmed or ignored it.
            if (sameFinancials(previous, incoming) && !conflictingOrders(previous, incoming)) {
                if (!isCurrent()) return@withTransaction CaptureResult.StaleEvidence
                dao.updateCaptureEvidence(previous.copy(
                    observedAt = maxOf(previous.observedAt, incoming.observedAt),
                    orderId = previous.orderId ?: incoming.orderId,
                    orderDomain = if (previous.orderId == null && incoming.orderId != null) incoming.orderDomain else previous.orderDomain,
                    merchantFingerprint = previous.merchantFingerprint ?: incoming.merchantFingerprint,
                    metadataJson = mergeMetadata(previous.metadataJson, incoming.metadataJson,
                        replaceTransactionTime = isReceiptTimeUpgrade(metadata(previous.metadataJson), metadata(incoming.metadataJson)))
                ))
                val existing = dao.getById(previous.transactionId)
                if (existing != null && canEnrich(existing)) {
                    val enriched = enrichTransaction(database, existing, transaction, enrichSemantics)
                    if (enriched != existing) {
                        dao.update(enriched)
                        if (visibleFieldsChanged(existing, enriched))
                            return@withTransaction CaptureResult.Enriched(existing.id)
                    }
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
            val existing = dao.getById(duplicate.transactionId)
            if (existing != null && canEnrich(existing)) {
                val enriched = enrichTransaction(database, existing, transaction, enrichSemantics)
                if (enriched != existing) {
                    dao.update(enriched)
                    if (visibleFieldsChanged(existing, enriched))
                        return@withTransaction CaptureResult.Enriched(existing.id)
                }
            }
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

    private fun canEnrich(transaction: TransactionEntity): Boolean =
        transaction.source in setOf(TxSource.NOTIFICATION, TxSource.ACCESSIBILITY) &&
            transaction.editVersion == 0L && transaction.status != TxStatus.IGNORED && transaction.duplicateOf == null

    private suspend fun enrichTransaction(database: AppDatabase, existing: TransactionEntity, incoming: TransactionEntity,
        enrichSemantics: Boolean): TransactionEntity {
        val oldMetadata = metadata(existing.metadataJson)
        val newMetadata = metadata(incoming.metadataJson)
        // Replayed local callbacks can add facts, but must not replace a completed
        // model classification with their original generic fallback category.
        val incomingModel = newMetadata?.get("semanticModelApplied") == JsonPrimitive(true)
        val modelHasCurrentContext = oldMetadata?.get("semanticEvidenceFingerprint") == null ||
            newMetadata?.get("semanticEvidenceFingerprint") != null || newMetadata?.get("semanticAttemptedFingerprint") != null
        val replaceSemantics = enrichSemantics && (oldMetadata?.get("semanticModelApplied") != JsonPrimitive(true) ||
            incomingModel && modelHasCurrentContext)
        val previousCategory = if (enrichSemantics && existing.categoryId != incoming.categoryId)
            database.categoryDao().getById(existing.categoryId) else null
        val oldCategoryFallback = oldMetadata?.get("semanticCategoryFallback")?.let { it == JsonPrimitive(true) }
            ?: previousCategory?.name?.let(CaptureSemanticResolver::isFallbackCategory)
        val categoryFallback = newMetadata?.get("semanticCategoryFallback")
        val upgradeCategory = enrichSemantics && existing.categoryId != incoming.categoryId &&
            oldCategoryFallback == true && categoryFallback == JsonPrimitive(false)
        val keepSpecificCategory = replaceSemantics && categoryFallback == JsonPrimitive(true) &&
            oldCategoryFallback == false && previousCategory?.let { it.enabled && it.type == incoming.type } == true
        val keepKnownMeaning = replaceSemantics && incoming.businessKind == "unknown" &&
            existing.businessKind in CaptureSemanticResolver.businessKinds
        val incomingMetadata = if (newMetadata != null && (keepSpecificCategory || keepKnownMeaning)) {
            val retained = newMetadata.toMutableMap()
            val assumptions = (newMetadata["assumptions"] as? JsonArray).orEmpty().toMutableList()
            if (keepSpecificCategory) {
                retained["semanticCategoryId"] = JsonPrimitive(existing.categoryId)
                retained["semanticCategoryFallback"] = JsonPrimitive(false)
                assumptions.removeAll { (it as? JsonPrimitive)?.content?.startsWith("分类暂用") == true }
                assumptions += JsonPrimitive("分类保留原结果，补充凭证没有更明确的归类依据")
            }
            if (keepKnownMeaning) {
                retained["semanticBusinessKind"] = JsonPrimitive(existing.businessKind)
                assumptions.remove(JsonPrimitive("已保留金额与资金方向，交易用途待确认"))
                assumptions += JsonPrimitive("补充凭证的用途证据不足，保留已识别用途")
            }
            retained["assumptions"] = JsonArray(assumptions)
            JsonObject(retained).toString()
        } else incoming.metadataJson
        // 只升级明确标记为采集时间的回退值；旧记录缺少来源时，不靠时间相等猜测。
        val replaceTransactionTime = isReceiptTimeUpgrade(oldMetadata, newMetadata)
        fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
        fun fillMissing(previous: String?, fresh: String?): String? = previous.nonBlank() ?: fresh.nonBlank() ?: previous
        val oldDetails = oldMetadata?.get("receiptDetails") as? JsonObject
        val newDetails = newMetadata?.get("receiptDetails") as? JsonObject
        val details = mergeReceiptDetails(oldDetails, newDetails)
        val oldFundingAccount = detailValue(oldDetails, "fundingAccount")
        val freshFundingAccount = detailValue(newDetails, "fundingAccount")
        val account = when {
            oldFundingAccount != null -> fillMissing(existing.accountTag, oldFundingAccount)
            freshFundingAccount != null && (existing.accountTag.isNullOrBlank() ||
                existing.accountTag in setOf("微信", "微信支付", "支付宝", "支付宝支付", "云闪付", "银联云闪付")) -> freshFundingAccount
            replaceSemantics && incomingModel -> incoming.accountTag.nonBlank() ?: existing.accountTag
            else -> fillMissing(existing.accountTag, incoming.accountTag)
        }
        val oldReceiptNote = CaptureSemanticResolver.receiptNote(detailValue(oldDetails, "productDescription"), detailValue(oldDetails, "remark"))
        val receiptNote = CaptureSemanticResolver.receiptNote(detailValue(details, "productDescription"), detailValue(details, "remark"))
        val note = when {
            replaceSemantics && incomingModel -> incoming.note.nonBlank() ?: existing.note
            oldMetadata?.get("semanticModelApplied") == JsonPrimitive(true) -> fillMissing(existing.note, receiptNote ?: incoming.note)
            existing.note.isNullOrBlank() || existing.note == oldReceiptNote || existing.note == "用途暂按资金转移记录" ->
                receiptNote ?: incoming.note.nonBlank() ?: existing.note
            else -> existing.note
        }
        return existing.copy(
            merchant = fillMissing(existing.merchant, incoming.merchant),
            accountTag = account,
            categoryId = if (upgradeCategory || replaceSemantics && !keepSpecificCategory) incoming.categoryId else existing.categoryId,
            note = note,
            occurredAt = if (replaceTransactionTime) incoming.occurredAt else existing.occurredAt,
            metadataJson = mergeMetadata(existing.metadataJson, incomingMetadata, replaceSemantics, replaceTransactionTime,
                replaceCategory = upgradeCategory && !replaceSemantics),
            businessKind = if (replaceSemantics && !keepKnownMeaning || existing.businessKind == "unknown")
                incoming.businessKind else existing.businessKind,
            status = if (replaceSemantics && incoming.status == TxStatus.CONFIRMED) TxStatus.CONFIRMED else existing.status
        )
    }

    private fun visibleFieldsChanged(previous: TransactionEntity, incoming: TransactionEntity): Boolean =
        previous.categoryId != incoming.categoryId || previous.businessKind != incoming.businessKind ||
            previous.accountTag != incoming.accountTag || previous.note != incoming.note || previous.status != incoming.status ||
            previous.merchant != incoming.merchant || previous.occurredAt != incoming.occurredAt

    private fun metadata(raw: String?): JsonObject? = raw?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }

    private fun isReceiptTimeUpgrade(previous: JsonObject?, incoming: JsonObject?): Boolean =
        previous?.get("transactionTimeSource") == JsonPrimitive("observed") &&
            incoming?.get("transactionTimeSource") == JsonPrimitive("receipt")

    private fun mergeMetadata(previous: String?, incoming: String?, preferIncoming: Boolean = false,
        replaceTransactionTime: Boolean = false, replaceCategory: Boolean = false): String? {
        if (incoming == null) return previous
        return runCatching {
            val old = previous?.let { Json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
            val fresh = Json.parseToJsonElement(incoming) as JsonObject
            val merged = (if (preferIncoming) old + fresh else fresh + old).toMutableMap()
            fresh["semanticAttemptedFingerprint"]?.let { merged["semanticAttemptedFingerprint"] = it }
            if (replaceCategory) {
                fresh["semanticCategoryId"]?.let { merged["semanticCategoryId"] = it }
                fresh["semanticCategoryFallback"]?.let { merged["semanticCategoryFallback"] = it }
                fun isCategoryAssumption(value: kotlinx.serialization.json.JsonElement) =
                    (value as? JsonPrimitive)?.content?.startsWith("分类") == true
                merged["assumptions"] = JsonArray(
                    (old["assumptions"] as? JsonArray).orEmpty().filterNot(::isCategoryAssumption) +
                        (fresh["assumptions"] as? JsonArray).orEmpty().filter(::isCategoryAssumption))
            }
            val timeKeys = setOf("transactionTimeSource", "timePrecision", "observedAt", "receiptOccurredAt")
            timeKeys.forEach { key ->
                val value = if (replaceTransactionTime) fresh[key] else old[key]
                if (value == null) merged.remove(key) else merged[key] = value
            }
            val oldFields = (old["fieldEvidence"] as? JsonObject).orEmpty().filterValues {
                it != JsonNull && (it !is JsonPrimitive || !it.isString || it.content.isNotBlank())
            }
            val freshFields = (fresh["fieldEvidence"] as? JsonObject).orEmpty().filterValues {
                it != JsonNull && (it !is JsonPrimitive || !it.isString || it.content.isNotBlank())
            }
            if (old.containsKey("fieldEvidence") || fresh.containsKey("fieldEvidence")) {
                val fields = (freshFields + oldFields).toMutableMap()
                val timeEvidence = if (replaceTransactionTime) freshFields["occurredAt"] else oldFields["occurredAt"]
                if (timeEvidence == null) fields.remove("occurredAt") else fields["occurredAt"] = timeEvidence
                merged["fieldEvidence"] = JsonObject(fields)
            }
            if (old.containsKey("receiptDetails") || fresh.containsKey("receiptDetails")) {
                merged["receiptDetails"] = mergeReceiptDetails(old["receiptDetails"] as? JsonObject,
                    fresh["receiptDetails"] as? JsonObject)
            }
            if (old.containsKey("receiptContext") || fresh.containsKey("receiptContext")) {
                val context = CaptureReceiptContext.mergeContext(CaptureReceiptContext.readContext(old) + CaptureReceiptContext.readContext(fresh))
                merged["receiptContext"] = Json.parseToJsonElement(Json.encodeToString(context))
            }
            JsonObject(merged).toString()
        }.getOrDefault(previous)
    }

    private fun detailValue(details: JsonObject?, field: String): String? =
        (details?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

    private fun mergeReceiptDetails(previous: JsonObject?, incoming: JsonObject?): JsonObject {
        val fields = listOf("merchantName", "payeeName", "productDescription", "remark", "paymentChannel", "fundingAccount")
        val values = linkedMapOf<String, JsonPrimitive>()
        val sources = linkedMapOf<String, JsonPrimitive>()
        fields.forEach { field ->
            val old = detailValue(previous, field)
            val value = old ?: detailValue(incoming, field) ?: return@forEach
            values[field] = JsonPrimitive(value)
            val source = (if (old != null) previous else incoming)?.get("fieldSources") as? JsonObject
            detailValue(source, field)?.let { sources[field] = JsonPrimitive(it) }
        }
        return JsonObject(values + ("fieldSources" to JsonObject(sources)))
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

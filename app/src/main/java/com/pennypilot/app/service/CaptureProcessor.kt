package com.pennypilot.app.service

import android.content.Context
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.ModelRole
import com.pennypilot.app.data.recognition.*
import com.pennypilot.app.data.parser.CategoryInferrer
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.NotificationParser
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.util.MoneyUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 所有后台入口共用校验和原子写入；云识别独立授权、加密持久排队，不阻塞采集。 */
@Singleton
class CaptureProcessor internal constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences,
    private val llmClient: LLMClient,
    private val monitor: CaptureServiceMonitor,
    private val recognitionStore: RecognitionTaskStore?
) {
    @Inject constructor(@ApplicationContext context: Context, database: AppDatabase,
        preferences: AppPreferences, llmClient: LLMClient, monitor: CaptureServiceMonitor)
        : this(context, database, preferences, llmClient, monitor, null)

    private val countMutex = Mutex()
    private val recognitionCommitMutex = Mutex()
    private val taskStore by lazy { recognitionStore ?: RecognitionTaskStore(context) }
    private val recognitionEngine by lazy { PaymentRecognitionEngine(llmClient) }

    suspend fun refreshTodayCount() = countMutex.withLock {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        monitor.setTodayCount(database.transactionDao().countCapturedSince(start))
    }

    suspend fun processNotification(input: CapturedNotification, revision: Long, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent() || revision != LedgerRevision.current || !preferences.notificationCaptureEnabled.first()) return
        val eventKey = CaptureEventIdentity.key(input.packageName, input.notificationKey, input.postedAt,
            "${input.title}\n${input.content}")
        val rules = database.parseRuleDao().getRulesForPackage(input.packageName)
        val parsed = NotificationParser.parse(input.packageName, input.title, input.content, rules,
            false) { null }
        if (!isCurrent() || revision != LedgerRevision.current || !preferences.notificationCaptureEnabled.first()) return
        val text = "${input.title}\n${input.content}".trim()
        if (parsed != null) {
            val saved = recognitionCommitMutex.withLock {
                save(parsed, input.packageName, text, input.postedAt,
                    TxSource.NOTIFICATION, eventKey, input.updateIdentity, revision, verifiedRule = true,
                    businessKind = parsed.businessKind,
                    metadata = captureMetadata(parsed, "notification"), isCurrent = isCurrent)
            }
            if (saved && preferences.notificationCloudParsingEnabled.first() && isCurrent() && needsSemanticRecognition(eventKey)) {
                enqueueRecognition(input.packageName, text, eventKey, input.postedAt, revision,
                    TxSource.NOTIFICATION, input.updateIdentity)
            }
        }
        else if (preferences.notificationCloudParsingEnabled.first() &&
            NotificationParser.isCloudCandidate(input.packageName, input.title, input.content)) {
            enqueueRecognition(input.packageName, text, eventKey, input.postedAt, revision,
                TxSource.NOTIFICATION, input.updateIdentity)
        }
    }

    /** Called only for a safe read-only payment surface; returns after durable enqueue, not HTTP. */
    suspend fun processUnrecognizedScreen(
        packageName: String, text: String, eventKey: String, observedAt: Long,
        revision: Long, structure: String? = null, images: List<String> = emptyList()
    ): Boolean {
        if (revision != LedgerRevision.current || !preferences.screenCaptureEnabled.first() ||
            (!preferences.screenCloudParsingEnabled.first() && images.isEmpty())) return false
        if (images.isNotEmpty() && !preferences.cloudImageParsingEnabled.first()) return false
        // Only the read-only receipt tree from the capture adapter is accepted, never input/password nodes.
        return enqueueRecognition(packageName, text, eventKey, observedAt, revision, TxSource.ACCESSIBILITY, null, images,
            structure.takeIf { (it?.length ?: 0) <= 32_768 && preferences.screenCloudParsingEnabled.first() })
    }

    private suspend fun enqueueRecognition(packageName: String, text: String, eventKey: String,
        observedAt: Long, revision: Long, source: TxSource, notificationKey: String?, images: List<String> = emptyList(), structure: String? = null): Boolean {
        if (!PaymentProposalValidator.isSafeCandidate(packageName, text)) return false
        return try {
            val task = RecognitionTask(RecognitionTaskStore.id(eventKey), eventKey, packageName, text,
                observedAt, revision, llmClient.operationIdentity(if (images.isEmpty()) ModelRole.TEXT else ModelRole.VISION), source.name, notificationKey, images = images, structure = structure)
            if (revision != LedgerRevision.current || !taskAllowed(task)) return false
            val enqueued = recognitionCommitMutex.withLock {
                if (!taskAllowed(task)) return false
                taskStore.enqueue(task)
            }
            when (enqueued) {
                RecognitionTaskStore.EnqueueResult.FULL -> { monitor.onError("待识别支付过多，请联网完成识别后再试"); false }
                else -> {
                    RecognitionWorker.schedule(context, task.id, enqueueSuccessor = enqueued != RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED)
                    if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("model_queued", packageName)
                    true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { monitor.onError("支付识别任务未能保存，请检查 AI 配置后重试"); false }
    }

    private suspend fun taskAllowed(task: RecognitionTask): Boolean = task.ledgerRevision == LedgerRevision.current &&
        (task.structure == null || preferences.screenCloudParsingEnabled.first()) &&
        if (task.source == TxSource.ACCESSIBILITY.name) preferences.screenCaptureEnabled.first() && (if (task.images.isEmpty()) preferences.screenCloudParsingEnabled.first() else preferences.cloudImageParsingEnabled.first() && preferences.screenVisualCaptureEnabled.first())
        else preferences.notificationCaptureEnabled.first() && preferences.notificationCloudParsingEnabled.first()

    suspend fun processRecognitionTask(id: String): Boolean = RecognitionTaskRunner(
        taskStore, llmClient, ::taskAllowed,
        commit = ::completeRecognition,
        report = monitor::onError,
        onState = { task, status -> if (task.source == TxSource.ACCESSIBILITY.name) monitor.onScreenStatus(status, task.packageName) },
        semanticContext = { task ->
            val existing = database.transactionDao().captureEvidenceByKey(task.eventKey)
                ?.let { database.transactionDao().getById(it.transactionId) }
            captureSemanticContext(existing?.merchant, existing?.type?.name, existing?.id)
        }
    ).run(id)

    internal suspend fun completeRecognition(task: RecognitionTask, proposal: PaymentProposal,
        parsed: NotificationParseResult, isCurrent: () -> Boolean): Boolean = recognitionCommitMutex.withLock {
        if (!isCurrent() || !taskAllowed(task)) false else
            save(parsed, task.packageName, task.text, task.observedAt,
                TxSource.valueOf(task.source), task.eventKey, task.notificationKey, task.ledgerRevision,
                metadata = recognitionEngine.metadata(proposal.copy(merchant = parsed.merchant,
                    merchantEvidence = proposal.merchantEvidence.takeIf { parsed.merchant != null })),
                businessKind = proposal.businessKind, proposal = proposal, isCurrent = isCurrent,
                isAllowed = {
                    taskAllowed(task) && task.configurationId == llmClient.operationIdentity(
                        if (task.images.isEmpty()) ModelRole.TEXT else ModelRole.VISION)
                })
    }

    private fun receiptText(parsed: NotificationParseResult): String = buildString {
        append(when {
            parsed.businessKind == "refund" -> "退款已到账"
            parsed.scene == "transfer_sent" -> "转账成功"
            parsed.direction == "INCOME" -> "收款成功"
            else -> "支付成功"
        })
        append(" ").append(MoneyUtil.centsToYuanString(parsed.amountCents)).append("元")
        parsed.merchant?.let { append("\n商户：").append(it) }
        parsed.orderId?.let { append(if (parsed.orderIdKind == "refund") "\n退款单号：" else "\n订单号：").append(it) }
        parsed.paymentMethod?.let { append("\n支付方式：").append(it) }
        when (parsed.scene) {
            "red_packet_sent" -> append("\n交易场景：发出红包")
            "red_packet_received" -> append("\n交易场景：收到红包")
            "qr_payment_received" -> append("\n交易场景：收款码到账")
            "merchant_payment" -> append("\n交易场景：商户支付")
            "account_transfer" -> append("\n账户互转")
        }
    }

    suspend fun processScreen(
        packageName: String,
        parsed: NotificationParseResult,
        eventKey: String,
        observedAt: Long,
        revision: Long
    ): Boolean {
        if (revision != LedgerRevision.current || !preferences.screenCaptureEnabled.first()) return false
        // 只保留已提取的交易证据；整页文本、非交易节点不会被写入账本或发送到 AI。
        val receipt = receiptText(parsed)
        val saved = recognitionCommitMutex.withLock {
            save(parsed, packageName, receipt, observedAt, TxSource.ACCESSIBILITY,
                eventKey, null, revision, verifiedRule = true, businessKind = parsed.businessKind,
                metadata = captureMetadata(parsed, "page"))
        }
        if (saved && preferences.screenCloudParsingEnabled.first() && needsSemanticRecognition(eventKey))
            enqueueRecognition(packageName, receipt, eventKey, observedAt, revision, TxSource.ACCESSIBILITY, null)
        return saved
    }

    private suspend fun needsSemanticRecognition(eventKey: String): Boolean {
        val row = database.transactionDao().captureEvidenceByKey(eventKey)
            ?.let { database.transactionDao().getById(it.transactionId) } ?: return false
        return row.editVersion == 0L && row.status != TxStatus.IGNORED &&
            (row.metadataJson?.let { Json.parseToJsonElement(it) as JsonObject }?.get("semanticModelApplied") != JsonPrimitive(true))
    }

    private suspend fun captureSemanticContext(merchant: String?, direction: String?, excludedId: Long? = null): CaptureSemanticContext {
        val type = when (direction) { "EXPENSE" -> TxType.EXPENSE; "INCOME" -> TxType.INCOME; else -> null }
        val categories = database.categoryDao().observeAll().first().filter { it.enabled && (type == null || it.type == type) }
        val history = if (merchant.isNullOrBlank() || type == null) emptyList() else
            database.transactionDao().relatedCaptureHistory(merchant, type).filter { it.id != excludedId }.map { row ->
                CaptureSemanticHistory(merchant, row.type.name, row.businessKind, row.categoryId, row.accountTag, row.note)
            }
        val rawPreferences = context.getSharedPreferences("pennypilot_flutter", Context.MODE_PRIVATE)
            .getString("recognition_preferences", "[]").orEmpty()
        val remembered = runCatching {
            val rows = JSONArray(rawPreferences)
            (0 until minOf(rows.length(), 100)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val name = row.optString("merchant")
                val rowDirection = row.optString("type")
                if (name != merchant || rowDirection != direction) return@mapNotNull null
                val category = categories.firstOrNull { it.name == row.optString("category") && it.type.name == rowDirection }
                CaptureSemanticPreference(name, rowDirection, category?.id,
                    row.opt("businessKind") as? String, row.opt("accountTag") as? String)
            }
        }.getOrDefault(emptyList())
        return CaptureSemanticContext(ZonedDateTime.now().toString(), categories.map {
            CaptureSemanticCategory(it.id, it.name, it.type.name)
        }, history, remembered).bounded()
    }

    private fun captureMetadata(parsed: NotificationParseResult, source: String): String = buildJsonObject {
        put("scene", parsed.scene); put("ruleVersion", parsed.ruleVersion)
        put("source", source); put("paymentStatus", "COMPLETED"); put("currency", "CNY")
        parsed.orderIdKind?.let { put("transactionIdentityKind", it) }
        putJsonObject("fieldEvidence") { parsed.fieldEvidence.entries.take(12).forEach { (key, value) -> put(key.take(40), value.take(300)) } }
    }.toString()

    private suspend fun save(
        parsed: NotificationParseResult,
        packageName: String,
        text: String,
        occurredAt: Long,
        source: TxSource,
        eventKey: String,
        notificationKey: String?,
        revision: Long,
        verifiedRule: Boolean = false,
        metadata: String? = null,
        businessKind: String = "unknown",
        proposal: PaymentProposal? = null,
        isCurrent: () -> Boolean = { true },
        isAllowed: suspend () -> Boolean = { true }
    ): Boolean {
        if (!MoneyUtil.isValidAmountCents(parsed.amountCents) || parsed.direction !in setOf("INCOME", "EXPENSE")) return false
        val type = if (parsed.direction == "INCOME") TxType.INCOME else TxType.EXPENSE
        val amount = if (type == TxType.EXPENSE) parsed.amountCents else -parsed.amountCents
        val categories = database.categoryDao().observeAll().first()
        val semantics = CaptureSemanticResolver.resolve(parsed.copy(businessKind = businessKind), packageName,
            text, captureSemanticContext(parsed.merchant, parsed.direction,
                database.transactionDao().getByCaptureEvent(eventKey)?.id), proposal)
        val kind = semantics.businessKind
        val category = categories.firstOrNull { it.id == semantics.categoryId && it.enabled && it.type == type }
            ?: CategoryInferrer.infer("${parsed.merchant.orEmpty()} $text", categories, type)
            ?: categories.firstOrNull { it.enabled && it.name == "其他" && it.type == type }
            ?: categories.firstOrNull { it.enabled && it.type == type }
        if (category == null) {
            monitor.onError("缺少可用分类，请先在分类管理中添加分类")
            return false
        }
        if (!isCurrent() || revision != LedgerRevision.current ||
            source == TxSource.ACCESSIBILITY && !preferences.screenCaptureEnabled.first() ||
            source == TxSource.NOTIFICATION && !preferences.notificationCaptureEnabled.first()) return false
        val enrichedMetadata = buildJsonObject {
            metadata?.let { raw -> (Json.parseToJsonElement(raw) as JsonObject).forEach { (key, value) -> put(key, value) } }
            put("semanticBusinessKind", kind)
            put("semanticCategoryId", category.id)
            if (proposal != null) put("semanticModelApplied", true)
            put("assumptions", JsonArray(semantics.assumptions.map(::JsonPrimitive)))
        }.toString()
        val tx = TransactionEntity(amount = amount, type = type, categoryId = category.id,
            merchant = parsed.merchant, accountTag = semantics.accountTag ?: parsed.paymentMethod,
            note = semantics.note, occurredAt = parsed.occurredAt ?: occurredAt,
            source = source, status = if ((verifiedRule || proposal != null) && semantics.autoEligible && kind != "unknown" && preferences.captureAutoRecordEnabled.first()) TxStatus.CONFIRMED else TxStatus.PENDING,
            rawPayload = text, captureEventKey = eventKey, businessKind = kind, metadataJson = enrichedMetadata)
        if (!isCurrent() || !isAllowed()) return false
        val result = CapturePersistence.capture(database, revision, tx,
            CaptureEvidence(packageName = packageName, eventKey = eventKey, observedAt = occurredAt,
                notificationKey = notificationKey, text = text, orderId = parsed.orderId,
                orderDomain = parsed.orderIdKind?.let { "${CaptureEventIdentity.domainFor(packageName)}:$it" },
                metadataJson = enrichedMetadata), isCurrent = isCurrent,
            enrichSemantics = proposal != null || kind != "transfer" || businessKind != "unknown")
        val transactionId = when (result) {
            is CaptureResult.Duplicate -> {
                monitor.onDuplicate()
                if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("duplicate", packageName)
                return true
            }
            CaptureResult.StaleRevision, CaptureResult.StaleEvidence -> return false
            is CaptureResult.Conflict -> {
                monitor.onError("同一笔支付出现不同金额或交易标识，请核对已保存账目；新证据未覆盖原记录")
                if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("model_rejected", packageName)
                return true
            }
            is CaptureResult.Enriched -> result.transactionId
            is CaptureResult.Inserted -> {
                if (LedgerRevision.current != revision) return false
                monitor.onNotificationCaptured()
                try { refreshTodayCount() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { monitor.onError("账目已保存，今日统计暂时不可用") }
                result.transactionId
            }
        }
        if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("saved", packageName)
        if (LedgerRevision.current != revision) return false
        if (!NotificationHelper.captureNotificationsEnabled(context)) return true
        val stored = database.transactionDao().getById(transactionId) ?: return true
        val title = "${stored.merchant ?: "交易"} ${MoneyUtil.formatForDisplay(stored.amount, type == TxType.EXPENSE)}"
        val content = if (stored.duplicateOf != null) "疑似重复 · 请先在流水中核对"
            else if (stored.businessKind == "unknown") "资金变动已记录 · 请打开应用核对用途与分类"
            else if (stored.businessKind == "transfer") "资金转移已记录 · 不计入消费，可在账本修改用途"
            else "${category.name} · ${if (type == TxType.EXPENSE) "支出" else "收入"} · ${if (stored.status == TxStatus.CONFIRMED) "已记账" else "待确认"}"
        try {
            val storedEventKey = stored.captureEventKey ?: eventKey
            NotificationHelper.postCaptureNotification(context, transactionId, storedEventKey, title,
                content, allowQuickConfirm = stored.status == TxStatus.PENDING && stored.duplicateOf == null &&
                    stored.businessKind !in setOf("unknown", "transfer", "receivable", "payable"),
                recorded = stored.status == TxStatus.CONFIRMED)
            if (LedgerRevision.current != revision) {
                NotificationHelper.cancelCaptureNotification(context, transactionId, storedEventKey)
                return false
            }
        } catch (_: SecurityException) {
            monitor.onError("账目已保存，记账提醒权限不可用；请在流水中核对")
        }
        return true
    }

}

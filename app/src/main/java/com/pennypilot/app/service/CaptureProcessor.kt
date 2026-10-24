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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 所有后台入口共用校验和原子写入；云识别独立授权、加密持久排队，不阻塞采集。 */
@Singleton
class CaptureProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences,
    private val llmClient: LLMClient,
    private val monitor: CaptureServiceMonitor
) {
    private val countMutex = Mutex()
    private val recognitionCommitMutex = Mutex()
    private val taskStore by lazy { RecognitionTaskStore(context) }
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
        if (parsed != null) recognitionCommitMutex.withLock {
            if (save(parsed, input.packageName, text, input.postedAt,
                    TxSource.NOTIFICATION, eventKey, input.updateIdentity, revision, verifiedRule = true,
                    businessKind = when {
                        Regex("退款|退回").containsMatchIn(text) -> "refund"
                        Regex("转账|红包|借|还款|提现|充值").containsMatchIn(text) -> "unknown"
                        parsed.merchant != null && parsed.direction == "EXPENSE" -> "expense"
                        else -> "unknown"
                    })) runCatching { taskStore.remove(RecognitionTaskStore.id(eventKey)) }
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
        commit = { task, proposal, parsed, isCurrent ->
            recognitionCommitMutex.withLock {
                if (!isCurrent() || !taskAllowed(task)) false else
                    save(parsed, task.packageName, receiptText(parsed), task.observedAt,
                        TxSource.valueOf(task.source), task.eventKey, task.notificationKey, task.ledgerRevision,
                        verifiedRule = false, metadata = recognitionEngine.metadata(proposal.copy(merchant = parsed.merchant, merchantEvidence = proposal.merchantEvidence.takeIf { parsed.merchant != null }, businessKind = "unknown")),
                        businessKind = "unknown", isCurrent = isCurrent)
            }
        }, report = monitor::onError,
        onState = { task, status -> if (task.source == TxSource.ACCESSIBILITY.name) monitor.onScreenStatus(status, task.packageName) }
    ).run(id)

    private fun receiptText(parsed: NotificationParseResult): String = buildString {
        append(if (parsed.direction == "INCOME") "到账" else "支付成功")
        append(" ").append(MoneyUtil.centsToYuanString(parsed.amountCents)).append("元")
        parsed.merchant?.let { append("\n商户：").append(it) }
        parsed.orderId?.let { append("\n订单号：").append(it) }
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
        return recognitionCommitMutex.withLock {
            save(parsed, packageName, receipt, observedAt, TxSource.ACCESSIBILITY,
                eventKey, null, revision, verifiedRule = true, businessKind = parsed.businessKind,
                metadata = buildJsonObject {
                    put("scene", parsed.scene); put("ruleVersion", parsed.ruleVersion)
                    put("source", "page"); put("paymentStatus", "COMPLETED"); put("currency", "CNY")
                    putJsonObject("fieldEvidence") { parsed.fieldEvidence.entries.take(12).forEach { (key, value) -> put(key.take(40), value.take(300)) } }
                }.toString()).also { saved -> if (saved) runCatching { taskStore.remove(RecognitionTaskStore.id(eventKey)) } }
        }
    }

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
        isCurrent: () -> Boolean = { true }
    ): Boolean {
        if (!MoneyUtil.isValidAmountCents(parsed.amountCents) || parsed.direction !in setOf("INCOME", "EXPENSE")) return false
        val type = if (parsed.direction == "INCOME") TxType.INCOME else TxType.EXPENSE
        val amount = if (type == TxType.EXPENSE) parsed.amountCents else -parsed.amountCents
        val categories = database.categoryDao().observeAll().first()
        val category = CategoryInferrer.infer("${parsed.merchant.orEmpty()} $text", categories, type)
            ?: categories.firstOrNull { it.name == "其他" && it.type == type }
            ?: categories.firstOrNull { it.type == type }
        if (category == null) {
            monitor.onError("缺少可用分类，请先在分类管理中添加分类")
            return false
        }
        if (!isCurrent() || revision != LedgerRevision.current ||
            source == TxSource.ACCESSIBILITY && !preferences.screenCaptureEnabled.first() ||
            source == TxSource.NOTIFICATION && !preferences.notificationCaptureEnabled.first()) return false
        val tx = TransactionEntity(amount = amount, type = type, categoryId = category.id,
            merchant = parsed.merchant, accountTag = parsed.paymentMethod, occurredAt = occurredAt,
            source = source, status = if (verifiedRule && businessKind != "unknown" && preferences.captureAutoRecordEnabled.first()) TxStatus.CONFIRMED else TxStatus.PENDING,
            rawPayload = text, captureEventKey = eventKey, businessKind = businessKind, metadataJson = metadata)
        val result = CapturePersistence.capture(database, revision, tx,
            CaptureEvidence(packageName = packageName, eventKey = eventKey, observedAt = occurredAt,
                notificationKey = notificationKey, text = text, orderId = parsed.orderId, metadataJson = metadata), isCurrent = isCurrent)
        when (result) {
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
            is CaptureResult.Inserted -> {
                if (LedgerRevision.current != revision) return false
                monitor.onNotificationCaptured()
                if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("saved", packageName)
                try { refreshTodayCount() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { monitor.onError("账目已保存，今日统计暂时不可用") }
                // 输出提醒权限不足不影响待确认入库。
                if (LedgerRevision.current != revision) return false
                if (!NotificationHelper.captureNotificationsEnabled(context)) return true
                val title = "${parsed.merchant ?: "交易"} ${MoneyUtil.formatForDisplay(amount, type == TxType.EXPENSE)}"
                val content = if (result.duplicateOf != null) "疑似重复 · 请先在流水中核对"
                    else if (businessKind == "unknown") "资金变动已记录 · 请打开应用核对用途与分类"
                    else "${category.name} · ${if (type == TxType.EXPENSE) "支出" else "收入"} · ${if (tx.status == TxStatus.CONFIRMED) "已记账" else "待确认"}"
                try {
                    NotificationHelper.postCaptureNotification(context, result.transactionId, eventKey, title,
                        content, allowQuickConfirm = result.duplicateOf == null && businessKind !in setOf("unknown", "transfer", "receivable", "payable"))
                    if (LedgerRevision.current != revision) {
                        NotificationHelper.cancelCaptureNotification(context, result.transactionId, eventKey)
                        return false
                    }
                } catch (_: SecurityException) {
                    monitor.onError("账目已保存，记账提醒权限不可用；请在流水中核对")
                }
                return true
            }
        }
    }

}

package com.pennypilot.app.service

import android.content.Context
import android.os.SystemClock
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.ModelRole
import com.pennypilot.app.data.recognition.*
import com.pennypilot.app.data.parser.CategoryInferrer
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.NotificationParser
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
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
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import java.time.Instant
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
    internal val checkoutContext = CheckoutContext()
    private val taskStore by lazy { recognitionStore ?: RecognitionTaskStore(context) }
    private val recognitionEngine by lazy { PaymentRecognitionEngine(llmClient) }

    suspend fun refreshTodayCount() = countMutex.withLock {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        monitor.setTodayCount(database.transactionDao().countCapturedSince(start))
    }

    internal suspend fun processNotification(input: CapturedNotification, revision: Long,
        liveArrival: CheckoutContext.LiveNotification? = null, expectedAuthorizationId: String? = null,
        isCurrent: () -> Boolean = { true }) {
        val authorizationId = expectedAuthorizationId ?: preferences.captureAuthorizationId(input.packageName,
            page = false, image = false)
        if (!isCurrent() || revision != LedgerRevision.current || !preferences.notificationCaptureEnabled.first()) return
        if (input.packageName !in preferences.captureAppPackages.first()) return
        val eventKey = CaptureEventIdentity.key(input.packageName, input.notificationKey, input.postedAt,
            "${input.title}\n${input.content}")
        val rules = database.parseRuleDao().getRulesForPackage(input.packageName)
        val parsed = NotificationParser.parse(input.packageName, input.title, input.content, rules,
            cloudParsingEnabled = false, observedAt = input.postedAt) { null }
        if (!isCurrent() || revision != LedgerRevision.current || !preferences.notificationCaptureEnabled.first()) return
        val text = "${input.title}\n${input.content}".trim()
        if (parsed != null) {
            val receiptContent = NotificationParser.receiptContent(input.packageName, input.title, input.content)
            val semanticText = "${input.title}\n$receiptContent".trim()
            val saved = recognitionCommitMutex.withLock {
                val supplemental = if (liveArrival != null && preferences.screenCaptureEnabled.first())
                    checkoutContext.enrichNotification(input, parsed, liveArrival, revision, SystemClock.elapsedRealtime()) else null
                suspend fun record(receipt: NotificationParseResult, current: () -> Boolean = isCurrent,
                    allowed: suspend () -> Boolean = { true }) = save(receipt, input.packageName, text, input.postedAt,
                    TxSource.NOTIFICATION, eventKey, input.updateIdentity, revision, verifiedRule = true,
                    businessKind = receipt.businessKind, metadata = captureMetadata(receipt, "notification"),
                    semanticText = semanticText, isCurrent = current, isAllowed = allowed)
                if (supplemental == null) record(parsed) else {
                    val current = { isCurrent() && checkoutContext.remainsAuthorized(supplemental.authorization) }
                    record(supplemental.receipt, current) { current() && preferences.screenCaptureEnabled.first() } || record(parsed)
                }
            }
            if (saved && preferences.notificationCloudParsingEnabled.first() && isCurrent() && savedReceiptMatches(eventKey, parsed)) {
                enqueueRecognition(input.packageName, receiptText(parsed), eventKey, input.postedAt, revision,
                    TxSource.NOTIFICATION, input.updateIdentity, enrichExisting = true,
                    authorizationIdOverride = authorizationId)
            }
        }
        else if (preferences.notificationCloudParsingEnabled.first() &&
            NotificationParser.isCloudCandidate(input.packageName, input.title, input.content)) {
            enqueueRecognition(input.packageName, text, eventKey, input.postedAt, revision,
                TxSource.NOTIFICATION, input.updateIdentity, authorizationIdOverride = authorizationId)
        }
    }

    /** Called only for a safe read-only payment surface; returns after durable enqueue, not HTTP. */
    suspend fun processUnrecognizedScreen(
        packageName: String, text: String, eventKey: String, observedAt: Long,
        revision: Long, structure: String? = null, images: List<String> = emptyList(),
        expectedAuthorizationId: String? = null
    ): Boolean {
        val authorizationId = expectedAuthorizationId ?: preferences.captureAuthorizationId(packageName,
            page = true, image = false)
        if (revision != LedgerRevision.current || !preferences.screenCaptureEnabled.first() ||
            !preferences.screenCloudParsingEnabled.first() || images.isNotEmpty()) return false
        trace(eventKey, TxSource.ACCESSIBILITY, CaptureLatencyTrace.Stage.RECEIVED, observedAt)
        // Only the read-only receipt tree from the capture adapter is accepted, never input/password nodes.
        return enqueueRecognition(packageName, text, eventKey, observedAt, revision, TxSource.ACCESSIBILITY, null, images,
            structure.takeIf { (it?.length ?: 0) <= 32_768 && preferences.screenCloudParsingEnabled.first() },
            authorizationIdOverride = authorizationId)
    }

    /** Called only with an image from the current, independently qualified receipt window. */
    suspend fun processReceiptImage(packageName: String, image: String, eventKey: String,
        observedAt: Long, revision: Long, allowImageOnly: Boolean = false,
        expectedAuthorizationId: String? = null): Boolean {
        if (revision != LedgerRevision.current || packageName !in PaymentAppRegistry.packages ||
            !preferences.screenCaptureEnabled.first() || !preferences.screenCloudVisionEnabled.first()) return false
        val mode = if (allowImageOnly) ReceiptImageMode.IMAGE_ONLY else ReceiptImageMode.VERIFIED_RECEIPT
        return enqueueRecognition(packageName, "支付凭证图片", eventKey, observedAt, revision, TxSource.ACCESSIBILITY,
            null, images = listOf(image), receiptImageMode = mode, authorizationIdOverride = expectedAuthorizationId)
    }

    private suspend fun enqueueRecognition(packageName: String, text: String, eventKey: String,
        observedAt: Long, revision: Long, source: TxSource, notificationKey: String?, images: List<String> = emptyList(), structure: String? = null,
        enrichExisting: Boolean = false, receiptImageMode: ReceiptImageMode? = null,
        authorizationIdOverride: String? = null): Boolean {
        if (receiptImageMode == null && !enrichExisting && !PaymentProposalValidator.isSafeCandidate(packageName, text)) return false
        return try {
            lateinit var task: RecognitionTask
            var waitingForReview = false
            val enqueued = recognitionCommitMutex.withLock {
                val snapshot = if (receiptImageMode == null) semanticSnapshot(eventKey) else null
                if (enrichExisting && snapshot == null) return false
                if (snapshot?.alreadyApplied == true) return true
                task = RecognitionTask(RecognitionTaskStore.id(eventKey), eventKey, packageName, text,
                    observedAt, revision, llmClient.operationIdentity(if (images.isEmpty()) ModelRole.TEXT else ModelRole.VISION),
                    source.name, notificationKey, images = images, structure = structure,
                    receipt = snapshot?.receipt, semanticFingerprint = snapshot?.fingerprint, receiptImageMode = receiptImageMode,
                    authorizationId = authorizationIdOverride ?: preferences.captureAuthorizationId(packageName,
                        page = source == TxSource.ACCESSIBILITY, image = images.isNotEmpty()))
                if (!taskAllowed(task)) return false
                taskStore.enqueue(task).also { result ->
                    if (result != RecognitionTaskStore.EnqueueResult.FULL) {
                        waitingForReview = taskStore.get(task.id)?.state == RecognitionTaskState.NEEDS_ATTENTION
                        if (waitingForReview) {
                            NotificationHelper.cancelRecognitionPending(context, eventKey)
                            return@also
                        }
                        trace(eventKey, source, CaptureLatencyTrace.Stage.QUEUED)
                        // Never wait for network/AI to acknowledge a safely detected receipt.
                        // This alert explicitly says it has not yet been recorded.
                        if (snapshot == null && database.transactionDao().captureEvidenceByKey(eventKey) == null) {
                            try {
                                val posted = NotificationHelper.postRecognitionPending(context, eventKey)
                                trace(eventKey, source, if (posted) CaptureLatencyTrace.Stage.NOTIFICATION_SUBMITTED
                                    else if (!NotificationHelper.captureNotificationsEnabled(context)) CaptureLatencyTrace.Stage.NOTIFICATION_BLOCKED
                                    else CaptureLatencyTrace.Stage.NOTIFICATION_SKIPPED)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: RuntimeException) {
                                // Notification permission/binder failures must not strand durable work.
                                trace(eventKey, source, CaptureLatencyTrace.Stage.NOTIFICATION_BLOCKED)
                                monitor.onError("支付识别已排队，系统提醒暂时不可用")
                            }
                        }
                    }
                }
            }
            when (enqueued) {
                RecognitionTaskStore.EnqueueResult.FULL -> { monitor.onError("待识别凭证已满，请在未识别凭证中处理或移除旧凭证"); false }
                else -> {
                    if (!waitingForReview) RecognitionWorker.schedule(context, task.id,
                        enqueueSuccessor = enqueued != RecognitionTaskStore.EnqueueResult.ALREADY_QUEUED)
                    if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus(
                        if (waitingForReview) "model_attention" else "model_queued", packageName)
                    true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { monitor.onError("支付识别任务未能保存，请检查 AI 配置后重试"); false }
    }

    private suspend fun taskAllowed(task: RecognitionTask): Boolean {
        if (task.ledgerRevision != LedgerRevision.current) return false
        if (task.packageName !in preferences.captureAppPackages.first()) return false
        if (task.authorizationId != preferences.captureAuthorizationId(task.packageName,
                page = task.source == TxSource.ACCESSIBILITY.name, image = task.images.isNotEmpty())) return false
        if (database.transactionDao().tombstone(task.eventKey) != null) return false
        if (task.receiptImageMode != null) {
            if (task.source != TxSource.ACCESSIBILITY.name || task.images.size != 1 ||
                !preferences.screenCaptureEnabled.first() || !preferences.screenCloudVisionEnabled.first()) return false
            val dao = database.transactionDao()
            val evidence = dao.captureEvidenceByKey(task.eventKey)
            if (evidence == null) return task.receiptImageMode == ReceiptImageMode.IMAGE_ONLY
            val metadata = evidence.metadataJson?.let { Json.parseToJsonElement(it) as? JsonObject }
            return metadata?.get("receiptVisionApplied") != JsonPrimitive(true) && verifiedPayment(task) != null
        }
        // Old images were captured for a different grant and must never use the new opt-in.
        if (task.images.isNotEmpty() ||
            task.structure != null && !preferences.screenCloudParsingEnabled.first()) return false
        val allowed = if (task.source == TxSource.ACCESSIBILITY.name)
            preferences.screenCaptureEnabled.first() && preferences.screenCloudParsingEnabled.first()
        else preferences.notificationCaptureEnabled.first() && preferences.notificationCloudParsingEnabled.first()
        if (!allowed) return false
        if (task.semanticFingerprint == null) return true
        val snapshot = semanticSnapshot(task.eventKey) ?: return false
        return !snapshot.alreadyApplied && snapshot.fingerprint == task.semanticFingerprint
    }

    suspend fun recognitionTaskAllowed(task: RecognitionTask): Boolean = taskAllowed(task) &&
        task.configurationId == llmClient.operationIdentity(if (task.images.isEmpty()) ModelRole.TEXT else ModelRole.VISION)

    suspend fun processRecognitionTask(id: String): Boolean = RecognitionTaskRunner(
        taskStore, llmClient, ::taskAllowed,
        commit = ::completeRecognition,
        report = monitor::onError,
        onState = { task, status ->
            val stage = when (status) {
                "model_processing" -> CaptureLatencyTrace.Stage.STARTED
                "model_completed" -> CaptureLatencyTrace.Stage.COMPLETED
                "model_failed", "model_rejected", "model_discarded", "model_exhausted" -> CaptureLatencyTrace.Stage.FAILED
                else -> null
            }
            stage?.let { CaptureLatencyTrace.record(context, task.eventKey, CaptureLatencyTrace.Source.MODEL, it) }
            if (status in setOf("model_completed", "model_rejected", "model_discarded", "model_exhausted"))
                NotificationHelper.cancelRecognitionPending(context, task.eventKey)
            if (task.source == TxSource.ACCESSIBILITY.name) monitor.onScreenStatus(when (status) {
                "model_completed" -> "saved"
                "model_exhausted" -> if (task.isUnrecognized) "model_attention" else "saved"
                "model_discarded" -> "model_rejected"
                "model_superseded" -> "model_queued"
                else -> status
            }, task.packageName)
        },
        verifiedPayment = ::verifiedPayment,
        semanticContext = { task ->
            if (task.receiptImageMode != null) return@RecognitionTaskRunner captureSemanticContext(null, null)
            val existing = database.transactionDao().captureEvidenceByKey(task.eventKey)
                ?.let { database.transactionDao().getById(it.transactionId) }
            val merchant = task.receipt?.details?.get("merchantName") ?: task.receipt?.details?.get("payeeName")
                ?: task.receipt?.context?.firstOrNull { it.label == "交易对方" }?.value
            captureSemanticContext(merchant, existing?.type?.name, existing?.id).copy(receipt = task.receipt)
        }
    ).run(id)

    private suspend fun verifiedPayment(task: RecognitionTask): NotificationParseResult? {
        val dao = database.transactionDao()
        val evidence = dao.captureEvidenceByKey(task.eventKey) ?: return null
        if (evidence.packageName != task.packageName || evidence.source.name != task.source) return null
        val row = dao.getById(evidence.transactionId) ?: return null
        if (row.editVersion != 0L || row.status == TxStatus.IGNORED || row.duplicateOf != null ||
            row.amount != evidence.amount || row.type != evidence.type) return null
        val amount = if (evidence.type == TxType.EXPENSE) evidence.amount else -evidence.amount
        if (!MoneyUtil.isValidAmountCents(amount)) return null
        val metadata = evidence.metadataJson?.let { Json.parseToJsonElement(it) as? JsonObject }
        if (CaptureReceiptContext.string(metadata, "paymentStatus") != "COMPLETED") return null
        val receipt = CaptureReceiptContext.fromEvidence(listOf(evidence))
        val fields = receipt.details
        fun strings(obj: JsonObject?): Map<String, String> = obj.orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
        }.toMap()
        val fieldSources = (metadata?.get("receiptDetails") as? JsonObject)?.get("fieldSources") as? JsonObject
        val time = receipt.occurredAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?.takeIf { it in 1..task.observedAt }
        return NotificationParseResult(amount, row.merchant, evidence.type.name,
            orderId = evidence.orderId,
            paymentMethod = fields["fundingAccount"] ?: fields["paymentChannel"],
            businessKind = row.businessKind,
            scene = receipt.scene,
            ruleVersion = CaptureReceiptContext.string(metadata, "ruleVersion"),
            fieldEvidence = strings(metadata?.get("fieldEvidence") as? JsonObject),
            occurredAt = time,
            occurredAtIsCreationTime = CaptureReceiptContext.string(metadata, "transactionTimeSource") == "receipt_created",
            orderIdKind = CaptureReceiptContext.string(metadata, "transactionIdentityKind"),
            details = PaymentReceiptDetails(fields["merchantName"], fields["payeeName"], fields["productDescription"],
                fields["remark"], fields["paymentChannel"], fields["fundingAccount"],
                fieldSources = strings(fieldSources), context = receipt.context,
                originalAmountCents = fields["originalAmountCents"]?.toLongOrNull(),
                discountAmountCents = fields["discountAmountCents"]?.toLongOrNull(),
                feeAmountCents = fields["feeAmountCents"]?.toLongOrNull(),
                receivedAmountCents = fields["receivedAmountCents"]?.toLongOrNull(),
                transferFromAccount = fields["transferFromAccount"],
                transferToAccount = fields["transferToAccount"]))
    }

    internal suspend fun completeRecognition(task: RecognitionTask, proposal: PaymentProposal,
        parsed: NotificationParseResult, isCurrent: () -> Boolean): Boolean = recognitionCommitMutex.withLock {
        if (!isCurrent() || !taskAllowed(task)) return@withLock false
        val imageTask = task.receiptImageMode != null
        // A local receipt can arrive while the image request is in flight. It controls
        // financial facts at commit, even when the task started without a local receipt.
        val existing = if (imageTask) verifiedPayment(task) else null
        if (existing != null && (existing.amountCents != parsed.amountCents || existing.direction != parsed.direction ||
                existing.orderId != null && parsed.orderId != null && existing.orderIdKind == parsed.orderIdKind &&
                existing.orderId != parsed.orderId)) return@withLock true
        val payment = if (existing == null) parsed else enrichVerifiedReceipt(existing, parsed)
        save(payment, task.packageName, if (imageTask) receiptText(payment) else task.text, task.observedAt,
                TxSource.valueOf(task.source), task.eventKey, task.notificationKey, task.ledgerRevision,
                metadata = buildJsonObject {
                    (Json.parseToJsonElement(recognitionEngine.metadata(proposal.copy(merchant = payment.merchant,
                        merchantEvidence = proposal.merchantEvidence.takeIf { payment.merchant == proposal.merchant }))) as JsonObject)
                        .forEach { (key, value) -> put(key, value) }
                    if (imageTask) put("receiptVisionApplied", true)
                }.toString(),
                businessKind = if (task.semanticFingerprint == null) proposal.businessKind else payment.businessKind,
                proposal = proposal, semanticReceipt = task.receipt,
                semanticFingerprint = task.semanticFingerprint,
                sourceMetadata = if (task.semanticFingerprint == null) null else
                    database.transactionDao().captureEvidenceByKey(task.eventKey)?.metadataJson,
                forceReview = imageTask && existing == null,
                isCurrent = isCurrent,
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
        val details = receiptDetailFields(parsed)
        val merchant = details["merchantName"] ?: parsed.merchant
        merchant?.let {
            append(if (details["merchantName"] == null && details["payeeName"] == it) "\n收款方：" else "\n商户：")
                .append(it)
        }
        details["payeeName"]?.takeIf { it != merchant }?.let { append("\n收款方：").append(it) }
        parsed.orderId?.let { append(if (parsed.orderIdKind == "refund") "\n退款单号：" else "\n订单号：").append(it) }
        parsed.paymentMethod?.let { append("\n支付方式：").append(it) }
        details["paymentChannel"]?.let { append("\n支付渠道：").append(it) }
        details["fundingAccount"]?.let { append("\n扣款账户：").append(it) }
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
        revision: Long,
        queueSemanticModel: Boolean = true,
        expectedAuthorizationId: String? = null
    ): Boolean {
        val authorizationId = expectedAuthorizationId ?: preferences.captureAuthorizationId(packageName,
            page = true, image = false)
        if (revision != LedgerRevision.current || !preferences.screenCaptureEnabled.first()) return false
        if (packageName !in preferences.captureAppPackages.first()) return false
        // 只保留已提取的交易证据；整页文本、非交易节点不会被写入账本或发送到 AI。
        val receipt = receiptText(parsed)
        val saved = recognitionCommitMutex.withLock {
            save(parsed, packageName, receipt, observedAt, TxSource.ACCESSIBILITY,
                eventKey, null, revision, verifiedRule = true, businessKind = parsed.businessKind,
                metadata = captureMetadata(parsed, "page"))
        }
        if (saved && queueSemanticModel && "reviewRequired" !in parsed.fieldEvidence &&
            preferences.screenCloudParsingEnabled.first() && savedReceiptMatches(eventKey, parsed))
            enqueueRecognition(packageName, receipt, eventKey, observedAt, revision, TxSource.ACCESSIBILITY, null,
                enrichExisting = true, authorizationIdOverride = authorizationId)
        return saved
    }

    private suspend fun savedReceiptMatches(eventKey: String, parsed: NotificationParseResult): Boolean {
        val evidence = database.transactionDao().captureEvidenceByKey(eventKey) ?: return false
        val amount = if (parsed.direction == "INCOME") -parsed.amountCents else parsed.amountCents
        return evidence.amount == amount && evidence.type.name == parsed.direction &&
            (evidence.orderId == null || parsed.orderId == null || evidence.orderId == parsed.orderId)
    }

    private data class SemanticSnapshot(val receipt: CaptureSemanticReceipt, val fingerprint: String, val alreadyApplied: Boolean)

    private suspend fun semanticSnapshot(eventKey: String): SemanticSnapshot? {
        val row = database.transactionDao().captureEvidenceByKey(eventKey)
            ?.let { database.transactionDao().getById(it.transactionId) } ?: return null
        if (row.editVersion != 0L || row.status == TxStatus.IGNORED || row.duplicateOf != null) return null
        val notificationAllowed = preferences.notificationCaptureEnabled.first() && preferences.notificationCloudParsingEnabled.first()
        val pageAllowed = preferences.screenCaptureEnabled.first() && preferences.screenCloudParsingEnabled.first()
        // Each source keeps its own consent: a notification task cannot export local-only page details.
        val evidence = database.transactionDao().evidenceForTransaction(row.id).filter {
            it.source == TxSource.NOTIFICATION && notificationAllowed || it.source == TxSource.ACCESSIBILITY && pageAllowed
        }
        val receipt = CaptureSemanticContext(receipt = CaptureReceiptContext.fromEvidence(evidence, includeLocalCheckout = false))
            .bounded().receipt!!
        val sources = evidence.map { it.source.name }.distinct().sorted().joinToString(",")
        val fingerprint = CaptureEventIdentity.fingerprint("$sources\n${Json.encodeToString(receipt)}")
        val metadata = row.metadataJson?.let { Json.parseToJsonElement(it) as? JsonObject }
        val attempted = CaptureReceiptContext.string(metadata, "semanticAttemptedFingerprint") == fingerprint
        // Older versions marked any model response complete, even one that left a fallback category.
        val legacySpecific = !attempted &&
            CaptureReceiptContext.string(metadata, "semanticEvidenceFingerprint") == fingerprint &&
            database.categoryDao().getById(row.categoryId)?.let {
                it.enabled && !CaptureSemanticResolver.isFallbackCategory(it.name)
            } == true
        return SemanticSnapshot(receipt, fingerprint, attempted || legacySpecific)
    }

    private suspend fun captureSemanticContext(merchant: String?, direction: String?, excludedId: Long? = null): CaptureSemanticContext {
        val type = when (direction) { "EXPENSE" -> TxType.EXPENSE; "INCOME" -> TxType.INCOME; else -> null }
        val categories = database.categoryDao().observeAll().first().filter { it.enabled && (type == null || it.type == type) }
        val fallbackIds = categories.filter { CaptureSemanticResolver.isFallbackCategory(it.name) }.map { it.id }.toSet()
        val history = if (merchant.isNullOrBlank() || type == null) emptyList() else
            database.transactionDao().relatedCaptureHistory(merchant, type).filter { row ->
                row.id != excludedId && !(row.categoryId in fallbackIds && row.editVersion == 0L &&
                    row.source in setOf(TxSource.NOTIFICATION, TxSource.ACCESSIBILITY))
            }.map { row ->
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
        if ("reviewRequired" in parsed.fieldEvidence) put("captureRequiresReview", true)
        if ("checkoutAmountEstimate" in parsed.fieldEvidence) {
            put("checkoutAmountEstimate", true)
            put("paymentStatus", "AMOUNT_UNVERIFIED")
        }
        parsed.occurredAt?.let { put("receiptOccurredAt", java.time.Instant.ofEpochMilli(it).toString()) }
        parsed.orderIdKind?.let { put("transactionIdentityKind", it) }
        putJsonObject("fieldEvidence") { parsed.fieldEvidence.entries.take(12).forEach { (key, value) -> put(key.take(40), value.take(300)) } }
        val details = receiptDetailFields(parsed)
        val money = parsed.details.moneyValues().filterValues { it != null }
        if (details.isNotEmpty() || money.isNotEmpty()) putJsonObject("receiptDetails") {
            details.forEach { (field, value) -> put(field, value) }
            money.forEach { (field, value) -> put(field, value!!) }
            putJsonObject("fieldSources") {
                (details.keys + money.keys).forEach { field ->
                    parsed.details.fieldSources[field]?.takeIf(String::isNotBlank)?.let { put(field, it.take(80)) }
                }
            }
        }
        val context = PaymentReceiptDetails.normalizeContext(listOfNotNull(
            parsed.merchant?.takeIf { it.isNotBlank() && parsed.details.merchantName == null && parsed.details.payeeName == null }
                ?.let { PaymentReceiptContext("交易对方", it, source) }) + parsed.details.context)
        if (context.isNotEmpty()) {
            put("receiptContext", Json.parseToJsonElement(Json.encodeToString(context)))
        }
    }.toString()

    private fun receiptDetailFields(parsed: NotificationParseResult): Map<String, String> = with(parsed.details) {
        textValues()
            .mapNotNull { (field, value) ->
                value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotBlank)?.let {
                    field to it.take(if (field in setOf("productDescription", "remark")) 300 else 120)
                }
            }.toMap()
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
        proposal: PaymentProposal? = null,
        semanticReceipt: CaptureSemanticReceipt? = null,
        semanticFingerprint: String? = null,
        sourceMetadata: String? = null,
        forceReview: Boolean = false,
        semanticText: String = text,
        isCurrent: () -> Boolean = { true },
        isAllowed: suspend () -> Boolean = { true }
    ): Boolean {
        if (!MoneyUtil.isValidAmountCents(parsed.amountCents) || parsed.direction !in setOf("INCOME", "EXPENSE")) return false
        if (packageName !in preferences.captureAppPackages.first()) return false
        if (parsed.occurredAt != null && parsed.occurredAt !in 1..occurredAt) return false
        val type = if (parsed.direction == "INCOME") TxType.INCOME else TxType.EXPENSE
        val amount = if (type == TxType.EXPENSE) parsed.amountCents else -parsed.amountCents
        val categories = database.categoryDao().observeAll().first()
        val dao = database.transactionDao()
        val existingId = dao.captureEvidenceByKey(eventKey)?.transactionId ?: dao.getByCaptureEvent(eventKey)?.id
        val semantics = CaptureSemanticResolver.resolve(parsed.copy(businessKind = businessKind), packageName,
            semanticText, captureSemanticContext(parsed.merchant, parsed.direction, existingId).copy(receipt = semanticReceipt), proposal)
        val kind = semantics.businessKind
        val category = categories.firstOrNull { it.id == semantics.categoryId && it.enabled && it.type == type }
            ?: CategoryInferrer.infer("${parsed.merchant.orEmpty()} $semanticText", categories, type)
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
            // A semantic result must not rewrite the provenance of facts already captured locally.
            (Json.parseToJsonElement(sourceMetadata ?: captureMetadata(parsed,
                if (source == TxSource.NOTIFICATION) "notification" else "page")) as JsonObject)
                .forEach { (key, value) -> put(key, value) }
            metadata?.let { raw -> (Json.parseToJsonElement(raw) as JsonObject).forEach { (key, value) -> put(key, value) } }
            val (timeSource, timePrecision) = when {
                parsed.occurredAt == null -> "observed" to "unspecified"
                parsed.occurredAtIsCreationTime -> "receipt_created" to "approximate"
                else -> "receipt" to "exact"
            }
            put("transactionTimeSource", timeSource)
            put("timePrecision", timePrecision)
            put("observedAt", occurredAt)
            put("semanticBusinessKind", kind)
            put("semanticCategoryId", category.id)
            put("semanticCategoryFallback", semantics.categoryIsFallback)
            semanticFingerprint?.let {
                put("semanticAttemptedFingerprint", it)
                if (proposal?.categoryId == category.id && !CaptureSemanticResolver.isFallbackCategory(category.name))
                    put("semanticEvidenceFingerprint", it)
            }
            if (proposal != null) put("semanticModelApplied", true)
            put("assumptions", JsonArray(semantics.assumptions.map(::JsonPrimitive)))
        }.toString()
        val tx = TransactionEntity(amount = amount, type = type, categoryId = category.id,
            merchant = parsed.merchant, accountTag = semantics.accountTag ?: parsed.paymentMethod,
            note = semantics.note, occurredAt = parsed.occurredAt ?: occurredAt,
            source = source, status = if (!forceReview && !parsed.occurredAtIsCreationTime &&
                "reviewRequired" !in parsed.fieldEvidence &&
                (verifiedRule || proposal != null) && semantics.autoEligible &&
                kind != "unknown" && preferences.captureAutoRecordEnabled.first()) TxStatus.CONFIRMED else TxStatus.PENDING,
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
                trace(eventKey, source, CaptureLatencyTrace.Stage.SAVED)
                NotificationHelper.cancelRecognitionPending(context, eventKey)
                result.replacedDraftId?.let { NotificationHelper.cancelCaptureNotification(context, it, eventKey) }
                monitor.onDuplicate()
                if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("duplicate", packageName)
                return true
            }
            CaptureResult.StaleRevision, CaptureResult.StaleEvidence -> return false
            is CaptureResult.Conflict -> {
                trace(eventKey, source, CaptureLatencyTrace.Stage.FAILED)
                NotificationHelper.cancelRecognitionPending(context, eventKey)
                monitor.onError("同一笔支付出现不同金额或交易标识，请核对已保存账目；新证据未覆盖原记录")
                if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("model_rejected", packageName)
                return true
            }
            is CaptureResult.Enriched -> {
                result.transactionId
            }
            is CaptureResult.Inserted -> {
                if (LedgerRevision.current != revision) return false
                monitor.onNotificationCaptured()
                result.transactionId
            }
        }
        trace(eventKey, source, CaptureLatencyTrace.Stage.SAVED)
        if (source == TxSource.ACCESSIBILITY) monitor.onScreenStatus("saved", packageName)
        if (LedgerRevision.current != revision) return false
        try {
            postSavedNotification(transactionId, eventKey, source, type, category, result is CaptureResult.Enriched, revision)
        } finally {
            // Counting is secondary: an immediate alert must not wait for a ledger summary query.
            try { refreshTodayCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { monitor.onError("账目已保存，今日统计暂时不可用") }
        }
        return LedgerRevision.current == revision
    }

    private suspend fun postSavedNotification(transactionId: Long, eventKey: String, source: TxSource,
        type: TxType, category: CategoryEntity, updateOnly: Boolean, revision: Long) {
        if (!NotificationHelper.captureNotificationsEnabled(context)) {
            trace(eventKey, source, CaptureLatencyTrace.Stage.NOTIFICATION_BLOCKED)
            NotificationHelper.cancelRecognitionPending(context, eventKey)
            return
        }
        val stored = database.transactionDao().getById(transactionId) ?: return
        val categoryName = if (stored.categoryId == category.id) category.name
            else database.categoryDao().getById(stored.categoryId)?.name.orEmpty()
        val title = "${stored.merchant ?: "交易"} ${MoneyUtil.formatForDisplay(stored.amount, type == TxType.EXPENSE)}"
        val content = if (stored.duplicateOf != null) "疑似重复 · 请先在流水中核对"
            else if (stored.businessKind == "unknown") "资金变动已记录 · 请打开应用核对用途与分类"
            else if (stored.businessKind == "transfer") "资金转移已记录 · 不计入消费，可在账本修改用途"
            else "$categoryName · ${if (type == TxType.EXPENSE) "支出" else "收入"} · ${if (stored.status == TxStatus.CONFIRMED) "已记账" else "待确认"}"
        try {
            val storedEventKey = stored.captureEventKey ?: eventKey
            val awaitingAmount = stored.metadataJson?.let { raw ->
                (Json.parseToJsonElement(raw) as? JsonObject)?.get("checkoutAmountEstimate") == JsonPrimitive(true)
            } == true
            val posted = NotificationHelper.postCaptureNotification(context, transactionId, storedEventKey, title,
                if (awaitingAmount) "订单金额待核对 · 尚未计入支出" else content,
                allowQuickConfirm = !awaitingAmount && stored.status == TxStatus.PENDING && stored.duplicateOf == null &&
                    stored.businessKind !in setOf("unknown", "transfer", "receivable", "payable"),
                recorded = stored.status == TxStatus.CONFIRMED, updateOnly = updateOnly, pendingEventKey = eventKey)
            trace(eventKey, source, if (posted) CaptureLatencyTrace.Stage.NOTIFICATION_SUBMITTED else CaptureLatencyTrace.Stage.NOTIFICATION_SKIPPED)
            NotificationHelper.cancelRecognitionPending(context, eventKey)
            if (LedgerRevision.current != revision) {
                NotificationHelper.cancelCaptureNotification(context, transactionId, storedEventKey)
                return
            }
        } catch (_: SecurityException) {
            trace(eventKey, source, CaptureLatencyTrace.Stage.NOTIFICATION_BLOCKED)
            monitor.onError("账目已保存，记账提醒权限不可用；请在流水中核对")
        }
    }

    private fun trace(eventKey: String, source: TxSource, stage: CaptureLatencyTrace.Stage,
        at: Long = System.currentTimeMillis()) {
        CaptureLatencyTrace.record(context, eventKey,
            if (source == TxSource.NOTIFICATION) CaptureLatencyTrace.Source.NOTIFICATION else CaptureLatencyTrace.Source.ACCESSIBILITY,
            stage, at)
    }

}

package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentReceiptDetailsReader
import com.pennypilot.app.data.parser.WalletReceiptDetailsParser
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicLong
import java.io.FileDescriptor
import java.io.PrintWriter

/** 可选的本机支付结果读取；系统授权及应用开关均满足后才读取指定应用。 */
class PaymentAccessibilityService : AccessibilityService() {
    private companion object {
        const val OCR_TIMEOUT_MS = 2_500L
        const val MAX_RECEIPT_IMAGE_ATTEMPTS = 3
        val receiptImageAnchor = Regex(
            """(?m)^(?:实付金额|实际支付|实际付款|实付款|支付金额|转账金额|退款金额|收款金额|支付时间|付款时间|交易时间|交易单号|微信支付单号|商户订单号|收款方|收款人|商户名称)(?:\s*[:：]|[ \t]*$)""")
    }

    private data class ReadRequest(val packageName: String, val windowId: Int, val revision: Long,
        val epoch: Long, val eventType: Int = 0, val className: String? = null)
    private data class ActiveRead(val epoch: Long, val job: Job)
    private val windowEpoch = AtomicLong()
    private val readStateLock = Any()
    private var activeRead: ActiveRead? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = PaymentScreenSession()
    private val checkoutContext get() = entryPoint.captureProcessor().checkoutContext
    private var checkoutExpiry: Job? = null
    private val redPacketFlow = WechatRedPacketFlow()
    private val redPacketReceiveFlow = WechatRedPacketFlow()
    // 保留最新窗口；同一窗口重绘不取消已有采样，避免动画密集时一直延后。
    private val requests = Channel<ReadRequest>(Channel.CONFLATED)
    private var readJob: Job? = null
    private var preferenceJob: Job? = null
    private data class VisualAttempt(val firstAt: Long, val lastAt: Long, val count: Int)
    private val visualAttempts = linkedMapOf<String, VisualAttempt>()
    private data class VisualResult(val generation: Long, val recognition: PaymentTextParser.ScreenRecognition)
    private val visualResults = linkedMapOf<String, VisualResult>()
    private val detailAttempts = linkedMapOf<String, VisualAttempt>()
    private data class DetailResult(val generation: Long, val snapshotHash: Int, val parsed: NotificationParseResult)
    private val detailResults = linkedMapOf<String, DetailResult>()
    private val receiptImageAttempts = linkedMapOf<String, Int>()
    private val visualGeneration = AtomicLong()
    private data class OpaqueReceipt(val parsed: NotificationParseResult, val cloudImage: String? = null)
    private data class OpaqueAttempt(val firstAt: Long, val lastAt: Long, val attempts: Int,
        val generation: Long, val cloudVision: Boolean, val parsed: NotificationParseResult? = null)
    private val opaqueAttempts = linkedMapOf<Int, OpaqueAttempt>()
    @Volatile private var enabled = false
    @Volatile private var latestWindow: ReadRequest? = null
    private val hiltEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }
    // 仅供本机服务集成测试替换依赖/调度器，生产始终使用 Hilt 和系统节点。
    internal var dependencies: CaptorServiceEntryPoint? = null
    internal var readDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var nowMillis: () -> Long = System::currentTimeMillis
    internal var elapsedMillis: () -> Long = SystemClock::elapsedRealtime
    internal var unknownScreenHandler: (suspend (String, String, String, Long, Long, String?, List<String>) -> Boolean)? = null
    internal var visualCapture: (suspend (PaymentScreenReader.Snapshot) -> Bitmap?)? = null
    internal var visualOcr: suspend (Bitmap) -> String = PaymentImageReader::ocrBitmap
    internal var opaqueVisualOcr: suspend (Bitmap) -> List<PaymentImageReader.OcrLine> = PaymentImageReader::ocrLines
    internal var receiptImageHandler: (suspend (String, String, String, Long, Long, Boolean) -> Boolean)? = null
    private val entryPoint get() = dependencies ?: hiltEntryPoint

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.monitor().onAccessibilityConnected()
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            entryPoint.appPreferences().screenCaptureEnabled.distinctUntilChanged().collect { allow ->
                enabled = allow
                readJob?.cancel()
                replaceWindow(null)
                visualGeneration.incrementAndGet()
                while (requests.tryReceive().isSuccess) { /* 开关切换前的事件不能在恢复后补读。 */ }
                synchronized(session) { session.reset() }
                clearCheckoutContext()
                synchronized(visualAttempts) { visualAttempts.clear(); visualResults.clear() }
                synchronized(detailAttempts) { detailAttempts.clear(); detailResults.clear() }
                synchronized(receiptImageAttempts) { receiptImageAttempts.clear() }
                synchronized(opaqueAttempts) { opaqueAttempts.clear() }
                redPacketFlow.reset()
                redPacketReceiveFlow.reset()
                entryPoint.monitor().onScreenStatus(if (allow) "waiting" else "disabled")
                if (allow) readJob = scope.launch(readDispatcher) {
                    // 重连/打开开关时页面可能已稳定，不会再发一个内容变化事件。
                    requestCurrentWindow()
                    for (request in requests) {
                        var newest = requests.tryReceive().getOrNull() ?: request
                        // 支付结果可能在 window 事件后才加载；短时有界重试不依赖通知或二次事件。
                        val pauses = longArrayOf(0, 250, 750, 1_500, 2_500, 10_000)
                        var attempt = 0
                        while (attempt < pauses.size) {
                            val pause = pauses[attempt]
                            // 新页面事件应立即唤醒采样，不能被上一页的重试等待挡住。
                            val update = if (pause > 0) withTimeoutOrNull(pause) {
                                requests.receiveCatching().getOrNull()
                            } else null
                            if (update != null) {
                                newest = update
                                // A confirmation arriving during the last old-page retry still
                                // needs its own bounded retries, including the OCR budget refill.
                                attempt = 0
                            }
                            if (!isCurrent(newest) || sampleCurrentWindow(newest, refreshCache = pause > 0)) break
                            attempt++
                        }
                    }
                }
            }
        }
        scope.launch(readDispatcher) {
            try { entryPoint.captureProcessor().refreshTodayCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { entryPoint.monitor().onError("捕获统计暂时不可用，请稍后重试") }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Only a user-owned open-button event in this exact wallet window can begin a claim.
            // Never inspect click text from chats, generic pages, input fields or other packages.
            if (enabled && event.packageName?.toString() == WechatOpaqueTransferReader.PACKAGE &&
                latestWindow?.className == WechatRedPacketReader.RECEIVE_ACTIVITY &&
                latestWindow?.windowId == event.windowId && event.text.any { it.toString().trim() in setOf("开", "打开红包", "拆红包") }) {
                redPacketReceiveFlow.start(elapsedMillis())
                synchronized(opaqueAttempts) { opaqueAttempts.clear() }
            }
            return
        }
        if (!enabled || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        val packageName = event.packageName?.toString()
        if (packageName == null) {
            // 某些窗口变化事件没有 package；只重新采样此前已确认的目标，不读任意应用。
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                visualGeneration.incrementAndGet()
                latestWindow?.let { requests.trySend(it) }
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) synchronized(checkoutContext) {
            checkoutContext.onWindowChanged(packageName, LedgerRevision.current, elapsedMillis(), nowMillis())
        }
        if (packageName !in PaymentAppRegistry.packages) {
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                visualGeneration.incrementAndGet()
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) replaceWindow(null)
            }
            return
        }
        val previous = latestWindow
        // In split-screen or during a transition a background window can emit redraws. It may
        // only replace the current payment surface once it is the active application root.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && previous != null &&
            (previous.packageName != packageName || previous.windowId != event.windowId) &&
            !activeWindowMatches(packageName, event.windowId)) return
        visualGeneration.incrementAndGet()
        val eventClass = event.className?.toString()
        if (packageName == WechatOpaqueTransferReader.PACKAGE && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventClass == WechatRedPacketReader.PREPARE_ACTIVITY && latestWindow?.className != eventClass) {
                redPacketFlow.start(elapsedMillis())
                redPacketReceiveFlow.reset()
                synchronized(opaqueAttempts) { opaqueAttempts.clear() }
            } else if (eventClass == "com.tencent.mm.ui.chatting.ChattingUI") {
                redPacketFlow.leaveUnpaid()
                redPacketReceiveFlow.leaveUnpaid()
            }
            else if (eventClass == "com.tencent.mm.plugin.remittance.ui.RemittanceUI") redPacketFlow.reset()
        }
        // Content redraws often name a View instead of the Activity. Retain only the Activity
        // established by a window-state event for this exact window, never across navigation.
        val pageClass = if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            previous?.let { it.packageName == packageName && it.windowId == event.windowId } == true)
            previous.className else eventClass
        if (packageName == WechatOpaqueTransferReader.PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventClass == "com.tencent.mm.plugin.remittance.ui.RemittanceUI") {
            // A new outgoing transfer flow can reuse the same result window and amount.
            synchronized(opaqueAttempts) {
                synchronized(session) { opaqueAttempts.keys.forEach { session.onInputPage(packageName, it, elapsedMillis()) } }
                opaqueAttempts.clear()
            }
        }
        val revision = LedgerRevision.current
        val epoch = previous?.takeIf { it.packageName == packageName && it.windowId == event.windowId &&
            it.revision == revision && it.className == pageClass }?.epoch ?: windowEpoch.incrementAndGet()
        val request = ReadRequest(packageName, event.windowId, revision, epoch, event.eventType, pageClass)
        replaceWindow(request)
        entryPoint.monitor().onScreenEvent(packageName)
        requests.trySend(request)
    }

    private fun isCurrent(request: ReadRequest) = enabled && request.revision == LedgerRevision.current &&
        latestWindow?.epoch == request.epoch

    private fun replaceWindow(request: ReadRequest?) {
        val superseded = synchronized(readStateLock) {
            latestWindow = request
            activeRead?.takeUnless { it.epoch == request?.epoch }?.job
        }
        superseded?.cancel()
    }

    private suspend fun sampleCurrentWindow(request: ReadRequest, refreshCache: Boolean): Boolean = coroutineScope {
        val attempt = async(start = CoroutineStart.LAZY) { readScreen(request, refreshCache) }
        val current = synchronized(readStateLock) {
            isCurrent(request).also { if (it) activeRead = ActiveRead(request.epoch, attempt) }
        }
        if (!current) {
            attempt.cancel()
            return@coroutineScope true
        }
        try { attempt.await() }
        catch (_: CancellationException) {
            // Cancel stale sampling on navigation, but propagate a stopped service/reader.
            currentCoroutineContext().ensureActive()
            true
        } finally {
            synchronized(readStateLock) { if (activeRead?.job === attempt) activeRead = null }
        }
    }

    @Suppress("DEPRECATION")
    private fun activeWindowMatches(packageName: String, windowId: Int): Boolean {
        val root = activeRoot(packageName, windowId) ?: return false
        return try {
            windowId < 0 || root.windowId == windowId
        } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun requestCurrentWindow() {
        try {
            val root = activeRoot() ?: return
            try {
                val packageName = root.packageName?.toString() ?: return
                val request = synchronized(readStateLock) {
                    if (!enabled || latestWindow != null || packageName !in PaymentAppRegistry.packages) return
                    ReadRequest(packageName, root.windowId, LedgerRevision.current, windowEpoch.incrementAndGet())
                        .also { latestWindow = it }
                }
                entryPoint.monitor().onScreenEvent(packageName)
                requests.trySend(request)
            } finally { root.recycle() }
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("unreadable")
        }
    }

    @Suppress("DEPRECATION")
    private fun activeRoot(expectedPackage: String? = null, expectedWindow: Int = -1): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            val owner = root.packageName?.toString()
            if ((expectedPackage == null || owner == expectedPackage) &&
                (expectedWindow < 0 || root.windowId < 0 || root.windowId == expectedWindow)) return root
            root.recycle()
            // A stale root from a supported app can occur during checkout handoff. Require
            // an exact window ID before resolving another app, and exclude system overlays.
            if (owner != expectedPackage && (expectedWindow < 0 || owner !in PaymentAppRegistry.packages)) return null
        }
        // The active root can still point at the previous page while the result is already active.
        // Require the current window list to confirm the exact event window.
        val available = windows
        try {
            val active = available.filter { it.isActive }
            val window = if (active.isEmpty() && expectedWindow < 0) {
                available.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            } else active.singleOrNull()
            if (window == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                (expectedWindow >= 0 && window.id != expectedWindow)) return null
            val root = window.root ?: return null
            if ((expectedPackage != null && root.packageName?.toString() != expectedPackage) ||
                (expectedWindow >= 0 && root.windowId != expectedWindow)) {
                root.recycle()
                return null
            }
            return root
        } finally { available.forEach { it.recycle() } }
    }

    /** true 表示本轮结果已处理；false 允许当前窗口在短时重试中变为可读。 */
    @Suppress("DEPRECATION")
    private suspend fun readScreen(request: ReadRequest, refreshCache: Boolean = false): Boolean {
        if (!isCurrent(request)) return true
        try {
            // Android 13+ 的旧节点缓存可能连续返回同一棵缺失树；补读前重新向目标应用取树。
            // 旧系统仍使用有界补读；不能以缓存清理失败为由放行不完整页面。
            if (refreshCache && Build.VERSION.SDK_INT >= 33) runCatching { clearCache() }
            val root = activeRoot(request.packageName, request.windowId)
            if (root == null) {
                entryPoint.monitor().onScreenRead("unreadable", packageName = request.packageName)
                CaptureDiagnosticStore.record(request.packageName,
                    PaymentScreenReader.Snapshot(null, false, "unreadable", emptyMap()),
                    request.windowId, request.eventType, request.className, false)
                return false
            }
            val snapshot: PaymentScreenReader.Snapshot
            val actualWindowId: Int
            try {
                actualWindowId = root.windowId
                snapshot = PaymentScreenReader.inspect(root, request.packageName)
            } finally { root.recycle() }
            if (!isCurrent(request)) return true
            val text = snapshot.text
            if (text == null) {
                if (acceptsOpaque(request, snapshot)) {
                    val cloudVision = visualModeStillAllowed(true)
                    CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                        request.eventType, request.className, false)
                    val receipt = readOpaqueTransfer(request, snapshot, actualWindowId, cloudVision) ?: return false
                    if (!isCurrent(request) || !visualModeStillAllowed(cloudVision)) return true
                    val parsed = receipt.parsed
                    entryPoint.monitor().onScreenRead("parsed", snapshot.diagnostics, request.packageName)
                    val flowKey = when (parsed.scene) {
                        "red_packet_sent" -> redPacketFlow.keyFor(parsed.amountCents, elapsedMillis()) ?: return false
                        "red_packet_received" -> redPacketReceiveFlow.keyFor(parsed.amountCents, elapsedMillis()) ?: return false
                        else -> null
                    }
                    val now = nowMillis()
                    val cloudEnrichment: (suspend (String) -> Boolean)? = if (cloudVision) { key ->
                        val queued = receipt.cloudImage?.let {
                            enqueueReceiptImage(request, it, key, now, allowImageOnly = false)
                        } == true
                        if (queued) synchronized(opaqueAttempts) {
                            opaqueAttempts[actualWindowId]?.let { attempt ->
                                opaqueAttempts[actualWindowId] = attempt.copy(parsed = parsed)
                            }
                        }
                        queued
                    } else null
                    return persistScreen(request, actualWindowId, parsed, now, elapsedMillis(), flowKey, cloudEnrichment)
                }
                // 明确输入页代表离开凭证；节点缺失/树未加载只算暂不可读，不能重置去重身份。
                if (snapshot.hasInput) synchronized(session) {
                    session.onInputPage(request.packageName, actualWindowId, elapsedMillis())
                }
                if (snapshot.hasInput) synchronized(visualAttempts) {
                    val prefix = "${request.packageName}:$actualWindowId:"
                    visualAttempts.keys.removeAll { it.startsWith(prefix) }
                    visualResults.keys.removeAll { it.startsWith(prefix) }
                    synchronized(detailAttempts) {
                        detailAttempts.keys.removeAll { it.startsWith(prefix) }
                        detailResults.keys.removeAll { it.startsWith(prefix) }
                    }
                }
                entryPoint.monitor().onScreenRead(snapshot.status, snapshot.diagnostics, request.packageName)
                CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                    request.eventType, request.className, false)
                return false
            }
            if (CheckoutContext.isPreview(request.packageName, text)) {
                val preview = snapshot.structure?.let { CheckoutContext.parse(request.packageName, text, it) }
                if (!isCurrent(request)) return true
                synchronized(session) { session.onInputPage(request.packageName, actualWindowId, elapsedMillis()) }
                synchronized(checkoutContext) {
                    if (isCurrent(request)) {
                        if (preview == null) checkoutContext.reset()
                        else if (checkoutContext.remember(preview, request.revision, elapsedMillis())) {
                            checkoutExpiry?.cancel()
                            checkoutExpiry = scope.launch {
                                delay(CheckoutContext.TTL_MS)
                                synchronized(checkoutContext) { checkoutContext.expire(LedgerRevision.current, elapsedMillis()) }
                            }
                        }
                    }
                }
                // The order is not yet paid. No ledger entry, OCR, or cloud task can come from it.
                entryPoint.monitor().onScreenRead("not_receipt", snapshot.diagnostics, request.packageName)
                return true
            }
            val received = if (request.packageName == WechatOpaqueTransferReader.PACKAGE &&
                request.className == WechatReceivedTransferReader.ACTIVITY)
                WechatReceivedTransferReader.parseText(text, nowMillis()) else null
            val detailed = received ?: WalletReceiptDetailsParser.parse(request.packageName, text, nowMillis(), snapshot.structure)
            var recognition = detailed?.let { PaymentTextParser.ScreenRecognition(it, it.scene!!,
                modelCandidate = false, evidenceText = text) }
                ?: PaymentTextParser.analyzeScreen(request.packageName, text, snapshot.structure)
            val hasReadableReceipt = recognition.parsed != null
            val cloudVision = (hasReadableReceipt || recognition.visualCandidate) && visualModeStillAllowed(true)
            CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                request.eventType, request.className, recognition.parsed != null || recognition.visualCandidate)
            val now = nowMillis()
            val elapsed = elapsedMillis()
            var visualTimedOut = false
            if (cloudVision && recognition.parsed == null && recognition.visualCandidate &&
                receiptImageAnchor.containsMatchIn(recognition.evidenceText)) {
                val candidateKey = synchronized(session) {
                    session.observeCandidate(request.packageName, actualWindowId, recognition.evidenceText, elapsed)
                }
                if (candidateKey != null) {
                    val image = captureCloudReceipt(request, snapshot, actualWindowId)
                    if (image != null && enqueueReceiptImage(request, image, candidateKey, now, allowImageOnly = true)) {
                        synchronized(session) { session.markHandled(candidateKey) }
                        return true
                    }
                }
            } else if (!cloudVision && recognition.parsed == null && recognition.visualCandidate && Build.VERSION.SDK_INT >= 30 &&
                entryPoint.appPreferences().screenVisualCaptureEnabled.first()) {
                val identity = "${request.packageName}:${actualWindowId}:${request.epoch}:${text.hashCode()}"
                val cached = synchronized(visualAttempts) {
                    visualResults[identity]?.takeIf { it.generation == visualGeneration.get() }?.recognition
                }
                if (cached != null) recognition = cached
                val shouldCapture = synchronized(visualAttempts) {
                    visualAttempts.entries.removeAll { elapsed - it.value.firstAt > 60_000 }
                    val previous = visualAttempts[identity]
                    // Rendering or screenshot delivery can lag the first event. Retry within
                    // a small budget without letting continuous redraws trigger unlimited OCR.
                    if (cached != null || previous != null &&
                        (previous.count >= 3 || elapsed - previous.lastAt < 750)) false else {
                        visualAttempts[identity] = VisualAttempt(previous?.firstAt ?: elapsed,
                            elapsed, (previous?.count ?: 0) + 1)
                        while (visualAttempts.size > 12) visualAttempts.remove(visualAttempts.keys.first())
                        true
                    }
                }
                if (shouldCapture) {
                    val generation = visualGeneration.get()
                    val bitmap = if (visualCapture != null) visualCapture!!.invoke(snapshot) else captureVisual(request, snapshot, generation)
                    if (bitmap != null) {
                        try {
                            val ocr = withTimeoutOrNull(OCR_TIMEOUT_MS) { visualOcr(bitmap) }
                            if (!matchesSnapshot(request, snapshot, generation)) {
                                synchronized(visualAttempts) { visualResults.remove(identity) }
                                return true
                            }
                            visualTimedOut = ocr == null
                            // Do not truncate a long OCR result and lose a failure/pending suffix.
                            if (ocr != null && text.length + ocr.length + 1 <= 12_000 &&
                                visualModeStillAllowed(false)) {
                                val visual = PaymentTextParser.analyzeScreen(request.packageName, "$text\n$ocr",
                                    evidenceSource = "页面与本地OCR")
                                if (visual.parsed != null) synchronized(visualAttempts) {
                                    recognition = visual
                                    // Keep extracted facts without retaining raw OCR or page text in the cache.
                                    visualResults[identity] = VisualResult(generation, visual.copy(evidenceText = "",
                                        modelCandidate = false, visualCandidate = false))
                                    while (visualResults.size > 12) visualResults.remove(visualResults.keys.first())
                                }
                            }
                            if (visualGeneration.get() != generation) {
                                synchronized(visualAttempts) { visualResults.remove(identity) }
                                return true
                            }
                        } finally { bitmap.recycle() }
                    } else if (visualGeneration.get() != generation) synchronized(visualAttempts) {
                        visualResults.remove(identity)
                    }
                }
            }
            if (!isCurrent(request)) return true
            val parsed = recognition.parsed
            if (parsed != null && !hasReadableReceipt && !visualModeStillAllowed(false)) return false
            val status = when {
                parsed != null -> "parsed"
                visualTimedOut -> "visual_unavailable"
                else -> "not_receipt"
            }
            val readDiagnostics = if (visualTimedOut) snapshot.diagnostics + ("visualOcrTimeout" to 1) else snapshot.diagnostics
            entryPoint.monitor().onScreenRead(status, readDiagnostics, request.packageName)
            if (parsed == null) {
                if (recognition.modelCandidate) {
                    val candidateKey = synchronized(session) {
                        session.observeCandidate(request.packageName, actualWindowId, recognition.evidenceText, elapsed)
                    }
                    val handled = if (candidateKey == null) false else unknownScreenHandler?.invoke(
                        request.packageName, recognition.evidenceText, candidateKey, now, request.revision,
                        snapshot.structure?.toJson(), emptyList()) ?: entryPoint.captureProcessor().processUnrecognizedScreen(
                        request.packageName, recognition.evidenceText, candidateKey, now, request.revision,
                        snapshot.structure?.toJson())
                    if (candidateKey != null && handled) {
                        synchronized(session) { session.markHandled(candidateKey) }
                    }
                } else if (!recognition.visualCandidate ||
                    !PaymentTextParser.canUseFallback(recognition.evidenceText, requireAmount = false)) {
                    synchronized(session) { session.observe(request.packageName, actualWindowId, null, elapsed) }
                }
                // A standalone completed result with temporarily unavailable OCR is still the
                // same receipt. Cooldown or a slow read must not create a false navigation gap.
                return false
            }
            if (request.packageName == WechatOpaqueTransferReader.PACKAGE && request.className == WechatRedPacketReader.DETAIL_ACTIVITY) {
                // The same history guard applies when a future WeChat version exposes nodes.
                val key = when (parsed.scene) {
                    "red_packet_sent" -> redPacketFlow.keyFor(parsed.amountCents, elapsed)
                    "red_packet_received" -> redPacketReceiveFlow.confirm(parsed.amountCents, elapsed)
                    else -> null
                }
                if (key == null) {
                    entryPoint.monitor().onScreenStatus("visual_requires_flow", request.packageName)
                    return false
                }
                return persistScreen(request, actualWindowId, parsed, now, elapsed, key)
            }
            val flowKey = if (parsed.scene == "red_packet_sent" && request.packageName == WechatOpaqueTransferReader.PACKAGE) {
                redPacketFlow.keyFor(parsed.amountCents, elapsed)
                    ?: if (redPacketFlow.isPreparing(elapsed)) redPacketFlow.confirm(parsed.amountCents, elapsed) else null
            } else if (parsed.scene == "red_packet_received" && request.packageName == WechatOpaqueTransferReader.PACKAGE) {
                redPacketReceiveFlow.keyFor(parsed.amountCents, elapsed)
                    ?: if (redPacketReceiveFlow.isPreparing(elapsed)) redPacketReceiveFlow.confirm(parsed.amountCents, elapsed) else null
            } else null
            // Resolve consent before publishing saved; disabled visual reading must not delay
            // scheduling the next node sample after a receipt has already been persisted.
            val enrichDetails = hasReadableReceipt && needsVisualDetails(parsed) &&
                !cloudVision && visualModeStillAllowed(false)
            if (!isCurrent(request)) return true
            val withCheckout = synchronized(checkoutContext) {
                checkoutContext.enrich(request.packageName, snapshot.structure, parsed, request.revision, elapsed)
            }
            val cloudEnrichment: (suspend (String) -> Boolean)? =
                if (cloudVision && hasReadableReceipt && needsVisualDetails(withCheckout)) { key ->
                    captureCloudReceipt(request, snapshot, actualWindowId)?.let {
                        enqueueReceiptImage(request, it, key, now, allowImageOnly = false)
                    } == true
                } else null
            val saved = persistScreen(request, actualWindowId, withCheckout, now, elapsed, flowKey, cloudEnrichment)
            // Save verified node evidence first: a slow/failed OCR or navigation must not lose it.
            // The amount-recovery path already used its image and must not request a second one.
            if (saved && enrichDetails) {
                val enriched = enrichVisualDetails(request, snapshot, actualWindowId, withCheckout)
                if (enriched != withCheckout && isCurrent(request) &&
                    visualModeStillAllowed(false)) {
                    persistScreen(request, actualWindowId, enriched, now, elapsed, flowKey)
                }
            }
            // Keep sampling readable results so late order IDs can enrich existing evidence.
            return false
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("error", request.packageName)
            entryPoint.monitor().onError("支付页面读取未完成；可检查授权或手动补记")
            return false
        }
    }

    private fun needsVisualDetails(parsed: NotificationParseResult): Boolean =
        (parsed.merchant == null && parsed.details.merchantName == null && parsed.details.payeeName == null) ||
            parsed.scene == "merchant_payment" ||
            (parsed.scene in setOf("transfer_sent", "transfer_received") &&
                (parsed.details.fundingAccount.isNullOrBlank() || parsed.details.remark.isNullOrBlank()))

    private suspend fun visualModeStillAllowed(cloud: Boolean): Boolean {
        val prefs = entryPoint.appPreferences()
        val cloudEnabled = Build.VERSION.SDK_INT >= 34 && prefs.screenCloudVisionEnabled.first()
        return if (cloud) cloudEnabled else !cloudEnabled && prefs.screenVisualCaptureEnabled.first()
    }

    private suspend fun captureCloudReceipt(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int): String? {
        if (Build.VERSION.SDK_INT < 34 || windowId < 0 ||
            (snapshot.diagnostics["foreignNodes"] ?: 0) > 0 || !visualModeStillAllowed(true)) return null
        val generation = visualGeneration.get()
        val bitmap = visualCapture?.invoke(snapshot) ?: if (visualCapture == null)
            captureVisual(request, snapshot, generation, windowId, includePanel = true) else null
        bitmap ?: return null
        return try {
            if (!matchesSnapshot(request, snapshot, generation, windowId)) return null
            val image = PaymentImageReader.dataUrl(bitmap)
            image.takeIf { matchesSnapshot(request, snapshot, generation, windowId) && visualModeStillAllowed(true) }
        } catch (_: IllegalArgumentException) {
            entryPoint.monitor().onScreenRead("visual_unavailable", snapshot.diagnostics, request.packageName)
            null
        } finally { bitmap.recycle() }
    }

    private suspend fun enqueueReceiptImage(request: ReadRequest, image: String, key: String,
        observedAt: Long, allowImageOnly: Boolean): Boolean {
        if (!isCurrent(request) || !visualModeStillAllowed(true)) return false
        return receiptImageHandler?.invoke(request.packageName, image, key, observedAt, request.revision, allowImageOnly)
            ?: entryPoint.captureProcessor().processReceiptImage(
                request.packageName, image, key, observedAt, request.revision, allowImageOnly)
    }

    private suspend fun enrichVisualDetails(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int, original: NotificationParseResult): NotificationParseResult {
        val diagnostics = mutableMapOf<String, Int>()
        val savedStatus = entryPoint.monitor().health.value.screenCaptureStatus
        fun reason(name: String): NotificationParseResult {
            diagnostics[name] = 1
            entryPoint.monitor().onScreenRead(savedStatus, snapshot.diagnostics + diagnostics, request.packageName)
            return original
        }
        // Expanded details are captured from this application window only, never the display.
        if (Build.VERSION.SDK_INT < 34 || windowId < 0) return reason("detailWindowUnavailable")
        if ((snapshot.diagnostics["foreignNodes"] ?: 0) > 0) return reason("detailForeignNodes")
        val generation = visualGeneration.get()
        if (!matchesSnapshot(request, snapshot, generation, windowId)) return reason("detailSnapshotChanged")
        val identity = "${request.packageName}:$windowId:${request.epoch}:${original.amountCents}:${original.direction}:" +
            "${original.orderId}:${original.occurredAt}"
        val elapsed = elapsedMillis()
        synchronized(detailAttempts) {
            val expired = detailAttempts.filterValues { elapsed - it.firstAt > 60_000 }.keys
            expired.forEach { detailAttempts.remove(it); detailResults.remove(it) }
            detailResults[identity]?.takeIf { it.generation == generation && it.snapshotHash == snapshot.structure.hashCode() }
        }?.let { return it.parsed }
        val blocked = synchronized(detailAttempts) {
            val previous = detailAttempts[identity]
            when {
                previous != null && previous.count >= 3 -> "detailBudgetExhausted"
                previous != null && elapsed - previous.lastAt < 750 -> "detailRetryWaiting"
                else -> {
                    detailAttempts[identity] = VisualAttempt(previous?.firstAt ?: elapsed, elapsed, (previous?.count ?: 0) + 1)
                    while (detailAttempts.size > 12) {
                        val oldest = detailAttempts.keys.first()
                        detailAttempts.remove(oldest)
                        detailResults.remove(oldest)
                    }
                    null
                }
            }
        }
        if (blocked != null) return reason(blocked)
        try {
            val bitmap = if (visualCapture != null) visualCapture!!.invoke(snapshot) else
                captureVisual(request, snapshot, generation, windowId, includePanel = true, diagnostics = diagnostics)
            if (bitmap == null) return reason("detailScreenshotUnavailable")
            val ocr = try { withTimeoutOrNull(OCR_TIMEOUT_MS) { visualOcr(bitmap) } } finally { bitmap.recycle() }
                ?: return reason("detailOcrTimeout")
            if (!visualModeStillAllowed(false)) return reason("detailConsentRevoked")
            if (!matchesSnapshot(request, snapshot, generation, windowId)) return reason("detailSnapshotChanged")
            if (ocr.isBlank()) return reason("detailOcrEmpty")
            val text = snapshot.text ?: return reason("detailSnapshotChanged")
            if (text.length + ocr.length + 1 > 12_000) return reason("detailTextLimit")
            if (!PaymentTextParser.isConsistentFallback(ocr, original)) return reason("detailFactsConflict")
            val combined = "$text\n$ocr"
            val candidate = WalletReceiptDetailsParser.parse(request.packageName, combined, nowMillis(), snapshot.structure)
                ?: PaymentTextParser.analyzeScreen(request.packageName, combined, snapshot.structure,
                    evidenceSource = "页面与本地OCR").parsed
                ?: return reason("detailUnverified")
            if (candidate.amountCents != original.amountCents || candidate.direction != original.direction ||
                (original.orderId != null && candidate.orderId != original.orderId) ||
                (original.occurredAt != null && candidate.occurredAt != original.occurredAt) ||
                (original.orderIdKind != null && candidate.orderIdKind != original.orderIdKind))
                return reason("detailFactsConflict")
            val supplemental = PaymentTextParser.withReceiptDetails(request.packageName, combined, candidate,
                snapshot.structure, "页面与本地OCR").details
            // Inspect OCR roles separately: conflicting duplicate labels can disappear from a
            // combined parse. A store name and its collecting company are complementary roles.
            val ocrRead = PaymentReceiptDetailsReader.read(request.packageName, ocr, null, "本地OCR")
            if (ocrRead.ambiguousFields.isNotEmpty()) return reason("detailFieldsConflict")
            val ocrDetails = ocrRead.details
            val conflicts = listOf(original.details.merchantName to ocrDetails.merchantName.takeUnless { ocrRead.merchantFromStore },
                original.details.payeeName to ocrDetails.payeeName,
                original.details.paymentChannel to ocrDetails.paymentChannel,
                original.details.fundingAccount to ocrDetails.fundingAccount)
                .any { (known, next) -> known != null && next != null && known != next }
            if (conflicts) return reason("detailFieldsConflict")
            if (original.merchant != null && candidate.merchant != null && original.merchant != candidate.merchant &&
                !(original.details.merchantName == null && original.details.payeeName == original.merchant &&
                    supplemental.merchantName != null)) return reason("detailFieldsConflict")
            // Financial facts, identity, time and their provenance remain owned by the node receipt.
            val enriched = original.copy(merchant = original.merchant ?: candidate.merchant,
                details = original.details.mergeMissing(supplemental))
            if (enriched == original) return reason("detailNoAdditionalFields")
            synchronized(detailAttempts) {
                detailResults[identity] = DetailResult(generation, snapshot.structure.hashCode(), enriched)
            }
            diagnostics["detailEnriched"] = 1
            entryPoint.monitor().onScreenRead(savedStatus, snapshot.diagnostics + diagnostics, request.packageName)
            return enriched
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            return reason("detailOcrFailure")
        }
    }

    private suspend fun persistScreen(request: ReadRequest, windowId: Int, parsed: NotificationParseResult,
        now: Long, elapsed: Long, flowKey: String? = null,
        cloudEnrichment: (suspend (String) -> Boolean)? = null): Boolean {
        val key = flowKey ?: synchronized(session) { session.observe(request.packageName, windowId, parsed, elapsed) }
        if (key == null && cloudEnrichment == null) {
            entryPoint.monitor().onScreenStatus("duplicate", request.packageName)
            return true
        }
        if (key != null) {
            val evidence = synchronized(session) { session.evidence(key) } ?: parsed
            CaptureLatencyTrace.record(this, key, CaptureLatencyTrace.Source.ACCESSIBILITY, CaptureLatencyTrace.Stage.RECEIVED, now)
            if (!entryPoint.captureProcessor().processScreen(request.packageName, evidence, key, now, request.revision,
                    queueSemanticModel = cloudEnrichment == null)) {
                entryPoint.monitor().onScreenStatus("error", request.packageName)
                return false
            }
            synchronized(session) { session.markHandled(key) }
        }
        if (cloudEnrichment == null) return true
        val imageKey = key ?: synchronized(session) { session.handledReceiptKey(request.packageName, windowId) } ?: return true
        // A failed image capture can retry without creating another ledger entry.
        synchronized(receiptImageAttempts) {
            val attempts = receiptImageAttempts[imageKey] ?: 0
            if (attempts >= MAX_RECEIPT_IMAGE_ATTEMPTS) return true
            receiptImageAttempts[imageKey] = attempts + 1
            while (receiptImageAttempts.size > 16) receiptImageAttempts.remove(receiptImageAttempts.keys.first())
        }
        if (!cloudEnrichment(imageKey)) return false
        synchronized(receiptImageAttempts) { receiptImageAttempts[imageKey] = MAX_RECEIPT_IMAGE_ATTEMPTS }
        return true
    }

    private suspend fun readOpaqueTransfer(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int, cloudVision: Boolean): OpaqueReceipt? {
        val monitor = entryPoint.monitor()
        var diagnostics = snapshot.diagnostics
        fun status(value: String) = monitor.onScreenRead(value, diagnostics, request.packageName)
        if (!visualModeStillAllowed(cloudVision)) {
            status("visual_required")
            return null
        }
        if (request.className == WechatRedPacketReader.DETAIL_ACTIVITY &&
            !redPacketFlow.hasPaid(elapsedMillis()) && !redPacketReceiveFlow.hasPaid(elapsedMillis()) &&
            !redPacketReceiveFlow.isPreparing(elapsedMillis())) {
            status("visual_requires_flow")
            return null
        }
        // Window-specific capture excludes other apps and overlays. Older Android versions retain
        // the normal node-based path; no broad full-display fallback for an unobservable page.
        if (Build.VERSION.SDK_INT < 34 || windowId < 0) {
            status("visual_unavailable")
            return null
        }
        val now = elapsedMillis()
        val generation = visualGeneration.get()
        val previous = synchronized(opaqueAttempts) { opaqueAttempts[windowId]?.takeIf { it.cloudVision == cloudVision } }
        if (previous != null && now - previous.firstAt < 10_000) {
            previous.parsed?.takeIf { previous.generation == generation }?.let { return OpaqueReceipt(it) }
            if (now - previous.lastAt < 750 || previous.attempts >= 3) {
                status("visual_waiting")
                return null
            }
        }
        synchronized(opaqueAttempts) {
            opaqueAttempts[windowId] = OpaqueAttempt(
                previous?.firstAt?.takeIf { now - it < 10_000 } ?: now, now,
                if (previous != null && now - previous.firstAt < 10_000) previous.attempts + 1 else 1, generation, cloudVision)
            while (opaqueAttempts.size > 8) opaqueAttempts.remove(opaqueAttempts.keys.first())
        }
        if (!matchesOpaqueSnapshot(request, snapshot, generation)) return null
        status("visual_read")
        val screenshotDiagnostics = mutableMapOf<String, Int>()
        val bitmap = if (visualCapture != null) visualCapture!!.invoke(snapshot) else captureBitmap(windowId, screenshotDiagnostics)
        diagnostics = diagnostics + screenshotDiagnostics
        if (bitmap == null) { status("visual_unavailable"); return null }
        try {
            val lines = withTimeoutOrNull(OCR_TIMEOUT_MS) { opaqueVisualOcr(bitmap) }
            if (lines == null) {
                diagnostics = diagnostics + ("visualOcrTimeout" to 1)
                status("visual_unavailable")
                return null
            }
            diagnostics = diagnostics + WechatOpaqueTransferReader.diagnostics(lines, bitmap.width, bitmap.height)
            if (request.className == WechatReceivedTransferReader.ACTIVITY) {
                diagnostics = diagnostics + WechatReceivedTransferReader.diagnostics(lines)
            }
            if (!matchesOpaqueSnapshot(request, snapshot, generation) ||
                !visualModeStillAllowed(cloudVision)) {
                synchronized(opaqueAttempts) { opaqueAttempts.remove(windowId) }
                return null
            }
            val receivedDiagnostics = mutableMapOf<String, Int>()
            val parsed = if (request.className == WechatReceivedTransferReader.ACTIVITY)
                WechatReceivedTransferReader.parse(lines, bitmap.width, bitmap.height, nowMillis(), diagnostics = receivedDiagnostics)
                else if (request.className == WechatRedPacketReader.DETAIL_ACTIVITY) {
                    WechatRedPacketReader.parseSentDetail(lines, bitmap.width, bitmap.height)?.takeIf {
                        redPacketFlow.keyFor(it.amountCents, elapsedMillis()) != null
                    } ?: WechatRedPacketReader.parseReceivedDetail(lines, bitmap.width, bitmap.height)?.takeIf {
                        redPacketReceiveFlow.confirm(it.amountCents, elapsedMillis()) != null
                    }
                }
                else WechatOpaqueTransferReader.parse(lines, bitmap.width, bitmap.height)
                    ?: (if (redPacketFlow.isPreparing(elapsedMillis()))
                        WechatRedPacketReader.parsePaymentResult(lines, bitmap.width, bitmap.height)?.takeIf {
                            redPacketFlow.confirm(it.amountCents, elapsedMillis()) != null
                        } else null)
                    ?: if (request.className == WechatOpaqueTransferReader.ACTIVITY &&
                        isCurrent(request) && visualGeneration.get() == generation) {
                        WechatOpaqueMerchantReceiptReader.parse(lines, bitmap.width, bitmap.height, nowMillis())?.let { candidate ->
                            candidate.merchantOrderId?.let { order ->
                                checkoutContext.enrichOpaqueMerchant(request.packageName, candidate.receipt,
                                    order, candidate.merchant, request.revision, elapsedMillis())
                            } ?: candidate.receipt.takeIf { candidate.independentlyVerified }
                        }
                    } else null
            diagnostics = diagnostics + receivedDiagnostics
            if (parsed == null) {
                // A readable input/history/failed image is a real non-receipt boundary. An empty
                // OCR result can be a transient loading frame and must not reset transaction identity.
                if (lines.isNotEmpty()) synchronized(session) {
                    session.observe(request.packageName, windowId, null, elapsedMillis())
                }
                status("visual_not_receipt")
                return null
            }
            // Opaque WeChat windows need template verification to prove they contain a receipt.
            // In cloud mode only the verified financial facts survive the local read.
            val facts = if (cloudVision) parsed.copy(
                merchant = null,
                details = PaymentReceiptDetails(paymentChannel = parsed.details.paymentChannel),
                fieldEvidence = parsed.fieldEvidence.filterKeys {
                    it in setOf("amount", "direction", "paymentStatus", "orderId", "occurredAt", "transferTime")
                }) else parsed
            val image = if (cloudVision) encodeOpaqueReceiptImage(bitmap, parsed.scene, request.className) else null
            if (!matchesOpaqueSnapshot(request, snapshot, generation) || !visualModeStillAllowed(cloudVision)) return null
            if (!cloudVision) synchronized(opaqueAttempts) { opaqueAttempts[windowId]?.let {
                opaqueAttempts[windowId] = it.copy(parsed = facts)
            } }
            return OpaqueReceipt(facts, image)
        } finally { bitmap.recycle() }
    }

    private fun encodeOpaqueReceiptImage(bitmap: Bitmap, scene: String?, activity: String?): String? {
        // These edges correspond to the receipt content checked by the opaque templates.
        // Group red-packet details may show other recipients below the user's own result.
        val bottom = when (scene) {
            "transfer_sent" -> .45
            "red_packet_received" -> .55
            "red_packet_sent" -> if (activity == WechatRedPacketReader.DETAIL_ACTIVITY) .35 else .61
            "transfer_received", "merchant_payment" -> .97
            else -> return null
        }
        val top = (bitmap.height * .045).toInt()
        val height = (bitmap.height * bottom).toInt() - top
        if (height <= 0) return null
        val receipt = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
        return try { PaymentImageReader.dataUrl(receipt) }
        catch (_: IllegalArgumentException) { null }
        finally { if (receipt !== bitmap) receipt.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun matchesOpaqueSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation || latestWindow?.className != request.className) return false
        val root = activeRoot(request.packageName, request.windowId) ?: return false
        return try {
            root.windowId == request.windowId &&
                PaymentScreenReader.inspect(root, request.packageName).let {
                    it.diagnostics == snapshot.diagnostics &&
                        acceptsOpaque(request, it)
                }
        } finally { root.recycle() }
    }

    private fun acceptsOpaque(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot): Boolean =
        WechatOpaqueTransferReader.accepts(request.packageName, request.className, snapshot) ||
            WechatRedPacketReader.accepts(request.packageName, request.className, snapshot)

    @Suppress("DEPRECATION")
    private fun matchesSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long,
        windowId: Int? = null): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation) return false
        if (windowId != null && request.windowId >= 0 && windowId != request.windowId) return false
        val root = activeRoot(request.packageName, windowId ?: request.windowId) ?: return false
        return try {
            (windowId == null || root.windowId == windowId) &&
                PaymentScreenReader.inspect(root, request.packageName).let {
                    it.text != null && !it.hasInput && it.text == snapshot.text && it.structure == snapshot.structure
                }
        } finally { root.recycle() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureVisual(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long,
        windowId: Int? = null, includePanel: Boolean = false,
        diagnostics: MutableMap<String, Int> = mutableMapOf()): Bitmap? {
        if (!matchesSnapshot(request, snapshot, generation, windowId)) return null
        val targetWindow = windowId ?: request.windowId.takeIf { it >= 0 && Build.VERSION.SDK_INT >= 34 }
        val whole = captureBitmap(targetWindow, diagnostics) ?: run {
            entryPoint.monitor().onScreenRead("visual_unavailable", snapshot.diagnostics + diagnostics, request.packageName)
            return null
        }
        return try {
            if (!matchesSnapshot(request, snapshot, generation, windowId)) return null
            val region = PaymentVisualRegion.bounds(snapshot.structure, whole.width, whole.height,
                includePanel = includePanel, windowRelative = targetWindow != null) ?: run {
                diagnostics["visualRegionUnavailable"] = 1
                return null
            }
            Bitmap.createBitmap(whole, region.left, region.top, region.width(), region.height())
                .let { if (it === whole) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        } finally { whole.recycle() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureBitmap(windowId: Int? = null,
        diagnostics: MutableMap<String, Int> = mutableMapOf()): Bitmap? {
        var completed = false
        val whole = withTimeoutOrNull(2_500) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        completed = true
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            runCatching {
                                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                                    try {
                                        if (hardware.width.toLong() * hardware.height > 12_000_000) null
                                        else hardware.copy(Bitmap.Config.ARGB_8888, false)
                                    } finally { hardware.recycle() }
                                }
                            }.getOrNull()
                        } finally { buffer.close() }
                        if (bitmap == null) diagnostics["visualBitmapUnavailable"] = 1
                        // A window change can cancel between callback delivery and resuming OCR.
                        continuation.resume(bitmap, onCancellation = { _, undelivered, _ -> undelivered?.recycle() })
                    }
                    override fun onFailure(errorCode: Int) {
                        completed = true
                        diagnostics["visualScreenshotErrorCode"] = errorCode
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                if (windowId != null && Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(windowId, mainExecutor, callback)
                else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }
        if (!completed) diagnostics["visualScreenshotTimeout"] = 1
        return whole
    }

    // ADB diagnostics expose states/counters only, never screen/OCR text, images, amounts or names.
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val health = entryPoint.monitor().health.value
        writer.println("screenCaptureStatus=${health.screenCaptureStatus}")
        writer.println("screenReadDiagnostics=${health.screenReadDiagnostics}")
        writer.println("todayCaptureCount=${health.todayCaptureCount}")
        health.screenReadHistory.forEach { writer.println("trace=${it.timestamp} ${it.status} ${it.diagnostics}") }
        CaptureLatencyTrace.dumpTo(this, writer)
    }

    // 本服务没有语音/触觉反馈；onInterrupt 不代表系统已撤销授权。
    override fun onInterrupt() = Unit

    private fun clearCheckoutContext() = synchronized(checkoutContext) {
        checkoutContext.clearAuthorization()
        checkoutExpiry?.cancel()
        checkoutExpiry = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        enabled = false
        visualGeneration.incrementAndGet()
        replaceWindow(null)
        readJob?.cancel()
        preferenceJob?.cancel()
        clearCheckoutContext()
        entryPoint.monitor().onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        enabled = false
        visualGeneration.incrementAndGet()
        replaceWindow(null)
        clearCheckoutContext()
        scope.cancel()
        requests.close()
        entryPoint.monitor().onAccessibilityDisconnected()
        super.onDestroy()
    }
}

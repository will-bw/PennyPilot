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
import com.pennypilot.app.data.parser.WalletReceiptDetailsParser
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private data class ReadRequest(val packageName: String, val windowId: Int, val revision: Long,
        val eventType: Int = 0, val className: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = PaymentScreenSession()
    private val redPacketFlow = WechatRedPacketFlow()
    private val redPacketReceiveFlow = WechatRedPacketFlow()
    // 保留最新窗口；同一窗口重绘不取消已有采样，避免动画密集时一直延后。
    private val requests = Channel<ReadRequest>(Channel.CONFLATED)
    private var readJob: Job? = null
    private var preferenceJob: Job? = null
    private val visualAttempts = linkedMapOf<String, Long>()
    private val visualResults = linkedMapOf<String, PaymentTextParser.ScreenRecognition>()
    private val visualGeneration = AtomicLong()
    private data class OpaqueAttempt(val firstAt: Long, val lastAt: Long, val attempts: Int,
        val generation: Long, val parsed: NotificationParseResult? = null)
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
    private val entryPoint get() = dependencies ?: hiltEntryPoint

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.monitor().onAccessibilityConnected()
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            entryPoint.appPreferences().screenCaptureEnabled.distinctUntilChanged().collect { allow ->
                enabled = allow
                readJob?.cancel()
                latestWindow = null
                visualGeneration.incrementAndGet()
                while (requests.tryReceive().isSuccess) { /* 开关切换前的事件不能在恢复后补读。 */ }
                synchronized(session) { session.reset() }
                synchronized(visualAttempts) { visualAttempts.clear(); visualResults.clear() }
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
                            if (!isCurrent(newest) || readScreen(newest, refreshCache = pause > 0)) break
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
        visualGeneration.incrementAndGet()
        val packageName = event.packageName?.toString()
        if (packageName == null) {
            // 某些窗口变化事件没有 package；只重新采样此前已确认的目标，不读任意应用。
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) latestWindow?.let { requests.trySend(it) }
            return
        }
        if (packageName !in PaymentAppRegistry.packages) return
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
            latestWindow?.let { it.packageName == packageName && it.windowId == event.windowId } == true)
            latestWindow?.className else eventClass
        if (packageName == WechatOpaqueTransferReader.PACKAGE &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventClass == "com.tencent.mm.plugin.remittance.ui.RemittanceUI") {
            // A new outgoing transfer flow can reuse the same result window and amount.
            synchronized(opaqueAttempts) {
                synchronized(session) { opaqueAttempts.keys.forEach { session.onInputPage(packageName, it, elapsedMillis()) } }
                opaqueAttempts.clear()
            }
        }
        val request = ReadRequest(packageName, event.windowId, LedgerRevision.current,
            event.eventType, pageClass)
        latestWindow = request
        entryPoint.monitor().onScreenEvent(packageName)
        requests.trySend(request)
    }

    private fun isCurrent(request: ReadRequest) = enabled && request.revision == LedgerRevision.current &&
        latestWindow?.let { it.packageName == request.packageName && it.windowId == request.windowId &&
            it.revision == request.revision && it.className == request.className } == true

    @Suppress("DEPRECATION")
    private fun requestCurrentWindow() {
        try {
            val root = activeRoot() ?: return
            try {
                val packageName = root.packageName?.toString() ?: return
                if (!enabled || latestWindow != null || packageName !in PaymentAppRegistry.packages) return
                val request = ReadRequest(packageName, root.windowId, LedgerRevision.current)
                latestWindow = request
                entryPoint.monitor().onScreenEvent(packageName)
                requests.trySend(request)
            } finally { root.recycle() }
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("unreadable")
        }
    }

    @Suppress("DEPRECATION")
    private fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        // 对话框/多窗口切换时 active root 可能为空；只读活动/聚焦的应用窗口，不能读背后旧支付页。
        val available = windows
        try {
            return available.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                .mapNotNull { it.root }.firstOrNull()
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
            val root = activeRoot()
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
                // 部分事件的 windowId 未定义；已给出 id 时必须匹配，不能误读旧窗口。
                if (root.packageName?.toString() != request.packageName ||
                    (request.windowId >= 0 && actualWindowId >= 0 && actualWindowId != request.windowId)) {
                    entryPoint.monitor().onScreenRead("unreadable", packageName = request.packageName)
                    return false
                }
                snapshot = PaymentScreenReader.inspect(root, request.packageName)
            } finally { root.recycle() }
            if (!isCurrent(request)) return true
            val text = snapshot.text
            if (text == null) {
                if (acceptsOpaque(request, snapshot)) {
                    CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                        request.eventType, request.className, false)
                    val parsed = readOpaqueTransfer(request, snapshot, actualWindowId) ?: return false
                    if (!isCurrent(request) || !entryPoint.appPreferences().screenVisualCaptureEnabled.first()) return true
                    entryPoint.monitor().onScreenRead("parsed", snapshot.diagnostics, request.packageName)
                    val flowKey = when (parsed.scene) {
                        "red_packet_sent" -> redPacketFlow.keyFor(parsed.amountCents, elapsedMillis()) ?: return false
                        "red_packet_received" -> redPacketReceiveFlow.keyFor(parsed.amountCents, elapsedMillis()) ?: return false
                        else -> null
                    }
                    return persistScreen(request, actualWindowId, parsed, nowMillis(), elapsedMillis(), flowKey)
                }
                // 明确输入页代表离开凭证；节点缺失/树未加载只算暂不可读，不能重置去重身份。
                if (snapshot.hasInput) synchronized(session) {
                    session.onInputPage(request.packageName, actualWindowId, elapsedMillis())
                }
                if (snapshot.hasInput) synchronized(visualAttempts) {
                    val prefix = "${request.packageName}:$actualWindowId:"
                    visualAttempts.keys.removeAll { it.startsWith(prefix) }
                    visualResults.keys.removeAll { it.startsWith(prefix) }
                }
                entryPoint.monitor().onScreenRead(snapshot.status, snapshot.diagnostics, request.packageName)
                CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                    request.eventType, request.className, false)
                return false
            }
            val received = if (request.packageName == WechatOpaqueTransferReader.PACKAGE &&
                request.className == WechatReceivedTransferReader.ACTIVITY)
                WechatReceivedTransferReader.parseText(text, nowMillis()) else null
            val detailed = received ?: WalletReceiptDetailsParser.parse(request.packageName, text, nowMillis(), snapshot.structure)
            var recognition = detailed?.let { PaymentTextParser.ScreenRecognition(it, it.scene!!,
                modelCandidate = false, evidenceText = text) }
                ?: PaymentTextParser.analyzeScreen(request.packageName, text, snapshot.structure)
            CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                request.eventType, request.className, recognition.parsed != null || recognition.visualCandidate)
            val now = nowMillis()
            val elapsed = elapsedMillis()
            var images = emptyList<String>()
            if (recognition.parsed == null && recognition.visualCandidate && Build.VERSION.SDK_INT >= 30 &&
                entryPoint.appPreferences().screenVisualCaptureEnabled.first()) {
                val identity = "${request.packageName}:${actualWindowId}:${text.hashCode()}"
                val cached = synchronized(visualAttempts) { visualResults[identity] }
                if (cached != null) recognition = cached
                val shouldCapture = synchronized(visualAttempts) {
                    visualAttempts.entries.removeAll { elapsed - it.value > 60_000 }
                    if (cached != null || identity in visualAttempts) false else {
                        visualAttempts[identity] = elapsed
                        while (visualAttempts.size > 12) visualAttempts.remove(visualAttempts.keys.first())
                        true
                    }
                }
                if (shouldCapture) {
                    val generation = visualGeneration.get()
                    val bitmap = if (visualCapture != null) visualCapture!!.invoke(snapshot) else captureVisual(request, snapshot, generation)
                    if (bitmap != null) {
                        try {
                            val ocr = visualOcr(bitmap)
                            if (!matchesSnapshot(request, snapshot, generation)) {
                                synchronized(visualAttempts) { visualAttempts.remove(identity); visualResults.remove(identity) }
                                return true
                            }
                            val combined = "$text\n$ocr".take(12_000)
                            // Do not truncate a long OCR result and lose a failure/pending suffix.
                            if (text.length + ocr.length + 1 <= 12_000) {
                                recognition = PaymentTextParser.analyzeScreen(request.packageName, combined,
                                    evidenceSource = "页面与本地OCR")
                                if (recognition.parsed != null) synchronized(visualAttempts) {
                                    // Retain the extracted facts, never the raw OCR/page text.
                                    visualResults[identity] = recognition.copy(evidenceText = "",
                                        modelCandidate = false, visualCandidate = false)
                                    while (visualResults.size > 12) visualResults.remove(visualResults.keys.first())
                                }
                                if (recognition.parsed == null && recognition.modelCandidate &&
                                    entryPoint.appPreferences().cloudImageParsingEnabled.first()) {
                                    val amount = PaymentTextParser.screenCandidateAmount(request.packageName,
                                        recognition.evidenceText)
                                    images = amount?.let { PaymentImageReader.automaticCloudDataUrl(bitmap, it) }
                                        ?.let(::listOf).orEmpty()
                                }
                            }
                            if (visualGeneration.get() != generation) {
                                synchronized(visualAttempts) { visualAttempts.remove(identity); visualResults.remove(identity) }
                                return true
                            }
                        } finally { bitmap.recycle() }
                    } else if (visualGeneration.get() != generation) synchronized(visualAttempts) {
                        visualAttempts.remove(identity)
                        visualResults.remove(identity)
                    }
                }
            }
            if (!isCurrent(request)) return true
            val parsed = recognition.parsed
            entryPoint.monitor().onScreenRead(if (parsed == null) "not_receipt" else "parsed", snapshot.diagnostics, request.packageName)
            if (parsed == null) {
                if (recognition.modelCandidate) {
                    val candidateKey = synchronized(session) {
                        session.observeCandidate(request.packageName, actualWindowId, recognition.evidenceText, elapsed)
                    }
                    val handled = if (candidateKey == null) false else unknownScreenHandler?.invoke(
                        request.packageName, recognition.evidenceText, candidateKey, now, request.revision,
                        snapshot.structure?.toJson(), images) ?: entryPoint.captureProcessor().processUnrecognizedScreen(
                        request.packageName, recognition.evidenceText, candidateKey, now, request.revision,
                        snapshot.structure?.toJson(), images)
                    if (candidateKey != null && handled) {
                        synchronized(session) { session.markHandled(candidateKey) }
                    }
                } else synchronized(session) { session.observe(request.packageName, actualWindowId, null, elapsed) }
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
            persistScreen(request, actualWindowId, parsed, now, elapsed, flowKey)
            // Keep sampling readable results so late order IDs can enrich existing evidence.
            return false
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("error", request.packageName)
            entryPoint.monitor().onError("支付页面读取未完成；可检查授权或手动补记")
            return false
        }
    }

    private suspend fun persistScreen(request: ReadRequest, windowId: Int, parsed: NotificationParseResult,
        now: Long, elapsed: Long, flowKey: String? = null): Boolean {
        val key = flowKey ?: synchronized(session) { session.observe(request.packageName, windowId, parsed, elapsed) }
        if (key == null) {
            entryPoint.monitor().onScreenStatus("duplicate", request.packageName)
            return true
        }
        val evidence = synchronized(session) { session.evidence(key) } ?: parsed
        if (entryPoint.captureProcessor().processScreen(request.packageName, evidence, key, now, request.revision)) {
            synchronized(session) { session.markHandled(key) }
            return true
        }
        entryPoint.monitor().onScreenStatus("error", request.packageName)
        return false
    }

    private suspend fun readOpaqueTransfer(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int): NotificationParseResult? {
        val monitor = entryPoint.monitor()
        var diagnostics = snapshot.diagnostics
        fun status(value: String) = monitor.onScreenRead(value, diagnostics, request.packageName)
        if (!entryPoint.appPreferences().screenVisualCaptureEnabled.first()) {
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
        val previous = synchronized(opaqueAttempts) { opaqueAttempts[windowId] }
        if (previous != null && now - previous.firstAt < 10_000) {
            previous.parsed?.takeIf { previous.generation == generation }?.let { return it }
            if (now - previous.lastAt < 750 || previous.attempts >= 3) {
                status("visual_waiting")
                return null
            }
        }
        synchronized(opaqueAttempts) {
            opaqueAttempts[windowId] = OpaqueAttempt(
                previous?.firstAt?.takeIf { now - it < 10_000 } ?: now, now,
                if (previous != null && now - previous.firstAt < 10_000) previous.attempts + 1 else 1, generation)
            while (opaqueAttempts.size > 8) opaqueAttempts.remove(opaqueAttempts.keys.first())
        }
        if (!matchesOpaqueSnapshot(request, snapshot, generation)) return null
        status("visual_read")
        val bitmap = if (visualCapture != null) visualCapture!!.invoke(snapshot) else captureBitmap(windowId)
        if (bitmap == null) { status("visual_unavailable"); return null }
        try {
            val lines = opaqueVisualOcr(bitmap)
            diagnostics = diagnostics + WechatOpaqueTransferReader.diagnostics(lines, bitmap.width, bitmap.height)
            if (request.className == WechatReceivedTransferReader.ACTIVITY) {
                diagnostics = diagnostics + WechatReceivedTransferReader.diagnostics(lines)
            }
            if (!matchesOpaqueSnapshot(request, snapshot, generation) ||
                !entryPoint.appPreferences().screenVisualCaptureEnabled.first()) {
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
                    ?: if (redPacketFlow.isPreparing(elapsedMillis()))
                        WechatRedPacketReader.parsePaymentResult(lines, bitmap.width, bitmap.height)?.takeIf {
                            redPacketFlow.confirm(it.amountCents, elapsedMillis()) != null
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
            synchronized(opaqueAttempts) { opaqueAttempts[windowId]?.let {
                opaqueAttempts[windowId] = it.copy(parsed = parsed)
            } }
            return parsed
        } finally { bitmap.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun matchesOpaqueSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation || latestWindow?.className != request.className) return false
        val root = activeRoot() ?: return false
        return try {
            root.packageName?.toString() == request.packageName && root.windowId == request.windowId &&
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
    private fun matchesSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation) return false
        val root = activeRoot() ?: return false
        return try {
            root.packageName?.toString() == request.packageName &&
                (request.windowId < 0 || root.windowId < 0 || root.windowId == request.windowId) &&
                PaymentScreenReader.inspect(root, request.packageName).let { it.text != null && it.text == snapshot.text }
        } finally { root.recycle() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureVisual(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long): Bitmap? {
        if (!matchesSnapshot(request, snapshot, generation)) return null
        val whole = captureBitmap() ?: run {
            entryPoint.monitor().onScreenStatus("visual_unavailable", request.packageName)
            return null
        }
        return try {
            if (!matchesSnapshot(request, snapshot, generation)) return null
            val region = PaymentVisualRegion.bounds(snapshot.structure, whole.width, whole.height) ?: return null
            Bitmap.createBitmap(whole, region.left, region.top, region.width(), region.height())
                .let { if (it === whole) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        } finally { whole.recycle() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureBitmap(windowId: Int? = null): Bitmap? {
        val whole = withTimeoutOrNull(2_500) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
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
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                if (windowId != null && Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(windowId, mainExecutor, callback)
                else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }
        return whole
    }

    // ADB diagnostics expose states/counters only, never screen/OCR text, images, amounts or names.
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val health = entryPoint.monitor().health.value
        writer.println("screenCaptureStatus=${health.screenCaptureStatus}")
        writer.println("screenReadDiagnostics=${health.screenReadDiagnostics}")
        writer.println("todayCaptureCount=${health.todayCaptureCount}")
        health.screenReadHistory.forEach { writer.println("trace=${it.timestamp} ${it.status} ${it.diagnostics}") }
    }

    // 本服务没有语音/触觉反馈；onInterrupt 不代表系统已撤销授权。
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        enabled = false
        visualGeneration.incrementAndGet()
        latestWindow = null
        readJob?.cancel()
        preferenceJob?.cancel()
        entryPoint.monitor().onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        enabled = false
        visualGeneration.incrementAndGet()
        latestWindow = null
        scope.cancel()
        requests.close()
        entryPoint.monitor().onAccessibilityDisconnected()
        super.onDestroy()
    }
}

package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.pennypilot.app.data.parser.PaymentSceneAdapters
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicLong
import java.io.FileDescriptor
import java.io.PrintWriter
import java.security.MessageDigest

/** 可选的本机支付结果读取；系统授权及应用开关均满足后才读取指定应用。 */
class PaymentAccessibilityService : AccessibilityService() {
    private companion object {
        const val MAX_RECEIPT_IMAGE_ATTEMPTS = 3
        const val IMAGE_WINDOW_MS = 60_000L
        const val WECHAT = "com.tencent.mm"
        const val WECHAT_RESULT_CONTAINER = "com.tencent.mm.framework.app.UIPageFragmentActivity"
        val walletPreparationActivities = setOf(
            "com.tencent.mm.plugin.remittance.ui.RemittanceUI",
            "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI")
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
    private var walletPreparationAt: Long? = null
    private var opaqueHandoffWindow: Int? = null
    // 保留最新窗口；同一窗口重绘不取消已有采样，避免动画密集时一直延后。
    private val requests = Channel<ReadRequest>(Channel.CONFLATED)
    private var readJob: Job? = null
    private var preferenceJob: Job? = null
    private data class ReceiptImage(val data: String, val authorizationId: String)
    private data class ImageAttempt(val firstAt: Long, val lastAt: Long, val failures: Int = 0)
    private val imageAttempts = linkedMapOf<String, ImageAttempt>()
    private val submittedImages = linkedMapOf<String, MutableSet<String>>()
    private val receiptImageAttempts = linkedMapOf<String, Int>()
    private val visualGeneration = AtomicLong()
    private val visualSampleSequence = AtomicLong()
    @Volatile private var enabled = false
    @Volatile private var selectedPackages: Set<String> = emptySet()
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
    internal var receiptImageHandler: (suspend (String, String, String, Long, Long, Boolean) -> Boolean)? = null
    private val entryPoint get() = dependencies ?: hiltEntryPoint

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.monitor().onAccessibilityConnected()
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            val preferences = entryPoint.appPreferences()
            combine(preferences.screenCaptureEnabled, preferences.captureAppPackages) { enabled, packages -> enabled to packages }
                .distinctUntilChanged().collect { (allow, packages) ->
                selectedPackages = packages.intersect(PaymentAppRegistry.packages)
                serviceInfo?.let { info ->
                    info.packageNames = selectedPackages.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf(packageName)
                    serviceInfo = info
                }
                enabled = allow && selectedPackages.isNotEmpty()
                readJob?.cancel()
                replaceWindow(null)
                visualGeneration.incrementAndGet()
                while (requests.tryReceive().isSuccess) { /* 开关切换前的事件不能在恢复后补读。 */ }
                synchronized(session) { session.reset() }
                clearCheckoutContext()
                synchronized(imageAttempts) { imageAttempts.clear(); submittedImages.clear() }
                synchronized(receiptImageAttempts) { receiptImageAttempts.clear() }
                walletPreparationAt = null
                opaqueHandoffWindow = null
                entryPoint.monitor().onScreenStatus(if (enabled) "waiting" else "disabled")
                if (enabled) readJob = scope.launch(readDispatcher) {
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
                                // needs its own bounded retries, including delayed image availability.
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
        if (packageName !in selectedPackages) {
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                walletPreparationAt = null
                opaqueHandoffWindow = null
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName == WECHAT && eventClass in walletPreparationActivities) {
                walletPreparationAt = elapsedMillis()
                opaqueHandoffWindow = null
                synchronized(session) { session.onInputPage(packageName, event.windowId, elapsedMillis()) }
            } else if (packageName == WECHAT && eventClass == WECHAT_RESULT_CONTAINER &&
                walletPreparationAt?.let { elapsedMillis() - it in 0..120_000 } == true) {
                opaqueHandoffWindow = event.windowId
            } else {
                walletPreparationAt = null
                opaqueHandoffWindow = null
            }
        }
        // Content redraws often name a View instead of the Activity. Retain only the Activity
        // established by a window-state event for this exact window, never across navigation.
        val pageClass = if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            previous?.let { it.packageName == packageName && it.windowId == event.windowId } == true)
            previous.className else eventClass
        val revision = LedgerRevision.current
        val epoch = previous?.takeIf { it.packageName == packageName && it.windowId == event.windowId &&
            it.revision == revision && it.className == pageClass }?.epoch ?: windowEpoch.incrementAndGet()
        val request = ReadRequest(packageName, event.windowId, revision, epoch, event.eventType, pageClass)
        replaceWindow(request)
        entryPoint.monitor().onScreenEvent(packageName)
        requests.trySend(request)
    }

    private fun isCurrent(request: ReadRequest) = enabled && request.packageName in selectedPackages && request.revision == LedgerRevision.current &&
        latestWindow?.epoch == request.epoch

    private fun replaceWindow(request: ReadRequest?) {
        val superseded = synchronized(readStateLock) {
            latestWindow?.takeIf { request == null || it.packageName != request.packageName ||
                it.windowId != request.windowId || it.className != request.className }?.let { previous ->
                synchronized(session) { session.observe(previous.packageName, previous.windowId, null, elapsedMillis()) }
            }
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
                    if (!enabled || latestWindow != null || packageName !in selectedPackages) return
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
            val sampleVersion = visualSampleSequence.incrementAndGet()
            val textAuthorization = entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = false)
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
                snapshot = PaymentScreenReader.inspect(root, request.packageName, request.className)
            } finally { root.recycle() }
            if (!isCurrent(request)) return true
            val text = snapshot.text
            if (text == null) {
                val opaque = acceptsOpaque(request, snapshot)
                CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                    request.eventType, request.className, opaque)
                if (opaque) {
                    queueOpaqueReceipt(request, snapshot, actualWindowId, sampleVersion)
                    // A loading wallet can paint the amount without another accessibility event.
                    return false
                }
                if (snapshot.hasInput) synchronized(session) {
                    session.onInputPage(request.packageName, actualWindowId, elapsedMillis())
                }
                entryPoint.monitor().onScreenRead(snapshot.status, snapshot.diagnostics, request.packageName)
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
                // The order is not yet paid. No ledger entry or cloud task can come from it.
                entryPoint.monitor().onScreenRead("not_receipt", snapshot.diagnostics, request.packageName)
                return true
            }
            val now = nowMillis()
            val elapsed = elapsedMillis()
            val adapter = PaymentSceneAdapters.match(request.packageName, text, request.className, snapshot.structure)
            val parsedByAdapter = PaymentSceneAdapters.parse(request.packageName, text, now,
                snapshot.structure, request.className)
            val recognition = parsedByAdapter?.let { PaymentTextParser.ScreenRecognition(it, it.scene ?: "receipt",
                modelCandidate = false, evidenceText = text) }
                ?: if (PaymentSceneAdapters.isDetailPage(request.packageName, text))
                    PaymentTextParser.ScreenRecognition(null, "receipt_detail", modelCandidate = false, evidenceText = text)
                else PaymentTextParser.analyzeScreen(request.packageName, text, snapshot.structure)
            val parsed = recognition.parsed
            val imageCandidate = parsed != null || adapter?.allowsCloudImage == true
            CaptureDiagnosticStore.record(request.packageName, snapshot, actualWindowId,
                request.eventType, request.className, imageCandidate)
            entryPoint.monitor().onScreenRead(if (parsed != null) "parsed" else "not_receipt",
                snapshot.diagnostics, request.packageName)
            if (parsed == null) {
                if (imageCandidate && cloudVisionAllowed()) {
                    val key = synchronized(session) {
                        session.observeCandidate(request.packageName, actualWindowId, text, elapsed, sampleVersion)
                    } ?: return true
                    if (queueImageCandidate(request, snapshot, actualWindowId, key, now)) {
                        synchronized(session) { session.markHandled(key) }
                        return false
                    }
                } else if (recognition.modelCandidate) {
                    val key = synchronized(session) {
                        session.observeCandidate(request.packageName, actualWindowId, recognition.evidenceText, elapsed)
                    }
                    val handled = if (key == null) false else unknownScreenHandler?.invoke(
                        request.packageName, recognition.evidenceText, key, now, request.revision,
                        snapshot.structure?.toJson(), emptyList()) ?: entryPoint.captureProcessor().processUnrecognizedScreen(
                        request.packageName, recognition.evidenceText, key, now, request.revision,
                        snapshot.structure?.toJson(), expectedAuthorizationId = textAuthorization)
                    if (key != null && handled) synchronized(session) { session.markHandled(key) }
                } else if (!imageCandidate) {
                    synchronized(session) { session.observe(request.packageName, actualWindowId, null, elapsed) }
                } else entryPoint.monitor().onScreenStatus("visual_required", request.packageName)
                return false
            }
            val withCheckout = synchronized(checkoutContext) {
                checkoutContext.enrich(request.packageName, snapshot.structure, parsed, request.revision, elapsed)
            }
            val cloudEnrichment: (suspend (String) -> Boolean)? = if (cloudVisionAllowed() &&
                (Build.VERSION.SDK_INT >= 34 || displayWindowUnoccluded(request, snapshot.windowBounds))) { key ->
                captureCloudReceipt(request, snapshot, actualWindowId)?.let {
                    enqueueReceiptImage(request, it, key, now, allowImageOnly = false)
                } == true
            } else null
            if (!isCurrent(request)) return true
            // Persist node facts first. Image enrichment retains the same event and cannot delay a valid local entry.
            persistScreen(request, actualWindowId, withCheckout, now, elapsed,
                expectedAuthorizationId = textAuthorization, cloudEnrichment = cloudEnrichment)
            // Late order IDs and receipt fields can still enrich the saved evidence.
            return false
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("error", request.packageName)
            entryPoint.monitor().onError("支付页面读取未完成；可检查授权或手动补记")
            return false
        }
    }

    private suspend fun cloudVisionAllowed(): Boolean =
        Build.VERSION.SDK_INT >= 30 && entryPoint.appPreferences().screenCloudVisionEnabled.first()

    private suspend fun captureCloudReceipt(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int): ReceiptImage? {
        if (windowId < 0 || (snapshot.diagnostics["foreignNodes"] ?: 0) > 0 || !cloudVisionAllowed()) return null
        if (Build.VERSION.SDK_INT < 34 && !displayWindowUnoccluded(request, snapshot.windowBounds)) return null
        val authorization = entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = true)
        val generation = visualGeneration.get()
        val bitmap = visualCapture?.invoke(snapshot) ?: if (visualCapture == null)
            captureVisual(request, snapshot, generation, windowId, includePanel = true) else null
        bitmap ?: return null
        return try {
            if (!matchesSnapshot(request, snapshot, generation, windowId)) return null
            val image = PaymentImageReader.dataUrl(bitmap)
            ReceiptImage(image, authorization).takeIf { matchesSnapshot(request, snapshot, generation, windowId) && cloudVisionAllowed() &&
                authorization == entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = true) &&
                (Build.VERSION.SDK_INT >= 34 || displayWindowUnoccluded(request, snapshot.windowBounds)) }
        } catch (_: IllegalArgumentException) {
            entryPoint.monitor().onScreenRead("visual_unavailable", snapshot.diagnostics, request.packageName)
            null
        } finally { bitmap.recycle() }
    }

    private fun reserveImageAttempt(key: String): Boolean = synchronized(imageAttempts) {
        val now = elapsedMillis()
        val previous = imageAttempts[key]
        if (submittedImages[key].orEmpty().size >= MAX_RECEIPT_IMAGE_ATTEMPTS || previous != null &&
            (previous.failures >= MAX_RECEIPT_IMAGE_ATTEMPTS || now - previous.firstAt !in 0..IMAGE_WINDOW_MS ||
                now - previous.lastAt < 750)) false
        else {
            imageAttempts[key] = ImageAttempt(previous?.firstAt ?: now, now, previous?.failures ?: 0)
            while (imageAttempts.size > 16) {
                val oldest = imageAttempts.keys.first()
                imageAttempts.remove(oldest)
                submittedImages.remove(oldest)
            }
            true
        }
    }

    private fun failedImageAttempt(key: String) = synchronized(imageAttempts) {
        imageAttempts[key]?.let { imageAttempts[key] = it.copy(failures = it.failures + 1) }
    }

    private suspend fun submitCandidateImage(request: ReadRequest, image: ReceiptImage, key: String, observedAt: Long): Boolean {
        val fingerprint = image.authorizationId + ":" + MessageDigest.getInstance("SHA-256")
            .digest(image.data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        // Redraws of the same loading frame are processed locally and cannot consume another model request.
        if (synchronized(imageAttempts) { fingerprint in submittedImages[key].orEmpty() }) return true
        val queued = enqueueReceiptImage(request, image, key, observedAt, allowImageOnly = true)
        if (queued) {
            synchronized(imageAttempts) { submittedImages.getOrPut(key) { linkedSetOf() }.add(fingerprint) }
            entryPoint.monitor().onScreenStatus("model_queued", request.packageName)
        } else failedImageAttempt(key)
        return queued
    }

    private suspend fun queueImageCandidate(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int, key: String, observedAt: Long): Boolean {
        if (!cloudVisionAllowed() || Build.VERSION.SDK_INT < 34 && !displayWindowUnoccluded(request, snapshot.windowBounds)) return false
        if (!reserveImageAttempt(key)) return false
        val image = captureCloudReceipt(request, snapshot, windowId)
        if (image == null) { failedImageAttempt(key); return false }
        return submitCandidateImage(request, image, key, observedAt)
    }

    private suspend fun enqueueReceiptImage(request: ReadRequest, image: ReceiptImage, key: String,
        observedAt: Long, allowImageOnly: Boolean): Boolean {
        if (!isCurrent(request) || !cloudVisionAllowed() || image.authorizationId !=
            entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = true)) return false
        return receiptImageHandler?.invoke(request.packageName, image.data, key, observedAt, request.revision, allowImageOnly)
            ?: entryPoint.captureProcessor().processReceiptImage(
                request.packageName, image.data, key, observedAt, request.revision, allowImageOnly,
                expectedAuthorizationId = image.authorizationId)
    }

    private suspend fun persistScreen(request: ReadRequest, windowId: Int, parsed: NotificationParseResult,
        now: Long, elapsed: Long, expectedAuthorizationId: String,
        cloudEnrichment: (suspend (String) -> Boolean)? = null): Boolean {
        val key = synchronized(session) { session.observe(request.packageName, windowId, parsed, elapsed) }
        if (key == null && cloudEnrichment == null) {
            entryPoint.monitor().onScreenStatus("duplicate", request.packageName)
            return true
        }
        if (key != null) {
            val evidence = synchronized(session) { session.evidence(key) } ?: parsed
            CaptureLatencyTrace.record(this, key, CaptureLatencyTrace.Source.ACCESSIBILITY, CaptureLatencyTrace.Stage.RECEIVED, now)
            if (!entryPoint.captureProcessor().processScreen(request.packageName, evidence, key, now, request.revision,
                    queueSemanticModel = cloudEnrichment == null, expectedAuthorizationId = expectedAuthorizationId)) {
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

    private suspend fun queueOpaqueReceipt(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int, sampleVersion: Long): Boolean {
        if (!cloudVisionAllowed()) {
            entryPoint.monitor().onScreenRead("visual_required", snapshot.diagnostics, request.packageName)
            return false
        }
        val key = synchronized(session) {
            session.observeCandidate(request.packageName, windowId, "opaque:${request.className}", elapsedMillis(), sampleVersion)
        } ?: return true
        val authorization = entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = true)
        val generation = visualGeneration.get()
        if (!matchesOpaqueSnapshot(request, snapshot, generation)) return false
        if (Build.VERSION.SDK_INT < 34 && !displayWindowUnoccluded(request, snapshot.windowBounds)) return false
        if (!reserveImageAttempt(key)) return false
        val diagnostics = mutableMapOf<String, Int>()
        val bitmap = visualCapture?.invoke(snapshot) ?: if (visualCapture == null)
            captureBitmap(windowId.takeIf { Build.VERSION.SDK_INT >= 34 }, diagnostics) else null
        if (bitmap == null) {
            failedImageAttempt(key)
            entryPoint.monitor().onScreenRead("visual_unavailable", snapshot.diagnostics + diagnostics, request.packageName)
            return false
        }
        val image = try {
            if (!matchesOpaqueSnapshot(request, snapshot, generation) || !cloudVisionAllowed() ||
                Build.VERSION.SDK_INT < 34 && !displayWindowUnoccluded(request, snapshot.windowBounds)) return false
            // Opaque dedicated wallet windows have no text coordinates. Crop to their window bounds
            // on Android 11–13; Android 14+ supplies that window directly, excluding other windows.
            val region = PaymentVisualRegion.windowBounds(snapshot.windowBounds, bitmap.width, bitmap.height,
                Build.VERSION.SDK_INT >= 34) ?: return false
            val receipt = Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
            try { PaymentImageReader.dataUrl(receipt) }
            finally { if (receipt !== bitmap) receipt.recycle() }
        } finally { bitmap.recycle() }
        if (!matchesOpaqueSnapshot(request, snapshot, generation) ||
            authorization != entryPoint.appPreferences().captureAuthorizationId(request.packageName, page = true, image = true)) return false
        val queued = submitCandidateImage(request, ReceiptImage(image, authorization), key, nowMillis())
        if (queued) synchronized(session) { session.markHandled(key) }
        return queued
    }

    @Suppress("DEPRECATION")
    private fun matchesOpaqueSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation || latestWindow?.className != request.className) return false
        val root = activeRoot(request.packageName, request.windowId) ?: return false
        return try {
            root.windowId == request.windowId && PaymentScreenReader.inspect(root, request.packageName, request.className).let {
                it.diagnostics == snapshot.diagnostics && it.windowBounds == snapshot.windowBounds && acceptsOpaque(request, it)
            }
        } finally { root.recycle() }
    }

    private fun acceptsOpaque(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot): Boolean {
        if (!snapshot.isBoundedOpaqueTree) return false
        val dedicated = PaymentSceneAdapters.match(request.packageName, "", request.className)
        if (dedicated?.allowsCloudImage == true) return true
        return request.packageName == WECHAT && request.className == WECHAT_RESULT_CONTAINER &&
            opaqueHandoffWindow == request.windowId && walletPreparationAt?.let { elapsedMillis() - it in 0..120_000 } == true
    }

    @Suppress("DEPRECATION")
    private fun matchesSnapshot(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long,
        windowId: Int? = null): Boolean {
        if (!isCurrent(request) || visualGeneration.get() != generation) return false
        if (windowId != null && request.windowId >= 0 && windowId != request.windowId) return false
        val root = activeRoot(request.packageName, windowId ?: request.windowId) ?: return false
        return try {
            (windowId == null || root.windowId == windowId) &&
                PaymentScreenReader.inspect(root, request.packageName, request.className).let {
                    it.text != null && !it.hasInput && it.text == snapshot.text && it.structure == snapshot.structure
                }
        } finally { root.recycle() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun captureVisual(request: ReadRequest, snapshot: PaymentScreenReader.Snapshot, generation: Long,
        windowId: Int? = null, includePanel: Boolean = false,
        diagnostics: MutableMap<String, Int> = mutableMapOf()): Bitmap? {
        if (!matchesSnapshot(request, snapshot, generation, windowId)) return null
        val targetWindow = (windowId ?: request.windowId.takeIf { it >= 0 }).takeIf { Build.VERSION.SDK_INT >= 34 }
        if (targetWindow == null && !displayWindowUnoccluded(request, snapshot.windowBounds)) return null
        val whole = captureBitmap(targetWindow, diagnostics) ?: run {
            entryPoint.monitor().onScreenRead("visual_unavailable", snapshot.diagnostics + diagnostics, request.packageName)
            return null
        }
        return try {
            if (!matchesSnapshot(request, snapshot, generation, windowId) ||
                targetWindow == null && !displayWindowUnoccluded(request, snapshot.windowBounds)) return null
            val walletImage = PaymentSceneAdapters.match(request.packageName, snapshot.text.orEmpty(), request.className,
                snapshot.structure)?.kind == PaymentSceneAdapters.Kind.OPAQUE_RECEIPT
            val region = (if (walletImage) PaymentVisualRegion.windowBounds(snapshot.windowBounds, whole.width, whole.height,
                targetWindow != null) else PaymentVisualRegion.bounds(snapshot.structure, whole.width, whole.height,
                includePanel = includePanel, windowRelative = targetWindow != null, windowBounds = snapshot.windowBounds,
                receiptBounds = snapshot.receiptBounds)) ?: run {
                diagnostics["visualRegionUnavailable"] = 1
                return null
            }
            Bitmap.createBitmap(whole, region.left, region.top, region.width(), region.height())
                .let { if (it === whole) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        } finally { whole.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun displayWindowUnoccluded(request: ReadRequest, bounds: List<Int>): Boolean {
        if (bounds.size != 4) return false
        val area = Rect(bounds[0], bounds[1], bounds[2], bounds[3])
        if (area.isEmpty) return false
        val visibleWindows = windows
        return try {
            val target = visibleWindows.singleOrNull { it.id == request.windowId && it.isActive &&
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
            visibleWindows.none { other ->
                if (other.id == target.id || other.layer <= target.layer) false else {
                    val overlay = Rect().also(other::getBoundsInScreen)
                    // An unknown overlay extent cannot prove that the receipt pixels are unoccluded.
                    overlay.isEmpty || Rect.intersects(area, overlay)
                }
            }
        } finally { visibleWindows.forEach { it.recycle() } }
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
                        // A window change can cancel between callback delivery and resuming image processing.
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

    // ADB diagnostics expose states/counters only, never screen text, images, amounts or names.
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

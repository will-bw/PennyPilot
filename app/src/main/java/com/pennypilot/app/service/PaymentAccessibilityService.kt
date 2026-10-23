package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentTextParser
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 可选的本机支付结果读取；系统授权及应用开关均满足后才读取指定应用。 */
class PaymentAccessibilityService : AccessibilityService() {
    private data class ReadRequest(val packageName: String, val windowId: Int, val revision: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = PaymentScreenSession()
    // 保留最新窗口；同一窗口重绘不取消已有采样，避免动画密集时一直延后。
    private val requests = Channel<ReadRequest>(Channel.CONFLATED)
    private var readJob: Job? = null
    private var preferenceJob: Job? = null
    @Volatile private var enabled = false
    @Volatile private var latestWindow: ReadRequest? = null
    private val hiltEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }
    // 仅供本机服务集成测试替换依赖/调度器，生产始终使用 Hilt 和系统节点。
    internal var dependencies: CaptorServiceEntryPoint? = null
    internal var readDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var nowMillis: () -> Long = System::currentTimeMillis
    private val entryPoint get() = dependencies ?: hiltEntryPoint

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.monitor().onAccessibilityConnected()
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            entryPoint.appPreferences().screenCaptureEnabled.collect { allow ->
                enabled = allow
                readJob?.cancel()
                latestWindow = null
                while (requests.tryReceive().isSuccess) { /* 开关切换前的事件不能在恢复后补读。 */ }
                synchronized(session) { session.reset() }
                entryPoint.monitor().onScreenStatus(if (allow) "waiting" else "disabled")
                if (allow) readJob = scope.launch(readDispatcher) {
                    // 重连/打开开关时页面可能已稳定，不会再发一个内容变化事件。
                    requestCurrentWindow()
                    for (request in requests) {
                        var newest = requests.tryReceive().getOrNull() ?: request
                        // 支付结果可能在 window 事件后才加载；短时有界重试不依赖通知或二次事件。
                        for (pause in longArrayOf(0, 250, 750, 1_500)) {
                            // 新页面事件应立即唤醒采样，不能被上一页的重试等待挡住。
                            if (pause > 0) newest = withTimeoutOrNull(pause) {
                                requests.receive()
                            } ?: newest
                            if (!isCurrent(newest) || readScreen(newest)) break
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
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) latestWindow?.let { requests.trySend(it) }
            return
        }
        if (packageName !in PaymentAppRegistry.packages) return
        val request = ReadRequest(packageName, event.windowId, LedgerRevision.current)
        latestWindow = request
        entryPoint.monitor().onScreenEvent(packageName)
        requests.trySend(request)
    }

    private fun isCurrent(request: ReadRequest) =
        enabled && latestWindow == request && request.revision == LedgerRevision.current

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
    private suspend fun readScreen(request: ReadRequest): Boolean {
        if (!isCurrent(request)) return true
        try {
            val root = activeRoot()
            if (root == null) {
                entryPoint.monitor().onScreenRead("unreadable")
                return false
            }
            val snapshot: PaymentScreenReader.Snapshot
            val actualWindowId: Int
            try {
                actualWindowId = root.windowId
                // 部分事件的 windowId 未定义；已给出 id 时必须匹配，不能误读旧窗口。
                if (root.packageName?.toString() != request.packageName ||
                    (request.windowId >= 0 && actualWindowId >= 0 && actualWindowId != request.windowId)) {
                    entryPoint.monitor().onScreenRead("unreadable")
                    return false
                }
                snapshot = PaymentScreenReader.inspect(root, request.packageName)
            } finally { root.recycle() }
            if (!isCurrent(request)) return true
            val text = snapshot.text
            if (text == null) {
                // 明确输入页代表离开凭证；节点缺失/树未加载只算暂不可读，不能重置去重身份。
                if (snapshot.hasInput) synchronized(session) {
                    session.observe(request.packageName, actualWindowId, null, nowMillis())
                }
                entryPoint.monitor().onScreenRead("protected")
                return false
            }
            val parsed = PaymentTextParser.parseScreen(request.packageName, text)
            entryPoint.monitor().onScreenRead(if (parsed == null) "not_receipt" else "parsed")
            val now = nowMillis()
            val key = synchronized(session) { session.observe(request.packageName, actualWindowId, parsed, now) }
            if (parsed == null) return false
            if (key == null) {
                entryPoint.monitor().onScreenStatus("duplicate")
                return true
            }
            if (entryPoint.captureProcessor().processScreen(request.packageName, parsed, key, now, request.revision)) {
                synchronized(session) { session.markHandled(key) }
                return true
            }
            entryPoint.monitor().onScreenStatus("error")
            return false
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            entryPoint.monitor().onScreenStatus("error")
            entryPoint.monitor().onError("支付页面读取未完成；可检查授权或手动补记")
            return false
        }
    }

    // 本服务没有语音/触觉反馈；onInterrupt 不代表系统已撤销授权。
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        enabled = false
        latestWindow = null
        readJob?.cancel()
        preferenceJob?.cancel()
        entryPoint.monitor().onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        enabled = false
        latestWindow = null
        requests.close()
        scope.cancel()
        entryPoint.monitor().onAccessibilityDisconnected()
        super.onDestroy()
    }
}

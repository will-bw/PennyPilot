package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentTextParser
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 可选的本机支付结果读取；系统授权及应用开关均满足后才读取指定应用。 */
class PaymentAccessibilityService : AccessibilityService() {
    private data class ReadRequest(val packageName: String, val windowId: Int, val revision: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = PaymentScreenSession()
    // 只需最终可见窗口，缓冲有界；当前写入期间的新事件仍会触发下一次读取。
    private val requests = Channel<ReadRequest>(Channel.CONFLATED)
    private var readJob: Job? = null
    private var preferenceJob: Job? = null
    @Volatile private var enabled = false
    @Volatile private var latestWindow: ReadRequest? = null
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        entryPoint.monitor().onAccessibilityConnected()
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            entryPoint.appPreferences().screenCaptureEnabled.collect { allow ->
                enabled = allow
                readJob?.cancel()
                while (requests.tryReceive().isSuccess) { /* 暂停前的事件不能在恢复后补读。 */ }
                synchronized(session) { session.reset() }
                if (allow) readJob = scope.launch(Dispatchers.IO) {
                    for (request in requests) {
                        delay(350)
                        // 合并这一小段动画重绘；后续新事件留在队列，不会因当前任务繁忙而丢失。
                        val newest = requests.tryReceive().getOrNull() ?: request
                        readScreen(newest)
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try { entryPoint.captureProcessor().refreshTodayCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { entryPoint.monitor().onError("捕获统计暂时不可用，请稍后重试") }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!enabled || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in PaymentAppRegistry.packages) return
        val request = ReadRequest(packageName, event.windowId, LedgerRevision.current)
        latestWindow = request
        requests.trySend(request)
    }

    @Suppress("DEPRECATION")
    private suspend fun readScreen(request: ReadRequest) {
        if (!enabled || request.revision != LedgerRevision.current) return
        try {
            val text = rootInActiveWindow?.let { root ->
                try {
                    if (root.packageName?.toString() != request.packageName || root.windowId != request.windowId) null
                    else PaymentScreenReader.read(root, request.packageName)
                } finally { root.recycle() }
            } ?: return
            if (!enabled || latestWindow != request || request.revision != LedgerRevision.current) return
            val parsed = PaymentTextParser.parseScreen(request.packageName, text)
            val now = System.currentTimeMillis()
            val key = synchronized(session) { session.observe(request.packageName, request.windowId, parsed, now) }
                ?: return
            if (parsed != null && entryPoint.captureProcessor().processScreen(request.packageName, parsed, key, now, request.revision)) {
                synchronized(session) { session.markHandled(key) }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            entryPoint.monitor().onError("支付页面读取未完成；可检查授权或手动补记")
        }
    }

    // 本服务没有语音/触觉反馈；onInterrupt 不代表系统已撤销授权。
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        enabled = false
        readJob?.cancel()
        preferenceJob?.cancel()
        entryPoint.monitor().onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        enabled = false
        requests.close()
        scope.cancel()
        entryPoint.monitor().onAccessibilityDisconnected()
        super.onDestroy()
    }
}

package com.pennypilot.app.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.settings.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptorServiceEntryPoint {
    fun monitor(): CaptureServiceMonitor
    fun appPreferences(): AppPreferences
    fun captureProcessor(): CaptureProcessor
}

class NotificationCaptorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Work(val notification: CapturedNotification, val revision: Long, val generation: Long, val key: String)
    private val queue = Channel<Work>(64)
    private val queuedEvents = mutableSetOf<String>()
    @Volatile private var captureEnabled = true
    @Volatile private var captureGeneration = 0L
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            entryPoint.appPreferences().notificationCaptureEnabled.collect { enabled ->
                if (captureEnabled != enabled) captureGeneration++
                captureEnabled = enabled
            }
        }
        scope.launch {
            for (work in queue) {
                try {
                    if (captureEnabled && captureGeneration == work.generation) {
                        entryPoint.captureProcessor().processNotification(work.notification, work.revision) {
                            captureEnabled && captureGeneration == work.generation
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    entryPoint.monitor().onError("自动捕获未完成，请检查设置并核对最近交易")
                } finally {
                    synchronized(queuedEvents) { queuedEvents.remove(work.key) }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        entryPoint.monitor().onServiceConnected()
        scope.launch {
            try { entryPoint.captureProcessor().refreshTodayCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { entryPoint.monitor().onError("捕获统计暂时不可用，请稍后重试") }
        }
        // 只补查仍在通知栏中的近期支付通知；不能恢复已撤回或从未发布的通知。
        val now = System.currentTimeMillis()
        runCatching { activeNotifications }.onSuccess { active ->
            active.orEmpty().filter { NotificationContentExtractor.shouldRecover(it.postTime, now) }
                .sortedBy { it.postTime }.forEach(::enqueue)
        }.onFailure { entryPoint.monitor().onError("近期通知补查未完成，请尝试重新连接") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let(::enqueue) }

    private fun enqueue(sbn: StatusBarNotification) {
        if (!captureEnabled || sbn.packageName !in PaymentAppRegistry.packages) return
        val input = runCatching { NotificationContentExtractor.extract(sbn) }.getOrNull() ?: return
        val key = CaptureEventIdentity.key(input.packageName, input.notificationKey, input.postedAt,
            "${input.title}\n${input.content}")
        if (!synchronized(queuedEvents) { queuedEvents.add(key) }) return
        if (!queue.trySend(Work(input, LedgerRevision.current, captureGeneration, key)).isSuccess) {
            synchronized(queuedEvents) { queuedEvents.remove(key) }
            entryPoint.monitor().onError("通知过于密集，部分通知未处理；请核对最近交易")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        entryPoint.monitor().onServiceDisconnected()
        runCatching { requestRebind(ComponentName(this, NotificationCaptorService::class.java)) }
            .onFailure { entryPoint.monitor().onError("通知连接已断开，请在设置中重新连接") }
    }

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        entryPoint.monitor().onServiceDisconnected()
        super.onDestroy()
    }
}

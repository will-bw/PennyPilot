package com.pennypilot.app.service

import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.data.settings.NotificationCapturePolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.PrintWriter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptorServiceEntryPoint {
    fun monitor(): CaptureServiceMonitor
    fun appPreferences(): AppPreferences
    fun captureProcessor(): CaptureProcessor
}

class NotificationCaptorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Work(val notification: CapturedNotification, val revision: Long,
        val key: String, val receivedAt: Long, val liveArrival: CheckoutContext.LiveNotification?)
    private class CaptureSession(val recoverAfter: Long) {
        val live = Channel<Work>(64)
        val queuedEvents = mutableSetOf<String>()
    }
    // The framework callbacks and the preference collector transition on the main thread.
    private var connected = false
    private var policy: NotificationCapturePolicy? = null
    @Volatile private var session: CaptureSession? = null
    private val hiltEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }
    internal var dependencies: CaptorServiceEntryPoint? = null
    internal var readDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val entryPoint get() = dependencies ?: hiltEntryPoint

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            entryPoint.appPreferences().notificationCapturePolicy.distinctUntilChanged().collect { settings ->
                policy = settings
                refreshSession()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        entryPoint.monitor().onServiceConnected()
        scope.launch(readDispatcher) {
            CaptureLatencyTrace.record(this@NotificationCaptorService, "notification-listener", CaptureLatencyTrace.Source.SERVICE, CaptureLatencyTrace.Stage.CONNECTED)
            com.pennypilot.app.data.recognition.RecognitionWorker.recover(this@NotificationCaptorService)
            try { entryPoint.captureProcessor().refreshTodayCount() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { entryPoint.monitor().onError("捕获统计暂时不可用，请稍后重试") }
        }
        refreshSession()
    }

    private fun refreshSession() {
        val settings = policy
        if (!connected || settings?.enabled != true) {
            stopSession()
            return
        }
        if (session?.recoverAfter == settings.recoverAfter) return
        stopSession()
        val current = CaptureSession(settings.recoverAfter)
        session = current
        val checkedAt = System.currentTimeMillis()
        val revision = LedgerRevision.current
        // Only recent, still-active wallet notices posted after the last re-enable can be recovered.
        val recovery = runCatching { activeNotifications }.getOrElse {
            entryPoint.monitor().onError("近期通知补查未完成，请尝试重新连接")
            null
        }.orEmpty().filter {
            it.packageName in PaymentAppRegistry.packages && it.postTime > current.recoverAfter &&
                NotificationContentExtractor.shouldRecover(it.postTime, checkedAt)
        }.sortedBy { it.postTime }.iterator()
        scope.launch(readDispatcher) {
            var backfill: Iterator<StatusBarNotification>? = recovery
            while (session === current) {
                // Reconnect may find many active notices. They must not fill the live queue or
                // make a newly posted payment wait until the entire recovery batch is processed.
                val pending = backfill
                val work = current.live.tryReceive().getOrNull()
                    ?: if (pending != null && LedgerRevision.current == revision && pending.hasNext()) {
                        prepare(current, pending.next(), revision, checkedAt, null) ?: continue
                    } else {
                        backfill = null
                        current.live.receiveCatching().getOrNull() ?: break
                    }
                try {
                    if (session !== current) break
                    CaptureLatencyTrace.record(this@NotificationCaptorService, work.key, CaptureLatencyTrace.Source.NOTIFICATION,
                        CaptureLatencyTrace.Stage.SOURCE_POSTED, work.notification.postedAt)
                    CaptureLatencyTrace.record(this@NotificationCaptorService, work.key, CaptureLatencyTrace.Source.NOTIFICATION,
                        CaptureLatencyTrace.Stage.RECEIVED, work.receivedAt)
                    entryPoint.captureProcessor().processNotification(work.notification, work.revision, work.liveArrival) {
                        session === current
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    if (session === current) entryPoint.monitor().onError("自动捕获未完成，请检查设置并核对最近交易")
                } finally {
                    synchronized(current.queuedEvents) { current.queuedEvents.remove(work.key) }
                }
            }
        }
    }

    private fun stopSession() {
        val previous = session
        session = null
        // Discard buffered events. The session check prevents new writes by the in-flight
        // worker without interrupting the alert of a payment that has already committed.
        previous?.live?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val current = session ?: return
        if (sbn == null || sbn.packageName !in PaymentAppRegistry.packages) return
        val receivedAt = System.currentTimeMillis()
        if (receivedAt >= current.recoverAfter && sbn.postTime < current.recoverAfter) return
        val arrival = CheckoutContext.LiveNotification(receivedAt, SystemClock.elapsedRealtime())
        val work = prepare(current, sbn, LedgerRevision.current, receivedAt, arrival) ?: return
        if (!current.live.trySend(work).isSuccess) {
            synchronized(current.queuedEvents) { current.queuedEvents.remove(work.key) }
            entryPoint.monitor().onError("通知过于密集，部分通知未处理；请核对最近交易")
        }
    }

    private fun prepare(current: CaptureSession, sbn: StatusBarNotification, revision: Long, receivedAt: Long,
        liveArrival: CheckoutContext.LiveNotification?): Work? {
        if (session !== current) return null
        val input = runCatching { NotificationContentExtractor.extract(sbn) }.getOrNull() ?: return null
        val key = CaptureEventIdentity.key(input.packageName, input.notificationKey, input.postedAt,
            "${input.title}\n${input.content}")
        if (!synchronized(current.queuedEvents) { current.queuedEvents.add(key) }) return null
        return Work(input, revision, key, receivedAt, liveArrival)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        stopSession()
        entryPoint.monitor().onServiceDisconnected()
        scope.launch(readDispatcher) {
            CaptureLatencyTrace.record(this@NotificationCaptorService, "notification-listener", CaptureLatencyTrace.Source.SERVICE, CaptureLatencyTrace.Stage.DISCONNECTED)
        }
        if (NotificationCapturePermission.isGranted(this)) {
            runCatching { requestRebind(ComponentName(this, NotificationCaptorService::class.java)) }
                .onFailure { entryPoint.monitor().onError("通知连接已断开，请在设置中重新连接") }
        }
    }

    override fun onDestroy() {
        connected = false
        stopSession()
        scope.cancel()
        entryPoint.monitor().onServiceDisconnected()
        super.onDestroy()
    }

    /** Technical state only; never expose original notifications or ledger content. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println("notificationListenerConnected=${entryPoint.monitor().health.value.isBound}")
        writer.println("captureRemindersEnabled=${NotificationHelper.captureNotificationsEnabled(this)}")
        writer.println("captureHeadsUpEnabled=${NotificationHelper.captureHeadsUpEnabled(this)}")
        runBlocking {
            withTimeoutOrNull(1_000) {
                val prefs = entryPoint.appPreferences()
                writer.println("notificationCaptureEnabled=${prefs.notificationCaptureEnabled.first()}")
                writer.println("notificationCloudParsingEnabled=${prefs.notificationCloudParsingEnabled.first()}")
                writer.println("screenCaptureEnabled=${prefs.screenCaptureEnabled.first()}")
                writer.println("screenCloudParsingEnabled=${prefs.screenCloudParsingEnabled.first()}")
                writer.println("screenVisualCaptureEnabled=${prefs.screenVisualCaptureEnabled.first()}")
            }
        }
        CaptureLatencyTrace.dumpTo(this, writer)
    }
}

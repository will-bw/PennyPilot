package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [CaptureNotificationListenerShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class NotificationCaptorServiceIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var context: Context
    private lateinit var preferences: AppPreferences
    private lateinit var database: AppDatabase
    private lateinit var processor: CaptureProcessor
    private lateinit var monitor: CaptureServiceMonitor
    private var controller: ServiceController<NotificationCaptorService>? = null
    private lateinit var listener: NotificationCaptorService
    private lateinit var shadow: CaptureNotificationListenerShadow

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context)
        preferences.setNotificationCloudParsingEnabled(false)
        val direct = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0,
            type = TxType.EXPENSE, sortOrder = 0))
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        monitor = CaptureServiceMonitor()
        val cloud = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String = error("local capture only")
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools =
                error("local capture only")
        }
        processor = CaptureProcessor(context, database, preferences, cloud, monitor)
    }

    @After fun tearDown() {
        controller?.destroy()
        scheduler.runCurrent()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `cold startup honors disabled capture and persisted re-enable time`() {
        runBlocking { preferences.setNotificationCaptureEnabled(false) }
        val whileDisabled = notice(1, System.currentTimeMillis() - 500)
        start(whileDisabled)
        listener.onNotificationPosted(whileDisabled)
        assertEquals(0, shadow.snapshotReads)

        controller?.destroy()
        scheduler.runCurrent()
        runBlocking { preferences.setNotificationCaptureEnabled(true) }
        start(whileDisabled)
        await { shadow.snapshotReads == 1 }
        // Some devices deliver queued callbacks after reconnect. The old post remains ineligible.
        listener.onNotificationPosted(whileDisabled)
        listener.onNotificationPosted(notice(2))
        await { transactions().isNotEmpty() }
        assertEquals(listOf(2_00L), transactions().map { it.amount })
    }

    @Test fun `disconnect discards queued work and live posts run before recent recovery`() {
        runBlocking { preferences.setNotificationCaptureEnabled(true) }
        start()
        await { shadow.snapshotReads == 1 }
        listener.onNotificationPosted(notice(1))
        listener.onListenerDisconnected()
        listener.onNotificationPosted(notice(2))

        val recovered = notice(3)
        shadow.active = arrayOf(notice(4, System.currentTimeMillis() - 11 * 60_000), recovered)
        listener.onListenerConnected()
        listener.onNotificationPosted(notice(5))
        await { transactions().size >= 2 }
        assertEquals(listOf(5_00L, 3_00L), transactions().sortedBy { it.id }.map { it.amount })

        listener.onNotificationPosted(notice(6, recovered.postTime, order = 3))
        await { monitor.health.value.duplicateCount > 0 }
        assertEquals(2, transactions().size)
    }

    private fun start(vararg active: StatusBarNotification) {
        controller = Robolectric.buildService(NotificationCaptorService::class.java).also { built ->
            listener = built.get().apply {
                dependencies = object : CaptorServiceEntryPoint {
                    override fun monitor() = monitor
                    override fun appPreferences() = preferences
                    override fun captureProcessor() = processor
                }
                readDispatcher = dispatcher
            }
            shadow = Shadow.extract(listener)
            shadow.active = arrayOf(*active)
            built.create()
            listener.onListenerConnected()
        }
    }

    private fun notice(id: Int, at: Long = System.currentTimeMillis(), order: Int = id) = StatusBarNotification(
        "com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone", id, null, 1000, 0, 0,
        Notification().apply {
            extras.putCharSequence(Notification.EXTRA_TITLE, "支付宝")
            extras.putCharSequence(Notification.EXTRA_TEXT, "支付成功，实付${order}元，订单号：2026110700000$order")
        }, Process.myUserHandle(), at)

    private fun transactions() = runBlocking { database.backupDao().transactions() }

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("listener did not settle; ${monitor.health.value}", condition())
    }
}

/** Framework-supplied active notifications can be controlled without changing system settings. */
@Implements(NotificationListenerService::class)
class CaptureNotificationListenerShadow : ShadowNotificationListenerService() {
    var active: Array<StatusBarNotification> = emptyArray()
    var snapshotReads = 0

    @Implementation
    protected fun getActiveNotifications(): Array<StatusBarNotification> {
        snapshotReads++
        return active
    }
}

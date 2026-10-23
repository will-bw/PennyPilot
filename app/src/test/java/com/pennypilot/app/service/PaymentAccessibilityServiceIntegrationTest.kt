package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
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
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

/**
 * Android framework simulation: real service events, node reader, parser, processor and Room.
 * Only Hilt dependencies, coroutine scheduling and system-owned visible nodes are substituted.
 * This does not establish compatibility with a particular WeChat build or phone background policy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [PaymentAccessibilityWindowShadow::class, PaymentScreenReaderChildShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class PaymentAccessibilityServiceIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var context: Context
    private lateinit var preferences: AppPreferences
    private lateinit var database: AppDatabase
    private lateinit var monitor: CaptureServiceMonitor
    private lateinit var controller: ServiceController<PaymentAccessibilityService>
    private lateinit var service: PaymentAccessibilityService
    private lateinit var window: PaymentAccessibilityWindowShadow
    private var cloudCalls = 0
    private val startedAt = System.currentTimeMillis()

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context)
        preferences.setNotificationCaptureEnabled(false)
        preferences.setNotificationCloudParsingEnabled(false)
        preferences.setScreenCaptureEnabled(false)
        val directExecutor = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0,
            type = TxType.EXPENSE, sortOrder = 0))
        database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0,
            type = TxType.INCOME, sortOrder = 1))
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        monitor = CaptureServiceMonitor()
        val cloud = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
                cloudCalls++
                error("Screen collection must remain local")
            }
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools =
                error("Screen collection must remain local")
        }
        val processor = CaptureProcessor(context, database, preferences, cloud, monitor)
        controller = Robolectric.buildService(PaymentAccessibilityService::class.java)
        service = controller.get().apply {
            dependencies = object : CaptorServiceEntryPoint {
                override fun monitor() = monitor
                override fun appPreferences() = preferences
                override fun captureProcessor() = processor
            }
            readDispatcher = dispatcher
            nowMillis = { startedAt + scheduler.currentTime }
        }
        window = Shadow.extract(service)
        controller.create()
        Unit
    }

    @After fun tearDown() {
        controller.destroy()
        scheduler.runCurrent()
        runBlocking {
            preferences.setScreenCaptureEnabled(false)
            preferences.setNotificationCaptureEnabled(true)
        }
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `wechat transfer event creates pending expense without either notification permission or notification capture`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n完成") }
        sendEvent()
        awaitStatus("saved")
        val row = rows().single()
        assertEquals(1800L, row.amount)
        assertEquals(TxType.EXPENSE, row.type)
        assertEquals(TxSource.ACCESSIBILITY, row.source)
        assertEquals(TxStatus.PENDING, row.status)
        assertEquals("小明", row.merchant)
        assertEquals(1, monitor.health.value.todayCaptureCount)
        assertEquals(0, cloudCalls)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun `sender waiting for payee confirmation is captured as expense through actual service`() {
        connect(enabled = true)
        window.root = { screen("已转账\n¥18.00\n待对方收款\n收款方\n小明\n完成") }
        sendEvent()
        awaitStatus("saved")
        val row = rows().single()
        assertEquals(TxType.EXPENSE, row.type)
        assertEquals(1800L, row.amount)
        assertEquals(TxStatus.PENDING, row.status)
    }

    @Test fun `system binding without app consent never reads the payment page`() {
        connect(enabled = false)
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        drainRetries()
        assertTrue(monitor.health.value.isAccessibilityBound)
        assertEquals("disabled", monitor.health.value.screenCaptureStatus)
        assertEquals(0, window.reads)
        assertTrue(rows().isEmpty())
    }

    @Test fun `app opt in without a system service connection cannot capture`() {
        runBlocking { preferences.setScreenCaptureEnabled(true) }
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        drainRetries()
        assertFalse(monitor.health.value.isAccessibilityBound)
        assertEquals(0, window.reads)
        assertTrue(rows().isEmpty())
    }

    @Test fun `temporarily unavailable root is retried without any second accessibility event`() {
        connect(enabled = true)
        sendEvent()
        awaitStatus("unreadable")
        val firstReads = window.reads
        window.root = { screen("转账成功\n¥18.00\n完成") }
        // The result becomes visible during the first bounded retry, with no new event or notification.
        scheduler.advanceTimeBy(250)
        awaitStatus("saved")
        assertTrue(window.reads > firstReads)
        assertEquals(1, rows().size)
    }

    @Test fun `already stable receipt is sampled on service connection without a new event`() {
        window.root = { screen("转账成功\n¥18.00\n完成") }
        connect(enabled = true, expectedStatus = "saved")
        assertEquals(1, rows().size)
    }

    @Test fun `same receipt redraws and windows changes never insert a second pending record`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n完成") }
        sendEvent()
        awaitStatus("saved")
        repeat(8) {
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            awaitStatus("duplicate")
        }
        sendEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        awaitStatus("duplicate")
        assertEquals(1, rows().size)
        assertEquals(1, monitor.health.value.todayCaptureCount)
    }

    @Test fun `failed transfer and editable chat text never become transactions`() {
        connect(enabled = true)
        window.root = { screen("转账失败\n¥18.00\n余额不足\n重新转账") }
        sendEvent()
        awaitStatus("not_receipt")
        drainRetries()
        assertTrue(rows().isEmpty())
        window.root = { screen("转账成功\n¥18.00\n完成", editableChild = true) }
        sendEvent()
        awaitStatus("input_page")
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `independent same amount transfer after input page is not swallowed as a redraw`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n完成") }
        sendEvent()
        awaitStatus("saved")
        window.root = { screen("转账\n¥18.00\n确认转账", editableChild = true) }
        sendEvent()
        awaitStatus("input_page")
        scheduler.advanceTimeBy(2_000)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.status == TxStatus.PENDING && it.amount == 1800L })
    }

    @Test fun `late receipt details do not duplicate the page and a changed order can capture`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n订单号 202610240001\n完成") }
        sendEvent()
        awaitStatus("duplicate")
        assertEquals(1, rows().size)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n订单号 202610240002\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, rows().size)
    }

    @Test fun `mismatched window or unsupported app cannot provide payment evidence`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n完成", windowId = 9) }
        sendEvent(windowId = 7)
        awaitStatus("unreadable")
        drainRetries()
        assertTrue(rows().isEmpty())
        val priorReads = window.reads
        window.root = { screen("转账成功\n¥18.00\n完成", packageName = "untrusted.app") }
        sendEvent(packageName = "untrusted.app")
        drainRetries()
        assertEquals(priorReads, window.reads)
        assertTrue(rows().isEmpty())
    }

    @Test fun `disconnect cancels a pending retry and does not read or save a later receipt`() {
        connect(enabled = true)
        sendEvent()
        awaitStatus("unreadable")
        service.onUnbind(Intent())
        val priorReads = window.reads
        window.root = { screen("转账成功\n¥18.00\n完成") }
        drainRetries()
        assertFalse(monitor.health.value.isAccessibilityBound)
        assertEquals("disconnected", monitor.health.value.screenCaptureStatus)
        assertEquals(priorReads, window.reads)
        assertTrue(rows().isEmpty())
    }

    @Test fun `stable wechat result under a hidden container is captured without notification`() {
        connect(enabled = true)
        window.root = {
            screen("", visible = false).apply {
                shadowOf(this).addChild(screen("转账成功\n¥18.00\n收款方\n小明\n完成"))
            }
        }
        sendEvent()
        awaitStatus("saved")
        assertEquals(1800L, rows().single().amount)
        assertEquals(1, monitor.health.value.screenReadDiagnostics["hiddenNodes"])
        assertEquals(0, cloudCalls)
    }

    @Test fun `foreign input overlay does not poison a complete wechat result or its deduplication`() {
        connect(enabled = true)
        window.root = {
            screen("已转账\n¥18.00\n待对方收款\n完成").apply {
                shadowOf(this).addChild(screen("never read this input", packageName = "com.example.keyboard").apply {
                    isEditable = true
                    isPassword = true
                })
            }
        }
        sendEvent()
        awaitStatus("saved")
        assertEquals(0, monitor.health.value.screenReadDiagnostics["editableNodes"])
        assertEquals(0, monitor.health.value.screenReadDiagnostics["passwordNodes"])
        assertEquals(1, monitor.health.value.screenReadDiagnostics["foreignNodes"])
        scheduler.advanceTimeBy(2_000)
        sendEvent()
        awaitStatus("duplicate")
        assertEquals(1, rows().size)
    }

    @Test fun `receipt with more than old node budget is scanned completely while late failure still rejects`() {
        connect(enabled = true)
        fun largeResult(failure: Boolean) = screen("转账成功\n¥18.00\n完成").apply {
            repeat(300) { shadowOf(this).addChild(screen("")) }
            if (failure) shadowOf(this).addChild(screen("支付失败"))
        }
        window.root = { largeResult(true) }
        sendEvent()
        awaitStatus("not_receipt")
        assertTrue(rows().isEmpty())
        window.root = { largeResult(false) }
        sendEvent()
        awaitStatus("saved")
        assertEquals(1, rows().size)
        assertTrue(monitor.health.value.screenReadDiagnostics.getValue("visitedNodes") > 250)
    }

    @Test fun `empty cached tree is diagnosed and invalidated before retrying stable receipt`() {
        connect(enabled = true)
        val initialClears = window.cacheClears
        window.root = {
            if (window.cacheClears == initialClears) screen("")
            else screen("转账成功\n¥18.00\n完成")
        }
        sendEvent()
        awaitStatus("empty_page")
        assertEquals(0, monitor.health.value.screenReadDiagnostics["textNodes"])
        assertTrue(rows().isEmpty())
        scheduler.advanceTimeBy(250)
        awaitStatus("saved")
        assertTrue(window.cacheClears > initialClears)
        assertEquals(1, rows().size)
    }

    @Test fun `receipt arriving after previous retry window still captures without a new event`() {
        connect(enabled = true)
        window.root = { screen("") }
        sendEvent()
        awaitStatus("empty_page")
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertTrue(rows().isEmpty())
        window.root = { screen("转账成功\n¥18.00\n完成") }
        scheduler.advanceTimeBy(2_000)
        awaitStatus("saved")
        assertEquals(1, rows().size)
    }

    @Test fun `missing subtree retries without saving partial receipt or forgetting handled identity`() {
        connect(enabled = true)
        var incomplete = true
        window.root = {
            screen("转账成功\n¥18.00\n完成").apply {
                shadowOf(this).addChild(screen("收款方\n小明"))
                Shadow.extract<PaymentScreenReaderChildShadow>(this).nullReadsRemaining = if (incomplete) 2 else 0
            }
        }
        sendEvent()
        awaitStatus("incomplete_tree")
        assertEquals(1, monitor.health.value.screenReadDiagnostics["missingChildren"])
        assertTrue(rows().isEmpty())
        incomplete = false
        scheduler.advanceTimeBy(250)
        awaitStatus("saved")
        assertEquals(1, rows().size)
        incomplete = true
        sendEvent()
        awaitStatus("incomplete_tree")
        scheduler.advanceTimeBy(2_000)
        incomplete = false
        sendEvent()
        awaitStatus("duplicate")
        assertEquals(1, rows().size)
        assertEquals(0, cloudCalls)
    }

    @Test fun `password and oversized trees have precise reasons and never persist partial evidence`() {
        connect(enabled = true)
        window.root = { screen("转账成功\n¥18.00\n完成").apply { isPassword = true } }
        sendEvent()
        awaitStatus("password_page")
        assertEquals(1, monitor.health.value.screenReadDiagnostics["passwordNodes"])
        assertTrue(rows().isEmpty())
        window.root = {
            screen("转账成功\n¥18.00\n完成").apply {
                repeat(2_001) { shadowOf(this).addChild(screen("")) }
            }
        }
        sendEvent()
        awaitStatus("traversal_limit")
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, cloudCalls)
    }

    private fun connect(enabled: Boolean, expectedStatus: String = if (enabled) "waiting" else "disabled") {
        runBlocking { preferences.setScreenCaptureEnabled(enabled) }
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        // `waiting` is also the connection's initial state: require the preference worker to sample
        // its root as evidence that the enabled collector and read worker have actually started.
        awaitCondition("screen service did not become $expectedStatus") {
            monitor.health.value.screenCaptureStatus == expectedStatus && (!enabled || window.reads > 0)
        }
        window.reads = 0
    }

    private fun sendEvent(type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                          packageName: String = "com.tencent.mm", windowId: Int = 7) {
        val event = AccessibilityEvent.obtain(type).apply { this.packageName = packageName }
        shadowOf(event).setWindowId(windowId)
        service.onAccessibilityEvent(event)
        event.recycle()
    }

    private fun screen(text: String, packageName: String = "com.tencent.mm", windowId: Int = 7,
                       editableChild: Boolean = false, visible: Boolean = true): AccessibilityNodeInfo {
        val accessibilityWindow = AccessibilityWindowInfo.obtain()
        shadowOf(accessibilityWindow).setId(windowId)
        return AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            this.text = text
            isVisibleToUser = visible
            shadowOf(this).setAccessibilityWindowInfo(accessibilityWindow)
            if (editableChild) {
                val input = AccessibilityNodeInfo.obtain().apply {
                    this.packageName = packageName
                    isVisibleToUser = true
                    isEditable = true
                    this.text = "private chat input"
                }
                shadowOf(this).addChild(input)
            }
        }
    }

    private fun rows() = runBlocking { database.backupDao().transactions() }

    private fun awaitStatus(status: String) = awaitCondition("expected $status, got ${monitor.health.value}") {
        monitor.health.value.screenCaptureStatus == status
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        // DataStore/Room own real executor threads. Pump only ready coroutine work while they settle;
        // payment retry time itself stays virtual and is advanced explicitly by each scenario.
        repeat(200) {
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }

    private fun drainRetries() {
        repeat(6) {
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
        }
    }
}

/** Test-owned visible window; production still calls Android's rootInActiveWindow. */
@Implements(AccessibilityService::class)
class PaymentAccessibilityWindowShadow : ShadowAccessibilityService() {
    var root: () -> AccessibilityNodeInfo? = { null }
    var reads = 0
    var cacheClears = 0

    @Implementation(minSdk = 33)
    protected fun clearCache(): Boolean {
        cacheClears++
        return true
    }

    @Implementation
    protected fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        reads++
        return root()
    }
}

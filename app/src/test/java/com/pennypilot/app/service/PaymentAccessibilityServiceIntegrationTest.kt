package com.pennypilot.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.ModelRole
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.data.recognition.PaymentProposal
import com.pennypilot.app.data.recognition.RecognitionTask
import com.pennypilot.app.data.recognition.RecognitionTaskStore
import com.pennypilot.app.data.recognition.ReceiptImageMode
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private var serviceDestroyed = false
    private var cloudCalls = 0
    private lateinit var model: LLMClient
    private val startedAt = System.currentTimeMillis()

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context)
        preferences.setNotificationCaptureEnabled(false)
        preferences.setNotificationCloudParsingEnabled(false)
        preferences.setScreenCaptureEnabled(false)
        preferences.setCaptureAppPackages(com.pennypilot.app.data.parser.PaymentAppRegistry.packages)
        preferences.setScreenCloudParsingEnabled(false)
        preferences.setScreenVisualCaptureEnabled(false)
        preferences.setScreenCloudVisionEnabled(false)
        preferences.setCloudImageParsingEnabled(false)
        preferences.setCaptureAutoRecordEnabled(false)
        CaptureDiagnosticStore.clear()
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
        model = cloud
        val processor = CaptureProcessor(context, database, preferences, cloud, monitor)
        controller = Robolectric.buildService(PaymentAccessibilityService::class.java)
        service = controller.get().apply {
            serviceInfo = android.accessibilityservice.AccessibilityServiceInfo()
            dependencies = object : CaptorServiceEntryPoint {
                override fun monitor() = monitor
                override fun appPreferences() = preferences
                override fun captureProcessor() = processor
            }
            readDispatcher = dispatcher
            nowMillis = { startedAt + scheduler.currentTime }
            elapsedMillis = { scheduler.currentTime }
        }
        window = Shadow.extract(service)
        controller.create()
        Unit
    }

    @After fun tearDown() {
        destroyService()
        scheduler.runCurrent()
        runBlocking {
            preferences.setScreenCaptureEnabled(false)
            preferences.setNotificationCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setScreenVisualCaptureEnabled(false)
            preferences.setScreenCloudVisionEnabled(false)
            preferences.setCloudImageParsingEnabled(false)
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
        awaitCondition("late receipt did not enrich the original transaction") { rows().singleOrNull()?.merchant == "小明" }
        assertEquals(1, rows().size)
        assertEquals("202610240001", runBlocking { database.backupDao().captureEvents() }.single().orderId)
        window.root = { screen("转账成功\n¥18.00\n收款方\n小明\n订单号 202610240002\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, rows().size)
    }

    @Test fun `late order without a second event enriches persisted evidence under the original event`() {
        connect(enabled = true)
        var detailed = false
        window.root = { screen(if (detailed) "转账成功\n¥18.00\n收款方\n小明\n订单号202610240001\n完成"
            else "转账成功\n¥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        val event = rows().single().captureEventKey
        detailed = true
        scheduler.advanceTimeBy(250)
        awaitCondition("late evidence was not retained") {
            runBlocking { database.backupDao().captureEvents() }.singleOrNull()?.orderId == "202610240001"
        }
        val evidence = runBlocking { database.backupDao().captureEvents() }.single()
        assertEquals(event, evidence.eventKey)
        assertEquals("202610240001", evidence.orderId)
        assertEquals(1, rows().size)
    }

    @Test fun `an explicit input boundary permits a fast second identical transfer`() {
        connect(enabled = true)
        window.root = { screen("已转账\n¥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        val first = rows().single().captureEventKey
        window.root = { screen("转账", editableChild = true) }
        sendEvent()
        awaitStatus("input_page")
        scheduler.advanceTimeBy(100)
        window.root = { screen("已转账\n¥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, rows().size)
        assertEquals(2, rows().map { it.captureEventKey }.distinct().size)
        assertTrue(rows().any { it.captureEventKey != first })
    }

    @Test fun `unknown safe result reaches inference once and later known layout reuses logical event`() {
        val requests = mutableListOf<Pair<String, String>>()
        service.unknownScreenHandler = { _, text, key, _, _, structure, _ ->
            assertNotNull(structure)
            requests += key to text
            true
        }
        connect(enabled = true)
        window.root = { screen("本次付款已成功\n￥18.00\n完成") }
        sendEvent()
        awaitCondition("unknown result was not sent to processor") { requests.size == 1 }
        drainRetries()
        assertEquals(1, requests.size)
        assertTrue(rows().isEmpty())
        window.root = { screen("付款成功\n￥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(requests.single().first, rows().single().captureEventKey)
    }

    @Test fun `unsafe unknown screens never reach inference even with a configured handler`() {
        var requests = 0
        service.unknownScreenHandler = { _, _, _, _, _, _, _ -> requests++; true }
        connect(enabled = true)
        listOf("账单详情\n本次付款成功\n￥18.00\n完成", "聊天记录\n本次付款成功\n￥18.00\n完成",
            "本次付款失败\n￥18.00\n完成", "明天准备\n本次付款成功\n￥18.00\n完成").forEach { text ->
            window.root = { screen(text) }
            sendEvent()
            awaitStatus("not_receipt")
        }
        window.root = { screen("本次付款成功\n￥18.00\n完成", editableChild = true) }
        sendEvent()
        awaitStatus("input_page")
        assertEquals(0, requests)
        assertEquals(0, cloudCalls)
    }

    @Test fun `manual image permission cannot upload an automatic receipt`() {
        connect(enabled = true)
        runBlocking { preferences.setCloudImageParsingEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; null }
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(0, captures)
        assertEquals(1, rows().size)
    }

    @Test fun `a success label alone cannot upload a cloud receipt image`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; null }
        window.root = { screen("支付成功") }
        sendEvent()
        awaitStatus("not_receipt")
        assertEquals(0, captures)
        assertTrue(rows().isEmpty())
    }

    @Test fun `disabling cloud vision before submission preserves the locally confirmed payment only`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var frames = 0
        service.visualCapture = {
            preferences.setScreenCloudVisionEnabled(false)
            frames++
            Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        awaitCondition("receipt image was not attempted") { frames == 1 }
        drainRetries()
        assertEquals(0, submitted)
        assertEquals(1800L, rows().single().amount)
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

    @Test fun `active payment dialog can be read while the global root still points at the preceding window`() {
        connect(enabled = true)
        window.root = { screen("页面正在加载", windowId = 7) }
        sendEvent()
        awaitStatus("not_receipt")

        val dialog = screen("转账成功\n¥18.00\n收款方：小明\n完成", windowId = 9)
        window.setWindows(listOf(
            applicationWindow(screen("页面正在加载", windowId = 7), focused = true),
            applicationWindow(dialog, active = true)
        ))
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, windowId = 9)

        awaitStatus("saved")
        assertEquals(1800L, rows().single().amount)
        assertEquals("小明", receiptDetail("payeeName"))
        assertTrue(window.screenshotWindows.isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `active wallet can be read while the global root still points at the shopping app`() {
        connect(enabled = true)
        val shopping = "com.sankuai.meituan"
        val wallet = "com.eg.android.AlipayGphone"
        window.root = { screen("正在打开支付", packageName = shopping, windowId = 7) }
        sendEvent(packageName = shopping)
        awaitStatus("not_receipt")

        val receipt = screen("支付成功\n实付金额：18.00元\n商户名称：测试商户\n完成", packageName = wallet, windowId = 9)
        window.setWindows(listOf(
            applicationWindow(screen("正在打开支付", packageName = shopping, windowId = 7), focused = true),
            applicationWindow(receipt, active = true)
        ))
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, packageName = wallet, windowId = 9)
        awaitStatus("saved")
        assertEquals(1800L, rows().single().amount)
        assertEquals("测试商户", receiptDetail("merchantName"))
        assertTrue(window.screenshotWindows.isEmpty())
    }

    @Test fun `exact window lookup refuses background receipts and roots without matching ownership`() {
        connect(enabled = true)
        val previous = screen("页面正在加载", windowId = 7)
        val receipt = screen("转账成功\n¥18.00\n完成", windowId = 9)
        window.root = { screen("页面正在加载", windowId = 7) }
        window.setWindows(listOf(applicationWindow(previous, active = true), applicationWindow(receipt, focused = true)))
        sendEvent(windowId = 9)
        awaitStatus("unreadable")

        val wrongOwner = screen("转账成功\n¥18.00\n完成", packageName = "untrusted.app", windowId = 9)
        window.setWindows(listOf(applicationWindow(wrongOwner, active = true)))
        sendEvent(windowId = 9)
        drainRetries()

        window.root = { screen("系统面板", packageName = "com.android.systemui", windowId = 8) }
        window.setWindows(listOf(applicationWindow(receipt, active = true)))
        sendEvent(windowId = 9)
        drainRetries()

        assertTrue(rows().isEmpty())
        assertTrue(window.screenshotWindows.isEmpty())
        assertEquals(0, cloudCalls)
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

    @Test fun `destroying service during retry wait closes channel without uncaught coroutine failure`() = runTest(dispatcher) {
        connect(enabled = true)
        sendEvent()
        awaitStatus("unreadable")
        // The read worker is now suspended in its timed receive, rather than the outer channel loop.
        scheduler.runCurrent()
        val priorReads = window.reads
        destroyService()
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        scheduler.advanceUntilIdle()
        assertFalse(monitor.health.value.isAccessibilityBound)
        assertEquals("disconnected", monitor.health.value.screenCaptureStatus)
        assertEquals(priorReads, window.reads)
        assertTrue(rows().isEmpty())
        // runTest also fails if closing the channel escapes as an unhandled exception in the service.
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

    @Test fun `readable wallet scene matrix reaches Room with the intended direction and business purpose`() {
        connect(enabled=true)
        val cases=listOf(
            Triple("com.tencent.mm","微信红包\n已存入零钱\n领取金额3元\n红包总额100元",TxType.INCOME),
            Triple("com.tencent.mm","红包已退还\n退还金额4元\n完成",TxType.INCOME),
            Triple("com.tencent.mm","二维码收款\n收款成功\n收款金额5元\n今日收款100元",TxType.INCOME),
            Triple("com.eg.android.AlipayGphone","交易成功\n实付金额6元\n收款方：便利店\n完成",TxType.EXPENSE),
            Triple("com.eg.android.AlipayGphone","交易成功\n收款金额7元\n付款方：小明\n完成",TxType.INCOME),
            Triple("com.eg.android.AlipayGphone","退款已到账\n本次退款金额8元\n原支付金额30元\n退款单号RF202610240008\n完成",TxType.INCOME))
        for((index,c) in cases.withIndex()) {
            window.root = { screen(c.second,packageName=c.first) }
            sendEvent(packageName=c.first)
            awaitCondition("wallet matrix case $index did not persist") { rows().size==index+1 }
            assertEquals(c.third,rows().last().type)
        }
        val refund=rows().last()
        assertEquals(-800L,refund.amount)
        assertEquals("refund",refund.businessKind)
        assertEquals("alipay:refund",runBlocking { database.backupDao().captureEvents() }.single { it.orderId=="RF202610240008" }.orderDomain)
        assertEquals(0,cloudCalls)
    }

    @Test fun `supported wallet amount-first payment nodes reach Room through the service`() {
        connect(enabled = true)
        val packages = listOf("com.eg.android.AlipayGphone", "com.unionpay", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew")
        for ((index, packageName) in packages.withIndex()) {
            window.root = { amountFirstPaymentScreen(packageName, "18.5$index", "SC20261101000$index") }
            sendEvent(packageName = packageName)
            awaitCondition("amount-first receipt for $packageName did not persist") { rows().size == index + 1 }
            val row = rows().last()
            assertEquals(1850L + index, row.amount)
            assertEquals(TxType.EXPENSE, row.type)
            assertEquals(TxStatus.PENDING, row.status)
            assertEquals(TxSource.ACCESSIBILITY, row.source)
            assertEquals("expense", row.businessKind)
        }
        window.root = { amountFirstPaymentScreen(packages.first(), "20.00", "SC202611010009", editableChild = true) }
        sendEvent(packageName = packages.first())
        awaitStatus("input_page")
        drainRetries()
        assertEquals(packages.size, rows().size)
        assertTrue(window.screenshotWindows.isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `identified alipay current and historical receipt preserves real time while editable page stays excluded`() {
        connect(enabled=true)
        val format=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val actual=(startedAt-30_000)/1_000*1_000
        val text="账单详情\n二维码收款\n交易成功\n+18.00\n收支类型\n收入\n交易时间\n${format.format(Instant.ofEpochMilli(actual))}\n交易单号ALI202610240001"
        window.root = { screen(text,packageName="com.eg.android.AlipayGphone",editableChild=true) }
        sendEvent(packageName="com.eg.android.AlipayGphone")
        awaitStatus("input_page")
        assertTrue(rows().isEmpty())
        window.root = { screen(text,packageName="com.eg.android.AlipayGphone") }
        sendEvent(packageName="com.eg.android.AlipayGphone")
        awaitStatus("saved")
        assertEquals(actual,rows().single().occurredAt)
        assertEquals(-1800L,rows().single().amount)
        val old=text.replace(format.format(Instant.ofEpochMilli(actual)),format.format(Instant.ofEpochMilli(startedAt-31*60_000)))
            .replace("ALI202610240001","ALI202610240002")
        window.root = { screen(old,packageName="com.eg.android.AlipayGphone") }
        sendEvent(packageName="com.eg.android.AlipayGphone")
        awaitCondition("historical identified receipt was not captured") { rows().size == 2 }
        assertEquals((startedAt-31*60_000)/1_000*1_000, rows().last().occurredAt)
    }

    @Test fun `read only checkout enriches its verified same order without creating a payment before completion`() {
        connect(enabled = true)
        val pkg = "com.sankuai.meituan"
        val order = "MT20261107001234"
        window.root = {
            screen("确认订单", packageName = pkg).apply {
                listOf("提交订单", "商家订单号：$order", "应付金额：18.00元", "商品名称：红烧牛肉面", "数量：2")
                    .forEach { shadowOf(this).addChild(screen(it, packageName = pkg)) }
            }
        }
        sendEvent(packageName = pkg)
        awaitStatus("not_receipt")
        assertTrue(rows().isEmpty())
        assertTrue(window.screenshotWindows.isEmpty())

        window.root = {
            screen("支付成功\n完成\n实付金额：18.00元", packageName = pkg, windowId = 8).apply {
                shadowOf(this).addChild(screen("商户订单号：$order", packageName = pkg, windowId = 8))
            }
        }
        sendEvent(packageName = pkg, windowId = 8)
        awaitStatus("saved")
        val first = rows().single()
        val metadata = first.metadataJson!!
        assertEquals(1800L, first.amount)
        assertTrue(metadata.contains("红烧牛肉面"))
        assertTrue(metadata.contains(CheckoutContext.LOCAL_SOURCE))
        assertEquals(order, runBlocking { database.transactionDao().captureEvidenceByKey(first.captureEventKey!!)!!.orderId })
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, packageName = pkg, windowId = 8)
        drainRetries()
        assertEquals(first.id, rows().single().id)
        assertTrue(rows().single().metadataJson!!.contains("红烧牛肉面"))
        assertEquals(0, cloudCalls)
    }

    @Test fun `cloud receipt enriches a saved node transaction and retries a missing screenshot`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        val frame = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        service.visualCapture = { if (++captures == 1) null else frame }
        var submitted = 0
        service.receiptImageHandler = { _, image, key, _, _, imageOnly ->
            assertFalse(imageOnly)
            assertTrue(image.startsWith("data:image/jpeg;base64,"))
            assertEquals(rows().single().id, database.transactionDao().captureEvidenceByKey(key)?.transactionId)
            submitted++
            true
        }
        window.root = { screen("转账成功\n¥18.00\n完成") }
        sendEvent()
        awaitCondition("first image attempt after node save") { captures == 1 && rows().size == 1 }
        val id = rows().single().id
        scheduler.advanceTimeBy(250)
        awaitCondition("second image attempt enqueued") { submitted == 1 }
        assertEquals(id, rows().single().id)
        assertTrue(frame.isRecycled)
    }

    @Test fun `image amount receipt goes directly to cloud without invented local amount`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var submitted = 0
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888) }
        service.receiptImageHandler = { _, _, _, _, _, imageOnly ->
            assertTrue(imageOnly)
            submitted++
            true
        }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("unreadable amount was not sent to vision") { submitted == 1 }
        repeat(3) { sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); drainRetries() }
        assertEquals(1, submitted)
        assertTrue(rows().isEmpty())
    }

    @Test fun `dedicated opaque wallet page can reach vision with no local text recognition`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var submitted = 0
        val frame = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        service.visualCapture = { frame }
        service.receiptImageHandler = { _, _, _, _, _, imageOnly ->
            assertTrue(imageOnly); submitted++; true
        }
        window.root = { screen("", visible = false) }
        sendEvent(className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")
        awaitCondition("opaque receipt was not enqueued") { submitted == 1 }
        assertTrue(rows().isEmpty())
        assertTrue(frame.isRecycled)
    }

    @Test fun `generic opaque container requires an observed wallet preparation handoff`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var submitted = 0
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888) }
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("", visible = false) }
        sendEvent(className = "com.tencent.mm.framework.app.UIPageFragmentActivity")
        drainRetries()
        assertEquals(0, submitted)
        window.root = { screen("转账金额", editableChild = true) }
        sendEvent(className = "com.tencent.mm.plugin.remittance.ui.RemittanceUI")
        awaitStatus("input_page")
        window.root = { screen("", visible = false) }
        sendEvent(className = "com.tencent.mm.framework.app.UIPageFragmentActivity")
        awaitCondition("wallet handoff did not qualify the opaque receipt") { submitted == 1 }
        assertTrue(rows().isEmpty())
    }

    @Test fun `opaque page with foreign nodes or hidden inputs never uploads`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; null }
        for (foreign in listOf(true, false)) {
            window.root = { screen("", visible = false).apply {
                shadowOf(this).addChild(screen("", visible = false).apply {
                    if (foreign) packageName = "other.app" else isEditable = true
                })
            } }
            sendEvent(className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")
            drainRetries()
        }
        assertEquals(0, captures)
    }

    @Test fun `legacy local visual switch no longer invokes image recognition`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; null }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitStatus("visual_required")
        drainRetries()
        assertEquals(0, captures)
        assertTrue(rows().isEmpty())
    }

    @Test fun `navigation during image capture discards image and recycles bitmap`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        val frame = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        var captures = 0
        service.visualCapture = {
            captures++
            window.root = { screen("聊天记录", editableChild = true, windowId = 8) }
            sendEvent(windowId = 8, className = "com.tencent.mm.ui.chatting.ChattingUI")
            frame
        }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("image capture did not run") { captures == 1 }
        drainRetries()
        assertEquals(0, submitted)
        assertTrue(frame.isRecycled)
        assertTrue(rows().isEmpty())
    }

    @Test fun `same window redraw invalidates an image even when the node text is unchanged`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        service.visualCapture = {
            captures++
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("first frame was not captured") { captures >= 1 }
        drainRetries()
        assertTrue(captures in 1..3)
        assertEquals(0, submitted)
    }

    @Test fun `candidate screenshot failure has a bounded attempt budget`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; null }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("first screenshot was not requested") { captures == 1 }
        repeat(2) { index ->
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            awaitCondition("bounded screenshot retry ${index + 2} was not requested") { captures == index + 2 }
        }
        assertEquals(3, captures)
        repeat(10) { sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); drainRetries() }
        assertEquals(3, captures)
        assertTrue(rows().isEmpty())
    }

    @Test fun `removing a selected application cancels collection and clears dynamic subscription`() {
        connect(enabled = true)
        runBlocking { preferences.setCaptureAppPackages(setOf("com.eg.android.AlipayGphone")) }
        awaitCondition("selection not applied") { service.serviceInfo?.packageNames?.toSet() == setOf("com.eg.android.AlipayGphone") }
        window.root = { screen("转账成功\n¥18.00\n完成") }
        val before = window.reads
        sendEvent()
        drainRetries()
        assertEquals(before, window.reads)
        assertTrue(rows().isEmpty())
        runBlocking { preferences.setCaptureAppPackages(emptySet()) }
        awaitStatus("disabled")
        assertFalse(service.serviceInfo?.packageNames.isNullOrEmpty())
    }

    @Test @Config(sdk = [30]) fun `android eleven cloud image excludes other windows before uploading`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        service.visualCapture = { captures++; Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888) }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        val receipt = screen("支付成功\n实付金额\n完成").apply { setBoundsInScreen(Rect(0, 100, 1000, 1900)) }
        window.root = { screen("支付成功\n实付金额\n完成").apply { setBoundsInScreen(Rect(0, 100, 1000, 1900)) } }
        val target = applicationWindow(receipt, active = true).apply { shadowOf(this).setLayer(1) }
        val overlay = AccessibilityWindowInfo.obtain().apply { shadowOf(this).apply {
            setId(9); setType(AccessibilityWindowInfo.TYPE_SYSTEM); setLayer(2)
            setBoundsInScreen(Rect(0, 100, 1000, 500))
        } }
        window.setWindows(listOf(target, overlay))
        sendEvent()
        drainRetries()
        assertEquals(0, captures)
        assertEquals(0, submitted)
        assertNull(monitor.health.value.lastError)
        // A new user visit after the covering notification disappears can use the display crop.
        window.root = { screen("首页") }
        sendEvent()
        drainRetries()
        window.root = { screen("支付成功\n实付金额\n完成").apply { setBoundsInScreen(Rect(0, 100, 1000, 1900)) } }
        window.setWindows(listOf(target))
        sendEvent()
        awaitCondition("unoccluded Android 11 receipt image was not queued") { submitted == 1 }
        assertTrue(rows().isEmpty())
        assertNull(monitor.health.value.lastError)
    }

    @Test @Config(sdk = [30]) fun `overlay appearing while display screenshot is in flight discards it`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        val receipt = screen("支付成功\n实付金额\n完成").apply { setBoundsInScreen(Rect(0, 100, 1000, 1900)) }
        window.root = { screen("支付成功\n实付金额\n完成").apply { setBoundsInScreen(Rect(0, 100, 1000, 1900)) } }
        val target = applicationWindow(receipt, active = true).apply { shadowOf(this).setLayer(1) }
        window.setWindows(listOf(target))
        var captures = 0
        val frame = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        service.visualCapture = {
            captures++
            val overlay = AccessibilityWindowInfo.obtain().apply { shadowOf(this).apply {
                setId(9); setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY); setLayer(2)
                setBoundsInScreen(Rect(20, 200, 600, 700))
            } }
            window.setWindows(listOf(target, overlay))
            frame
        }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        sendEvent()
        awaitCondition("display image was not requested") { captures == 1 }
        drainRetries()
        assertEquals(0, submitted)
        assertNull(monitor.health.value.lastError)
        assertTrue(frame.isRecycled)
    }

    @Test fun `revoking and regranting vision during capture discards the former authorization image`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        var frame: Bitmap? = null
        service.visualCapture = {
            captures++
            preferences.setScreenCloudVisionEnabled(false)
            preferences.setScreenCloudVisionEnabled(true)
            Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).also { frame = it }
        }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("vision revocation test did not finish capturing") { captures > 0 && frame?.isRecycled == true }
        drainRetries()
        assertTrue(captures > 0)
        assertEquals(0, submitted)
    }

    @Test fun `detail without financial time never falls back to a fresh generic payment`() {
        connect(enabled = true)
        var textRequests = 0
        service.unknownScreenHandler = { _, _, _, _, _, _, _ -> textRequests++; true }
        val pkg = "com.eg.android.AlipayGphone"
        window.root = { screen("支付详情\n支付成功\n实付金额：18.00元\n交易单号：20251024000012345678", packageName = pkg) }
        sendEvent(packageName = pkg)
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, textRequests)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var imageRequests = 0
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888) }
        service.receiptImageHandler = { _, _, _, _, _, imageOnly -> assertTrue(imageOnly); imageRequests++; true }
        sendEvent(packageName = pkg)
        awaitCondition("detail could not use image review") { imageRequests == 1 }
        assertTrue(rows().isEmpty())
    }

    @Test fun `opaque loading redraws do not consume new image quota before the completed frame arrives`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var color = android.graphics.Color.GRAY
        var captures = 0
        service.visualCapture = {
            captures++
            Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        val requests = mutableListOf<Pair<String, String>>()
        service.receiptImageHandler = { _, image, key, _, _, imageOnly ->
            assertTrue(imageOnly); requests += key to image; true
        }
        window.root = { screen("", visible = false) }
        sendEvent(className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")
        awaitCondition("initial opaque frame was not queued") { requests.size == 1 }
        repeat(5) {
            val before = captures
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            awaitCondition("loading redraw did not compare its image") { captures > before }
        }
        assertEquals(1, requests.size)
        color = android.graphics.Color.GREEN
        scheduler.advanceTimeBy(1_000)
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        awaitCondition("completed opaque frame was not queued") { requests.size == 2 }
        assertEquals(requests.first().first, requests.last().first)
        assertNotEquals(requests.first().second, requests.last().second)
        assertTrue(rows().isEmpty())
    }

    @Test fun `pixel amount arriving without another accessibility event is captured by bounded reread`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var color = android.graphics.Color.GRAY
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) } }
        val keys = mutableListOf<String>()
        service.receiptImageHandler = { _, _, key, _, _, imageOnly -> assertTrue(imageOnly); keys += key; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("first frame was not queued") { keys.size == 1 }
        color = android.graphics.Color.GREEN
        // No second event: the existing bounded 250/750 ms reader must revisit these same nodes.
        scheduler.advanceTimeBy(1_000)
        awaitCondition("late pixel amount was not reread") { keys.size == 2 }
        assertEquals(keys.first(), keys.last())
        assertTrue(rows().isEmpty())
    }

    @Test fun `same receipt can submit at most three distinct images despite continuous repaint`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var color = android.graphics.Color.RED
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) } }
        val keys = mutableListOf<String>()
        service.receiptImageHandler = { _, _, key, _, _, _ -> keys += key; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("first image missing") { keys.size == 1 }
        listOf(android.graphics.Color.GREEN, android.graphics.Color.BLUE).forEachIndexed { index, next ->
            color = next
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            awaitCondition("new image missing") { keys.size == index + 2 }
        }
        repeat(8) {
            color = if (it % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            scheduler.runCurrent()
        }
        drainRetries()
        assertEquals(3, keys.size)
        assertEquals(1, keys.distinct().size)
    }

    @Test fun `unchanged image sampling window cannot be renewed by repeated content events`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var captures = 0
        var color = android.graphics.Color.GRAY
        service.visualCapture = { captures++; Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) } }
        var submitted = 0
        service.receiptImageHandler = { _, _, _, _, _, _ -> submitted++; true }
        window.root = { screen("支付成功\n实付金额\n完成") }
        sendEvent()
        awaitCondition("first frame missing") { submitted == 1 }
        scheduler.advanceTimeBy(61_000)
        scheduler.runCurrent()
        awaitCondition("queued reread did not settle") { monitor.health.value.screenCaptureStatus != "error" }
        val previous = captures
        color = android.graphics.Color.GREEN
        repeat(5) { sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); drainRetries() }
        assertEquals(previous, captures)
        assertEquals(1, submitted)
    }

    @Test fun `image resampling cannot overwrite or duplicate a successfully saved cloud receipt`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenCloudVisionEnabled(true) }
        var color = android.graphics.Color.GRAY
        service.visualCapture = { Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) } }
        val processor = service.dependencies!!.captureProcessor()
        var submitted = 0
        var identity: String? = null
        service.receiptImageHandler = { pkg, image, key, at, revision, imageOnly ->
            assertTrue(imageOnly)
            submitted++
            if (identity == null) {
                identity = key
                val task = RecognitionTask(RecognitionTaskStore.id(key), key, pkg, "支付凭证图片", at, revision,
                    model.operationIdentity(ModelRole.VISION), TxSource.ACCESSIBILITY.name, images = listOf(image),
                    receiptImageMode = ReceiptImageMode.IMAGE_ONLY,
                    authorizationId = preferences.captureAuthorizationId(pkg, page = true, image = true))
                val parsed = PaymentTextParser.parseScreen(pkg, "支付成功\n实付金额：18.00元\n商户名称：早餐店\n完成")!!
                assertTrue(processor.completeRecognition(task, PaymentProposal(valid = true, businessKind = "expense"), parsed) { true })
                true
            } else {
                assertEquals(identity, key)
                // A later frame reaches the real production guard; saved vision cannot be replaced.
                assertFalse(processor.processReceiptImage(pkg, image, key, at, revision, allowImageOnly = true))
                false
            }
        }
        window.root = { screen("", visible = false) }
        sendEvent(className = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")
        awaitCondition("cloud result was not saved") { rows().size == 1 }
        val original = rows().single()
        color = android.graphics.Color.GREEN
        scheduler.advanceTimeBy(1_000)
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        awaitCondition("later frame did not reach the saved-result guard") { submitted >= 2 }
        drainRetries()
        assertEquals(original, rows().single())
        assertEquals(1800L, rows().single().amount)
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
                          packageName: String = "com.tencent.mm", windowId: Int = 7, className: String? = null) {
        val event = AccessibilityEvent.obtain(type).apply { this.packageName = packageName; this.className = className }
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

    private fun applicationWindow(root: AccessibilityNodeInfo, active: Boolean = false,
                                  focused: Boolean = false): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            shadowOf(window).apply {
                setId(root.windowId)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION)
                setActive(active)
                setFocused(focused)
                setRoot(root)
            }
        }

    private fun amountFirstPaymentScreen(packageName: String, amount: String, orderId: String,
                                         editableChild: Boolean = false): AccessibilityNodeInfo =
        screen("支付成功\n完成\n订单号：$orderId", packageName = packageName, editableChild = editableChild).apply {
            val row = screen("", packageName = packageName)
            shadowOf(row).addChild(screen(amount, packageName = packageName))
            shadowOf(row).addChild(screen("支付金额", packageName = packageName))
            shadowOf(this).addChild(row)
        }

    private fun rows() = runBlocking { database.backupDao().transactions() }

    private fun receiptDetail(field: String): String? {
        val raw = rows().singleOrNull()?.metadataJson ?: return null
        val details = (Json.parseToJsonElement(raw) as? JsonObject)?.get("receiptDetails") as? JsonObject
        return (details?.get(field) as? JsonPrimitive)?.content
    }

    private fun destroyService() {
        if (!serviceDestroyed) {
            serviceDestroyed = true
            controller.destroy()
        }
    }

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
        fail("$message; finalHealth=${monitor.health.value}; reads=${window.reads}; " +
            "cacheClears=${window.cacheClears}; virtualTime=${scheduler.currentTime}; rows=${rows().size}")
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
    val screenshotWindows = mutableListOf<Int>()

    private var configuration = android.accessibilityservice.AccessibilityServiceInfo()

    @Implementation protected fun getServiceInfo(): android.accessibilityservice.AccessibilityServiceInfo = configuration
    @Implementation protected fun setServiceInfo(info: android.accessibilityservice.AccessibilityServiceInfo) { configuration = info }

    @Implementation override fun getWindows(): MutableList<AccessibilityWindowInfo> =
        super.getWindows().map { AccessibilityWindowInfo.obtain(it) }.toMutableList()

    @Implementation(minSdk = 34)
    protected fun takeScreenshotOfWindow(windowId: Int, executor: Executor, callback: AccessibilityService.TakeScreenshotCallback) {
        screenshotWindows += windowId
        callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
    }

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

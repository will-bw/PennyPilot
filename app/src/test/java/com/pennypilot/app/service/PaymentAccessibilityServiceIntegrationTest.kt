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
import kotlinx.coroutines.test.runTest
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
    private val startedAt = System.currentTimeMillis()

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        preferences = AppPreferences(context)
        preferences.setNotificationCaptureEnabled(false)
        preferences.setNotificationCloudParsingEnabled(false)
        preferences.setScreenCaptureEnabled(false)
        preferences.setScreenCloudParsingEnabled(false)
        preferences.setScreenVisualCaptureEnabled(false)
        preferences.setCloudImageParsingEnabled(false)
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
        awaitStatus("duplicate")
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
        awaitStatus("duplicate")
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

    @Test fun `visual recovery requires explicit consent and remains local when ocr proves a transfer`() {
        var screenshots = 0
        service.visualCapture = { screenshots++; Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }
        service.visualOcr = { "￥18.00" }
        connect(enabled = true)
        window.root = { screen("转账成功\n完成") }
        sendEvent()
        awaitStatus("not_receipt")
        drainRetries()
        assertEquals(0, screenshots)
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        sendEvent()
        awaitStatus("saved")
        assertEquals(1, screenshots)
        assertEquals(1800L, rows().single().amount)
        assertEquals(0, cloudCalls)
        drainRetries()
        sendEvent()
        awaitStatus("duplicate")
        assertEquals(1, screenshots)
        assertEquals(1, rows().size)
        assertEquals("duplicate", monitor.health.value.screenCaptureStatus)
        // A second real transfer with the same image-only amount must not share the cooldown.
        window.root = { screen("转账", editableChild = true) }
        sendEvent()
        awaitStatus("input_page")
        window.root = { screen("转账成功\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, screenshots)
        assertEquals(2, rows().size)
    }

    @Test fun `visual recovery refuses a changed window before using OCR output`() {
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        service.visualCapture = { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }
        service.visualOcr = {
            window.root = { screen("聊天记录", editableChild = true) }
            "转账成功\n￥18.00"
        }
        connect(enabled = true)
        window.root = { screen("转账成功\n完成") }
        sendEvent()
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `same-window content event invalidates old image even when node text did not change`() {
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        var screenshots = 0
        service.visualCapture = { screenshots++; Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }
        service.visualOcr = {
            if (screenshots == 1) {
                // The result amount is image-only; a new frame can leave all node labels identical.
                sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                "￥18.00"
            } else "￥28.00"
        }
        connect(enabled = true)
        window.root = { screen("转账成功\n完成") }
        sendEvent()
        awaitStatus("saved")
        assertEquals(2, screenshots)
        assertEquals(2800L, rows().single().amount)
        assertEquals(0, cloudCalls)
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

    private fun opaqueLines(amount: String = "¥3.00") = listOf(
        PaymentImageReader.OcrLine("支付成功", Rect(570, 192, 780, 245)),
        PaymentImageReader.OcrLine("待测试收款人确认收款", Rect(370, 570, 895, 625)),
        PaymentImageReader.OcrLine(amount, Rect(440, 700, 825, 824)),
        PaymentImageReader.OcrLine("完成", Rect(579, 2380, 685, 2430)))

    private fun allowOpaqueCapture() {
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        service.visualCapture = { Bitmap.createBitmap(1264, 2780, Bitmap.Config.ARGB_8888) }
        service.opaqueVisualOcr = { opaqueLines() }
        window.root = { screen("", visible = false) }
    }

    private fun receivedLines(at: Long = startedAt - 20_000) : List<PaymentImageReader.OcrLine> {
        val format = DateTimeFormatter.ofPattern("uuuu年M月d日HH:mm:ss").withZone(ZoneId.systemDefault())
        return listOf(
            PaymentImageReader.OcrLine("你已收款，资金已存入零钱", Rect(326, 752, 941, 799)),
            PaymentImageReader.OcrLine("5.00", Rect(438, 882, 820, 998)),
            PaymentImageReader.OcrLine("零钱余额", Rect(544, 1089, 719, 1137)),
            PaymentImageReader.OcrLine("转账时间", Rect(100, 1295, 280, 1333)),
            PaymentImageReader.OcrLine(format.format(Instant.ofEpochMilli(at - 10_000)), Rect(645, 1295, 1164, 1333)),
            PaymentImageReader.OcrLine("收款时间", Rect(100, 1371, 280, 1411)),
            PaymentImageReader.OcrLine(format.format(Instant.ofEpochMilli(at)), Rect(645, 1371, 1164, 1411)),
            PaymentImageReader.OcrLine("账单详情", Rect(545, 2657, 720, 2703)))
    }

    private fun redPacketPaymentLines() = listOf(
        PaymentImageReader.OcrLine("支付成功", Rect(520,250,740,310)),
        PaymentImageReader.OcrLine("微信红包", Rect(500,520,760,580)),
        PaymentImageReader.OcrLine("¥3.00", Rect(440,900,825,1030)),
        PaymentImageReader.OcrLine("完成", Rect(580,2390,700,2440)))
    private fun redPacketDetailLines() = listOf(
        PaymentImageReader.OcrLine("测试发件人的红包",Rect(480,495,855,550)),
        PaymentImageReader.OcrLine("恭喜发财，大吉大利",Rect(440,595,840,645)),
        PaymentImageReader.OcrLine("红包金额3.00元，等待对方领取",Rect(50,825,680,865)),
        PaymentImageReader.OcrLine("未领取的红包，将于24小时后发起退款",Rect(250,2650,1015,2705)))
    private fun receivedRedPacketLines() = listOf(
        PaymentImageReader.OcrLine("测试发送方的红包",Rect(480,495,855,550)),
        PaymentImageReader.OcrLine("3.00元",Rect(450,850,800,960)),
        PaymentImageReader.OcrLine("已存入零钱，可直接使用",Rect(500,1050,950,1090)),
        PaymentImageReader.OcrLine("红包总额100.00元",Rect(100,1400,650,1450)),
        PaymentImageReader.OcrLine("¥10.00",Rect(900,1500,1150,1550)))

    private fun openRedPacket() {
        val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName="com.tencent.mm"
            className="android.widget.Button"
            text.add("开")
        }
        shadowOf(event).setWindowId(7)
        service.onAccessibilityEvent(event)
        event.recycle()
    }

    @Test fun `a user open gesture then own credited result captures a red packet and a second same amount claim`() {
        connect(enabled=true)
        repeat(2) { index ->
            window.root = { screen("领取红包\n开") }
            sendEvent(className=WechatRedPacketReader.RECEIVE_ACTIVITY)
            awaitStatus("not_receipt")
            openRedPacket()
            allowOpaqueCapture()
            service.opaqueVisualOcr = { receivedRedPacketLines() }
            sendEvent(className=WechatRedPacketReader.DETAIL_ACTIVITY)
            awaitCondition("own claimed red packet $index not captured") { rows().size==index+1 }
            assertTrue(rows().all { it.amount == -300L && it.type==TxType.INCOME && it.status==TxStatus.PENDING })
        }
        scheduler.advanceTimeBy(1_000)
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,className="android.view.View")
        awaitStatus("duplicate")
        assertEquals(2,rows().size)
        assertEquals(0,cloudCalls)
    }

    @Test fun `open text in chat and historical claimed details cannot start a red packet claim`() {
        connect(enabled=true)
        window.root = { screen("聊天",editableChild=true) }
        sendEvent(className="com.tencent.mm.ui.chatting.ChattingUI")
        awaitStatus("input_page")
        openRedPacket()
        allowOpaqueCapture()
        service.opaqueVisualOcr = { receivedRedPacketLines() }
        sendEvent(className=WechatRedPacketReader.DETAIL_ACTIVITY)
        awaitStatus("visual_requires_flow")
        assertTrue(rows().isEmpty())
    }

    @Test fun `readable historical red packet details also require a fresh claim rather than bypassing OCR history guard`() {
        connect(enabled=true)
        window.root = { screen("微信红包\n已存入零钱\n领取金额3元\n完成") }
        sendEvent(className=WechatRedPacketReader.DETAIL_ACTIVITY)
        awaitStatus("visual_requires_flow")
        assertTrue(rows().isEmpty())
    }

    @Test fun `red packet payment result and sender detail persist once through a verified fresh flow`() {
        connect(enabled = true)
        window.root = { screen("发红包", editableChild = true) }
        sendEvent(className = WechatRedPacketReader.PREPARE_ACTIVITY)
        awaitStatus("input_page")
        allowOpaqueCapture()
        service.opaqueVisualOcr = { redPacketPaymentLines() }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("saved")
        val first = rows().single()
        assertEquals(300L,first.amount)
        assertEquals("unknown",first.businessKind)
        assertTrue(first.metadataJson!!.contains("red_packet_sent"))
        service.opaqueVisualOcr = { redPacketDetailLines() }
        window.root = { screen("",visible=false,windowId=8) }
        sendEvent(windowId=8,className=WechatRedPacketReader.DETAIL_ACTIVITY)
        awaitStatus("duplicate")
        assertEquals(1,rows().size)
        // A new entry flow, even with the same amount and reused window, is a separate payment.
        window.root = { screen("发红包",editableChild=true) }
        sendEvent(className=WechatRedPacketReader.PREPARE_ACTIVITY)
        awaitStatus("input_page")
        allowOpaqueCapture()
        service.opaqueVisualOcr = { redPacketPaymentLines() }
        sendEvent(className=WechatOpaqueTransferReader.ACTIVITY)
        awaitCondition("second same-amount red packet swallowed") { rows().size==2 }
        assertEquals(0,cloudCalls)
    }

    @Test fun `opening old red packet detail or cancelling preparation never causes screenshot or insertion`() {
        connect(enabled=true)
        allowOpaqueCapture()
        var screenshots=0
        service.visualCapture = { screenshots++; Bitmap.createBitmap(1264,2780,Bitmap.Config.ARGB_8888) }
        service.opaqueVisualOcr = { redPacketDetailLines() }
        sendEvent(className=WechatRedPacketReader.DETAIL_ACTIVITY)
        awaitStatus("visual_requires_flow")
        assertEquals(0,screenshots)
        window.root = { screen("发红包",editableChild=true) }
        sendEvent(className=WechatRedPacketReader.PREPARE_ACTIVITY)
        awaitStatus("input_page")
        window.root = { screen("聊天",editableChild=true) }
        sendEvent(className="com.tencent.mm.ui.chatting.ChattingUI")
        awaitStatus("input_page")
        window.root = { screen("",visible=false) }
        sendEvent(className=WechatRedPacketReader.DETAIL_ACTIVITY)
        awaitStatus("visual_requires_flow")
        assertTrue(rows().isEmpty())
        assertEquals(0,screenshots)
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

    @Test fun `recent identified alipay receipt uses real time and historical or editable receipt stays excluded`() {
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
        awaitStatus("not_receipt")
        assertEquals(1,rows().size)
    }

    @Test fun `confirmed incoming empty root receipt saves pending income with financial time separate from observation`() {
        connect(enabled = true)
        allowOpaqueCapture()
        service.opaqueVisualOcr = { receivedLines() }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("saved")
        val row = rows().single()
        assertEquals(-500L, row.amount)
        assertEquals(TxType.INCOME, row.type)
        assertEquals(TxStatus.PENDING, row.status)
        assertEquals((startedAt - 20_000) / 1_000 * 1_000, row.occurredAt)
        assertEquals(startedAt, runBlocking { database.backupDao().captureEvents() }.single().observedAt)
        repeat(2) {
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
            awaitStatus("duplicate")
        }
        assertEquals(1, rows().size)
        assertEquals(0, cloudCalls)
    }

    @Test fun `readable confirmed receipt follows the same adapter without screenshots`() {
        connect(enabled = true)
        window.root = { screen(receivedLines().joinToString("\n") { it.text }) }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("saved")
        assertEquals(TxType.INCOME, rows().single().type)
        assertTrue(window.screenshotWindows.isEmpty())
    }

    @Test fun `pending receipt is ignored and own confirmation later captures through the same activity`() {
        connect(enabled = true)
        allowOpaqueCapture()
        var confirmed = false
        service.opaqueVisualOcr = { receivedLines().map {
            if (!confirmed && it.text.startsWith("你已")) it.copy(text = "待确认收款") else it
        } }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("visual_not_receipt")
        assertTrue(rows().isEmpty())
        scheduler.advanceTimeBy(1_000)
        confirmed = true
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
        awaitStatus("saved")
        assertEquals(TxType.INCOME, rows().single().type)
    }

    @Test fun `independent identical received amounts with different receipt times are both captured`() {
        connect(enabled = true)
        allowOpaqueCapture()
        var time = startedAt - 20_000
        service.opaqueVisualOcr = { receivedLines(time) }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("saved")
        scheduler.advanceTimeBy(1_000)
        time += 1_000
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
        awaitCondition("second same-amount receipt was swallowed") { rows().size == 2 }
        assertTrue(rows().all { it.type == TxType.INCOME && it.amount == -500L })
    }

    @Test fun `confirmation after pending OCR budget exhaustion is retried without another event`() {
        connect(enabled = true)
        allowOpaqueCapture()
        var confirmed = false
        var calls = 0
        service.opaqueVisualOcr = {
            calls++
            receivedLines().map {
                if (!confirmed && it.text.startsWith("你已")) it.copy(text = "待确认收款") else it
            }
        }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("visual_not_receipt")
        // DataStore settles on real executors. Pump bounded virtual steps so a callback counter
        // cannot make the next advance race ahead of scheduling its following retry.
        for (i in 0 until 50) {
            if (calls >= 3) break
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
            Thread.sleep(5)
        }
        awaitCondition("third pending screenshot") { calls >= 3 }
        scheduler.advanceTimeBy(500)
        confirmed = true
        sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
        // No further events. Wait through the bounded budget refill instead of dropping confirmation.
        repeat(20) {
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            Thread.sleep(10)
        }
        awaitCondition("confirmed receipt was lost after pending OCR exhausted its budget") { rows().size == 1 }
        assertEquals(TxType.INCOME, rows().single().type)
        assertTrue(calls <= 4)
    }

    @Test fun `old incoming receipts never create new income`() {
        connect(enabled = true)
        allowOpaqueCapture()
        service.opaqueVisualOcr = { receivedLines(startedAt - 31 * 60_000) }
        sendEvent(className = WechatReceivedTransferReader.ACTIVITY)
        awaitStatus("visual_not_receipt")
        drainRetries()
        assertTrue(rows().isEmpty())
    }

    @Test fun `incoming receipt cannot match the outgoing activity template`() {
        connect(enabled = true)
        allowOpaqueCapture()
        service.opaqueVisualOcr = { receivedLines() }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("visual_not_receipt")
        assertTrue(rows().isEmpty())
    }

    @Test fun `real phone empty root result is recovered locally and redraws save only one pending transfer`() {
        connect(enabled = true)
        allowOpaqueCapture()
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("saved")
        val row = rows().single()
        assertEquals(300L, row.amount)
        assertEquals(TxType.EXPENSE, row.type)
        assertEquals(TxStatus.PENDING, row.status)
        assertEquals("unknown", row.businessKind)
        assertTrue(row.metadataJson!!.contains("wechat-opaque-transfer"))
        repeat(2) {
            scheduler.advanceTimeBy(1_000)
            sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
            awaitStatus("duplicate")
        }
        assertEquals(1, rows().size)
        assertEquals(0, cloudCalls)
    }

    @Test fun `empty root never screenshots without the separate local visual switch or on a generic activity`() {
        connect(enabled = true)
        var screenshots = 0
        service.visualCapture = { screenshots++; null }
        window.root = { screen("", visible = false) }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("visual_required")
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        sendEvent(className = "com.tencent.mm.ui.LauncherUI")
        awaitStatus("empty_page")
        drainRetries()
        assertEquals(0, screenshots)
        assertTrue(rows().isEmpty())
    }

    @Test fun `opaque capture uses the exact window and screenshot failure cannot reach OCR or database`() {
        connect(enabled = true)
        runBlocking { preferences.setScreenVisualCaptureEnabled(true) }
        window.root = { screen("", visible = false) }
        var ocrCalls = 0
        service.opaqueVisualOcr = { ocrCalls++; opaqueLines() }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("visual_unavailable")
        assertEquals(listOf(7), window.screenshotWindows)
        assertEquals(0, ocrCalls)
        assertTrue(rows().isEmpty())
    }

    @Test fun `leaving the opaque window or revoking visual consent discards in flight OCR`() {
        connect(enabled = true)
        allowOpaqueCapture()
        service.opaqueVisualOcr = {
            window.root = { screen("聊天", windowId = 8, editableChild = true) }
            sendEvent(windowId = 8, className = "com.tencent.mm.ui.LauncherUI")
            opaqueLines()
        }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("input_page")
        assertTrue(rows().isEmpty())
        allowOpaqueCapture()
        scheduler.advanceTimeBy(1_000)
        service.opaqueVisualOcr = {
            preferences.setScreenVisualCaptureEnabled(false)
            opaqueLines()
        }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `opaque pending password history or extra chat text cannot become a transaction`() {
        connect(enabled = true)
        allowOpaqueCapture()
        service.opaqueVisualOcr = {
            opaqueLines() + PaymentImageReader.OcrLine("请输入支付密码", Rect(100, 1400, 600, 1450))
        }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("visual_not_receipt")
        drainRetries()
        assertTrue(rows().isEmpty())
        assertEquals(0, cloudCalls)
    }

    @Test fun `two identical opaque transfers separated by a new transfer flow are both captured`() {
        connect(enabled = true)
        allowOpaqueCapture()
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("saved")
        window.root = { screen("转账", windowId = 8, editableChild = true) }
        sendEvent(windowId = 8, className = "com.tencent.mm.plugin.remittance.ui.RemittanceUI")
        awaitStatus("input_page")
        window.root = { screen("", visible = false) }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("saved")
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.amount == 300L && it.status == TxStatus.PENDING })
    }

    @Test fun `new opaque frame invalidates an older image despite identical empty node facts`() {
        connect(enabled = true)
        allowOpaqueCapture()
        var calls = 0
        service.opaqueVisualOcr = {
            calls++
            if (calls == 1) {
                sendEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, className = "android.view.View")
                opaqueLines("¥3.00")
            } else opaqueLines("¥4.00")
        }
        sendEvent(className = WechatOpaqueTransferReader.ACTIVITY)
        awaitStatus("saved")
        assertEquals(2, calls)
        assertEquals(400L, rows().single().amount)
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

    private fun rows() = runBlocking { database.backupDao().transactions() }

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
    val screenshotWindows = mutableListOf<Int>()

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

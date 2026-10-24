package com.pennypilot.app.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context)
    private var cloudCalls = 0
    private val cloud = object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            cloudCalls++
            error("本地凭证不得调用云端")
        }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("not used")
    }

    @Test fun `both channels persist once for same order without cloud or notification permission`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            preferences.setNotificationCaptureEnabled(true)
            preferences.setNotificationCloudParsingEnabled(true)
            preferences.setScreenCaptureEnabled(true)
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            val monitor = CaptureServiceMonitor()
            val processor = CaptureProcessor(context, database, preferences, cloud, monitor)
            val now = System.currentTimeMillis()
            processor.processNotification(CapturedNotification("com.sankuai.meituan", "test-1", now, "美团支付",
                "支付成功，实付18.00元，商户：麦当劳，订单号：20261023000001"), LedgerRevision.current)
            assertTrue(processor.processScreen("com.sankuai.meituan", NotificationParseResult(1800, "麦当劳", "EXPENSE",
                orderId = "20261023000001"), "page-test-1", now + 1000, LedgerRevision.current))
            val rows = database.backupDao().transactions()
            assertEquals(1, rows.size)
            assertEquals(TxStatus.PENDING, rows.single().status)
            assertEquals(1, monitor.health.value.todayCaptureCount)
            assertEquals(1, monitor.health.value.duplicateCount)
            assertEquals(0, cloudCalls)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        } finally {
            preferences.setNotificationCloudParsingEnabled(false)
            preferences.setScreenCaptureEnabled(false)
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
            database.close()
        }
    }

    @Test fun `paused channels do not write and screen raw payload contains receipt fields only`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor())
            preferences.setNotificationCaptureEnabled(false)
            preferences.setScreenCaptureEnabled(false)
            val now = System.currentTimeMillis()
            processor.processNotification(CapturedNotification("com.sankuai.meituan", "test-2", now, "支付成功", "实付18元"), LedgerRevision.current)
            val payment = NotificationParseResult(1800, "咖啡店", "EXPENSE")
            assertFalse(processor.processScreen("com.sankuai.meituan", payment, "page-test-2", now, LedgerRevision.current))
            assertTrue(database.backupDao().transactions().isEmpty())
            preferences.setScreenCaptureEnabled(true)
            assertTrue(processor.processScreen("com.sankuai.meituan", payment, "page-test-2", now, LedgerRevision.current))
            val row = database.backupDao().transactions().single()
            assertEquals(TxSource.ACCESSIBILITY, row.source)
            assertEquals("支付成功 18.00元\n商户：咖啡店", row.rawPayload)
            assertEquals(0, cloudCalls)
        } finally {
            preferences.setNotificationCaptureEnabled(true)
            preferences.setScreenCaptureEnabled(false)
            database.close()
        }
    }
    @Test fun `automatic recording keeps unknown transfers pending without quick confirm`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            preferences.setScreenCaptureEnabled(true)
            preferences.setCaptureAutoRecordEnabled(true)
            shadowOf(manager).setNotificationsEnabled(true)
            NotificationHelper.createChannels(context)
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor())
            val now = System.currentTimeMillis()
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(1800, "小林", "EXPENSE", businessKind = "unknown"), "unknown-transfer", now, LedgerRevision.current))
            assertEquals(TxStatus.PENDING, database.backupDao().transactions().single().status)
            val notification = manager.activeNotifications.single { it.tag == "unknown-transfer" }.notification
            assertFalse(notification.actions.orEmpty().any { it.title.toString() == "记入" })
            assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("核对用途"))
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(2800, "咖啡店", "EXPENSE", businessKind = "expense"), "known-consumption", now, LedgerRevision.current))
            assertEquals(TxStatus.CONFIRMED, database.backupDao().transactions().single { it.captureEventKey == "known-consumption" }.status)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            manager.cancelAll()
            database.close()
        }
    }

    @Test fun `conflicting same event reports conflict without replacing financial facts`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            preferences.setScreenCaptureEnabled(true)
            val monitor = CaptureServiceMonitor()
            val processor = CaptureProcessor(context, database, preferences, cloud, monitor)
            val now = System.currentTimeMillis()
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(1800, "小林", "EXPENSE"), "conflicting-event", now, LedgerRevision.current))
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(2800, "小林", "EXPENSE"), "conflicting-event", now, LedgerRevision.current))
            assertEquals(1800L, database.backupDao().transactions().single().amount)
            assertTrue(monitor.health.value.lastError.orEmpty().contains("不同金额"))
            assertEquals("model_rejected", monitor.health.value.screenCaptureStatus)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            context.getSystemService(NotificationManager::class.java).cancelAll()
            database.close()
        }
    }

}

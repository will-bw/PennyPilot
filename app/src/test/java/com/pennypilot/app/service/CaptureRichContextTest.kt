package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
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
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.recognition.RecognitionTaskStore
import com.pennypilot.app.data.recognition.TaskCipher
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureRichContextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context)
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val file = File.createTempFile("rich-capture", ".enc").apply { delete() }
    private val store = RecognitionTaskStore(file, object : TaskCipher {
        override fun encrypt(bytes: ByteArray) = bytes
        override fun decrypt(bytes: ByteArray) = bytes
    })
    private var calls = 0
    private var messages: List<Message> = emptyList()
    private var duringRequest: (suspend () -> Unit)? = null
    private val cloud = object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            calls++
            this@CaptureRichContextTest.messages = messages
            duringRequest?.also { duringRequest = null }?.invoke()
            return """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"18.00元","direction_evidence":"支付成功","status_evidence":"支付成功","business_kind":"expense","category_id":2,"note":"午餐"}"""
        }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("unused")
    }
    private val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor(), store)
    private val now = System.currentTimeMillis()
    private fun receipt(rich: Boolean = false) = NotificationParseResult(1800, "示例商家", "EXPENSE",
        orderId = "WX20261107000001", scene = "merchant_payment", details = PaymentReceiptDetails(
            merchantName = "示例商家",
            context = if (rich) listOf(PaymentReceiptContext("门店名称", "示例广场店", "页面"),
                PaymentReceiptContext("商品明细", "鸡腿饭 × 1\n少辣，明天自取", "页面")) else emptyList()))

    private fun fixture(block: suspend () -> Unit) = runBlocking {
        try {
            database.categoryDao().insert(CategoryEntity(id = 1, name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            database.categoryDao().insert(CategoryEntity(id = 2, name = "餐饮", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 1))
            preferences.setScreenCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(true)
            preferences.setNotificationCaptureEnabled(true)
            preferences.setNotificationCloudParsingEnabled(true)
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
            NotificationHelper.createChannels(context)
            block()
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setNotificationCloudParsingEnabled(false)
            database.close()
            file.delete()
        }
    }

    @Test fun `new receipt context reclassifies once after first AI result and remains one order`() = fixture {
        processor.processScreen("com.tencent.mm", receipt(), "rich-page", now, LedgerRevision.current)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        assertEquals(1, calls)
        val originalId = database.backupDao().transactions().single().id
        processor.processScreen("com.tencent.mm", receipt(true), "rich-page", now + 100, LedgerRevision.current)
        val updated = store.pending().single()
        assertEquals(2, updated.receipt!!.context.size)
        assertFalse(updated.text.contains("明天"))
        assertTrue(processor.processRecognitionTask(updated.id))
        assertTrue(messages.last().content.contains("示例广场店"))
        assertTrue(messages.last().content.contains("鸡腿饭"))
        assertEquals(2, calls)
        processor.processScreen("com.tencent.mm", receipt(true), "rich-page", now + 200, LedgerRevision.current)
        processor.processScreen("com.tencent.mm", receipt(), "rich-page", now + 300, LedgerRevision.current)
        assertTrue(store.pending().isEmpty())
        val saved = database.backupDao().transactions().single()
        assertEquals(originalId, saved.id)
        assertEquals(1800L, saved.amount)
        assertEquals(2, Json.parseToJsonElement(saved.metadataJson!!).jsonObject["receiptContext"]!!.jsonArray.size)
    }

    @Test fun `verified payment notifies before AI and enrichment updates silently without resurrecting dismissed alert`() = fixture {
        val manager = context.getSystemService(NotificationManager::class.java)
        processor.processScreen("com.tencent.mm", receipt(), "instant-page", now, LedgerRevision.current)
        val initial = manager.activeNotifications.single { it.tag == "instant-page" }
        assertEquals(NotificationHelper.CHANNEL_CAPTURE, initial.notification.channelId)
        assertEquals(0, initial.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(0, calls)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        val enriched = manager.activeNotifications.single { it.tag == "instant-page" }
        assertEquals(initial.id, enriched.id)
        assertTrue(enriched.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val trace = CaptureLatencyTrace.snapshot(context).single { CaptureLatencyTrace.Stage.SAVED in it.stages }
        assertTrue(trace.stages.getValue(CaptureLatencyTrace.Stage.NOTIFICATION_SUBMITTED).firstAt <=
            trace.stages.getValue(CaptureLatencyTrace.Stage.STARTED).firstAt)
        manager.cancel(initial.tag, initial.id)
        processor.processScreen("com.tencent.mm", receipt(true), "instant-page", now + 1, LedgerRevision.current)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(1, database.backupDao().transactions().size)
    }

    @Test fun `candidate acknowledges immediately without creating a ledger until AI validation`() = fixture {
        val manager = context.getSystemService(NotificationManager::class.java)
        processor.processUnrecognizedScreen("com.tencent.mm", "支付成功\n实付18.00元\n完成", "pending-page", now, LedgerRevision.current)
        val pending = manager.activeNotifications.single().notification
        assertEquals("正在识别支付信息", pending.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(pending.extras.getString(Notification.EXTRA_TEXT)!!.contains("尚未记账"))
        assertTrue(database.backupDao().transactions().isEmpty())
        assertEquals(0, calls)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        assertEquals(1, database.backupDao().transactions().size)
        val completed = manager.activeNotifications.single()
        assertEquals("pending-page", completed.tag)
        assertTrue(completed.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test fun `local only page fields never travel through notification consent`() = fixture {
        preferences.setScreenCloudParsingEnabled(false)
        processor.processNotification(CapturedNotification("com.tencent.mm", "source-notice", now,
            "微信支付", "支付成功，实付18.00元，订单号：WX20261107000001"), LedgerRevision.current)
        processor.processScreen("com.tencent.mm", receipt(true), "local-page", now + 100, LedgerRevision.current)
        val task = store.pending().single()
        assertTrue(task.receipt!!.context.isEmpty())
        assertTrue(processor.processRecognitionTask(task.id))
        assertFalse(messages.last().content.contains("示例广场店"))
        assertFalse(messages.last().content.contains("鸡腿饭"))
        assertFalse(messages.last().content.contains("示例商家"))
        assertTrue(database.backupDao().transactions().single().metadataJson!!.contains("鸡腿饭"))
    }

    @Test fun `new details during request invalidate old response and preserve successor`() = fixture {
        processor.processScreen("com.tencent.mm", receipt(), "race-page", now, LedgerRevision.current)
        val first = store.pending().single()
        duringRequest = {
            processor.processScreen("com.tencent.mm", receipt(true), "race-page", now + 100, LedgerRevision.current)
        }
        assertTrue(processor.processRecognitionTask(first.id))
        val successor = store.pending().single()
        assertTrue(successor.evidenceVersion > first.evidenceVersion)
        assertFalse(database.backupDao().transactions().single().metadataJson!!.contains("semanticModelApplied"))
        assertTrue(processor.processRecognitionTask(successor.id))
        assertEquals(2, calls)
        assertEquals(1, database.backupDao().transactions().size)
    }

    @Test fun `same order authorized sources are merged and revocation blocks the snapshot`() = fixture {
        processor.processNotification(CapturedNotification("com.tencent.mm", "merge-notice", now,
            "微信支付", "支付成功，实付18.00元，商品说明：到店午餐，订单号：WX20261107000001"), LedgerRevision.current)
        processor.processScreen("com.tencent.mm", receipt(true), "merge-page", now + 100, LedgerRevision.current)
        val task = store.pending().first { it.source == "ACCESSIBILITY" }
        assertEquals("到店午餐", task.receipt!!.details["productDescription"])
        assertTrue(task.receipt!!.context.any { it.value.contains("示例广场店") })
        preferences.setNotificationCloudParsingEnabled(false)
        assertTrue(processor.processRecognitionTask(task.id))
        assertEquals(0, calls)
        processor.processScreen("com.tencent.mm", receipt(true), "merge-page", now + 200, LedgerRevision.current)
        val replacement = store.pending().first { it.source == "ACCESSIBILITY" }
        assertNull(replacement.receipt!!.details["productDescription"])
        assertTrue(processor.processRecognitionTask(replacement.id))
        assertFalse(messages.last().content.contains("到店午餐"))
        assertTrue(messages.last().content.contains("示例广场店"))
    }

    @Test fun `legacy counterparty arriving later reclassifies but model does not create new captured facts`() = fixture {
        val sparse = NotificationParseResult(1800, null, "EXPENSE", scene = "merchant_payment",
            details = PaymentReceiptDetails(context = (1..16).map { PaymentReceiptContext("商品规格", "示例规格$it") }))
        processor.processScreen("com.tencent.mm", sparse, "legacy-party", now, LedgerRevision.current)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        processor.processScreen("com.tencent.mm", sparse, "legacy-party", now + 100, LedgerRevision.current)
        assertTrue("model-created paymentChannel must not alter the captured snapshot", store.pending().isEmpty())
        val complete = sparse.copy(merchant = "示例经营部")
        processor.processScreen("com.tencent.mm", complete, "legacy-party", now + 200, LedgerRevision.current)
        val task = store.pending().single()
        assertTrue(task.receipt!!.context.any { it.label == "交易对方" && it.value == "示例经营部" })
        assertFalse(task.receipt!!.details.containsKey("merchantName"))
        assertTrue(processor.processRecognitionTask(task.id))
        processor.processScreen("com.tencent.mm", complete, "legacy-party", now + 300, LedgerRevision.current)
        assertTrue(store.pending().isEmpty())
        assertEquals(2, calls)
        assertEquals(1, database.backupDao().transactions().size)
    }
}

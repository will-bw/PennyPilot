package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private var modelReply = """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"18.00元","direction_evidence":"支付成功","status_evidence":"支付成功","business_kind":"expense","category_id":2,"note":"午餐"}"""
    private val cloud = object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            calls++
            this@CaptureRichContextTest.messages = messages
            duringRequest?.also { duringRequest = null }?.invoke()
            return modelReply
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

    @Test fun `financial detail fields survive persistence merge and semantic context without changing paid amount`() = fixture {
        val original = receipt().copy(details = PaymentReceiptDetails(
            originalAmountCents = 2000, discountAmountCents = 200,
            transferFromAccount = "余额", fieldSources = mapOf("discountAmountCents" to "页面·优惠金额")))
        processor.processScreen("com.tencent.mm", original, "financial-details", now, LedgerRevision.current,
            queueSemanticModel = false)
        val enriched = original.copy(details = PaymentReceiptDetails(
            originalAmountCents = 9999, discountAmountCents = 999, feeAmountCents = 0,
            receivedAmountCents = 1800, transferToAccount = "储蓄卡（1234）",
            fieldSources = mapOf("feeAmountCents" to "云端视觉·手续费")))
        processor.processScreen("com.tencent.mm", enriched, "financial-details", now + 1, LedgerRevision.current,
            queueSemanticModel = false)
        val saved = database.backupDao().transactions().single()
        assertEquals(1800L, saved.amount)
        val details = Json.parseToJsonElement(saved.metadataJson!!).jsonObject["receiptDetails"]!!.jsonObject
        assertEquals(JsonPrimitive(2000), details["originalAmountCents"])
        assertEquals(JsonPrimitive(200), details["discountAmountCents"])
        assertEquals(JsonPrimitive(0), details["feeAmountCents"])
        assertEquals(JsonPrimitive(1800), details["receivedAmountCents"])
        assertEquals(JsonPrimitive("余额"), details["transferFromAccount"])
        assertEquals(JsonPrimitive("储蓄卡（1234）"), details["transferToAccount"])
        val semantic = CaptureReceiptContext.fromEvidence(database.transactionDao().evidenceForTransaction(saved.id)).bounded()
        assertEquals("1800", semantic.details["receivedAmountCents"])
        assertEquals("余额", semantic.details["transferFromAccount"])
    }

    @Test fun `disabling an application prevents new capture and invalidates queued recognition`() = fixture {
        processor.processScreen("com.tencent.mm", receipt(), "selected-app", now, LedgerRevision.current)
        val task = store.pending().single()
        preferences.setCaptureAppPackages(emptySet())
        try {
            assertFalse(processor.recognitionTaskAllowed(task))
            assertFalse(processor.processScreen("com.tencent.mm", receipt(), "disabled-app", now + 1, LedgerRevision.current))
            assertTrue(processor.processRecognitionTask(task.id))
            assertEquals(0, calls)
            assertEquals(1, database.backupDao().transactions().size)
        } finally {
            preferences.setCaptureAppPackages(com.pennypilot.app.data.parser.PaymentAppRegistry.packages)
        }
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

    @Test fun `already verified receipt accepts category without another financial extraction`() = fixture {
        val original = receipt().copy(merchant = "计划经营店", details = PaymentReceiptDetails(merchantName = "计划经营店"))
        modelReply = """{"business_kind":"expense","category_id":2,"note":"牛肉面","amount_cents":9900,"direction":"INCOME","merchant":"编造的名称"}"""
        assertTrue(processor.processScreen("com.tencent.mm", original, "verified-semantic", now, LedgerRevision.current))
        val before = database.backupDao().transactions().single()
        assertEquals(1L, before.categoryId)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        val after = database.backupDao().transactions().single()
        assertEquals(before.id, after.id)
        assertEquals(1800L, after.amount)
        assertEquals(TxType.EXPENSE, after.type)
        assertEquals("计划经营店", after.merchant)
        assertEquals(2L, after.categoryId)
        assertEquals("牛肉面", after.note)
        assertEquals(original.orderId, database.transactionDao().captureEvidenceByKey("verified-semantic")!!.orderId)
    }

    @Test fun `semantic only reply never records an unverified candidate`() = fixture {
        modelReply = """{"business_kind":"expense","category_id":2,"note":"午餐"}"""
        processor.processUnrecognizedScreen("com.tencent.mm", "支付成功\n实付18.00元\n完成",
            "unverified-semantic", now, LedgerRevision.current)
        val queued = store.pending().single()
        assertNull(queued.semanticFingerprint)
        assertTrue(processor.processRecognitionTask(queued.id))
        assertEquals(1, calls)
        assertTrue(database.backupDao().transactions().isEmpty())
    }

    @Test fun `generic reply is one attempt and cannot block richer local classification`() = fixture {
        modelReply = """{"business_kind":"expense","category_id":1,"note":"已有用途摘要"}"""
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "generic-semantic", now, LedgerRevision.current))
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        val generic = database.backupDao().transactions().single()
        assertEquals(1L, generic.categoryId)
        val metadata = Json.parseToJsonElement(generic.metadataJson!!).jsonObject
        assertTrue(metadata.containsKey("semanticAttemptedFingerprint"))
        assertFalse(metadata.containsKey("semanticEvidenceFingerprint"))
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "generic-semantic", now + 100, LedgerRevision.current))
        assertTrue(store.pending().isEmpty())
        assertTrue(processor.processScreen("com.tencent.mm", receipt(true), "generic-semantic", now + 200, LedgerRevision.current))
        val upgraded = database.backupDao().transactions().single()
        assertEquals(generic.id, upgraded.id)
        assertEquals(2L, upgraded.categoryId)
        assertEquals(generic.note, upgraded.note)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        assertEquals(2L, database.backupDao().transactions().single().categoryId)
    }

    @Test fun `later sparse order alias cannot erase specific category`() = fixture {
        preferences.setScreenCloudParsingEnabled(false)
        assertTrue(processor.processScreen("com.tencent.mm", receipt(true), "rich-order-alias", now, LedgerRevision.current))
        val rich = database.backupDao().transactions().single()
        assertEquals(2L, rich.categoryId)
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "sparse-order-alias", now + 100, LedgerRevision.current))
        val replay = database.backupDao().transactions().single()
        assertEquals(rich.id, replay.id)
        assertEquals(rich.categoryId, replay.categoryId)
    }

    @Test fun `legacy generic model completion is retried on the same verified receipt once`() = fixture {
        modelReply = """{"business_kind":"expense","category_id":1}"""
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "legacy-generic", now, LedgerRevision.current))
        val queued = store.pending().single()
        assertTrue(processor.processRecognitionTask(queued.id))
        val before = database.backupDao().transactions().single()
        val metadata = Json.parseToJsonElement(before.metadataJson!!).jsonObject.toMutableMap()
        metadata.remove("semanticAttemptedFingerprint")
        metadata.remove("semanticCategoryFallback")
        metadata["semanticEvidenceFingerprint"] = JsonPrimitive(queued.semanticFingerprint!!)
        database.transactionDao().update(before.copy(metadataJson = JsonObject(metadata).toString()))

        modelReply = """{"business_kind":"expense","category_id":2,"note":"午餐"}"""
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "legacy-generic", now + 100, LedgerRevision.current))
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        val upgraded = database.backupDao().transactions().single()
        assertEquals(before.id, upgraded.id)
        assertEquals(2L, upgraded.categoryId)
        assertTrue(processor.processScreen("com.tencent.mm", receipt(), "legacy-generic", now + 200, LedgerRevision.current))
        assertTrue(store.pending().isEmpty())
        assertEquals(2, calls)
    }

    @Test fun `uncertain model or replay cannot downgrade an already verified income kind`() = fixture {
        database.categoryDao().insert(CategoryEntity(id = 3, name = "其他", icon = "", colorIndex = 0, type = TxType.INCOME, sortOrder = 0))
        modelReply = """{"business_kind":"unknown","category_id":null}"""
        val income = NotificationParseResult(1800, "示例付款方", "INCOME", businessKind = "income")
        assertTrue(processor.processScreen("com.tencent.mm", income, "known-income", now, LedgerRevision.current))
        assertTrue(processor.processScreen("com.tencent.mm", income.copy(businessKind = "unknown"),
            "known-income", now + 100, LedgerRevision.current))
        assertEquals("income", database.backupDao().transactions().single().businessKind)
        assertTrue(processor.processRecognitionTask(store.pending().single().id))
        val stored = database.backupDao().transactions().single()
        assertEquals(-1800L, stored.amount)
        assertEquals("income", stored.businessKind)
        val metadata = Json.parseToJsonElement(stored.metadataJson!!).jsonObject
        assertEquals(JsonPrimitive("income"), metadata["semanticBusinessKind"])
    }

    @Test fun `local checkout description affects local category but is never included in cloud context`() = fixture {
        val base = receipt()
        val withCheckout = base.copy(details = base.details.copy(context = listOf(
            PaymentReceiptContext("商品名称", "鸡腿饭", CheckoutContext.LOCAL_SOURCE),
            PaymentReceiptContext("商品规格", "份量大", "页面"))))
        assertTrue(processor.processScreen("com.tencent.mm", withCheckout, "checkout-local", now, LedgerRevision.current))
        assertEquals(2L, database.backupDao().transactions().single().categoryId)
        val queued = store.pending().single()
        assertEquals(listOf("份量大"), queued.receipt!!.context.map { it.value })
        assertTrue(processor.processRecognitionTask(queued.id))
        assertTrue(messages.last().content.contains("份量大"))
        assertFalse(messages.last().content.contains("鸡腿饭"))
        assertFalse(messages.last().content.contains(CheckoutContext.LOCAL_SOURCE))
        assertTrue(database.backupDao().transactions().single().metadataJson!!.contains("鸡腿饭"))
    }

    @Test fun `live verified notification enriches local checkout without giving its text to notification AI`() = fixture {
        val notice = walletNoticeWithCheckout()
        val arrival = CheckoutContext.LiveNotification(now, SystemClock.elapsedRealtime())
        processor.processNotification(notice, LedgerRevision.current, arrival)
        val saved = database.backupDao().transactions().single()
        assertEquals(1800L, saved.amount)
        assertTrue(saved.metadataJson!!.contains("本机订单牛肉面"))
        val task = store.pending().single()
        assertFalse(task.text.contains("本机订单牛肉面"))
        assertTrue(task.receipt!!.context.none { it.source == CheckoutContext.LOCAL_SOURCE })
        assertTrue(processor.processRecognitionTask(task.id))
        assertFalse(messages.last().content.contains("本机订单牛肉面"))
        assertEquals(saved.id, database.backupDao().transactions().single().id)
    }

    @Test fun `recovered or page disabled notification keeps normal independent capture`() = fixture {
        val notice = walletNoticeWithCheckout()
        processor.processNotification(notice, LedgerRevision.current)
        val before = database.backupDao().transactions().single()
        assertFalse(before.metadataJson!!.contains("本机订单牛肉面"))
        preferences.setScreenCaptureEnabled(false)
        val arrival = CheckoutContext.LiveNotification(now, SystemClock.elapsedRealtime())
        processor.processNotification(notice.copy(notificationKey = "checkout-page-disabled"), LedgerRevision.current, arrival)
        val after = database.backupDao().transactions().single()
        assertEquals(before.id, after.id)
        assertFalse(after.metadataJson!!.contains("本机订单牛肉面"))
    }

    private fun walletNoticeWithCheckout(): CapturedNotification {
        val wallet = "com.tencent.mm"
        val order = "CHECKOUT202611070123"
        val elapsed = SystemClock.elapsedRealtime()
        processor.checkoutContext.remember(CheckoutContext.Preview("com.sankuai.meituan", order, 1800L, "示例经营部",
            listOf(PaymentReceiptContext("商品名称", "本机订单牛肉面", CheckoutContext.LOCAL_SOURCE))),
            LedgerRevision.current, elapsed - 1_000L)
        processor.checkoutContext.onWindowChanged(wallet, LedgerRevision.current, elapsed - 500L, now - 500L)
        return CapturedNotification(wallet, "checkout-live", now, "微信支付",
            "支付成功\n实付18.00元\n商户名称：示例经营部\n商户订单号：$order")
    }
}

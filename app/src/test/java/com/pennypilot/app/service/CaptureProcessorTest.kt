package com.pennypilot.app.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.data.recognition.PaymentProposal
import com.pennypilot.app.data.recognition.PaymentProposalValidator
import com.pennypilot.app.data.recognition.RecognitionTask
import com.pennypilot.app.data.recognition.RecognitionTaskStore
import com.pennypilot.app.data.recognition.TaskCipher
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context)
    private var cloudCalls = 0
    private var cloudResponse: String? = null
    private var sentMessages: List<Message> = emptyList()
    private val cloud = object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            cloudCalls++
            sentMessages = messages
            cloudResponse?.let { return it }
            error("本地凭证不得调用云端")
        }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("not used")
    }

    @Test fun `same refund from notification and page merges but a second installment and payment namespace stay separate`() = runBlocking {
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name="其他",icon="",colorIndex=0,type=TxType.INCOME,sortOrder=0))
            preferences.setNotificationCaptureEnabled(true)
            preferences.setNotificationCloudParsingEnabled(false)
            preferences.setScreenCaptureEnabled(true)
            preferences.setCaptureAutoRecordEnabled(false)
            val processor=CaptureProcessor(context,database,preferences,cloud,CaptureServiceMonitor())
            val now=System.currentTimeMillis()
            val text="退款已到账\n本次退款金额5元\n原支付金额30元\n交易单号PAY202610240001\n退款单号RF202610240001\n完成"
            processor.processNotification(CapturedNotification("com.tencent.mm","refund-notice",now,"微信支付",text),LedgerRevision.current)
            val parsed=PaymentTextParser.analyzeScreen("com.tencent.mm",text).parsed!!
            processor.processScreen("com.tencent.mm",parsed,"refund-page",now+1000,LedgerRevision.current)
            assertEquals(1,database.backupDao().transactions().size)
            assertTrue(database.backupDao().captureEvents().all { it.orderDomain=="wechat:refund" })
            assertTrue(database.backupDao().transactions().single().metadataJson!!.contains("refundId"))
            val second=PaymentTextParser.analyzeScreen("com.tencent.mm",text.replace("RF202610240001","RF202610240002")).parsed!!
            processor.processScreen("com.tencent.mm",second,"refund-second",now+2000,LedgerRevision.current)
            assertEquals(2,database.backupDao().transactions().size)
            processor.processScreen("com.tencent.mm",NotificationParseResult(500,null,"INCOME",orderId="RF202610240001"),
                "different-payment-namespace",now+3000,LedgerRevision.current)
            assertEquals(3,database.backupDao().transactions().size)
            assertEquals(0,cloudCalls)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setNotificationCaptureEnabled(true)
            database.close()
        }
    }

    @Test fun `both channels persist once for same order without cloud or notification permission`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            preferences.setNotificationCaptureEnabled(true)
            preferences.setNotificationCloudParsingEnabled(true)
            preferences.setScreenCaptureEnabled(true)
            preferences.setCaptureAutoRecordEnabled(false)
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
    @Test fun `automatic recording preserves unknown transfer as money movement without inflating expense`() = runBlocking {
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
            val transfer = database.backupDao().transactions().single()
            assertEquals(TxStatus.CONFIRMED, transfer.status)
            assertEquals("transfer", transfer.businessKind)
            assertEquals("微信", transfer.accountTag)
            val notification = manager.activeNotifications.single { it.tag == "unknown-transfer" }.notification
            assertFalse(notification.actions.orEmpty().any { it.title.toString() == "记入" })
            assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("不计入消费"))
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(2800, "咖啡店", "EXPENSE", businessKind = "expense"), "known-consumption", now, LedgerRevision.current))
            assertEquals(TxStatus.CONFIRMED, database.backupDao().transactions().single { it.captureEventKey == "known-consumption" }.status)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            manager.cancelAll()
            database.close()
        }
    }

    @Test fun `local transfer uses confirmed merchant history and account before automatic recording`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val food = database.categoryDao().insert(CategoryEntity(name = "餐饮", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 1))
            preferences.setScreenCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(true)
            val now = System.currentTimeMillis()
            repeat(2) { index ->
                database.transactionDao().insert(TransactionEntity(amount = 1800, type = TxType.EXPENSE,
                    categoryId = food, merchant = "小林", accountTag = "招商银行", note = "午饭",
                    occurredAt = now - (index + 1) * 86_400_000L, businessKind = "expense"))
            }
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor())
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(1700, "小林", "EXPENSE",
                scene = "transfer_sent"), "history-transfer", now, LedgerRevision.current))
            val saved = database.transactionDao().getByCaptureEvent("history-transfer")!!
            assertEquals(TxStatus.CONFIRMED, saved.status)
            assertEquals("expense", saved.businessKind)
            assertEquals(food, saved.categoryId)
            assertEquals("招商银行", saved.accountTag)
            assertTrue(saved.metadataJson!!.contains("稳定记账习惯"))
            assertEquals(0, cloudCalls)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            database.close()
        }
    }

    @Test fun `model semantics override provisional history and enrich the same captured event`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            val food = database.categoryDao().insert(CategoryEntity(name = "餐饮", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 1))
            preferences.setScreenCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(true)
            val now = System.currentTimeMillis()
            repeat(2) { index ->
                database.transactionDao().insert(TransactionEntity(amount = 1700, type = TxType.EXPENSE,
                    categoryId = 1, merchant = "小林", occurredAt = now - (index + 1) * 86_400_000L,
                    source = TxSource.ACCESSIBILITY, businessKind = "transfer", note = "用途暂按资金转移记录",
                    metadataJson = """{"assumptions":["用途暂按资金转移，不计入消费或收入"]}"""))
            }
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor())
            val original = NotificationParseResult(1700, "小林", "EXPENSE", scene = "transfer_sent")
            assertTrue(processor.processScreen("com.tencent.mm", original, "model-enrichment", now, LedgerRevision.current))
            val before = database.transactionDao().getByCaptureEvent("model-enrichment")!!
            assertEquals("transfer", before.businessKind)
            val text = "转账成功\n17.00元\n商户：小林"
            val proposal = PaymentProposal(valid = true, amountCents = 1700, direction = "EXPENSE", currency = "CNY",
                paymentStatus = "COMPLETED", amountEvidence = "17.00元", directionEvidence = "转账成功", statusEvidence = "转账成功",
                merchant = "小林", merchantEvidence = "商户：小林", businessKind = "expense", categoryId = food,
                accountTag = "微信", note = "晚餐", assumptions = listOf("按支付场景判断为餐饮"))
            val parsed = PaymentProposalValidator.validate("com.tencent.mm", text, proposal)!!
            preferences.setScreenCloudParsingEnabled(true)
            val task = RecognitionTask("model-test", "model-enrichment", "com.tencent.mm", text, now,
                LedgerRevision.current, cloud.operationIdentity(), "ACCESSIBILITY")
            assertTrue(processor.completeRecognition(task, proposal, parsed) { true })
            val saved = database.transactionDao().getByCaptureEvent("model-enrichment")!!
            assertEquals(3, database.backupDao().transactions().size)
            assertEquals(before.id, saved.id)
            assertEquals(before.amount, saved.amount)
            assertEquals(TxStatus.CONFIRMED, saved.status)
            assertEquals("expense", saved.businessKind)
            assertEquals(food, saved.categoryId)
            assertEquals("晚餐", saved.note)
            assertTrue(saved.metadataJson!!.contains("semanticModelApplied"))
            database.transactionDao().update(saved.copy(categoryId = before.categoryId, note = "自己修改", editVersion = 1))
            assertTrue(processor.completeRecognition(task, proposal, parsed) { true })
            assertEquals("自己修改", database.transactionDao().getById(saved.id)!!.note)
            assertEquals(before.categoryId, database.transactionDao().getById(saved.id)!!.categoryId)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            database.close()
        }
    }

    @Test fun `local receipt queues semantic AI then completes the same record with real category context`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("capture-semantics", ".enc").apply { delete() }
        val cipher = object : TaskCipher {
            override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 63).toByte() }.toByteArray()
            override fun decrypt(bytes: ByteArray) = encrypt(bytes)
        }
        val store = RecognitionTaskStore(file, cipher)
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            val food = database.categoryDao().insert(CategoryEntity(name = "餐饮", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 1))
            preferences.setNotificationCaptureEnabled(true)
            preferences.setNotificationCloudParsingEnabled(true)
            preferences.setCaptureAutoRecordEnabled(true)
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor(), store)
            val now = System.currentTimeMillis()
            processor.processNotification(CapturedNotification("com.tencent.mm", "semantic-notice", now,
                "微信支付", "支付成功，实付17.00元，商户：小林"), LedgerRevision.current)
            val original = database.backupDao().transactions().single()
            assertEquals(0, cloudCalls)
            val task = store.pending().single()
            assertEquals(original.captureEventKey, task.eventKey)
            cloudResponse = """{"valid":true,"amount_cents":1700,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"17.00元","direction_evidence":"支付成功","status_evidence":"支付成功","merchant":"小林","merchant_evidence":"商户：小林","business_kind":"expense","category_id":$food,"account_tag":"微信","note":"晚餐"}"""
            assertTrue(processor.processRecognitionTask(task.id))
            val saved = database.backupDao().transactions().single()
            assertEquals(original.id, saved.id)
            assertEquals("expense", saved.businessKind)
            assertEquals(food, saved.categoryId)
            assertEquals(TxStatus.CONFIRMED, saved.status)
            assertEquals("晚餐", saved.note)
            assertEquals(1, cloudCalls)
            assertTrue(sentMessages.any { it.content.contains("餐饮") && it.content.contains("allowedCategories") })
            assertTrue(store.pending().isEmpty())
            processor.processNotification(CapturedNotification("com.tencent.mm", "semantic-notice", now,
                "微信支付", "支付成功，实付17.00元，商户：小林"), LedgerRevision.current)
            assertTrue(store.pending().isEmpty())
            assertEquals(1, database.backupDao().transactions().size)
            assertEquals(food, database.backupDao().transactions().single().categoryId)
            assertEquals("晚餐", database.backupDao().transactions().single().note)
        } finally {
            preferences.setNotificationCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            file.delete()
            database.close()
        }
    }

    @Test fun `captured payment follows the user's corrected purpose category and account preference`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val uiPreferences = context.getSharedPreferences("pennypilot_flutter", Context.MODE_PRIVATE)
        try {
            database.categoryDao().insert(CategoryEntity(name = "其他", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
            val food = database.categoryDao().insert(CategoryEntity(name = "餐饮", icon = "", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 1))
            uiPreferences.edit().putString("recognition_preferences",
                """[{"merchant":"小林","type":"EXPENSE","category":"餐饮","businessKind":"receivable","accountTag":"招商银行"}]""").commit()
            preferences.setScreenCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(true)
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor())
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(1700, "小林", "EXPENSE",
                scene = "transfer_sent"), "remembered-purpose", System.currentTimeMillis(), LedgerRevision.current))
            val saved = database.transactionDao().getByCaptureEvent("remembered-purpose")!!
            assertEquals(TxStatus.CONFIRMED, saved.status)
            assertEquals("receivable", saved.businessKind)
            assertEquals(food, saved.categoryId)
            assertEquals("招商银行", saved.accountTag)
        } finally {
            uiPreferences.edit().remove("recognition_preferences").commit()
            preferences.setScreenCaptureEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            database.close()
        }
    }

    @Test fun `screen semantic tasks retain red packet scene and refund identity namespace`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("capture-scenes", ".enc").apply { delete() }
        val cipher = object : TaskCipher {
            override fun encrypt(bytes: ByteArray) = bytes
            override fun decrypt(bytes: ByteArray) = bytes
        }
        val store = RecognitionTaskStore(file, cipher)
        try {
            val redPacketCategory = database.categoryDao().insert(CategoryEntity(name = "红包", icon = "", colorIndex = 0, type = TxType.INCOME, sortOrder = 0))
            preferences.setScreenCaptureEnabled(true)
            preferences.setScreenCloudParsingEnabled(true)
            preferences.setCaptureAutoRecordEnabled(true)
            val processor = CaptureProcessor(context, database, preferences, cloud, CaptureServiceMonitor(), store)
            val now = System.currentTimeMillis()
            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(1700, "小林", "INCOME",
                scene = "red_packet_received"), "red-packet-semantic", now, LedgerRevision.current))
            val redPacketTask = store.pending().single()
            assertTrue(redPacketTask.text.contains("收到红包"))
            cloudResponse = """{"valid":true,"amount_cents":1700,"direction":"INCOME","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"17.00元","direction_evidence":"收款成功","status_evidence":"收款成功","merchant":"小林","merchant_evidence":"商户：小林","business_kind":"transfer","category_id":$redPacketCategory}"""
            assertTrue(processor.processRecognitionTask(redPacketTask.id))
            assertEquals("income", database.transactionDao().getByCaptureEvent("red-packet-semantic")!!.businessKind)

            assertTrue(processor.processScreen("com.tencent.mm", NotificationParseResult(500, null, "INCOME",
                orderId = "RF202611010001", businessKind = "refund", scene = "refund_received", orderIdKind = "refund"),
                "refund-semantic", now + 1000, LedgerRevision.current))
            val refundTask = store.pending().single()
            assertTrue(refundTask.text.contains("退款单号：RF202611010001"))
            cloudResponse = """{"valid":true,"amount_cents":500,"direction":"INCOME","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"5.00元","direction_evidence":"退款已到账","status_evidence":"退款已到账","business_kind":"refund","category_id":$redPacketCategory}"""
            assertTrue(processor.processRecognitionTask(refundTask.id))
            assertEquals("wechat:refund", database.transactionDao().captureEvidenceByKey("refund-semantic")!!.orderDomain)
            assertEquals("refund", database.transactionDao().getByCaptureEvent("refund-semantic")!!.businessKind)
            assertEquals(2, database.backupDao().transactions().size)
        } finally {
            preferences.setScreenCaptureEnabled(false)
            preferences.setScreenCloudParsingEnabled(false)
            preferences.setCaptureAutoRecordEnabled(false)
            file.delete()
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

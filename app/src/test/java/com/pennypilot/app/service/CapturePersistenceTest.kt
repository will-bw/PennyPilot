package com.pennypilot.app.service

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapturePersistenceTest {
    private lateinit var db: AppDatabase
    private val time = 1_790_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }
    @After fun tearDown() { db.close() }

    private fun tx(source: TxSource = TxSource.NOTIFICATION, merchant: String? = "小王面馆") = TransactionEntity(
        amount = 1800, type = TxType.EXPENSE, categoryId = 1, merchant = merchant,
        occurredAt = time, createdAt = time, source = source, status = TxStatus.PENDING
    )
    private fun evidence(
        event: String = "first", packageName: String = "com.sankuai.meituan", at: Long = time,
        notificationKey: String? = "notice-1", text: String = "小王面馆支付成功18元", orderId: String? = null
    ) = CaptureEvidence(packageName, event, at, text, notificationKey, orderId)

    private suspend fun capture(
        evidence: CaptureEvidence = evidence(), transaction: TransactionEntity = tx()
    ): CaptureResult = CapturePersistence.capture(db, LedgerRevision.current, transaction, evidence)

    private fun paymentMetadata(merchant: String, channel: String? = null, scene: String = "merchant_payment") = buildJsonObject {
        put("paymentStatus", "COMPLETED")
        put("scene", scene)
        putJsonObject("receiptDetails") {
            put("merchantName", merchant)
            channel?.let { put("paymentChannel", it) }
        }
    }.toString()

    @Test fun `platform payee and store notifications cannot count the same expense twice in either order`() = runTest {
        listOf(false, true).forEachIndexed { index, walletFirst ->
            val at = time + index * 300_000
            val shopping = evidence(event = "store-$index", at = at).copy(metadataJson = paymentMetadata("小王面馆"))
            val wallet = evidence(event = "wallet-$index", packageName = "com.tencent.mm", at = at + 5_000)
                .copy(metadataJson = paymentMetadata("美团"))
            val inputs = listOf(shopping to tx(), wallet to tx(merchant = "美团"))
                .let { if (walletFirst) it.reversed() else it }
            val results = inputs.map { (proof, row) -> capture(proof, row.copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted }
            val first = db.transactionDao().getById(results[0].transactionId)!!
            val second = db.transactionDao().getById(results[1].transactionId)!!
            assertEquals(TxStatus.CONFIRMED, first.status)
            assertEquals(TxStatus.PENDING, second.status)
            assertEquals(first.id, second.duplicateOf)
        }
        assertEquals(3600L, db.transactionDao().observeMonthExpense(time - 1, time + 1).first())
    }

    @Test fun `explicit wechat channel links different payees without merging their order identities`() = runTest {
        val first = capture(evidence(orderId = "MT202611130001").copy(
            metadataJson = paymentMetadata("小王面馆", "微信支付")), tx().copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        val walletEvidence = evidence(event = "wallet", packageName = "com.tencent.mm", at = time + 120_000,
            orderId = "WX202611130001").copy(metadataJson = paymentMetadata("平台收款公司", "微信支付"))
        val second = capture(walletEvidence, tx(merchant = "平台收款公司").copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        assertEquals(TxStatus.PENDING, db.transactionDao().getById(second.transactionId)!!.status)
        assertEquals(CaptureResult.Duplicate(second.transactionId), capture(walletEvidence))
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `cross app suspicion cannot be confirmed by semantic enrichment`() = runTest {
        val first = capture(evidence().copy(metadataJson = paymentMetadata("小王面馆")),
            tx().copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        val walletEvidence = evidence(event = "wallet", packageName = "com.tencent.mm")
            .copy(metadataJson = paymentMetadata("美团"))
        val wallet = tx(merchant = "美团").copy(status = TxStatus.CONFIRMED)
        val second = capture(walletEvidence, wallet) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        val before = db.transactionDao().getById(second.transactionId)
        assertEquals(CaptureResult.Duplicate(second.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, wallet.copy(categoryId = 2), walletEvidence, enrichSemantics = true))
        assertEquals(before, db.transactionDao().getById(second.transactionId))
    }

    @Test fun `cross app fallback rejects unrelated equal amounts other channels scenes and expired notifications`() = runTest {
        val cases = listOf(
            paymentMetadata("小王面馆") to paymentMetadata("其他店铺"),
            paymentMetadata("小王面馆", "支付宝") to paymentMetadata("美团"),
            paymentMetadata("小王面馆", "美团支付") to paymentMetadata("美团"),
            paymentMetadata("小王面馆", scene = "transfer_sent") to paymentMetadata("美团"),
            paymentMetadata("小王面馆") to paymentMetadata("美团", scene = "transfer_sent"),
            paymentMetadata("小王面馆") to paymentMetadata("美团附近便利店"),
            paymentMetadata("小王面馆") to """{"scene":"merchant_payment","paymentStatus":"COMPLETED","receiptDetails":{"productDescription":"美团","remark":"美团"}}"""
        )
        cases.forEachIndexed { index, (shopping, wallet) ->
            val at = time + index * 300_000
            capture(evidence(event = "store-$index", at = at).copy(metadataJson = shopping))
            val result = capture(evidence(event = "wallet-$index", packageName = "com.tencent.mm", at = at + 5_000)
                .copy(metadataJson = wallet), tx(merchant = null).copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
            assertNull("case $index", result.duplicateOf)
            assertEquals(TxStatus.CONFIRMED, db.transactionDao().getById(result.transactionId)!!.status)
        }
        val late = capture(evidence(event = "late", packageName = "com.tencent.mm", at = time + cases.size * 300_000)
            .copy(metadataJson = paymentMetadata("美团")), tx(merchant = "美团")) as CaptureResult.Inserted
        assertNull(late.duplicateOf)
    }

    @Test fun `distinct shopping orders are not chained through a suspected wallet duplicate`() = runTest {
        val shopping = evidence(orderId = "MT202611130001").copy(metadataJson = paymentMetadata("小王面馆"))
        val first = capture(shopping) as CaptureResult.Inserted
        val wallet = capture(evidence(event = "wallet", packageName = "com.tencent.mm", at = time + 1_000)
            .copy(metadataJson = paymentMetadata("美团")), tx(merchant = "美团")) as CaptureResult.Inserted
        assertEquals(first.transactionId, wallet.duplicateOf)
        val next = capture(shopping.copy(eventKey = "new-order", orderId = "MT202611130002", observedAt = time + 2_000),
            tx().copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        assertNull(next.duplicateOf)
        assertEquals(TxStatus.CONFIRMED, db.transactionDao().getById(next.transactionId)!!.status)
    }

    @Test fun `cross app hint requires matching expense notifications within the original time window`() = runTest {
        capture(evidence().copy(metadataJson = paymentMetadata("小王面馆")))
        val walletEvidence = evidence(event = "wallet", packageName = "com.tencent.mm", at = time + 1_000)
            .copy(metadataJson = paymentMetadata("美团"))
        val cases = listOf(
            walletEvidence.copy(observedAt = time + 120_001) to tx(merchant = null),
            walletEvidence to tx(merchant = null).copy(amount = 1900),
            walletEvidence to tx(merchant = null).copy(amount = -1800, type = TxType.INCOME),
            walletEvidence to tx(TxSource.ACCESSIBILITY, merchant = null),
            walletEvidence.copy(metadataJson = """{"scene":"merchant_payment","receiptDetails":{"merchantName":"美团"}}""") to tx(merchant = null)
        )
        cases.forEachIndexed { index, (proof, row) ->
            val result = capture(proof.copy(eventKey = "wallet-$index"), row) as CaptureResult.Inserted
            assertNull("case $index", result.duplicateOf)
        }
    }

    @Test fun `same order from another channel fills missing facts without semantic replacement`() = runTest {
        val original = tx(merchant = " ").copy(accountTag = "", note = " ", businessKind = "transfer",
            metadataJson = """{"fieldEvidence":{"amount":"通知·实付18元","merchant":""}}""")
        val first = capture(evidence(orderId = "ORDER202611060001"), original) as CaptureResult.Inserted
        val page = tx(TxSource.ACCESSIBILITY).copy(accountTag = "储蓄卡", note = "午餐", categoryId = 2,
            businessKind = "expense", status = TxStatus.CONFIRMED,
            metadataJson = """{"fieldEvidence":{"amount":"页面·18元","merchant":"页面·小王面馆","paymentMethod":"页面·储蓄卡"}}""")

        val result = capture(evidence(event = "page", notificationKey = null, orderId = "ORDER202611060001"), page)

        assertEquals(CaptureResult.Enriched(first.transactionId), result)
        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals("小王面馆", saved.merchant)
        assertEquals("储蓄卡", saved.accountTag)
        assertEquals("午餐", saved.note)
        assertEquals(original.amount, saved.amount)
        assertEquals(original.type, saved.type)
        assertEquals(original.categoryId, saved.categoryId)
        assertEquals(original.businessKind, saved.businessKind)
        assertEquals(original.status, saved.status)
        val fields = (Json.parseToJsonElement(saved.metadataJson!!) as JsonObject)["fieldEvidence"] as JsonObject
        assertEquals(JsonPrimitive("通知·实付18元"), fields["amount"])
        assertEquals(JsonPrimitive("页面·小王面馆"), fields["merchant"])
        assertEquals(JsonPrimitive("页面·储蓄卡"), fields["paymentMethod"])
        assertEquals(1, db.backupDao().transactions().size)
        assertEquals(2, db.backupDao().captureEvents().size)
    }

    @Test fun `same order local field conflicts never replace existing nonempty facts`() = runTest {
        val original = tx().copy(accountTag = "银行卡", note = "原备注", businessKind = "expense")
        val first = capture(evidence(orderId = "ORDER202611060002"), original) as CaptureResult.Inserted
        val incoming = original.copy(source = TxSource.ACCESSIBILITY, merchant = "其他商户", accountTag = "余额", note = "新备注")

        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(
            evidence(event = "page", notificationKey = null, orderId = "ORDER202611060002"), incoming))
        assertEquals(original.copy(id = first.transactionId, captureEventKey = "first"),
            db.transactionDao().getById(first.transactionId))
    }

    @Test fun `strong matching receipt upgrades only explicit observed time and its field evidence`() = runTest {
        val original = tx().copy(metadataJson = """{"transactionTimeSource":"observed","timePrecision":"unspecified","observedAt":$time,"fieldEvidence":{"amount":"通知·18元"}}""")
        val first = capture(evidence(orderId = "ORDER202611060003"), original) as CaptureResult.Inserted
        val receiptTime = time - 60_000
        val observedAt = time + 2_000
        val page = original.copy(source = TxSource.ACCESSIBILITY, occurredAt = receiptTime,
            metadataJson = """{"transactionTimeSource":"receipt","timePrecision":"exact","observedAt":$observedAt,"fieldEvidence":{"amount":"页面·18元","occurredAt":"页面·支付时间"}}""")

        assertEquals(CaptureResult.Enriched(first.transactionId), capture(
            evidence(event = "receipt", notificationKey = null, at = observedAt, orderId = "ORDER202611060003"), page))

        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals(receiptTime, saved.occurredAt)
        assertEquals(original.createdAt, saved.createdAt)
        assertEquals(original.source, saved.source)
        val metadata = Json.parseToJsonElement(saved.metadataJson!!) as JsonObject
        assertEquals(JsonPrimitive("receipt"), metadata["transactionTimeSource"])
        assertEquals(JsonPrimitive("exact"), metadata["timePrecision"])
        assertEquals(JsonPrimitive(observedAt), metadata["observedAt"])
        val fields = metadata["fieldEvidence"] as JsonObject
        assertEquals(JsonPrimitive("通知·18元"), fields["amount"])
        assertEquals(JsonPrimitive("页面·支付时间"), fields["occurredAt"])

        assertEquals(CaptureResult.Duplicate(first.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, original, evidence(orderId = "ORDER202611060003"), enrichSemantics = true))
        val replayed = db.transactionDao().getById(first.transactionId)!!
        assertEquals(receiptTime, replayed.occurredAt)
        assertEquals(metadata["transactionTimeSource"], (Json.parseToJsonElement(replayed.metadataJson!!) as JsonObject)["transactionTimeSource"])
    }

    @Test fun `receipt does not guess the time source of legacy records`() = runTest {
        val first = capture(evidence(orderId = "ORDER202611060004")) as CaptureResult.Inserted
        val incoming = tx(TxSource.ACCESSIBILITY).copy(occurredAt = time - 60_000,
            metadataJson = """{"transactionTimeSource":"receipt","timePrecision":"exact","observedAt":$time,"fieldEvidence":{"occurredAt":"页面·支付时间"}}""")

        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(
            evidence(event = "receipt", notificationKey = null, orderId = "ORDER202611060004"), incoming))
        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals(time, saved.occurredAt)
        val metadata = Json.parseToJsonElement(saved.metadataJson!!) as JsonObject
        assertFalse(metadata.containsKey("transactionTimeSource"))
        assertFalse((metadata["fieldEvidence"] as JsonObject).containsKey("occurredAt"))
    }

    @Test fun `late facts cannot alter user decisions manual records or unresolved duplicates`() = runTest {
        val base = tx(merchant = null).copy(metadataJson = """{"transactionTimeSource":"observed"}""")
        val changes: List<(TransactionEntity) -> TransactionEntity> = listOf(
            { it.copy(editVersion = 1, status = TxStatus.CONFIRMED) },
            { it.copy(status = TxStatus.IGNORED) },
            { it.copy(duplicateOf = 999) },
            { it.copy(source = TxSource.MANUAL) }
        )
        changes.forEachIndexed { index, change ->
            val order = "ORDER202611060010$index"
            val first = capture(evidence(event = "initial-$index", orderId = order), base) as CaptureResult.Inserted
            val protected = change(db.transactionDao().getById(first.transactionId)!!)
            db.transactionDao().update(protected)
            val page = tx(TxSource.ACCESSIBILITY).copy(accountTag = "银行卡", note = "新备注", occurredAt = time - 1_000,
                metadataJson = """{"transactionTimeSource":"receipt","timePrecision":"exact"}""")

            assertEquals(CaptureResult.Duplicate(first.transactionId), capture(
                evidence(event = "receipt-$index", notificationKey = null, orderId = order), page))
            assertEquals(protected, db.transactionDao().getById(first.transactionId))
        }
    }

    @Test fun `local receipt can fill merchant without replacing completed model semantics`() = runTest {
        val original = tx(merchant = null).copy(categoryId = 2, accountTag = "储蓄卡", note = "午餐", businessKind = "expense",
            metadataJson = """{"semanticModelApplied":true,"fieldEvidence":{"amount":"通知·18元"}}""")
        val first = capture(evidence(orderId = "ORDER202611060005"), original) as CaptureResult.Inserted
        val page = tx(TxSource.ACCESSIBILITY).copy(accountTag = "微信支付", note = "转账", businessKind = "transfer")

        assertEquals(CaptureResult.Enriched(first.transactionId), CapturePersistence.capture(db, LedgerRevision.current,
            page, evidence(event = "page", notificationKey = null, orderId = "ORDER202611060005"), enrichSemantics = true))
        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals("小王面馆", saved.merchant)
        assertEquals(original.categoryId, saved.categoryId)
        assertEquals(original.accountTag, saved.accountTag)
        assertEquals(original.note, saved.note)
        assertEquals(original.businessKind, saved.businessKind)
    }

    @Test fun `late details deep merge without replacing captured facts or completed model semantics`() = runTest {
        val original = tx().copy(categoryId = 2, accountTag = "招商银行", note = "午餐AA", businessKind = "expense",
            metadataJson = """{"semanticModelApplied":true,"receiptDetails":{"merchantName":"小王面馆","productDescription":"牛肉面","paymentChannel":"微信支付","fieldSources":{"merchantName":"通知·商户","productDescription":"页面·商品"}}}""")
        val first = capture(evidence(orderId = "ORDER202611060030"), original) as CaptureResult.Inserted
        val page = tx(TxSource.ACCESSIBILITY).copy(categoryId = 3, accountTag = "零钱", note = "新商品", businessKind = "transfer",
            metadataJson = """{"receiptDetails":{"merchantName":"错误商户","productDescription":"其他商品","remark":"少辣","fundingAccount":"招商银行(1234)","fieldSources":{"merchantName":"重绘商户","productDescription":"重绘商品","remark":"本地OCR·备注","fundingAccount":"页面·付款方式"}}}""")
        val later = evidence(event = "detail-page", notificationKey = null, orderId = "ORDER202611060030")
        assertEquals(CaptureResult.Duplicate(first.transactionId), CapturePersistence.capture(db, LedgerRevision.current,
            page, later, enrichSemantics = true))
        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals(original.categoryId, saved.categoryId)
        assertEquals(original.note, saved.note)
        assertEquals(original.accountTag, saved.accountTag)
        assertEquals(original.businessKind, saved.businessKind)
        val details = (Json.parseToJsonElement(saved.metadataJson!!) as JsonObject)["receiptDetails"] as JsonObject
        assertEquals(JsonPrimitive("小王面馆"), details["merchantName"])
        assertEquals(JsonPrimitive("牛肉面"), details["productDescription"])
        assertEquals(JsonPrimitive("少辣"), details["remark"])
        assertEquals(JsonPrimitive("招商银行(1234)"), details["fundingAccount"])
        val sources = details["fieldSources"] as JsonObject
        assertEquals(JsonPrimitive("页面·商品"), sources["productDescription"])
        assertEquals(JsonPrimitive("本地OCR·备注"), sources["remark"])
        CapturePersistence.capture(db, LedgerRevision.current, original,
            evidence(orderId = "ORDER202611060030"), enrichSemantics = true)
        val replayed = db.transactionDao().getById(first.transactionId)!!
        assertEquals(details, (Json.parseToJsonElement(replayed.metadataJson!!) as JsonObject)["receiptDetails"])
        assertEquals(original.note, replayed.note)
        assertEquals(1, db.backupDao().transactions().size)
    }

    @Test fun `late funding account replaces channel placeholder and new remark extends only generated receipt note`() = runTest {
        val original = tx().copy(accountTag = "微信", note = "商品：牛肉面", businessKind = "expense",
            metadataJson = """{"receiptDetails":{"productDescription":"牛肉面","paymentChannel":"微信","fieldSources":{"productDescription":"页面·商品"}}}""")
        val first = capture(transaction = original) as CaptureResult.Inserted
        val page = original.copy(source = TxSource.ACCESSIBILITY, accountTag = "招商银行(1234)", note = "备注：少辣",
            metadataJson = """{"receiptDetails":{"remark":"少辣","fundingAccount":"招商银行(1234)","fieldSources":{"remark":"本地OCR·备注","fundingAccount":"本地OCR·付款方式"}}}""")
        assertEquals(CaptureResult.Enriched(first.transactionId), capture(transaction = page))
        val saved = db.transactionDao().getById(first.transactionId)!!
        assertEquals("招商银行(1234)", saved.accountTag)
        assertEquals("商品：牛肉面；备注：少辣", saved.note)
        assertEquals(CaptureResult.Duplicate(first.transactionId), CapturePersistence.capture(db, LedgerRevision.current,
            original, evidence(), enrichSemantics = true))
        assertEquals(saved.note, db.transactionDao().getById(first.transactionId)!!.note)
        assertEquals(saved.accountTag, db.transactionDao().getById(first.transactionId)!!.accountTag)

        val edited = saved.copy(note = "我自己改的用途", accountTag = "现金", editVersion = 1)
        db.transactionDao().update(edited)
        capture(transaction = page.copy(note = "不应覆盖", metadataJson = """{"receiptDetails":{"remark":"后来文字"}}"""))
        assertEquals(edited, db.transactionDao().getById(first.transactionId))
        assertEquals(1, db.backupDao().transactions().size)
    }

    @Test fun `equal split refunds retain separate refund identities and never fill each other`() = runTest {
        val refund = tx(TxSource.ACCESSIBILITY, null).copy(type = TxType.INCOME, amount = -500, businessKind = "refund")
        val firstEvidence = evidence(event = "refund-1", notificationKey = null, orderId = "REFUND202611060001")
            .copy(orderDomain = "alipay:refund")
        val first = capture(firstEvidence, refund) as CaptureResult.Inserted
        val second = capture(firstEvidence.copy(eventKey = "refund-2", orderId = "REFUND202611060002"),
            refund.copy(merchant = "小王面馆")) as CaptureResult.Inserted
        assertNull(second.duplicateOf)
        assertNull(db.transactionDao().getById(first.transactionId)!!.merchant)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `AI semantics enrich one event and promote the untouched pending record`() = runTest {
        val initial = tx().copy(businessKind = "unknown", metadataJson = """{"scene":"transfer_sent"}""")
        val inserted = capture(transaction = initial) as CaptureResult.Inserted
        val resolved = initial.copy(categoryId = 2, businessKind = "expense", status = TxStatus.CONFIRMED,
            accountTag = "微信", note = "晚餐", metadataJson = """{"semanticBusinessKind":"expense","assumptions":["按餐饮消费记录"]}""")
        assertEquals(CaptureResult.Enriched(inserted.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, resolved, evidence(), enrichSemantics = true))
        val saved = db.transactionDao().getById(inserted.transactionId)!!
        assertEquals(1, db.backupDao().transactions().size)
        assertEquals(1800L, saved.amount)
        assertEquals(2L, saved.categoryId)
        assertEquals("expense", saved.businessKind)
        assertEquals("微信", saved.accountTag)
        assertEquals("晚餐", saved.note)
        assertEquals(TxStatus.CONFIRMED, saved.status)
        assertTrue(saved.metadataJson!!.contains("transfer_sent"))
        assertTrue(saved.metadataJson!!.contains("按餐饮消费记录"))
    }

    @Test fun `metadata only enrichment stays duplicate while preserving the new evidence`() = runTest {
        val initial = tx().copy(businessKind = "expense", metadataJson = """{"scene":"merchant_payment"}""")
        val first = capture(transaction = initial) as CaptureResult.Inserted
        val incoming = initial.copy(metadataJson = """{"semanticModelApplied":true,"assumptions":["补充识别依据"]}""")
        assertEquals(CaptureResult.Duplicate(first.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, incoming, evidence().copy(orderId = "ORDER202611010001"), enrichSemantics = true))
        assertEquals("ORDER202611010001", db.transactionDao().captureEvidenceByKey("first")!!.orderId)
        assertTrue(db.transactionDao().getById(first.transactionId)!!.metadataJson!!.contains("补充识别依据"))
        assertEquals(1, db.backupDao().transactions().size)
    }

    @Test fun `AI can refine an automatically recorded transfer but cannot overwrite user decisions`() = runTest {
        val initial = tx().copy(businessKind = "transfer", status = TxStatus.CONFIRMED)
        val inserted = capture(transaction = initial) as CaptureResult.Inserted
        val resolved = initial.copy(categoryId = 2, businessKind = "expense", note = "AI用途")
        assertEquals(CaptureResult.Enriched(inserted.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, resolved, evidence(), enrichSemantics = true))
        val updated = db.transactionDao().getById(inserted.transactionId)!!.copy(
            categoryId = 3, note = "用户用途", editVersion = 1)
        db.transactionDao().update(updated)
        assertEquals(CaptureResult.Duplicate(inserted.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, resolved, evidence(), enrichSemantics = true))
        assertEquals(updated, db.transactionDao().getById(inserted.transactionId))
        db.transactionDao().update(updated.copy(status = TxStatus.IGNORED))
        assertEquals(CaptureResult.Duplicate(inserted.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, resolved, evidence(), enrichSemantics = true))
        assertEquals(TxStatus.IGNORED, db.transactionDao().getById(inserted.transactionId)!!.status)
        db.transactionDao().deleteById(inserted.transactionId)
        assertEquals(CaptureResult.Duplicate(inserted.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, resolved, evidence(), enrichSemantics = true))
        assertTrue(db.backupDao().transactions().isEmpty())
    }

    @Test fun `semantic enrichment never auto confirms a weak duplicate`() = runTest {
        capture()
        val duplicateEvidence = evidence(event = "other-page", notificationKey = null)
        val duplicate = capture(duplicateEvidence, tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        val before = db.transactionDao().getById(duplicate.transactionId)!!
        assertEquals(CaptureResult.Duplicate(duplicate.transactionId), CapturePersistence.capture(db,
            LedgerRevision.current, tx(TxSource.ACCESSIBILITY).copy(categoryId = 2, businessKind = "expense",
                status = TxStatus.CONFIRMED), duplicateEvidence, enrichSemantics = true))
        assertEquals(before, db.transactionDao().getById(duplicate.transactionId))
        assertEquals(TxStatus.PENDING, before.status)
    }

    @Test fun `late refund identity upgrades its namespace and later notification uses the upgraded evidence`() = runTest {
        val payment=tx(TxSource.ACCESSIBILITY).copy(amount=-500,type=TxType.INCOME)
        val initial=evidence(event="refund-page",packageName="com.tencent.mm",notificationKey=null)
        val first=capture(initial,payment) as CaptureResult.Inserted
        val enriched=initial.copy(orderId="RF202610240001",orderDomain="wechat:refund",observedAt=time+1000)
        assertEquals(CaptureResult.Duplicate(first.transactionId),capture(enriched,payment))
        assertEquals("wechat:refund",db.transactionDao().captureEvidenceByKey("refund-page")!!.orderDomain)
        assertEquals(CaptureResult.Duplicate(first.transactionId),capture(enriched.copy(eventKey="refund-notification"),payment.copy(source=TxSource.NOTIFICATION)))
        assertEquals(1,db.backupDao().transactions().size)
        assertEquals(CaptureResult.Conflict(first.transactionId),capture(enriched.copy(orderDomain="wechat"),payment))
    }

    @Test fun `late receipt fields enrich durable identity without overwriting edited bill`() = runTest {
        val first = capture(evidence(event = "screen-session"), tx(TxSource.ACCESSIBILITY, null)) as CaptureResult.Inserted
        val row = db.transactionDao().getById(first.transactionId)!!
        db.transactionDao().update(row.copy(note = "手动备注", merchant = "用户名称", status = TxStatus.CONFIRMED, editVersion = 1))
        val later = evidence(event = "screen-session", orderId = "ORDER202610240009", at = time + 2000)
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(later, tx(TxSource.ACCESSIBILITY)))
        assertEquals("ORDER202610240009", db.transactionDao().captureEvidenceByKey("screen-session")!!.orderId)
        assertEquals("用户名称", db.transactionDao().getById(first.transactionId)!!.merchant)
        assertEquals("手动备注", db.transactionDao().getById(first.transactionId)!!.note)
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(later.copy(eventKey = "late-notification")))
        assertEquals(1, db.backupDao().transactions().size)
    }

    @Test fun `conflicting late amount or order never rewrites accepted evidence`() = runTest {
        val first = capture(evidence(orderId = "ORDER202610240001")) as CaptureResult.Inserted
        assertEquals(CaptureResult.Conflict(first.transactionId), capture(evidence(orderId = "ORDER202610240002"), tx().copy(amount = 9900)))
        val evidence = db.transactionDao().captureEvidenceByKey("first")!!
        assertEquals(1800L, evidence.amount)
        assertEquals("ORDER202610240001", evidence.orderId)
        assertEquals(1800L, db.transactionDao().getById(first.transactionId)!!.amount)
    }

    @Test fun `evidence changed while waiting for database cannot create a stale bill`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val held = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        entered.await()
        var current = true
        val pending = async(Dispatchers.IO) {
            CapturePersistence.capture(db, LedgerRevision.current, tx(), evidence(), isCurrent = { current })
        }
        current = false
        release.complete(Unit)
        held.await()
        assertEquals(CaptureResult.StaleEvidence, pending.await())
        assertTrue(db.backupDao().transactions().isEmpty())
        assertTrue(db.backupDao().captureEvents().isEmpty())
    }

    @Test fun `trusted auto record is confirmed but weak duplicate always waits`() = runTest {
        val first = capture(evidence(), tx().copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        assertEquals(TxStatus.CONFIRMED, db.transactionDao().getById(first.transactionId)!!.status)
        val second = capture(evidence(event = "other-page", notificationKey = null), tx(TxSource.ACCESSIBILITY).copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        assertEquals(TxStatus.PENDING, db.transactionDao().getById(second.transactionId)!!.status)
    }

    @Test fun `business transfers never inflate income or expense and refund offsets expense`() = runTest {
        db.transactionDao().insert(tx().copy(source = TxSource.AI_TEXT, status = TxStatus.CONFIRMED, businessKind = "expense"))
        db.transactionDao().insert(tx().copy(source = TxSource.AI_TEXT, status = TxStatus.CONFIRMED, businessKind = "transfer", amount = 90000))
        db.transactionDao().insert(tx().copy(source = TxSource.AI_TEXT, status = TxStatus.CONFIRMED, businessKind = "receivable", amount = 16000))
        db.transactionDao().insert(tx().copy(source = TxSource.AI_TEXT, status = TxStatus.CONFIRMED, businessKind = "refund", type = TxType.INCOME, amount = -500))
        assertEquals(1300L, db.insightDao().totalExpense(time - 1, time + 1))
        assertEquals(0L, db.insightDao().totalIncome(time - 1, time + 1))
    }

    @Test fun `unclassified capture requires purpose before bulk or notification confirmation`() = runTest {
        val saved = capture(transaction = tx().copy(businessKind = "unknown")) as CaptureResult.Inserted
        assertEquals(0, db.transactionDao().updateStatus(saved.transactionId, TxStatus.CONFIRMED))
        assertEquals(0, db.transactionDao().updateCapturedStatus(saved.transactionId, "first", TxStatus.CONFIRMED))
        val row = db.transactionDao().getById(saved.transactionId)!!
        db.transactionDao().update(row.copy(businessKind = "transfer"))
        assertEquals(1, db.transactionDao().updateStatus(saved.transactionId, TxStatus.CONFIRMED))
        assertEquals(0L, db.insightDao().totalExpense(time - 1, time + 1))
    }

    @Test fun `same order conflicting amount cannot auto confirm a second bill`() = runTest {
        val first = capture(evidence(orderId = "ORDER202610240031"), tx().copy(status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        val second = capture(evidence(event = "other-source", orderId = "ORDER202610240031"), tx().copy(amount = 2200, status = TxStatus.CONFIRMED)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        val row = db.transactionDao().getById(second.transactionId)!!
        assertEquals(TxStatus.PENDING, row.status)
        assertTrue(row.metadataJson!!.contains("同一订单出现不同金额"))
        assertEquals(1800L, db.insightDao().totalExpense(time - 1, time + 1))
    }

    @Test fun `deletion tombstones survive database backup restore`() = runTest {
        val first = capture(evidence(orderId = "ORDER202610240020")) as CaptureResult.Inserted
        db.transactionDao().deleteById(first.transactionId)
        val snapshot = db.backupDao().snapshot()
        db.backupDao().replace(snapshot)
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture())
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(evidence(event = "new-alias", orderId = "ORDER202610240020")))
        assertTrue(db.backupDao().transactions().isEmpty())
    }

    @Test fun `notification parsing started before restore cannot write into restored ledger`() = runTest {
        val parsedUnderRevision = LedgerRevision.current
        db.withTransaction { LedgerRevision.advance() }
        val transaction = tx().copy(captureEventKey = "event-before-restore")
        assertEquals(-1L, CapturePersistence.insertIfCurrent(db, parsedUnderRevision, transaction))
        assertEquals(CaptureResult.StaleRevision,
            CapturePersistence.capture(db, parsedUnderRevision, transaction, evidence()))
        assertTrue(db.backupDao().captureEvents().isEmpty())
        assertFalse(db.transactionDao().hasCaptureEvent(transaction.captureEventKey!!))
        assertTrue(CapturePersistence.insertIfCurrent(db, LedgerRevision.current, transaction) > 0)
        assertEquals(-1L, CapturePersistence.insertIfCurrent(db, LedgerRevision.current, transaction))
    }

    @Test fun `updated notification postTime creates durable alias without reviving ignored record`() = runTest {
        val first = capture() as CaptureResult.Inserted
        db.transactionDao().updateStatus(first.transactionId, TxStatus.IGNORED)
        val update = evidence(event = "changed-post-time", at = time + 1000)
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(update))
        // 新服务实例使用同一数据库，精确别名在短窗口过后仍可命中。
        assertEquals(CaptureResult.Duplicate(first.transactionId),
            capture(update.copy(observedAt = time + 600_000)))
        assertEquals(TxStatus.IGNORED, db.transactionDao().getById(first.transactionId)!!.status)
        assertEquals(1, db.backupDao().transactions().size)
        assertEquals(2, db.backupDao().captureEvents().size)
    }

    @Test fun `repeated callbacks cannot slide the notification update window indefinitely`() = runTest {
        val first = capture() as CaptureResult.Inserted
        assertEquals(CaptureResult.Duplicate(first.transactionId),
            capture(evidence(event = "update-1", at = time + 10_000)))
        val later = capture(evidence(event = "independent", at = time + 20_000)) as CaptureResult.Inserted
        assertNull(later.duplicateOf)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `independent notification identities and changed contents are retained`() = runTest {
        capture()
        assertTrue(capture(evidence(event = "other-key", notificationKey = "notice-2")) is CaptureResult.Inserted)
        assertTrue(capture(evidence(event = "other-text", text = "再次在小王面馆支付成功18元")) is CaptureResult.Inserted)
        assertEquals(3, db.backupDao().transactions().size)
    }

    @Test fun `same reliable order across platform package family and capture channels is merged`() = runTest {
        val order = "MT20261023001234"
        val first = capture(evidence(orderId = order)) as CaptureResult.Inserted
        db.transactionDao().updateStatus(first.transactionId, TxStatus.CONFIRMED)
        val result = capture(evidence(event = "page", packageName = "com.sankuai.meituan.takeoutnew",
            notificationKey = null, at = time + 600_000, orderId = order), tx(TxSource.ACCESSIBILITY))
        assertEquals(CaptureResult.Duplicate(first.transactionId), result)
        assertEquals(TxStatus.CONFIRMED, db.transactionDao().getById(first.transactionId)!!.status)
        assertEquals(1, db.transactionDao().countCapturedSince(time))
    }

    @Test fun `different reliable orders with same amount and merchant remain independent`() = runTest {
        capture(evidence(orderId = "MT20261023000001"))
        val result = capture(evidence(event = "page", notificationKey = null, orderId = "MT20261023000002"),
            tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        assertNull(result.duplicateOf)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `same order cannot merge a changed amount or direction`() = runTest {
        val order = "MT20261023000001"
        capture(evidence(orderId = order))
        assertTrue(capture(evidence(event = "amount", orderId = order), tx().copy(amount = 2200)) is CaptureResult.Inserted)
        assertTrue(capture(evidence(event = "refund", orderId = order),
            tx().copy(type = TxType.INCOME, amount = -1800)) is CaptureResult.Inserted)
        assertEquals(3, db.backupDao().transactions().size)
    }

    @Test fun `cross channel weak evidence remains pending and records suspected duplicate`() = runTest {
        val first = capture() as CaptureResult.Inserted
        val second = capture(evidence(event = "page", notificationKey = null, at = time + 5000),
            tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        val stored = db.transactionDao().getById(second.transactionId)!!
        assertEquals(TxStatus.PENDING, stored.status)
        assertEquals(first.transactionId, stored.duplicateOf)
        assertEquals(2, db.transactionDao().countCapturedSince(time))
    }

    @Test fun `platform and payment provider do not share order namespace`() = runTest {
        val order = "20261023000001"
        val first = capture(evidence(orderId = order)) as CaptureResult.Inserted
        val second = capture(evidence(event = "alipay", packageName = "com.eg.android.AlipayGphone",
            orderId = order, notificationKey = "ali-1")) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `same amount alone and generic platform merchant never imply duplicate`() = runTest {
        capture(transaction = tx(merchant = "美团"))
        assertNull((capture(evidence(event = "page", notificationKey = null),
            tx(TxSource.ACCESSIBILITY, "美团")) as CaptureResult.Inserted).duplicateOf)
        assertNull((capture(evidence(event = "unknown", notificationKey = null),
            tx(TxSource.ACCESSIBILITY, null)) as CaptureResult.Inserted).duplicateOf)
    }

    @Test fun `possible duplicates outside the short payment window remain independent`() = runTest {
        capture()
        val later = capture(evidence(event = "later-page", notificationKey = null, at = time + 120_001),
            tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        assertNull(later.duplicateOf)
    }

    @Test fun `page reentry with no order is retained for review even if text is identical`() = runTest {
        val first = capture(evidence(event = "session-a", notificationKey = null), tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        val second = capture(evidence(event = "session-b", notificationKey = null), tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
    }

    @Test fun `concurrent capture checks and writes commit one order with all event aliases`() = runTest {
        val results = (1..8).map { index ->
            async(Dispatchers.IO) { capture(evidence(event = "event-$index", orderId = "ORDER202610230001")) }
        }.awaitAll()
        assertEquals(1, results.count { it is CaptureResult.Inserted })
        assertEquals(7, results.count { it is CaptureResult.Duplicate })
        assertEquals(1, db.backupDao().transactions().size)
        assertEquals(8, db.backupDao().captureEvents().size)
    }

    @Test fun `legacy v4 event is recognized with new payload-aware identity`() = runTest {
        val text = "小王面馆支付成功18元"
        val oldKey = CaptureEventIdentity.key("com.sankuai.meituan", "notice-1", time)
        val id = db.transactionDao().insertCaptured(tx().copy(captureEventKey = oldKey, rawPayload = text,
            status = TxStatus.IGNORED))
        val newKey = CaptureEventIdentity.key("com.sankuai.meituan", "notice-1", time, text)
        assertEquals(CaptureResult.Duplicate(id), capture(evidence(event = newKey, text = text)))
        assertEquals(TxStatus.IGNORED, db.transactionDao().getById(id)!!.status)
        assertEquals(1, db.backupDao().captureEvents().size)
    }

    @Test fun `deleting captured original clears suspicion links and all durable event aliases`() = runTest {
        val original = evidence(orderId = "ORDER202610230001")
        val first = capture(original) as CaptureResult.Inserted
        assertEquals(CaptureResult.Duplicate(first.transactionId),
            capture(original.copy(eventKey = "order-alias", observedAt = time + 1000)))
        val second = capture(evidence(event = "page", notificationKey = null),
            tx(TxSource.ACCESSIBILITY)) as CaptureResult.Inserted
        assertEquals(first.transactionId, second.duplicateOf)
        assertEquals(3, db.backupDao().captureEvents().size)

        db.transactionDao().deleteById(first.transactionId)

        assertNull(db.transactionDao().getById(second.transactionId)!!.duplicateOf)
        assertNull(db.transactionDao().captureEvidenceByKey(original.eventKey))
        assertNull(db.transactionDao().captureEvidenceByKey("order-alias"))
        assertEquals(1, db.backupDao().captureEvents().size)
        // 用户删除后，迟到的原事件及同订单新别名都不能复活账目。
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(original))
        assertEquals(CaptureResult.Duplicate(first.transactionId), capture(original.copy(eventKey = "after-delete")))
    }

    @Test fun `late notification flags matching manual entry and preserves the original`() = runTest {
        val manual = tx(TxSource.MANUAL, " 小王 面馆 ").copy(
            occurredAt = time - 30_000, status = TxStatus.CONFIRMED, note = "已手动补记", accountTag = "现金"
        )
        val originalId = db.transactionDao().insert(manual)

        val result = capture() as CaptureResult.Inserted

        assertEquals(originalId, result.duplicateOf)
        assertEquals(manual.copy(id = originalId), db.transactionDao().getById(originalId))
        assertEquals(TxStatus.PENDING, db.transactionDao().getById(result.transactionId)!!.status)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `late automatic capture flags both AI text and AI voice matching records`() = runTest {
        listOf(TxSource.AI_TEXT, TxSource.AI_VOICE).forEachIndexed { index, source ->
            val merchant = "小王面馆${index + 1}店"
            val original = tx(source, merchant).copy(status = TxStatus.CONFIRMED,
                occurredAt = time - 10_000, note = "已通过AI补记")
            val originalId = db.transactionDao().insert(original)

            val result = capture(evidence(event = "late-$source"),
                tx(TxSource.ACCESSIBILITY, merchant)) as CaptureResult.Inserted

            assertEquals(originalId, result.duplicateOf)
            assertEquals(original.copy(id = originalId), db.transactionDao().getById(originalId))
            assertEquals(TxStatus.PENDING, db.transactionDao().getById(result.transactionId)!!.status)
        }
        assertEquals(4, db.backupDao().transactions().size)
    }

    @Test fun `manual fallback rejects amount-only matches ignored records old entries and other direction`() = runTest {
        val manual = tx(TxSource.MANUAL).copy(status = TxStatus.CONFIRMED)
        listOf(
            manual.copy(merchant = "其他商户"),
            manual.copy(merchant = null),
            manual.copy(status = TxStatus.IGNORED),
            manual.copy(occurredAt = time - 120_001),
            manual.copy(type = TxType.INCOME),
            manual.copy(amount = 1900)
        ).forEach { db.transactionDao().insert(it) }

        val result = capture() as CaptureResult.Inserted

        assertNull(result.duplicateOf)
        assertEquals(7, db.backupDao().transactions().size)
    }
}

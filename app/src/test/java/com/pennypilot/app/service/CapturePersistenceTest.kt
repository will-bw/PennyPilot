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
import kotlinx.coroutines.test.runTest
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
        // 被用户删除的账目不留下永久的订单/事件墓碑。
        assertTrue(capture(original) is CaptureResult.Inserted)
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

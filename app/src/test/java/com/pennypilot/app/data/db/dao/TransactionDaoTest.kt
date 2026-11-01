package com.pennypilot.app.data.db.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room DAO 单测（设计文档 §9）：内存库 + Robolectric，
 * 覆盖状态机流转、捕获计数回填、月度聚合与 IGNORED 排除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tx(
        amount: Long,
        type: TxType = TxType.EXPENSE,
        occurredAt: Long = 1_750_000_000_000L,
        createdAt: Long = occurredAt,
        source: TxSource = TxSource.MANUAL,
        status: TxStatus = TxStatus.CONFIRMED
    ) = TransactionEntity(
        amount = amount,
        type = type,
        categoryId = 1L,
        occurredAt = occurredAt,
        createdAt = createdAt,
        source = source,
        status = status
    )

    @Test
    fun `semantic history excludes automatic placeholders but keeps corrected and explicit transfers`() = runTest {
        val provisional = tx(1700, source = TxSource.ACCESSIBILITY).copy(merchant = "小林",
            businessKind = "transfer", note = "用途暂按资金转移记录")
        dao.insert(provisional)
        dao.insert(provisional.copy(note = null, metadataJson = """{"assumptions":["用途暂按资金转移，不计入消费"]}"""))
        val corrected = dao.insert(provisional.copy(editVersion = 1))
        val deliberate = dao.insert(provisional.copy(note = "本人账户转移"))
        val manual = dao.insert(provisional.copy(source = TxSource.MANUAL))
        val expense = dao.insert(provisional.copy(businessKind = "expense", note = "晚餐"))
        val history = dao.relatedCaptureHistory("小林", TxType.EXPENSE)
        assertEquals(setOf(corrected, deliberate, manual, expense), history.map { it.id }.toSet())
    }

    @Test
    fun `pending can transition to confirmed and ignored`() = runTest {
        val pendingId = dao.insert(tx(1800, status = TxStatus.PENDING))
        assertEquals(1, dao.observePendingCount().first())

        dao.updateStatus(pendingId, TxStatus.CONFIRMED)
        assertEquals(TxStatus.CONFIRMED, dao.getById(pendingId)?.status)
        assertEquals(0, dao.observePendingCount().first())

        val another = dao.insert(tx(2000, status = TxStatus.PENDING))
        dao.updateStatus(another, TxStatus.IGNORED)
        assertEquals(TxStatus.IGNORED, dao.getById(another)?.status)
    }

    @Test
    fun `reassigning a deleted category invalidates stale transaction versions`() = runTest {
        val changed = dao.insert(tx(1800))
        val unchanged = dao.insert(tx(2000).copy(categoryId = 3, editVersion = 2))

        dao.reassignCategory(1, 2)

        assertEquals(2L, dao.getById(changed)?.categoryId)
        assertEquals(1L, dao.getById(changed)?.editVersion)
        assertEquals(3L, dao.getById(unchanged)?.categoryId)
        assertEquals(2L, dao.getById(unchanged)?.editVersion)
    }

    @Test
    fun `countBySourceSince only counts matching source and window`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(tx(1800, createdAt = now, source = TxSource.NOTIFICATION, status = TxStatus.PENDING))
        dao.insert(tx(2500, createdAt = now, source = TxSource.NOTIFICATION, status = TxStatus.PENDING))
        dao.insert(tx(3000, createdAt = now, source = TxSource.MANUAL))
        // 窗口之外的通知捕获不计入
        dao.insert(tx(1200, createdAt = now - 2 * 86_400_000L, source = TxSource.NOTIFICATION))

        val todayStart = now - 3_600_000L
        assertEquals(2, dao.countBySourceSince(TxSource.NOTIFICATION, todayStart))
        assertEquals(1, dao.countBySourceSince(TxSource.MANUAL, todayStart))
        // since 在未来 → 无结果
        assertEquals(0, dao.countBySourceSince(TxSource.NOTIFICATION, now + 3_600_000L))
    }

    @Test
    fun `month aggregates exclude ignored transactions`() = runTest {
        val start = 1_749_900_000_000L
        val end = 1_750_900_000_000L
        dao.insert(tx(1800, occurredAt = start + 1000))                    // CONFIRMED 支出
        dao.insert(tx(2000, occurredAt = start + 2000, status = TxStatus.IGNORED)) // 被忽略
        dao.insert(tx(-5000, type = TxType.INCOME, occurredAt = start + 3000))     // 收入

        assertEquals(1800L, dao.observeMonthExpense(start, end).first())
        assertEquals(-5000L, dao.observeMonthIncome(start, end).first())
    }

    @Test
    fun `observeByMonth returns non-ignored transactions only`() = runTest {
        val start = 1_749_900_000_000L
        val end = 1_750_900_000_000L
        dao.insert(tx(1800, occurredAt = start + 1000))
        dao.insert(tx(2000, occurredAt = start + 2000, status = TxStatus.IGNORED))
        dao.insert(tx(900, occurredAt = start + 3000, status = TxStatus.PENDING))

        val list = dao.observeByMonth(start, end).first()
        assertEquals(2, list.size)
        assertTrue(list.none { it.status == TxStatus.IGNORED })
    }

    @Test
    fun `stale actions cannot change terminal transaction status`() = runTest {
        val confirmedId = dao.insert(tx(1800, status = TxStatus.PENDING))
        assertEquals(1, dao.updateStatus(confirmedId, TxStatus.CONFIRMED))
        assertEquals(0, dao.updateStatus(confirmedId, TxStatus.IGNORED))
        assertEquals(TxStatus.CONFIRMED, dao.getById(confirmedId)?.status)

        val ignoredId = dao.insert(tx(2000, status = TxStatus.PENDING))
        assertEquals(1, dao.updateStatus(ignoredId, TxStatus.IGNORED))
        assertEquals(0, dao.updateStatus(ignoredId, TxStatus.CONFIRMED))
        assertEquals(TxStatus.IGNORED, dao.getById(ignoredId)?.status)
    }

    @Test
    fun `queued notification action cannot affect a restored row reusing its ID`() = runTest {
        val old = tx(1800, source = TxSource.NOTIFICATION, status = TxStatus.PENDING)
            .copy(id = 42, captureEventKey = "old-event")
        dao.insertCaptured(old)
        dao.deleteById(42)
        dao.insertCaptured(old.copy(amount = 9900, captureEventKey = "restored-event"))

        assertEquals(0, dao.updateCapturedStatus(42, "old-event", TxStatus.IGNORED))
        assertEquals(TxStatus.PENDING, dao.getById(42)?.status)
        assertEquals(9900L, dao.getById(42)?.amount)
        assertEquals(1, dao.updateCapturedStatus(42, "restored-event", TxStatus.CONFIRMED))
        assertEquals(0, dao.updateCapturedStatus(42, "restored-event", TxStatus.IGNORED))
    }

    @Test
    fun `captured event conflicts are persisted without replacing ignored transaction`() = runTest {
        val captured = tx(1800, source = TxSource.NOTIFICATION, status = TxStatus.PENDING)
            .copy(captureEventKey = "wechat:key:1000", rawPayload = "付款18元")
        val id = dao.insertCaptured(captured)
        assertTrue(id > 0)
        dao.updateStatus(id, TxStatus.IGNORED)
        assertTrue(db.transactionDao().hasCaptureEvent(captured.captureEventKey!!))
        assertEquals(-1L, db.transactionDao().insertCaptured(captured))
        assertEquals(TxStatus.IGNORED, dao.getById(id)?.status)
        assertEquals(1, dao.countBySourceSince(TxSource.NOTIFICATION, 0))
    }

    @Test
    fun `same text from independent events and manual entries is preserved`() = runTest {
        val captured = tx(1800, source = TxSource.NOTIFICATION, status = TxStatus.PENDING)
            .copy(captureEventKey = "wechat:key1:1000", rawPayload = "付款18元")
        assertTrue(dao.insertCaptured(captured) > 0)
        assertTrue(dao.insertCaptured(captured.copy(captureEventKey = "wechat:key2:1000")) > 0)
        assertTrue(dao.insert(tx(1800)) > 0)
        assertTrue(dao.insert(tx(1800)) > 0)
        assertEquals(2, dao.countBySourceSince(TxSource.NOTIFICATION, 0))
    }

    @Test
    fun `month boundaries include start and exclude next month midnight`() = runTest {
        val start = 1000L
        val end = 2000L
        dao.insert(tx(100, occurredAt = start - 1))
        dao.insert(tx(200, occurredAt = start))
        dao.insert(tx(-300, type = TxType.INCOME, occurredAt = end - 1))
        dao.insert(tx(400, occurredAt = end))
        dao.insert(tx(-500, type = TxType.INCOME, occurredAt = end))
        assertEquals(2, dao.observeByMonth(start, end).first().size)
        assertEquals(200L, dao.observeMonthExpense(start, end).first())
        assertEquals(-300L, dao.observeMonthIncome(start, end).first())
        assertEquals(2, dao.observeByMonth(end, end + 1000).first().size)
    }
}

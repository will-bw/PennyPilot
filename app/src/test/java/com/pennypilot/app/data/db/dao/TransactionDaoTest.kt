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
}

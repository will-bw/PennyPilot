package com.pennypilot.app.data.db.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InsightDaoTrendTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InsightDao
    private lateinit var txDao: TransactionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.insightDao()
        txDao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ymOf(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM"))

    private fun tx(
        amount: Long,
        type: TxType = TxType.EXPENSE,
        occurredAt: Long,
        status: TxStatus = TxStatus.CONFIRMED
    ) = TransactionEntity(
        amount = amount,
        type = type,
        categoryId = 1L,
        occurredAt = occurredAt,
        createdAt = occurredAt,
        source = TxSource.MANUAL,
        status = status
    )

    // 2026-03-15 / 2026-04-10 / 2026-08-01（UTC 视角的固定毫秒，测试用 ymOf 推导期望）
    private val mar = 1_773_500_000_000L
    private val apr = 1_775_900_000_000L
    private val aug = 1_785_600_000_000L

    @Test
    fun `empty range returns empty list`() = runTest {
        assertTrue(dao.monthlyExpenseTrend(mar, aug + 1).isEmpty())
    }

    @Test
    fun `single month sums confirmed expenses only`() = runTest {
        txDao.insert(tx(1800, occurredAt = aug))
        txDao.insert(tx(700, occurredAt = aug + 3_600_000))
        txDao.insert(tx(-5000, type = TxType.INCOME, occurredAt = aug + 7_200_000))
        txDao.insert(tx(9999, occurredAt = aug, status = TxStatus.PENDING))

        val rows = dao.monthlyExpenseTrend(aug - 86_400_000L * 40, aug + 86_400_000L * 40)
        assertEquals(1, rows.size)
        assertEquals(2500L, rows[0].total)
    }

    @Test
    fun `multiple months ordered ascending`() = runTest {
        txDao.insert(tx(1000, occurredAt = mar))
        txDao.insert(tx(2000, occurredAt = apr))
        txDao.insert(tx(3000, occurredAt = aug))

        val rows = dao.monthlyExpenseTrend(mar - 86_400_000L, aug + 86_400_000L * 40)
        assertEquals(3, rows.size)
        assertEquals(listOf(ymOf(mar), ymOf(apr), ymOf(aug)), rows.map { it.ym })
        assertEquals(listOf(1000L, 2000L, 3000L), rows.map { it.total })
    }

    @Test
    fun `window is start-inclusive end-exclusive`() = runTest {
        txDao.insert(tx(1000, occurredAt = apr))
        val rows = dao.monthlyExpenseTrend(apr, apr + 1)
        assertEquals(1, rows.size)

        val excluded = dao.monthlyExpenseTrend(apr + 1, aug)
        assertTrue(excluded.isEmpty())
    }
}

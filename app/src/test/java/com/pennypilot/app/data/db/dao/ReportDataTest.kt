package com.pennypilot.app.data.db.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.worker.saveReportIfCurrent
import kotlinx.coroutines.flow.first
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
class ReportDataTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `replacing a month retains one complete latest report`() = runTest {
        val dao = db.monthlyReportDao()
        dao.insert(MonthlyReportEntity(yearMonth = "2026-10", totalExpense = 100, totalIncome = 0, byCategoryJson = "[]", llmSummary = "旧"))
        dao.insert(MonthlyReportEntity(yearMonth = "2026-10", totalExpense = 250, totalIncome = 500, byCategoryJson = "[]", llmSummary = "新"))
        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertEquals(250L, rows.single().totalExpense)
        assertEquals("新", rows.single().llmSummary)
    }

    @Test
    fun `report prepared before a ledger restore cannot overwrite restored report`() = runTest {
        val snapshotRevision = LedgerRevision.current
        val restored = MonthlyReportEntity(yearMonth = "2026-10", totalExpense = 250,
            totalIncome = 500, byCategoryJson = "[]", llmSummary = "恢复的账本")
        db.monthlyReportDao().insert(restored)
        LedgerRevision.advance()
        val stale = restored.copy(totalExpense = 9999, llmSummary = "旧账本的延迟 AI 结果")
        assertFalse(saveReportIfCurrent(db, stale, snapshotRevision))
        assertEquals("恢复的账本", db.monthlyReportDao().getByMonth("2026-10")?.llmSummary)
        assertTrue(saveReportIfCurrent(db, restored.copy(llmSummary = "新账本重新计算"), LedgerRevision.current))
        assertEquals("新账本重新计算", db.monthlyReportDao().getByMonth("2026-10")?.llmSummary)
    }

    @Test
    fun `monthly trend observer refreshes on insert edit and delete`() = runTest {
        val txs = db.transactionDao()
        val original = TransactionEntity(amount = 100, type = TxType.EXPENSE, categoryId = 1, occurredAt = 1000)
        db.insightDao().observeMonthlyExpenseTrend(0, 2000).test {
            assertTrue(awaitItem().isEmpty())
            val id = txs.insert(original)
            assertEquals(100L, awaitItem().single().total)
            txs.update(original.copy(id = id, amount = 250))
            assertEquals(250L, awaitItem().single().total)
            txs.deleteById(id)
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `daily observer refreshes when a pending expense is confirmed`() = runTest {
        val id = db.transactionDao().insert(TransactionEntity(amount = 300, type = TxType.EXPENSE,
            categoryId = 1, occurredAt = 1000, status = TxStatus.PENDING))
        db.insightDao().observeDailyExpenses(0, 2000).test {
            assertTrue(awaitItem().isEmpty())
            db.transactionDao().updateStatus(id, TxStatus.CONFIRMED)
            assertEquals(300L, awaitItem().single().total)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

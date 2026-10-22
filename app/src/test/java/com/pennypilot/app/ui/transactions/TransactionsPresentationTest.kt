package com.pennypilot.app.ui.transactions

import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TransactionsPresentationTest {
    private val day = LocalDate.of(2026, 10, 22)
    private val stamp = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val food = CategoryEntity(1, "餐饮", "", 0, TxType.EXPENSE, 0)
    private val travel = CategoryEntity(2, "交通", "", 0, TxType.EXPENSE, 1)
    private fun tx(id: Long, amount: Long, cat: Long, status: TxStatus = TxStatus.CONFIRMED, merchant: String? = null) =
        TransactionEntity(id = id, amount = amount, categoryId = cat, status = status, merchant = merchant,
            type = if (amount < 0) TxType.INCOME else TxType.EXPENSE, occurredAt = stamp)
    private fun state(rows: List<TransactionEntity>, filter: TxFilter = TxFilter.All, query: String = "", cats: List<CategoryEntity> = listOf(food, travel)) =
        buildTransactionsState(rows, cats, day.withDayOfMonth(1), filter, query, day, ZoneOffset.UTC)

    @Test fun `category filter recalculates day totals while month totals stay confirmed and unfiltered`() {
        val result = state(listOf(tx(1, 100, 1), tx(2, 200, 2), tx(3, 900, 1, TxStatus.PENDING), tx(4, -500, 3)), TxFilter.Category(1))
        assertEquals(300L, result.monthExpense)
        assertEquals(500L, result.monthIncome)
        assertEquals(100L, result.dayGroups.single().expense)
        assertEquals(0L, result.dayGroups.single().income)
        assertEquals(1, result.dayGroups.single().pendingCount)
        assertEquals(listOf(1L, 3L), result.dayGroups.single().items.map { it.tx.id })
    }

    @Test fun `pending view shows pending counts without treating them as posted money`() {
        val result = state(listOf(tx(1, 100, 1), tx(2, 800, 2, TxStatus.PENDING)), TxFilter.Pending)
        assertEquals(1, result.pendingCount)
        assertEquals(0L, result.dayGroups.single().expense)
        assertEquals(1, result.dayGroups.single().pendingCount)
        assertEquals(100L, result.monthExpense)
    }

    @Test fun `search trims input ignores case and respects active filter`() {
        val rows = listOf(tx(1, 100, 1, merchant = "Coffee Shop"), tx(2, 200, 2, merchant = "COFFEE taxi"))
        assertEquals(listOf(1L), state(rows, TxFilter.Category(1), " coffee ").dayGroups.single().items.map { it.tx.id })
        assertEquals(listOf(2L), state(rows, query = "交通").dayGroups.single().items.map { it.tx.id })
        assertTrue(state(rows, query = "不存在").dayGroups.isEmpty())
    }

    @Test fun `all enabled categories and referenced disabled categories remain selectable`() {
        val categories = (1L..9L).map { food.copy(id = it, sortOrder = it.toInt(), enabled = it != 9L) } +
            food.copy(id = 10L, type = TxType.INCOME, name = "工资")
        val result = state(listOf(tx(1, 100, 9)), cats = categories)
        assertEquals(10, result.categories.size)
        assertTrue(result.categories.any { it.id == 9L })
        assertTrue(result.categories.any { it.type == TxType.INCOME })
    }

    @Test fun `income filter keeps income positive for display and excludes expense rows`() {
        val result = state(listOf(tx(1, -12345, 3), tx(2, 500, 1)), TxFilter.Type(TxType.INCOME))
        assertEquals(12345L, result.dayGroups.single().income)
        assertEquals(0L, result.dayGroups.single().expense)
    }
}

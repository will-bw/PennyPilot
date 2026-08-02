package com.pennypilot.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TxWithCategory(
    val tx: TransactionEntity,
    val category: CategoryEntity?
)

data class DayGroup(
    val label: String,
    val netAmount: Long,
    val items: List<TxWithCategory>
)

data class TransactionsUiState(
    val monthExpense: Long = 0,
    val monthIncome: Long = 0,
    val pendingCount: Int = 0,
    val dayGroups: List<DayGroup> = emptyList(),
    val selectedMonth: LocalDate = LocalDate.now()
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow(LocalDate.now())

    // Cache categories for in-memory join
    private val categories = catDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState = _selectedMonth.flatMapLatest { month ->
        val start = month.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = month.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        combine(
            txDao.observeMonthExpense(start, end),
            txDao.observeMonthIncome(start, end),
            txDao.observePendingCount(),
            txDao.observeByMonth(start, end),
            categories
        ) { expense, income, pending, txs, cats ->
            val catMap = cats.associateBy { it.id }
            TransactionsUiState(
                monthExpense = expense,
                monthIncome = -income,
                pendingCount = pending,
                dayGroups = groupByDay(txs, catMap),
                selectedMonth = month
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionsUiState())

    fun shiftMonth(delta: Int) {
        _selectedMonth.update { it.plusMonths(delta.toLong()) }
    }

    fun confirmTx(id: Long) = viewModelScope.launch {
        txDao.updateStatus(id, TxStatus.CONFIRMED)
    }

    fun ignoreTx(id: Long) = viewModelScope.launch {
        txDao.updateStatus(id, TxStatus.IGNORED)
    }

    private fun groupByDay(txs: List<TransactionEntity>, catMap: Map<Long, CategoryEntity>): List<DayGroup> {
        val today = LocalDate.now()
        return txs.groupBy {
            Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.toSortedMap(reverseOrder()).map { (date, dayTxs) ->
            val label = when (date) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
            DayGroup(
                label = label,
                netAmount = -dayTxs.sumOf { it.amount },
                items = dayTxs.map { TxWithCategory(it, catMap[it.categoryId]) }
            )
        }
    }
}

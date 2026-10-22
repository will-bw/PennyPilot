package com.pennypilot.app.ui.transactions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.service.NotificationHelper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
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

/** 筛选条件：全部 / 待确认 / 指定分类（原型 chips 行） */
sealed interface TxFilter {
    data object All : TxFilter
    data object Pending : TxFilter
    data class Category(val categoryId: Long) : TxFilter
}

data class DayGroup(
    val label: String,
    val dateText: String,
    val netAmount: Long,
    val items: List<TxWithCategory>
)

data class TransactionsUiState(
    val monthExpense: Long = 0,
    val monthIncome: Long = 0,
    val pendingCount: Int = 0,
    val dayGroups: List<DayGroup> = emptyList(),
    val selectedMonth: LocalDate = LocalDate.now(),
    val filter: TxFilter = TxFilter.All,
    val expenseCategories: List<CategoryEntity> = emptyList()
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao,
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow(LocalDate.now())
    private val _filter = MutableStateFlow<TxFilter>(TxFilter.All)

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
                selectedMonth = month,
                expenseCategories = cats
                    .filter { it.type == TxType.EXPENSE && it.enabled }
                    .sortedBy { it.sortOrder }
                    .take(5)
            )
        }
    }.flatMapLatest { base ->
        _filter.map { f ->
            base.copy(
                filter = f,
                dayGroups = applyFilter(base.dayGroups, f)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionsUiState())

    // 近 30 日支出节奏条：窗口截止于选中月月末与今天的较早者
    @OptIn(ExperimentalCoroutinesApi::class)
    val stripData: StateFlow<Pair<List<Long>, LocalDate>> = _selectedMonth.flatMapLatest { month ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val isCurrentMonth = month.year == today.year && month.month == today.month
        val windowEnd = if (isCurrentMonth) today else month.withDayOfMonth(month.lengthOfMonth())
        val windowStart = windowEnd.minusDays(29)
        val startMs = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = windowEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        txDao.observeByMonth(startMs, endMs).map { txs ->
            val byDay = txs.asSequence()
                .filter { it.type == TxType.EXPENSE && it.status == TxStatus.CONFIRMED }
                .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
                .mapValues { (_, v) -> v.sumOf { it.amount } }
            val values = (0L..29L).map { byDay[windowStart.plusDays(it)] ?: 0L }
            values to windowStart
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Long>() to LocalDate.now().minusDays(29))

    fun shiftMonth(delta: Int) {
        _selectedMonth.update { it.plusMonths(delta.toLong()) }
    }

    fun setFilter(filter: TxFilter) {
        _filter.value = filter
    }

    private fun applyFilter(groups: List<DayGroup>, filter: TxFilter): List<DayGroup> =
        when (filter) {
            TxFilter.All -> groups
            TxFilter.Pending -> groups.mapNotNull { g ->
                val items = g.items.filter { it.tx.status == TxStatus.PENDING }
                if (items.isEmpty()) null else g.copy(items = items)
            }
            is TxFilter.Category -> groups.mapNotNull { g ->
                val items = g.items.filter { it.tx.categoryId == filter.categoryId }
                if (items.isEmpty()) null else g.copy(items = items)
            }
        }

    fun confirmTx(id: Long) = processPending(id, TxStatus.CONFIRMED)

    fun ignoreTx(id: Long) = processPending(id, TxStatus.IGNORED)

    private fun processPending(id: Long, status: TxStatus) {
        val revision = LedgerRevision.current
        viewModelScope.launch {
            val processed = database.withTransaction {
                if (LedgerRevision.current != revision) return@withTransaction null
                val tx = txDao.getById(id) ?: return@withTransaction null
                if (txDao.updateStatus(id, status) == 0) null else tx
            }
            processed?.let { NotificationHelper.cancelCaptureNotification(context, id, it.captureEventKey) }
        }
    }

    /** 已确认行左滑删除（编辑页删除共用同一入口） */
    fun deleteTx(id: Long) = viewModelScope.launch {
        txDao.deleteById(id)
    }

    private fun groupByDay(txs: List<TransactionEntity>, catMap: Map<Long, CategoryEntity>): List<DayGroup> {
        val today = LocalDate.now()
        val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return txs.groupBy {
            Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.toSortedMap(reverseOrder()).map { (date, dayTxs) ->
            val weekday = weekdays[date.dayOfWeek.value - 1]
            val dateStr = "${date.monthValue}月${date.dayOfMonth}日"
            // 原型：今天/昨天 加「M月D日 · 周X」；更早日期以星期为主标签
            val (label, dateText) = when (date) {
                today -> "今天" to "$dateStr · $weekday"
                today.minusDays(1) -> "昨天" to "$dateStr · $weekday"
                else -> weekday to dateStr
            }
            DayGroup(
                label = label,
                dateText = dateText,
                netAmount = -dayTxs.sumOf { it.amount },
                items = dayTxs.map { TxWithCategory(it, catMap[it.categoryId]) }
            )
        }
    }
}

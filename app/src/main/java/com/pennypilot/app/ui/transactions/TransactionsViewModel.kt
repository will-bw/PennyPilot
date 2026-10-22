package com.pennypilot.app.ui.transactions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.util.DateRanges
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class TxWithCategory(val tx: TransactionEntity, val category: CategoryEntity?)

sealed interface TxFilter {
    data object All : TxFilter
    data object Pending : TxFilter
    data class Type(val type: TxType) : TxFilter
    data class Category(val categoryId: Long) : TxFilter
}

data class DayGroup(
    val date: LocalDate,
    val label: String,
    val dateText: String,
    val expense: Long,
    val income: Long,
    val pendingCount: Int,
    val items: List<TxWithCategory>
)

data class TransactionsUiState(
    val monthExpense: Long = 0,
    val monthIncome: Long = 0,
    val pendingCount: Int = 0,
    val monthRecordCount: Int = 0,
    val dayGroups: List<DayGroup> = emptyList(),
    val selectedMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
    val today: LocalDate = LocalDate.now(),
    val filter: TxFilter = TxFilter.All,
    val query: String = "",
    val categories: List<CategoryEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val processingIds: Set<Long> = emptySet()
)

/** 月汇总始终是全月已确认记录，日汇总仅包含当前筛选结果中的已确认记录。 */
internal fun buildTransactionsState(
    txs: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    month: LocalDate,
    filter: TxFilter,
    query: String,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): TransactionsUiState {
    val categoryMap = categories.associateBy { it.id }
    val confirmed = txs.filter { it.status == TxStatus.CONFIRMED }
    val needle = query.trim()
    val visible = txs.filter { tx ->
        val matchesFilter = when (filter) {
            TxFilter.All -> true
            TxFilter.Pending -> tx.status == TxStatus.PENDING
            is TxFilter.Type -> tx.type == filter.type
            is TxFilter.Category -> tx.categoryId == filter.categoryId
        }
        matchesFilter && (needle.isEmpty() || listOf(tx.merchant, tx.note, categoryMap[tx.categoryId]?.name)
            .any { it?.contains(needle, ignoreCase = true) == true })
    }
    val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val groups = visible.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        .toSortedMap(reverseOrder()).map { (date, dayTxs) ->
            val posted = dayTxs.filter { it.status == TxStatus.CONFIRMED }
            DayGroup(
                date = date,
                label = when (date) { today -> "今天"; today.minusDays(1) -> "昨天"; else -> weekdays[date.dayOfWeek.value - 1] },
                dateText = "${date.monthValue}月${date.dayOfMonth}日",
                expense = posted.filter { it.type == TxType.EXPENSE }.sumOf { it.amount },
                income = -posted.filter { it.type == TxType.INCOME }.sumOf { it.amount },
                pendingCount = dayTxs.count { it.status == TxStatus.PENDING },
                items = dayTxs.sortedByDescending { it.occurredAt }.map { TxWithCategory(it, categoryMap[it.categoryId]) }
            )
        }
    return TransactionsUiState(
        monthExpense = confirmed.filter { it.type == TxType.EXPENSE }.sumOf { it.amount },
        monthIncome = -confirmed.filter { it.type == TxType.INCOME }.sumOf { it.amount },
        pendingCount = txs.count { it.status == TxStatus.PENDING },
        monthRecordCount = txs.size,
        dayGroups = groups,
        selectedMonth = month,
        today = today,
        filter = filter,
        query = query,
        categories = categories.filter { it.enabled || txs.any { tx -> tx.categoryId == it.id } }
            .sortedWith(compareBy<CategoryEntity> { it.type }.thenBy { it.sortOrder }),
        isLoading = false
    )
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel @Inject constructor(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao,
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) : ViewModel() {
    private val selectedMonth = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    private val filter = MutableStateFlow<TxFilter>(TxFilter.All)
    private val query = MutableStateFlow("")
    private val refresh = MutableStateFlow(0)
    private val processingIds = MutableStateFlow<Set<Long>>(emptySet())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private val today = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000)
        }
    }.distinctUntilChanged()

    private val monthData = combine(selectedMonth, refresh, today) { month, _, day -> month to day }
        .flatMapLatest { (month, day) ->
            val range = DateRanges.month(YearMonth.from(month))
            combine(txDao.observeByMonth(range.start, range.endExclusive), catDao.observeAll()) { txs, cats ->
                buildTransactionsState(txs, cats, month, TxFilter.All, "", day)
            }.onStart { emit(TransactionsUiState(selectedMonth = month, today = day)) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(TransactionsUiState(selectedMonth = month, today = day, isLoading = false, error = "暂时无法读取账单，请重试。"))
                }
        }

    val uiState: StateFlow<TransactionsUiState> = combine(monthData, filter, query, processingIds) { base, f, q, busy ->
        if (base.isLoading || base.error != null) base.copy(filter = f, query = q, processingIds = busy)
        else buildTransactionsState(base.dayGroups.flatMap { g -> g.items.map { it.tx } }, base.categories,
            base.selectedMonth, f, q, base.today).copy(processingIds = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionsUiState())

    // 保持独立的完整 30 日窗口，不随搜索/分类筛选改变。
    val stripData: StateFlow<Pair<List<Long>, LocalDate>> = combine(selectedMonth, refresh, today) { month, _, day -> month to day }
        .flatMapLatest { (month, day) ->
            val windowEnd = minOf(month.withDayOfMonth(month.lengthOfMonth()), day)
            val windowStart = windowEnd.minusDays(29)
            val zone = ZoneId.systemDefault()
            txDao.observeByMonth(windowStart.atStartOfDay(zone).toInstant().toEpochMilli(),
                windowEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()).map { txs ->
                val byDay = txs.filter { it.type == TxType.EXPENSE && it.status == TxStatus.CONFIRMED }
                    .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
                    .mapValues { (_, rows) -> rows.sumOf { it.amount } }
                (0L..29L).map { byDay[windowStart.plusDays(it)] ?: 0L } to windowStart
            }.onStart { emit(emptyList<Long>() to windowStart) }
                .catch { e -> if (e is CancellationException) throw e; emit(emptyList<Long>() to windowStart) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Long>() to LocalDate.now().minusDays(29))

    fun shiftMonth(delta: Int) { selectedMonth.update { it.plusMonths(delta.toLong()) } }
    fun returnToCurrentMonth() { selectedMonth.value = LocalDate.now().withDayOfMonth(1) }
    fun setFilter(value: TxFilter) { filter.value = value }
    fun setQuery(value: String) { query.value = value }
    fun clearFilters() { filter.value = TxFilter.All; query.value = "" }
    fun retry() { refresh.update { it + 1 } }
    fun confirmTx(id: Long) = processPending(id, TxStatus.CONFIRMED)
    fun ignoreTx(id: Long) = processPending(id, TxStatus.IGNORED)

    private fun processPending(id: Long, status: TxStatus) {
        val revision = LedgerRevision.current
        runAction(id) {
        val processed = database.withTransaction {
            if (LedgerRevision.current != revision) return@withTransaction null
            val tx = txDao.getById(id) ?: return@withTransaction null
            if (txDao.updateStatus(id, status) == 0) null else tx
        }
        processed?.let { NotificationHelper.cancelCaptureNotification(context, id, it.captureEventKey) }
        if (processed == null) "记录已变化，请刷新后重试。" else if (status == TxStatus.CONFIRMED) "已记入账本" else "已忽略该记录"
        }
    }

    fun deleteTx(id: Long) {
        val revision = LedgerRevision.current
        runAction(id) {
        val deleted = database.withTransaction {
            if (LedgerRevision.current != revision) false
            else txDao.getById(id)?.let { txDao.deleteById(id); true } ?: false
        }
        if (deleted) "记录已删除" else "记录已变化，请刷新后重试。"
        }
    }

    private fun runAction(id: Long, action: suspend () -> String) {
        if (id in processingIds.value) return
        processingIds.update { it + id }
        viewModelScope.launch {
            try { messages.send(action()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { messages.send("操作未完成，请重试。") }
            finally { processingIds.update { it - id } }
        }
    }
}

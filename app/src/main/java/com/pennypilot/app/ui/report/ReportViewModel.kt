package com.pennypilot.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.MonthlyReportDao
import com.pennypilot.app.data.db.dao.MonthlyTrend
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.util.DateRanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel @Inject constructor(
    reportDao: MonthlyReportDao,
    categoryDao: CategoryDao,
    insightDao: InsightDao
) : ViewModel() {

    val reports: StateFlow<List<MonthlyReportEntity>> = reportDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Top3 分类条需要按名称匹配分类图标/色对
    val categories: StateFlow<List<CategoryEntity>> = categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 页面保持打开跨月时也重建查询；后台没有订阅者时停止计时。
    private val currentMonth = flow {
        while (true) {
            emit(YearMonth.now())
            delay(60_000)
        }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YearMonth.now())

    /** Room Flow 在新增、编辑、删除与确认交易后重新聚合。 */
    val trend: StateFlow<List<MonthlyTrend>> = currentMonth.flatMapLatest { month ->
        val first = month.minusMonths(5)
        insightDao.observeMonthlyExpenseTrend(DateRanges.month(first).start, DateRanges.month(month).endExclusive)
            .map { rows ->
                val byYm = rows.associateBy { it.ym }
                (0 until 6).map { i ->
                    val ym = first.plusMonths(i.toLong()).toString()
                    byYm[ym] ?: MonthlyTrend(ym, 0)
                }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当月每日支出（热力格数据） */
    val heat: StateFlow<List<com.pennypilot.app.data.db.dao.DailyAggregate>> = currentMonth.flatMapLatest { month ->
        val range = DateRanges.month(month)
        insightDao.observeDailyExpenses(range.start, range.endExclusive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

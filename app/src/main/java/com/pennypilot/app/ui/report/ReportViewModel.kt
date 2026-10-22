package com.pennypilot.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.MonthlyReportDao
import com.pennypilot.app.data.db.dao.MonthlyTrend
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
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

    /** 近 6 个月（含当月）每月支出，缺月补 0，升序 */
    val trend: StateFlow<List<MonthlyTrend>> = flow {
        val now = LocalDate.now()
        val startMonth = now.minusMonths(5).withDayOfMonth(1)
        val startMs = startMonth.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = now.plusMonths(1).withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val byYm = insightDao.monthlyExpenseTrend(startMs, endMs).associateBy { it.ym }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM")
        emit((0 until 6).map { i ->
            val ym = startMonth.plusMonths(i.toLong()).format(fmt)
            byYm[ym] ?: MonthlyTrend(ym, 0)
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当月每日支出（热力格数据） */
    val heat: StateFlow<List<com.pennypilot.app.data.db.dao.DailyAggregate>> = flow {
        val now = LocalDate.now()
        val startMs = now.withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = now.plusMonths(1).withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        emit(insightDao.aggregateByDay(startMs, endMs))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

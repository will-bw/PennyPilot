package com.pennypilot.app.ui.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.DailyAggregate
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.MonthlyReportDao
import com.pennypilot.app.data.db.dao.MonthlyTrend
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.util.DateRanges
import com.pennypilot.app.worker.MonthlyReportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

data class ReportUiState(
    val reports: List<MonthlyReportEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val trend: List<MonthlyTrend> = emptyList(),
    val heat: List<DailyAggregate> = emptyList(),
    val currentMonth: YearMonth = YearMonth.now(),
    val isLoading: Boolean = true,
    val error: String? = null
)

data class ReportGeneration(
    val month: YearMonth? = null,
    val state: WorkInfo.State? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null
) {
    val busy: Boolean get() = isSubmitting || (state != null && !state.isFinished)
}

internal fun adjacentPreviousReport(report: MonthlyReportEntity, reports: List<MonthlyReportEntity>): MonthlyReportEntity? {
    val month = runCatching { YearMonth.parse(report.yearMonth) }.getOrNull() ?: return null
    return reports.firstOrNull { it.yearMonth == month.minusMonths(1).toString() }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel @Inject constructor(
    reportDao: MonthlyReportDao,
    categoryDao: CategoryDao,
    insightDao: InsightDao,
    @ApplicationContext context: Context
) : ViewModel() {
    private val workManager = WorkManager.getInstance(context)
    private val refresh = MutableStateFlow(0)
    private val generationState = MutableStateFlow(ReportGeneration())
    val generation: StateFlow<ReportGeneration> = generationState.asStateFlow()
    private var generationJob: Job? = null
    private val currentMonth = flow {
        while (true) { emit(YearMonth.now()); delay(60_000) }
    }.distinctUntilChanged()

    val uiState: StateFlow<ReportUiState> = combine(currentMonth, refresh) { month, _ -> month }
        .flatMapLatest { month ->
            val range = DateRanges.month(month)
            combine(
                reportDao.observeAll(), categoryDao.observeAll(),
                insightDao.observeMonthlyExpenseTrend(DateRanges.month(month.minusMonths(5)).start, range.endExclusive),
                insightDao.observeDailyExpenses(range.start, range.endExclusive)
            ) { reports, categories, trend, heat ->
                val byMonth = trend.associateBy { it.ym }
                ReportUiState(
                    reports = reports.sortedByDescending { it.yearMonth }, categories = categories,
                    trend = (5 downTo 0).map { offset ->
                        val ym = month.minusMonths(offset.toLong()).toString()
                        byMonth[ym] ?: MonthlyTrend(ym, 0)
                    },
                    heat = heat, currentMonth = month, isLoading = false
                )
            }.onStart { emit(ReportUiState(currentMonth = month)) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(ReportUiState(currentMonth = month, isLoading = false, error = "暂时无法读取报告与收支数据，请重试。"))
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportUiState())

    init {
        // 进程重建后接续上月手动报告的状态，避免正在生成时再次展示空闲按钮。
        generationJob = viewModelScope.launch {
            try {
                val month = YearMonth.now().minusMonths(1)
                val running = workManager.getWorkInfosForUniqueWorkFlow("monthly_report_$month").first().firstOrNull { !it.state.isFinished }
                if (running != null) observeGeneration(month, running.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 报表读取不依赖后台任务状态查询。 */ }
        }
    }

    fun retry() { refresh.update { it + 1 } }

    fun regenerate(month: YearMonth) {
        if (generationState.value.busy || month >= YearMonth.now()) return
        generationJob?.cancel()
        generationState.value = ReportGeneration(month = month, isSubmitting = true)
        generationJob = viewModelScope.launch {
            try {
                val request = OneTimeWorkRequestBuilder<MonthlyReportWorker>()
                    .setInputData(workDataOf(MonthlyReportWorker.KEY_FORCE to true, MonthlyReportWorker.KEY_MONTH to month.toString()))
                    .build()
                val name = "monthly_report_$month"
                workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
                // KEEP 可能复用了另一处已排队的任务；追踪实际保存的任务，而非不存在的新 UUID。
                val infos = workManager.getWorkInfosForUniqueWorkFlow(name).first()
                val actual = infos.firstOrNull { it.id == request.id } ?: infos.firstOrNull { !it.state.isFinished }
                if (actual == null) generationState.value = ReportGeneration(month, error = "未能加入生成队列，请重试。")
                else observeGeneration(month, actual.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { generationState.value = ReportGeneration(month, error = "未能开始生成，请重试。") }
        }
    }

    private suspend fun observeGeneration(month: YearMonth, id: UUID) {
        workManager.getWorkInfoByIdFlow(id).collect { info ->
            if (info != null) generationState.value = ReportGeneration(month, state = info.state)
        }
    }
}

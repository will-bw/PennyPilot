package com.pennypilot.app.worker

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.di.AppDatabaseHolder
import com.pennypilot.app.di.AiClientEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.util.DateRanges
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.YearMonth

class MonthlyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_FORCE = "force"
        const val KEY_MONTH = "month"
        private const val MAX_REPORTS_PER_RUN = 6

        fun regenerate(context: Context, month: YearMonth) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "monthly_report_$month",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MonthlyReportWorker>()
                    .setInputData(workDataOf(KEY_FORCE to true, KEY_MONTH to month.toString()))
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabaseHolder.get(applicationContext)
            val currentMonth = YearMonth.now()
            val requestedMonth = inputData.getString(KEY_MONTH)
            val force = inputData.getBoolean(KEY_FORCE, false)
            val months = if (requestedMonth != null) {
                val month = runCatching { YearMonth.parse(requestedMonth) }.getOrNull()
                if (month == null || month >= currentMonth) return Result.failure()
                listOf(month)
            } else {
                missingReportMonths(
                    db.insightDao().transactionMonthsBefore(DateRanges.month(currentMonth).start),
                    db.monthlyReportDao().getMonths(),
                    currentMonth.minusMonths(1)
                )
            }

            var latestGenerated: MonthlyReportEntity? = null
            for (month in months.take(MAX_REPORTS_PER_RUN)) {
                if (!force && db.monthlyReportDao().getByMonth(month.toString()) != null) continue
                latestGenerated = generateReport(db, month) ?: return Result.retry()
            }
            latestGenerated?.let { report ->
                NotificationHelper.postReportNotification(
                    applicationContext,
                    title = "${YearMonth.parse(report.yearMonth).monthValue} 月账本已生成",
                    text = "总支出 ${yuan(report.totalExpense)} 元，点击查看 AI 点评"
                )
            }
            // 限制单次后台执行时长；历史积压按 WorkManager 退避继续补齐。
            if (months.size > MAX_REPORTS_PER_RUN) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun generateReport(db: AppDatabase, month: YearMonth): MonthlyReportEntity? {
        val range = DateRanges.month(month)
        val (snapshotRevision, totals) = db.withTransaction {
            LedgerRevision.current to Triple(
                db.insightDao().totalExpense(range.start, range.endExclusive),
                -db.insightDao().totalIncome(range.start, range.endExclusive),
                db.insightDao().aggregateByCategory(range.start, range.endExclusive, null, null)
            )
        }
        val (expense, income, categories) = totals
        val top3 = categories.take(3)
        val catSummary = top3.joinToString("、") { "${it.categoryName} ${yuan(it.total)}元(${it.count}笔)" }
        val prompt = "请用2-3句话总结${month}的月度账单：总支出${yuan(expense)}元，总收入${yuan(income)}元，主要支出分类：$catSummary。语气轻松友好。"
        val summary = try {
            EntryPointAccessors.fromApplication(applicationContext, AiClientEntryPoint::class.java).llmClient()
                .chat(listOf(Message("user", prompt)), jsonMode = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "${month}支出${yuan(expense)}元，收入${yuan(income)}元。主要支出分类：${catSummary.ifBlank { "暂无支出" }}。"
        }
        val report = MonthlyReportEntity(
            yearMonth = month.toString(),
            totalExpense = expense,
            totalIncome = income,
            byCategoryJson = buildJsonArray {
                top3.forEach { c -> add(buildJsonObject {
                    put("name", c.categoryName)
                    put("total", c.total)
                    put("count", c.count)
                }) }
            }.toString(),
            llmSummary = summary
        )
        return report.takeIf { saveReportIfCurrent(db, it, snapshotRevision) }
    }

    private fun yuan(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}

/** 与恢复共用 Room 写事务，不能在“校验版本”和“写入”之间换账本。 */
internal suspend fun saveReportIfCurrent(db: AppDatabase, report: MonthlyReportEntity, revision: Long): Boolean =
    db.withTransaction {
        if (LedgerRevision.current != revision) return@withTransaction false
        // 唯一月份索引 + REPLACE 保证并发生成也只保留一份完整报告。
        db.monthlyReportDao().insert(report)
        true
    }

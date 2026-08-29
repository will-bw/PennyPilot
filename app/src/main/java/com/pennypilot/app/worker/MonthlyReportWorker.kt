package com.pennypilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.llm.DynamicLlmClient
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.settings.SecureKeyStore
import com.pennypilot.app.di.AppDatabaseHolder
import com.pennypilot.app.service.NotificationHelper
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

class MonthlyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        /** 报告页手动「重新生成」时携带，跳过幂等守卫 */
        const val KEY_FORCE = "force"
    }

    override suspend fun doWork(): Result {
        val db = AppDatabaseHolder.get(applicationContext)
        val lastMonth = LocalDate.now().minusMonths(1)
        val ym = "${lastMonth.year}-${lastMonth.monthValue.toString().padStart(2, '0')}"

        // 幂等守卫：30 天周期可能漂移重复触发，上月报告已存在则直接返回（手动强制重生成除外）
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && db.monthlyReportDao().getByMonth(ym) != null) return Result.success()

        val start = lastMonth.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = lastMonth.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val expense = db.insightDao().totalExpense(start, end)
        // 收入在库中以负数存储（支出为正），报表展示取正数
        val income = -db.insightDao().totalIncome(start, end)
        val categories = db.insightDao().aggregateByCategory(start, end, null, null)
        val top3 = categories.take(3)

        // Build summary prompt
        val catSummary = top3.joinToString("、") { "${it.categoryName} ${it.total / 100}元(${it.count}笔)" }
        val prompt = "请用2-3句话总结以下月度账单：总支出${expense / 100}元，主要分类：$catSummary。语气轻松友好。"

        val summary = try {
            val keyStore = SecureKeyStore(applicationContext)
            // 动态路由到用户当前选择的供应商；未配置时回退到内置模板文案
            val client: LLMClient = DynamicLlmClient(keyStore)
            client.chat(listOf(Message("user", prompt)), jsonMode = false)
        } catch (e: Exception) {
            "本月支出${expense / 100}元，Top分类：$catSummary"
        }

        val catJson = buildJsonArray {
            top3.forEach { c ->
                add(buildJsonObject {
                    put("name", JsonPrimitive(c.categoryName))
                    put("total", JsonPrimitive(c.total))
                    put("count", JsonPrimitive(c.count))
                })
            }
        }.toString()

        // 重新生成时先删除同月旧报告，避免重复行
        db.monthlyReportDao().deleteByMonth(ym)
        db.monthlyReportDao().insert(MonthlyReportEntity(
            yearMonth = ym,
            totalExpense = expense,
            totalIncome = income,
            byCategoryJson = catJson,
            llmSummary = summary
        ))

        // 生成完成提醒，点击打开 App 查看
        NotificationHelper.postReportNotification(
            applicationContext,
            title = "${lastMonth.monthValue} 月账本已生成",
            text = "总支出 ${expense / 100} 元，点击查看 AI 点评"
        )

        return Result.success()
    }
}

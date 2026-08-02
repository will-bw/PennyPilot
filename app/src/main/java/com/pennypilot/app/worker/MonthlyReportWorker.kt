package com.pennypilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.llm.DeepSeekClient
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.QwenClient
import com.pennypilot.app.data.settings.SecureKeyStore
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

    override suspend fun doWork(): Result {
        val db = AppDatabase.build(applicationContext)
        val lastMonth = LocalDate.now().minusMonths(1)
        val ym = "${lastMonth.year}-${lastMonth.monthValue.toString().padStart(2, '0')}"

        val start = lastMonth.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = lastMonth.plusMonths(1).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val expense = db.insightDao().totalExpense(start, end)
        val categories = db.insightDao().aggregateByCategory(start, end, null, null)
        val top3 = categories.take(3)

        // Build summary prompt
        val catSummary = top3.joinToString("、") { "${it.categoryName} ${it.total / 100}元(${it.count}笔)" }
        val prompt = "请用2-3句话总结以下月度账单：总支出${expense / 100}元，主要分类：$catSummary。语气轻松友好。"

        val summary = try {
            val keyStore = SecureKeyStore(applicationContext)
            val client: LLMClient = when (keyStore.activeProvider) {
                "qwen" -> QwenClient(keyStore.qwenKey)
                else -> DeepSeekClient(keyStore.deepSeekKey)
            }
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

        db.monthlyReportDao().insert(MonthlyReportEntity(
            yearMonth = ym,
            totalExpense = expense,
            totalIncome = 0, // simplified
            byCategoryJson = catJson,
            llmSummary = summary
        ))

        return Result.success()
    }
}

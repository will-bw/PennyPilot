package com.pennypilot.app.domain.usecase

import com.pennypilot.app.data.db.dao.CategoryAggregate
import com.pennypilot.app.data.db.dao.DailyAggregate
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.FunctionDef
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 图表数据，供 UI 层渲染洞察图表 */
data class ChartData(
    val labels: List<String>,
    val values: List<Long>,
    val highlightIndex: Int,
    val unit: String = "元"
)

/** 洞察问答结果 */
data class InsightResult(
    val answer: String,
    val chartData: ChartData? = null
)

/**
 * 对话式账目洞察用例：通过 LLM Function Calling，
 * 由模型决定调用哪个本地查询工具，执行后回填结果并得到自然语言回答。
 */
class InsightUseCase @Inject constructor(
    private val llmClient: LLMClient,
    private val insightDao: InsightDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val tools = listOf(
        ToolDefinition(
            function = FunctionDef(
                name = "query_transactions",
                description = "查询交易明细列表",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("start") { put("type", "string"); put("description", "开始日期 yyyy-MM-dd") }
                        putJsonObject("end") { put("type", "string"); put("description", "结束日期 yyyy-MM-dd") }
                        putJsonObject("category") { put("type", "string"); put("description", "分类名，可选") }
                        putJsonObject("keyword") { put("type", "string"); put("description", "关键词（商户/备注），可选") }
                        putJsonObject("limit") { put("type", "integer"); put("description", "返回条数，默认20") }
                    }
                }
            )
        ),
        ToolDefinition(
            function = FunctionDef(
                name = "aggregate_by",
                description = "按分类或按天聚合统计支出",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("start") { put("type", "string"); put("description", "开始日期 yyyy-MM-dd") }
                        putJsonObject("end") { put("type", "string"); put("description", "结束日期 yyyy-MM-dd") }
                        putJsonObject("group_by") { put("type", "string"); put("description", "category 或 day") }
                        putJsonObject("keyword") { put("type", "string"); put("description", "关键词过滤，可选") }
                    }
                }
            )
        ),
        ToolDefinition(
            function = FunctionDef(
                name = "compare_periods",
                description = "对比两个时间段的支出",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("period_a_start") { put("type", "string") }
                        putJsonObject("period_a_end") { put("type", "string") }
                        putJsonObject("period_b_start") { put("type", "string") }
                        putJsonObject("period_b_end") { put("type", "string") }
                    }
                }
            )
        )
    )

    suspend fun ask(question: String, history: List<Message> = emptyList()): InsightResult {
        val systemMsg = Message(
            "system",
            """你是 PennyPilot 记账助手。用户会问关于收支的问题。
使用提供的工具查询数据，然后用简洁中文回答。
规则：
- 必须先调用工具获取数据，不要凭空编造数字
- 回答简洁（2-3句话），包含关键数字
- 金额显示为元（除以100）
当前日期：${LocalDate.now()}"""
        )

        val messages = mutableListOf(systemMsg)
        messages.addAll(history.takeLast(20)) // 10 rounds = 20 messages
        messages.add(Message("user", question))

        return try {
            val response: ChatResponseWithTools = llmClient.chatWithTools(messages, tools)
            val choice = response.choices.firstOrNull()
                ?: return InsightResult("抱歉，我没有理解你的问题。")

            val toolCalls = choice.message?.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                // 模型直接回答，无需工具
                return InsightResult(choice.message?.content ?: "无法回答")
            }

            // 执行工具调用并回填结果（遵循 OpenAI 兼容协议：
            // assistant 消息携带 tool_calls，tool 消息携带对应 tool_call_id）
            var chartData: ChartData? = null
            val toolMessages = toolCalls.map { tc ->
                val result = executeToolCall(tc.function.name, tc.function.arguments)
                if (tc.function.name == "aggregate_by" || tc.function.name == "compare_periods") {
                    chartData = buildChartData(tc.function.name, tc.function.arguments)
                }
                Message(role = "tool", content = result, toolCallId = tc.id)
            }

            val followUpMessages = messages.toMutableList()
            followUpMessages.add(
                Message(
                    role = "assistant",
                    content = choice.message?.content ?: "",
                    toolCalls = toolCalls
                )
            )
            followUpMessages.addAll(toolMessages)

            val finalResponse = llmClient.chat(followUpMessages, jsonMode = false)
            InsightResult(answer = finalResponse, chartData = chartData)
        } catch (e: Exception) {
            InsightResult(answer = "查询出错了：${e.message}，换个问法试试？")
        }
    }

    private suspend fun executeToolCall(name: String, arguments: String): String {
        val args = json.parseToJsonElement(arguments).jsonObject
        return when (name) {
            "query_transactions" -> {
                val start = parseDate(args["start"]?.jsonPrimitive?.content)
                val end = parseDate(args["end"]?.jsonPrimitive?.content)
                val category = args["category"]?.jsonPrimitive?.contentOrNull
                val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
                val txs = insightDao.queryTransactions(start, end, category, keyword, limit)
                // 手动构建 JSON，避免直接序列化 Room 实体
                buildJsonArray {
                    txs.forEach { t ->
                        addJsonObject {
                            put("amount", t.amount)
                            put("type", t.type.name)
                            put("category_id", t.categoryId)
                            put("note", t.note)
                            put("merchant", t.merchant)
                            put("occurred_at", t.occurredAt)
                        }
                    }
                }.toString()
            }
            "aggregate_by" -> {
                val start = parseDate(args["start"]?.jsonPrimitive?.content)
                val end = parseDate(args["end"]?.jsonPrimitive?.content)
                val groupBy = args["group_by"]?.jsonPrimitive?.content ?: "category"
                val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull
                if (groupBy == "day") {
                    val results = insightDao.aggregateByDay(start, end)
                    json.encodeToString(ListSerializer(DailyAggregate.serializer()), results)
                } else {
                    val results = insightDao.aggregateByCategory(start, end, null, keyword)
                    json.encodeToString(ListSerializer(CategoryAggregate.serializer()), results)
                }
            }
            "compare_periods" -> {
                val aStart = parseDate(args["period_a_start"]?.jsonPrimitive?.content)
                val aEnd = parseDate(args["period_a_end"]?.jsonPrimitive?.content)
                val bStart = parseDate(args["period_b_start"]?.jsonPrimitive?.content)
                val bEnd = parseDate(args["period_b_end"]?.jsonPrimitive?.content)
                val totalA = insightDao.totalExpense(aStart, aEnd)
                val totalB = insightDao.totalExpense(bStart, bEnd)
                """{"period_a_total":$totalA,"period_b_total":$totalB}"""
            }
            else -> """{"error":"unknown tool: $name"}"""
        }
    }

    private suspend fun buildChartData(toolName: String, arguments: String): ChartData? {
        val args = json.parseToJsonElement(arguments).jsonObject
        val start = parseDate(args["start"]?.jsonPrimitive?.content)
        val end = parseDate(args["end"]?.jsonPrimitive?.content)
        return when (toolName) {
            "aggregate_by" -> {
                val groupBy = args["group_by"]?.jsonPrimitive?.content ?: "category"
                if (groupBy == "day") {
                    // 按天分组时取最近 7 天数据，高亮最后一天
                    val results = insightDao.aggregateByDay(start, end).takeLast(7)
                    if (results.isEmpty()) null
                    else ChartData(
                        labels = results.map { it.day.takeLast(5) },
                        values = results.map { it.total },
                        highlightIndex = results.lastIndex
                    )
                } else {
                    val results = insightDao.aggregateByCategory(start, end, null, null)
                    if (results.isEmpty()) null
                    else ChartData(
                        labels = results.take(5).map { it.categoryName },
                        values = results.take(5).map { it.total },
                        highlightIndex = 0
                    )
                }
            }
            else -> null
        }
    }

    private fun parseDate(dateStr: String?): Long {
        return try {
            LocalDate.parse(dateStr).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}

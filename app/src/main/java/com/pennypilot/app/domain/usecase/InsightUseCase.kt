package com.pennypilot.app.domain.usecase

import com.pennypilot.app.data.db.dao.CategoryAggregate
import com.pennypilot.app.data.db.dao.DailyAggregate
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.FunctionDef
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.util.DateRange
import com.pennypilot.app.util.DateRanges
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
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
                        putJsonObject("end") { put("type", "string"); put("description", "结束日期 yyyy-MM-dd，包含该日全天") }
                        putJsonObject("category") { put("type", "string"); put("description", "分类名，可选") }
                        putJsonObject("keyword") { put("type", "string"); put("description", "关键词（商户/备注），可选") }
                        putJsonObject("limit") { put("type", "integer"); put("description", "返回条数，默认20") }
                    }
                    put("required", buildJsonArray { add("start"); add("end") })
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
                        putJsonObject("end") { put("type", "string"); put("description", "结束日期 yyyy-MM-dd，包含该日全天") }
                        putJsonObject("group_by") { put("type", "string"); put("description", "category 或 day") }
                        putJsonObject("keyword") { put("type", "string"); put("description", "关键词过滤，可选") }
                    }
                    put("required", buildJsonArray { add("start"); add("end") })
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
                        putJsonObject("period_a_end") { put("type", "string"); put("description", "包含结束日全天，yyyy-MM-dd") }
                        putJsonObject("period_b_start") { put("type", "string") }
                        putJsonObject("period_b_end") { put("type", "string"); put("description", "包含结束日全天，yyyy-MM-dd") }
                    }
                    put("required", buildJsonArray {
                        add("period_a_start"); add("period_a_end"); add("period_b_start"); add("period_b_end")
                    })
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
                result.chart?.let { chartData = it }
                Message(role = "tool", content = result.content, toolCallId = tc.id)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InsightResult(answer = "查询出错了：${e.message}，换个问法试试？")
        }
    }

    private data class ToolResult(val content: String, val chart: ChartData? = null)

    private suspend fun executeToolCall(name: String, arguments: String): ToolResult {
        val args = json.parseToJsonElement(arguments).jsonObject
        return when (name) {
            "query_transactions" -> {
                val range = dateRange(args)
                val category = args["category"]?.jsonPrimitive?.contentOrNull
                val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
                val txs = insightDao.queryTransactions(range.start, range.endExclusive, category, keyword, limit)
                // 手动构建 JSON，避免直接序列化 Room 实体
                ToolResult(buildJsonArray {
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
                }.toString())
            }
            "aggregate_by" -> {
                val range = dateRange(args)
                val groupBy = args["group_by"]?.jsonPrimitive?.content ?: "category"
                val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull
                if (groupBy == "day") {
                    val results = insightDao.aggregateByDay(range.start, range.endExclusive, keyword)
                    val plotted = results.takeLast(7)
                    ToolResult(
                        json.encodeToString(ListSerializer(DailyAggregate.serializer()), results),
                        plotted.takeIf { it.isNotEmpty() }?.let {
                            ChartData(it.map { day -> day.day.takeLast(5) }, it.map { day -> day.total }, it.lastIndex)
                        }
                    )
                } else {
                    require(groupBy == "category") { "group_by 只能是 category 或 day" }
                    val results = insightDao.aggregateByCategory(range.start, range.endExclusive, null, keyword)
                    val plotted = results.take(5)
                    ToolResult(
                        json.encodeToString(ListSerializer(CategoryAggregate.serializer()), results),
                        plotted.takeIf { it.isNotEmpty() }?.let {
                            ChartData(it.map { category -> category.categoryName }, it.map { category -> category.total }, 0)
                        }
                    )
                }
            }
            "compare_periods" -> {
                val a = dateRange(args, "period_a_")
                val b = dateRange(args, "period_b_")
                val totalA = insightDao.totalExpense(a.start, a.endExclusive)
                val totalB = insightDao.totalExpense(b.start, b.endExclusive)
                ToolResult("""{"period_a_total":$totalA,"period_b_total":$totalB}""")
            }
            else -> ToolResult(buildJsonObject { put("error", "unknown tool: $name") }.toString())
        }
    }

    private fun dateRange(args: kotlinx.serialization.json.JsonObject, prefix: String = ""): DateRange =
        DateRanges.inclusiveDates(
            args["${prefix}start"]?.jsonPrimitive?.contentOrNull,
            args["${prefix}end"]?.jsonPrimitive?.contentOrNull
        )
}

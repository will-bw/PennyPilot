package com.pennypilot.app.domain.usecase

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.*
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.util.DateRanges
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InsightUseCaseTest {
    private lateinit var db: AppDatabase
    private val range = DateRanges.inclusiveDates("2026-10-22", "2026-10-22")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.categoryDao().insert(CategoryEntity(1, "餐饮", "", 0, TxType.EXPENSE, 0))
        db.transactionDao().insert(TransactionEntity(amount = 2500, type = TxType.EXPENSE, categoryId = 1,
            merchant = "咖啡店", occurredAt = range.start + 12 * 3_600_000))
        db.transactionDao().insert(TransactionEntity(amount = 8000, type = TxType.EXPENSE, categoryId = 1,
            merchant = "超市", occurredAt = range.start + 13 * 3_600_000))
        db.transactionDao().insert(TransactionEntity(amount = 9999, type = TxType.EXPENSE, categoryId = 1,
            merchant = "咖啡店", occurredAt = range.endExclusive))
    }

    private class ToolClient(private val tool: String, private val args: String) : LLMClient {
        var submitted: List<Message> = emptyList()
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            submitted = messages
            return "已根据数据回答"
        }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>) =
            ChatResponseWithTools(listOf(ChoiceWithTools(AssistantMessage(toolCalls = listOf(
                ToolCall(id = "test-call", function = FunctionCallData(tool, args))
            )))))

        fun result() = Json.parseToJsonElement(submitted.last { it.role == "tool" }.content)
    }

    @Test
    fun `same date query includes noon but excludes following midnight`() = runTest {
        seed()
        val client = ToolClient("query_transactions", """{"start":"2026-10-22","end":"2026-10-22"}""")
        InsightUseCase(client, db.insightDao()).ask("今天的账单")
        assertEquals(listOf(8000L, 2500L), client.result().jsonArray.map { it.jsonObject.getValue("amount").jsonPrimitive.long })
    }

    @Test
    fun `daily keyword filter is identical in tool payload and chart`() = runTest {
        seed()
        val client = ToolClient("aggregate_by", """{"start":"2026-10-22","end":"2026-10-22","group_by":"day","keyword":"咖啡"}""")
        val result = InsightUseCase(client, db.insightDao()).ask("今天咖啡按天统计")
        val totals = client.result().jsonArray.map { it.jsonObject.getValue("total").jsonPrimitive.long }
        assertEquals(listOf(2500L), totals)
        assertEquals(totals, result.chartData?.values)
    }

    @Test
    fun `category chart keeps keyword filter from tool result`() = runTest {
        seed()
        val client = ToolClient("aggregate_by", """{"start":"2026-10-22","end":"2026-10-22","group_by":"category","keyword":"咖啡"}""")
        val result = InsightUseCase(client, db.insightDao()).ask("今天咖啡按分类统计")
        assertEquals(2500L, client.result().jsonArray.single().jsonObject.getValue("total").jsonPrimitive.long)
        assertEquals(listOf(2500L), result.chartData?.values)
    }

    @Test
    fun `missing dates produce error without querying arbitrary current time`() = runTest {
        val client = ToolClient("aggregate_by", """{"group_by":"day"}""")
        val result = InsightUseCase(client, db.insightDao()).ask("统计")
        assertTrue(result.answer.startsWith("查询出错了"))
        assertTrue(client.submitted.isEmpty())
    }
}

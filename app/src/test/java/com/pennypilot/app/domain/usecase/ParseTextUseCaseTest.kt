package com.pennypilot.app.domain.usecase

import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParseTextUseCaseTest {

    private val fakeCategories = listOf(
        CategoryEntity(id = 1, name = "餐饮", icon = "🍜", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0),
        CategoryEntity(id = 2, name = "交通", icon = "🚕", colorIndex = 1, type = TxType.EXPENSE, sortOrder = 1),
        CategoryEntity(id = 9, name = "其他", icon = "📦", colorIndex = 1, type = TxType.EXPENSE, sortOrder = 8),
    )

    private val fakeLlmResponse = """
        {"transactions": [
            {"amount": 2500, "type": "EXPENSE", "category": "餐饮", "note": "午饭", "merchant": "沙县小吃"},
            {"amount": 3200, "type": "EXPENSE", "category": "交通", "note": "打车"}
        ]}
    """.trimIndent()

    private fun createUseCase(llmResponse: String = fakeLlmResponse): ParseTextUseCase {
        val fakeClient = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String = llmResponse
        }
        val fakeDao = object : CategoryDao {
            override suspend fun insert(cat: CategoryEntity): Long = 0
            override suspend fun insertAll(cats: List<CategoryEntity>) {}
            override suspend fun update(cat: CategoryEntity) {}
            override fun observeEnabled(): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override fun observeByType(type: TxType): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override suspend fun maxColorIndex(): Int = 6
            override suspend fun getById(id: Long): CategoryEntity? = fakeCategories.find { it.id == id }
        }
        return ParseTextUseCase(fakeClient, fakeDao)
    }

    @Test
    fun `parses two transactions from one sentence`() = runTest {
        val result = createUseCase().execute("午饭 25 打车 32")
        assertNull(result.error)
        assertEquals(2, result.transactions.size)
        assertEquals(2500L, result.transactions[0].amount)
        assertEquals("餐饮", result.transactions[0].categoryName)
        assertEquals(1L, result.transactions[0].categoryId)
        assertEquals(3200L, result.transactions[1].amount)
        assertEquals("交通", result.transactions[1].categoryName)
    }

    @Test
    fun `unknown category falls back to 其他`() = runTest {
        val response = """{"transactions": [{"amount": 1000, "type": "EXPENSE", "category": "宠物"}]}"""
        val result = createUseCase(response).execute("买猫粮 10")
        assertEquals("其他", result.transactions[0].categoryName)
        assertEquals(9L, result.transactions[0].categoryId)
    }

    @Test
    fun `LLM failure returns error`() = runTest {
        val failingClient = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
                throw RuntimeException("Network timeout")
            }
        }
        val fakeDao = object : CategoryDao {
            override suspend fun insert(cat: CategoryEntity): Long = 0
            override suspend fun insertAll(cats: List<CategoryEntity>) {}
            override suspend fun update(cat: CategoryEntity) {}
            override fun observeEnabled(): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override fun observeByType(type: TxType): Flow<List<CategoryEntity>> = flowOf(fakeCategories)
            override suspend fun maxColorIndex(): Int = 6
            override suspend fun getById(id: Long): CategoryEntity? = null
        }
        val useCase = ParseTextUseCase(failingClient, fakeDao)
        val result = useCase.execute("test")
        assertTrue(result.transactions.isEmpty())
        assertNotNull(result.error)
    }
}

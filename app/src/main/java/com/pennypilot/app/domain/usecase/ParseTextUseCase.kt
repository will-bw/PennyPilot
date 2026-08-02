package com.pennypilot.app.domain.usecase

import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ParsedResult
import com.pennypilot.app.data.llm.ParsedTransaction
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ParseResult(
    val transactions: List<ResolvedTransaction>,
    val error: String? = null
)

data class ResolvedTransaction(
    val amount: Long,
    val type: TxType,
    val categoryId: Long,
    val categoryName: String,
    val note: String?,
    val merchant: String?,
    val occurredAt: Long
)

class ParseTextUseCase @Inject constructor(
    private val llmClient: LLMClient,
    private val categoryDao: CategoryDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(input: String): ParseResult {
        return try {
            val categories = categoryDao.observeEnabled().first()
            val systemPrompt = buildSystemPrompt(categories)
            val messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(role = "user", content = input)
            )

            val response = llmClient.chat(messages, jsonMode = true)
            val parsed = json.decodeFromString(ParsedResult.serializer(), response)

            val resolved = parsed.transactions.map { tx ->
                resolveTransaction(tx, categories)
            }

            ParseResult(transactions = resolved)
        } catch (e: Exception) {
            ParseResult(transactions = emptyList(), error = e.message ?: "解析失败")
        }
    }

    private fun buildSystemPrompt(categories: List<CategoryEntity>): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val expenseCats = categories.filter { it.type == TxType.EXPENSE }.joinToString("、") { it.name }
        val incomeCats = categories.filter { it.type == TxType.INCOME }.joinToString("、") { it.name }

        return """你是一个记账解析器。把用户输入解析为交易记录 JSON。

规则：
- 输出格式：{"transactions": [...]}
- 每笔交易字段：amount(整数,单位分), type("EXPENSE"或"INCOME"), category(分类名), note(备注,可选), merchant(商户,可选), occurred_at(ISO格式,可选)
- 支出分类只能从：$expenseCats 中选
- 收入分类只能从：$incomeCats 中选
- 无法确定分类时填"其他"
- 金额单位是分（1元=100分）
- 时间推断：无明确时间用当前时间；"昨天"、"上周三"等解析为绝对时间
- 一句话可能包含多笔交易，全部拆出
- 只输出JSON，不要解释

当前时间：$now"""
    }

    private fun resolveTransaction(tx: ParsedTransaction, categories: List<CategoryEntity>): ResolvedTransaction {
        val type = if (tx.type.equals("INCOME", ignoreCase = true)) TxType.INCOME else TxType.EXPENSE
        val category = categories.find { it.name == tx.category && it.type == type }
            ?: categories.find { it.name == "其他" && it.type == type }
            ?: categories.first()

        val occurredAt = try {
            if (tx.occurredAt != null) {
                LocalDateTime.parse(tx.occurredAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }

        return ResolvedTransaction(
            amount = tx.amount,
            type = type,
            categoryId = category.id,
            categoryName = category.name,
            note = tx.note,
            merchant = tx.merchant,
            occurredAt = occurredAt
        )
    }
}

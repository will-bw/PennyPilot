package com.pennypilot.app.data.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    @SerialName("role") val role: String,  // "system", "user", "assistant"
    @SerialName("content") val content: String
)

@Serializable
data class ChatRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<Message>,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("temperature") val temperature: Double = 0.1
)

@Serializable
data class ResponseFormat(
    @SerialName("type") val type: String = "json_object"
)

@Serializable
data class ChatResponse(
    @SerialName("choices") val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    @SerialName("message") val message: Message? = null
)

/** LLM 解析出的单笔交易草稿 */
@Serializable
data class ParsedTransaction(
    @SerialName("amount") val amount: Long,          // 分
    @SerialName("type") val type: String,            // "EXPENSE" / "INCOME"
    @SerialName("category") val category: String,    // 分类名
    @SerialName("note") val note: String? = null,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null  // ISO 8601
)

@Serializable
data class ParsedResult(
    @SerialName("transactions") val transactions: List<ParsedTransaction> = emptyList()
)

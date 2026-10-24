package com.pennypilot.app.data.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class Message(
    @SerialName("role") val role: String,  // "system", "user", "assistant", "tool"
    @SerialName("content") val content: String,
    /** role = "tool" 时必填：对应 assistant 消息中的 tool_call id */
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /** role = "assistant" 且模型发起工具调用时需回传 */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** Responses 无服务器状态的工具回合需要保留加密 reasoning；不发送给其他协议。 */
    @Transient val responsesItems: JsonArray? = null,
    @Transient val responsesAccountId: String? = null,
    @Transient val imageDataUrls: List<String> = emptyList()
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
    @SerialName("occurred_at") val occurredAt: String? = null,  // ISO 8601
    @SerialName("business_kind") val businessKind: String = "unknown"
)

@Serializable
data class ParsedResult(
    @SerialName("transactions") val transactions: List<ParsedTransaction> = emptyList()
)

// ===== Function Calling 相关模型 =====

@Serializable
data class ToolDefinition(
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: FunctionDef
)

@Serializable
data class FunctionDef(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonObject
)

@Serializable
data class ToolCall(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: String = "function",
    @SerialName("function") val function: FunctionCallData
)

@Serializable
data class FunctionCallData(
    @SerialName("name") val name: String,
    @SerialName("arguments") val arguments: String
)

@Serializable
data class AssistantMessage(
    @SerialName("role") val role: String = "assistant",
    @SerialName("content") val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @Transient val responsesItems: JsonArray? = null,
    @Transient val responsesAccountId: String? = null
)

@Serializable
data class ChoiceWithTools(
    @SerialName("message") val message: AssistantMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatResponseWithTools(
    @SerialName("choices") val choices: List<ChoiceWithTools> = emptyList()
)

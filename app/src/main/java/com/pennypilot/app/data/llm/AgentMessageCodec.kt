package com.pennypilot.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The Flutter bridge uses the same message contract in both directions. */
object AgentMessageCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun decodeMessages(value: String): List<Message> {
        val rows = json.parseToJsonElement(value)
        require(rows is JsonArray) { "AI 对话记录须为消息列表。" }
        return rows.map { raw ->
            require(raw is JsonObject) { "AI 对话记录格式无效。" }
            val content = raw["content"]
            // Older native replies omitted null content for tool-only turns.
            // Accept those persisted turns without weakening user/tool messages.
            val toolOnly = raw["role"] == JsonPrimitive("assistant") &&
                (raw["tool_calls"] as? JsonArray)?.isNotEmpty() == true
            val normalized = if ((content == null || content == JsonNull) && toolOnly) {
                JsonObject(raw + ("content" to JsonPrimitive("")))
            } else raw
            val basic = json.decodeFromJsonElement<Message>(normalized)
            basic.copy(
                responsesItems = raw["responsesItems"] as? JsonArray,
                responsesAccountId = (raw["responsesAccountId"] as? JsonPrimitive)?.content
            )
        }
    }

    fun encodeReply(message: AssistantMessage): String {
        val basic = json.encodeToJsonElement(message) as JsonObject
        return JsonObject(basic + buildMap {
            put("content", JsonPrimitive(message.content.orEmpty()))
            message.responsesItems?.let { put("responsesItems", it) }
            message.responsesAccountId?.let { put("responsesAccountId", JsonPrimitive(it)) }
        }).toString()
    }
}

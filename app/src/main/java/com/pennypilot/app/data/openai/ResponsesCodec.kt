package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.FunctionCallData
import com.pennypilot.app.data.llm.MultimodalMessages
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolCall
import com.pennypilot.app.data.llm.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.BufferedSource
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Stateless Responses mapping. Only a completed, validated response may leave this codec. */
internal object ResponsesCodec {
    const val NAMESPACE = "pennypilot"
    private const val MAX_LINE_BYTES = 512 * 1024L
    private const val MAX_EVENT_CHARS = 2 * 1024 * 1024
    private const val MAX_STREAM_BYTES = 8 * 1024 * 1024L
    private val functionName = Regex("[A-Za-z0-9_-]{1,64}")

    fun request(
        model: String, messages: List<Message>, tools: List<ToolDefinition> = emptyList(),
        jsonMode: Boolean = false
    ): JsonObject {
        if (model.isBlank() || model.length > 256 || model.any(Char::isISOControl)) throw OpenAiErrors.noModel()
        MultimodalMessages.validate(messages)
        if (messages.isEmpty()) throw OpenAiErrors.protocol("对话为空")
        val names = tools.map { it.function.name }
        if (names.distinct().size != names.size || tools.any { it.type != "function" || !functionName.matches(it.function.name) }) {
            throw OpenAiErrors.protocol("工具定义无效")
        }
        val input = buildJsonArray {
            val callIds = mutableSetOf<String>()
            val answeredIds = mutableSetOf<String>()
            messages.forEach { message ->
                when (message.role) {
                    "system", "developer", "user" -> {
                        if (message.responsesItems != null || !message.toolCalls.isNullOrEmpty()) {
                            throw OpenAiErrors.protocol("消息角色与工具历史不匹配")
                        }
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", if (message.role == "system") "developer" else message.role)
                            if (message.imageDataUrls.isEmpty()) put("content", message.content)
                            else putJsonArray("content") {
                                add(buildJsonObject { put("type", "input_text"); put("text", message.content) })
                                message.imageDataUrls.forEach { image -> add(buildJsonObject {
                                    put("type", "input_image"); put("image_url", image)
                                }) }
                            }
                        })
                    }
                    "assistant" -> {
                        val calls = message.toolCalls.orEmpty()
                        calls.forEach { call ->
                            validateHistoryCall(call)
                            if (!callIds.add(call.id)) throw OpenAiErrors.protocol("工具调用编号重复")
                        }
                        val preserved = message.responsesItems
                        if (preserved != null) {
                            // Include encrypted reasoning as well as tool calls when replaying store=false history.
                            val decoded = output(preserved, calls.map { it.function.name }.toSet())
                            if (decoded.toolCalls != calls) throw OpenAiErrors.protocol("工具历史不一致")
                            preserved.forEach { add(it) }
                        } else {
                            if (message.content.isNotEmpty() || calls.isEmpty()) add(buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("content", message.content)
                            })
                            calls.forEach { call -> add(buildJsonObject {
                                put("type", "function_call")
                                put("namespace", NAMESPACE)
                                put("call_id", call.id)
                                put("name", call.function.name)
                                put("arguments", call.function.arguments)
                            }) }
                        }
                    }
                    "tool" -> {
                        val callId = message.toolCallId
                        if (callId == null || callId !in callIds || !answeredIds.add(callId)) {
                            throw OpenAiErrors.protocol("工具结果缺少对应调用")
                        }
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", message.content)
                        })
                    }
                    else -> throw OpenAiErrors.protocol("消息角色不受支持")
                }
            }
            if (callIds != answeredIds) throw OpenAiErrors.protocol("工具结果尚未齐全")
        }
        return buildJsonObject {
            put("model", model)
            put("store", false)
            put("stream", true)
            put("input", input)
            putJsonArray("include") { add("reasoning.encrypted_content") }
            if (jsonMode) {
                put("instructions", "只输出一个有效的 JSON 对象，不要输出 Markdown 代码块或额外说明。")
                putJsonObject("text") { putJsonObject("format") { put("type", "json_object") } }
            }
            if (tools.isNotEmpty()) putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "namespace")
                    put("name", NAMESPACE)
                    put("description", "查询 PennyPilot 账本中的真实收支记录与统计。")
                    putJsonArray("tools") {
                        tools.forEach { definition -> add(buildJsonObject {
                            put("type", "function")
                            put("name", definition.function.name)
                            put("description", definition.function.description)
                            put("parameters", definition.function.parameters)
                            // Existing schemas intentionally contain optional query filters.
                            put("strict", false)
                        }) }
                    }
                })
            }
        }
    }

    /** Sniff only when Content-Type is absent, without consuming any stream bytes. */
    fun looksLikeEventStream(source: BufferedSource): Boolean {
        val peek = source.peek()
        peek.request(256)
        val prefix = peek.readUtf8(minOf(256L, peek.buffer.size)).removePrefix("\uFEFF").trimStart()
        return prefix.startsWith("data:") || prefix.startsWith("event:") || prefix.startsWith(":")
    }

    /** Multi-line SSE data is joined with LF. A sentinel or EOF never proves completion. */
    fun readStream(source: BufferedSource, allowedTools: Set<String>, requestId: String? = null): ResponseOutput {
        val data = StringBuilder()
        var eventName: String? = null
        var byteCount = 0L
        var firstLine = true
        val streamItems = StreamItems(requestId)
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        while (!source.exhausted()) {
            // Find a bounded LF before decoding, so broken UTF-8 cannot be silently replaced.
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 2)
            if (newline < 0 || newline > MAX_LINE_BYTES) {
                throw OpenAiErrors.protocol("数据流截断或单行过长", requestId)
            }
            val bytes = source.readByteArray(newline)
            source.skip(1)
            byteCount += newline + 1
            if (byteCount > MAX_STREAM_BYTES) throw OpenAiErrors.protocol("数据流过大", requestId)
            val line = try {
                utf8.decode(ByteBuffer.wrap(bytes)).toString().removeSuffix("\r")
            } catch (_: CharacterCodingException) {
                throw OpenAiErrors.protocol("UTF-8 编码无效", requestId)
            }
            val cleanLine = if (firstLine) line.removePrefix("\uFEFF") else line
            firstLine = false
            if (cleanLine.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = data.toString().removeSuffix("\n")
                    consumeEvent(payload, eventName, allowedTools, requestId, streamItems)?.let { return it }
                }
                data.setLength(0)
                eventName = null
            } else if (!cleanLine.startsWith(":")) {
                val separator = cleanLine.indexOf(':')
                val field = if (separator < 0) cleanLine else cleanLine.substring(0, separator)
                val value = if (separator < 0) "" else cleanLine.substring(separator + 1).removePrefix(" ")
                when (field) {
                    "data" -> {
                        if (data.length + value.length + 1 > MAX_EVENT_CHARS) throw OpenAiErrors.protocol("事件过大", requestId)
                        data.append(value).append('\n')
                    }
                    "event" -> eventName = value
                }
            }
        }
        throw OpenAiErrors.protocol("未收到完成事件", requestId)
    }

    private fun consumeEvent(
        payload: String, eventName: String?, allowedTools: Set<String>, requestId: String?, streamItems: StreamItems
    ): ResponseOutput? {
        if (payload == "[DONE]" || payload == "[END]") return null
        val event = objectFrom(payload, requestId)
        val type = event.string("type") ?: throw OpenAiErrors.protocol("事件缺少类型", requestId)
        if (!eventName.isNullOrEmpty() && eventName != "message" && eventName != type) {
            throw OpenAiErrors.protocol("事件类型不一致", requestId)
        }
        return when (type) {
            "response.output_item.added", "response.output_item.done" -> {
                streamItems.accept(type, event)
                null
            }
            "response.completed" -> {
                val response = event["response"] as? JsonObject ?: throw OpenAiErrors.protocol("完成事件缺少响应", requestId)
                if (response.string("status") != "completed") throw OpenAiErrors.protocol("响应尚未完整完成", requestId)
                val items = response["output"] as? JsonArray ?: throw OpenAiErrors.protocol("完成事件缺少输出", requestId)
                // Direct-route terminal events can omit the already-streamed output items.
                // Use only final item.done payloads, and only after response.completed.
                output(if (items.isEmpty()) streamItems.completed() else items, allowedTools, requestId)
            }
            "response.failed" -> {
                val response = event["response"] as? JsonObject
                throw OpenAiErrors.stream(response?.get("error") as? JsonObject, requestId)
            }
            "response.incomplete" -> throw OpenAiErrors.protocol("模型输出不完整", requestId)
            "error" -> throw OpenAiErrors.stream(event["error"] as? JsonObject ?: event, requestId)
            else -> null // Deltas remain provisional and are never returned as completed content.
        }
    }

    private class StreamItems(private val requestId: String?) {
        private val added = mutableMapOf<Int, JsonObject>()
        private val done = sortedMapOf<Int, JsonObject>()

        fun accept(type: String, event: JsonObject) {
            val index = (event["output_index"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 0..4095 } ?: throw OpenAiErrors.protocol("输出项序号无效", requestId)
            val item = event["item"] as? JsonObject ?: throw OpenAiErrors.protocol("事件缺少输出项", requestId)
            if (item.string("id").isNullOrBlank() || item.string("type").isNullOrBlank()) {
                throw OpenAiErrors.protocol("输出项缺少标识", requestId)
            }
            if (type == "response.output_item.added") {
                if (index in added || index in done) throw OpenAiErrors.protocol("输出项序号重复", requestId)
                added[index] = item
            } else {
                val initial = added[index]
                if (initial != null && (initial.string("id") != item.string("id") || initial.string("type") != item.string("type"))) {
                    throw OpenAiErrors.protocol("输出项标识不一致", requestId)
                }
                if (done.put(index, item) != null) throw OpenAiErrors.protocol("完成输出项重复", requestId)
            }
        }

        fun completed(): JsonArray {
            if (!done.keys.containsAll(added.keys) || done.keys.toList() != (0 until done.size).toList() ||
                done.values.map { it.string("id") }.distinct().size != done.size) {
                throw OpenAiErrors.protocol("流式输出项不完整", requestId)
            }
            return JsonArray(done.values.toList())
        }
    }

    private fun output(items: JsonArray, allowedTools: Set<String>, requestId: String? = null): ResponseOutput {
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        val ids = mutableSetOf<String>()
        items.forEach { element ->
            val item = element as? JsonObject ?: throw OpenAiErrors.protocol("输出项格式无效", requestId)
            when (item.string("type")) {
                "message" -> {
                    if (item.string("role") != "assistant" || item.string("status") != "completed") {
                        throw OpenAiErrors.protocol("助手消息不完整", requestId)
                    }
                    val content = item["content"] as? JsonArray ?: throw OpenAiErrors.protocol("助手消息缺少内容", requestId)
                    content.forEach { part ->
                        val value = part as? JsonObject ?: throw OpenAiErrors.protocol("文本格式无效", requestId)
                        when (value.string("type")) {
                            "output_text" -> text.append(value.string("text") ?: throw OpenAiErrors.protocol("文本缺失", requestId))
                            "refusal" -> throw OpenAiErrors.protocol("模型未能回答此请求", requestId)
                            else -> throw OpenAiErrors.protocol("输出内容类型不受支持", requestId)
                        }
                    }
                }
                "function_call" -> {
                    val name = item.string("name")
                    if (item.string("namespace") != NAMESPACE || name !in allowedTools) {
                        throw OpenAiErrors.protocol("模型返回了未授权的工具", requestId)
                    }
                    if (item.string("status")?.let { it != "completed" } == true) {
                        throw OpenAiErrors.protocol("工具调用不完整", requestId)
                    }
                    val call = ToolCall(
                        id = item.string("call_id") ?: throw OpenAiErrors.protocol("工具调用缺少编号", requestId),
                        function = FunctionCallData(name!!, item.string("arguments") ?: throw OpenAiErrors.protocol("工具参数缺失", requestId))
                    )
                    validateHistoryCall(call)
                    if (!ids.add(call.id)) throw OpenAiErrors.protocol("工具调用编号重复", requestId)
                    calls.add(call)
                }
                "reasoning" -> Unit // Opaque encrypted state is replayed unchanged, never displayed or executed.
                else -> throw OpenAiErrors.protocol("响应包含不受支持的输出", requestId)
            }
        }
        if (text.isBlank() && calls.isEmpty()) throw OpenAiErrors.protocol("响应没有有效内容", requestId)
        return ResponseOutput(text.toString(), calls, items)
    }

    private fun validateHistoryCall(call: ToolCall) {
        if (call.type != "function" || !functionName.matches(call.function.name) || call.id.isBlank() || call.id.length > 256) {
            throw OpenAiErrors.protocol("工具调用格式无效")
        }
        objectFrom(call.function.arguments)
    }

    fun validateJsonObject(text: String) { objectFrom(text) }

    private fun objectFrom(value: String, requestId: String? = null): JsonObject =
        runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
            ?: throw OpenAiErrors.protocol("JSON 格式无效", requestId)
}

internal data class ResponseOutput(val text: String, val toolCalls: List<ToolCall>, val items: JsonArray)

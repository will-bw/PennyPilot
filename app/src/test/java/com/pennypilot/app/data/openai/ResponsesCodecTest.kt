package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.FunctionDef
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.llm.UserFacingLlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ResponsesCodecTest {
    @Test fun `reasoning options are explicit and unsupported client effort is rejected`() {
        val messages = listOf(Message("user", "分析支出"))
        val on = ResponsesCodec.request("chosen-model", messages, reasoningEffort = "high", requestReasoningSummary = true)
        assertEquals("chosen-model", on.string("model"))
        assertEquals("high", on.getValue("reasoning").jsonObject.string("effort"))
        assertEquals("auto", on.getValue("reasoning").jsonObject.string("summary"))
        val off = ResponsesCodec.request("chosen-model", messages, reasoningEffort = "none")
        assertEquals("none", off.getValue("reasoning").jsonObject.string("effort"))
        assertFalse(off.getValue("reasoning").jsonObject.containsKey("summary"))
        assertFalse(ResponsesCodec.request("chosen-model", messages).containsKey("reasoning"))
        assertThrows(UserFacingLlmException::class.java) {
            ResponsesCodec.request("chosen-model", messages, reasoningEffort = "ultra")
        }
    }

    @Test fun `public summary deltas are separate from answer and encrypted reasoning`() {
        val previews = mutableListOf<String>()
        val text = mutableListOf<String>()
        val reasoning = Json.parseToJsonElement("""{"id":"rs_1","type":"reasoning","summary":[
            {"type":"summary_text","text":"先核对账本"},{"type":"summary_text","text":"再比较预算"}
        ],"encrypted_content":"never-show-private-reasoning"}""").jsonObject
        val sse = summaryEvent("response.reasoning_summary_part.added", 0, partText = "") +
            summaryEvent("response.reasoning_summary_text.delta", 0, delta = "先核对") +
            summaryEvent("response.reasoning_summary_text.delta", 0, delta = "账本") +
            summaryEvent("response.reasoning_summary_text.done", 0, text = "先核对账本") +
            summaryEvent("response.reasoning_summary_part.done", 0, partText = "先核对账本") +
            summaryEvent("response.reasoning_summary_part.added", 1, partText = "") +
            summaryEvent("response.reasoning_summary_text.done", 1, text = "再比较预算") +
            "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"never-show-raw-chain\"}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"结论\"}\n\n" +
            stream(JsonArray(listOf(reasoning) + textOutput("最终结论")))
        val result = ResponsesCodec.readStream(Buffer().writeUtf8(sse), emptySet(),
            onReasoningDelta = { previews.add(it) }, onTextDelta = { text.add(it) })
        assertEquals("先核对账本\n\n再比较预算", previews.joinToString(""))
        assertEquals("先核对账本\n\n再比较预算", result.reasoningSummary)
        assertEquals(listOf("结论"), text)
        assertEquals("最终结论", result.text)
        assertTrue(result.items.toString().contains("never-show-private-reasoning"))
        assertFalse((previews + text + result.reasoningSummary).joinToString().contains("never-show"))
    }

    @Test fun `completed summary events survive omitted summaries but provisional ones do not`() {
        val completed = summaryEvent("response.reasoning_summary_text.delta", 0, delta = "核对完成") +
            summaryEvent("response.reasoning_summary_text.done", 0, text = "核对完成")
        assertEquals("核对完成", parse(completed + stream(textOutput("答案"))).reasoningSummary)
        val provisional = summaryEvent("response.reasoning_summary_text.delta", 0, delta = "尚未完成")
        assertEquals("", parse(provisional + stream(textOutput("答案"))).reasoningSummary)
        val invalid = summaryEvent("response.reasoning_summary_text.delta", 0, delta = "初稿") +
            summaryEvent("response.reasoning_summary_text.done", 0, text = "另一份内容")
        assertThrows(UserFacingLlmException::class.java) { parse(invalid + stream(textOutput("答案"))) }
    }

    @Test fun `preview callbacks are incremental while completed output stays authoritative`() {
        val preview = mutableListOf<String>()
        val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分\"}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案☕\"}\n\n" +
            stream(textOutput("已核对的完整答案"))
        val result = ResponsesCodec.readStream(Buffer().writeUtf8(sse), emptySet(), "req_preview") { preview.add(it) }
        assertEquals(listOf("部分", "答案☕"), preview)
        assertEquals("已核对的完整答案", result.text)
        assertThrows(UserFacingLlmException::class.java) {
            ResponsesCodec.readStream(Buffer().writeUtf8("data: {\"type\":\"response.output_text.delta\",\"delta\":\"未完成\"}\n\n"), emptySet())
        }
    }

    @Test fun `requests use stateless Responses fields and developer messages`() {
        val request = ResponsesCodec.request("account-model", listOf(Message("system", "记账助手"), Message("user", "午饭 20 元")), jsonMode = true)
        assertFalse(request.getValue("store").jsonPrimitive.boolean)
        assertTrue(request.getValue("stream").jsonPrimitive.boolean)
        assertEquals("developer", request.getValue("input").jsonArray.first().jsonObject.string("role"))
        assertEquals("json_object", request.getValue("text").jsonObject.getValue("format").jsonObject.string("type"))
        assertTrue(request.string("instructions")!!.contains("JSON"))
        listOf("temperature", "max_output_tokens", "previous_response_id", "conversation", "background", "metadata", "user").forEach {
            assertFalse(it, request.containsKey(it))
        }
    }

    @Test fun `user receipt images use Responses input image format`() {
        val image = "data:image/jpeg;base64,aGVsbG8="
        val request = ResponsesCodec.request("vision-model", listOf(Message("user", "识别这张票据", imageDataUrls = listOf(image))), jsonMode = true)
        val parts = request.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("input_text", parts[0].jsonObject.string("type"))
        assertEquals("input_image", parts[1].jsonObject.string("type"))
        assertEquals(image, parts[1].jsonObject.string("image_url"))
        assertFalse(request.containsKey("tools"))
    }

    @Test fun `remote image urls and hidden system attachments are rejected`() {
        for (message in listOf(Message("user", "识别", imageDataUrls = listOf("https://example.com/private")),
            Message("system", "识别", imageDataUrls = listOf("data:image/png;base64,aA==")))) {
            try { ResponsesCodec.request("model", listOf(message)); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `tools are grouped in official namespace shape`() {
        val request = ResponsesCodec.request("account-model", listOf(Message("user", "支出")), listOf(tool))
        val namespace = request.getValue("tools").jsonArray.single().jsonObject
        assertEquals("namespace", namespace.string("type"))
        assertEquals("pennypilot", namespace.string("name"))
        val function = namespace.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", function.string("type"))
        assertEquals("query_transactions", function.string("name"))
        assertEquals(tool.function.parameters, function["parameters"])
        assertFalse(function.getValue("strict").jsonPrimitive.boolean)
    }

    @Test fun `tool round trip preserves ids arguments namespace and encrypted reasoning`() {
        val output = parse(stream(toolOutput), setOf("query_transactions"))
        assertEquals("call_expense", output.toolCalls.single().id)
        assertEquals("query_transactions", output.toolCalls.single().function.name)
        assertEquals("{\"month\":\"2026-10\"}", output.toolCalls.single().function.arguments)
        val request = ResponsesCodec.request("account-model", listOf(
            Message("user", "本月支出"),
            Message("assistant", output.text, toolCalls = output.toolCalls, responsesItems = output.items, responsesAccountId = "account"),
            Message("tool", "{\"expense\":2000}", toolCallId = "call_expense")
        ))
        val input = request.getValue("input").jsonArray
        assertEquals(toolOutput[0], input[1])
        assertEquals(toolOutput[1], input[2])
        assertEquals("function_call_output", input[3].jsonObject.string("type"))
        assertEquals("call_expense", input[3].jsonObject.string("call_id"))
        assertEquals("{\"expense\":2000}", input[3].jsonObject.string("output"))
        assertFalse(request.containsKey("tools")) // Final-answer path still carries full tool history.
    }

    @Test fun `only explicitly offered tools in the correct namespace are accepted`() {
        listOf(
            toolOutput.toString().replace("pennypilot", "unknown_namespace"),
            toolOutput.toString().replace("query_transactions", "delete_everything"),
            toolOutput.toString().replace("\"namespace\":\"pennypilot\",", "")
        ).forEach { value ->
            assertThrows(UserFacingLlmException::class.java) {
                parse(stream(Json.parseToJsonElement(value).jsonArray), setOf("query_transactions"))
            }
        }
    }

    @Test fun `tool history rejects orphan results duplicates and partial arguments`() {
        assertThrows(UserFacingLlmException::class.java) {
            ResponsesCodec.request("m", listOf(Message("tool", "data", toolCallId = "unknown")))
        }
        val result = parse(stream(toolOutput), setOf("query_transactions"))
        assertThrows(UserFacingLlmException::class.java) {
            ResponsesCodec.request("m", listOf(Message("assistant", "", toolCalls = result.toolCalls)))
        }
        assertThrows(UserFacingLlmException::class.java) {
            parse(stream(Json.parseToJsonElement(toolOutput.toString().replace("{\\\"month\\\":\\\"2026-10\\\"}", "{" )).jsonArray), setOf("query_transactions"))
        }
        val duplicates = JsonArray(toolOutput + toolOutput.last())
        assertThrows(UserFacingLlmException::class.java) { parse(stream(duplicates), setOf("query_transactions")) }
    }

    @Test fun `multiline SSE handles UTF8 CRLF comments and ignores provisional deltas`() {
        val payload = completed(textOutput("合计 ¥25.00，咖啡 ☕"))
        val split = payload.indexOf("\"response\"")
        val sse = "\uFEFF: keepalive\r\n\r\n" +
            "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"不应返回\"}\r\n\r\n" +
            "event: response.completed\r\ndata: ${payload.substring(0, split)}\r\ndata: ${payload.substring(split)}\r\n\r\n"
        assertEquals("合计 ¥25.00，咖啡 ☕", parse(sse).text)
    }

    @Test fun `failed incomplete and explicit errors never return provisional text`() {
        listOf(
            "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}",
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"}}",
            "{\"type\":\"error\",\"code\":\"subscription_sharing_usage_unavailable\"}"
        ).forEach { failure ->
            val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分答案\"}\n\ndata: $failure\n\n"
            assertThrows(UserFacingLlmException::class.java) { parse(sse) }
        }
    }

    @Test fun `empty terminal output uses final streamed text after completion`() {
        val item = textOutput("完整回答").single().jsonObject
        val sse = itemEvent("response.output_item.done", 0, item) + stream(JsonArray(emptyList()))
        assertEquals("完整回答", parse(sse).text)
    }

    @Test fun `empty terminal output preserves completed tools and encrypted reasoning in index order`() {
        val sse = itemEvent("response.output_item.done", 1, toolOutput[1].jsonObject) +
            itemEvent("response.output_item.done", 0, toolOutput[0].jsonObject) + stream(JsonArray(emptyList()))
        val result = parse(sse, setOf("query_transactions"))
        assertEquals(toolOutput, result.items)
        assertEquals("call_expense", result.toolCalls.single().id)
        assertThrows(UserFacingLlmException::class.java) { parse(sse) }
    }

    @Test fun `completed items never substitute for missing failed or incomplete response completion`() {
        val done = itemEvent("response.output_item.done", 0, textOutput("完整单项").single().jsonObject)
        listOf("", "data: [DONE]\n\n", "data: {\"type\":\"response.incomplete\"}\n\n",
            "data: {\"type\":\"response.failed\",\"response\":{}}\n\n").forEach { tail ->
            assertThrows(UserFacingLlmException::class.java) { parse(done + tail) }
        }
    }

    @Test fun `streamed fallback rejects missing duplicate mismatched and incomplete items`() {
        val item = textOutput("回答").single().jsonObject
        val done = itemEvent("response.output_item.done", 0, item)
        val added = itemEvent("response.output_item.added", 0, item)
        val incomplete = Json.parseToJsonElement(item.toString().replace("completed", "in_progress")).jsonObject
        val other = Json.parseToJsonElement(item.toString().replace("msg_1", "msg_other")).jsonObject
        listOf("", added, done + done, itemEvent("response.output_item.done", 1, item),
            added + itemEvent("response.output_item.done", 0, other),
            itemEvent("response.output_item.done", 0, incomplete)).forEach { prefix ->
            assertThrows(UserFacingLlmException::class.java) { parse(prefix + stream(JsonArray(emptyList()))) }
        }
    }

    @Test fun `completion requires completed status and full event framing`() {
        listOf(
            stream(textOutput("答案")).replace("\"status\":\"completed\"", "\"status\":\"incomplete\""),
            "data: [DONE]\n\n", "data: [END]\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分答案\"}\n\n",
            "data: ${completed(textOutput("答案"))}",
            "data: ${completed(textOutput("答案"))}\n",
            "event: response.failed\ndata: ${completed(textOutput("答案"))}\n\n"
        ).forEach { invalid -> assertThrows(UserFacingLlmException::class.java) { parse(invalid) } }
    }

    @Test fun `oversized streams and malformed JSON fail closed`() {
        assertThrows(UserFacingLlmException::class.java) { parse("data: " + "x".repeat(600_000) + "\n\n") }
        assertThrows(UserFacingLlmException::class.java) { parse((": " + "x".repeat(32_000) + "\n").repeat(270)) }
        assertThrows(UserFacingLlmException::class.java) { parse("data: {truncated\n\n") }
        assertThrows(UserFacingLlmException::class.java) { ResponsesCodec.validateJsonObject("{\"transactions\":[") }
        assertThrows(UserFacingLlmException::class.java) { ResponsesCodec.validateJsonObject("[]") }
        val brokenUtf8 = Buffer().writeUtf8("data: {\"type\":\"response.output_text.delta\",\"delta\":\"")
            .write(byteArrayOf(0xC3.toByte(), 0x28)).writeUtf8("\"}\n\n")
        assertThrows(UserFacingLlmException::class.java) { ResponsesCodec.readStream(brokenUtf8, emptySet()) }
    }

    @Test fun `model list filters visibility and preserves service ordering and names`() {
        val models = ChatGptModelRepository.parseModels("""{"models":[
            {"slug":"second","display_name":"我的模型 B","visibility":"list"},
            {"slug":"hidden","display_name":"隐藏","visibility":"hidden"},
            {"slug":"first","display_name":"我的模型 A","visibility":"list"},
            {"slug":"missing_name","visibility":"list"},
            {"slug":"second","display_name":"重复","visibility":"list"}
        ]}""")
        assertEquals(listOf(ChatGptModel("second", "我的模型 B"), ChatGptModel("first", "我的模型 A")), models)
        assertEquals(emptyList<ChatGptModel>(), ChatGptModelRepository.parseModels("{\"models\":[]}"))
        assertThrows(UserFacingLlmException::class.java) { ChatGptModelRepository.parseModels("{\"data\":[]}") }
    }

    @Test fun `account catalog capabilities preserve unknown explicit none and provider metadata`() {
        val models = ChatGptModelRepository.parseModels("""{"models":[
            {"slug":"selected","display_name":"自选模型","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"ultra"},{"effort":"medium"}],"default_reasoning_level":"low","supports_reasoning_summaries":true},
            {"slug":"none","display_name":"无推理","visibility":"list","supported_reasoning_efforts":[],"supports_reasoning_summaries":false},
            {"slug":"unknown","display_name":"未知","visibility":"list","supports_reasoning_summaries":"false"},
            {"slug":"efforts","display_name":"另一目录格式","visibility":"list","supported_reasoning_efforts":["none",{"effort":"high"}],"default_reasoning_effort":"high"},
            {"slug":"malformed","display_name":"无效元数据","visibility":"list","supported_reasoning_levels":[42,{}]}
        ]}""")
        assertEquals(listOf("low", "medium", "ultra"), models[0].reasoningEfforts)
        assertEquals("low", models[0].defaultReasoningEffort)
        assertEquals(true, models[0].supportsReasoningSummaries)
        assertEquals(emptyList<String>(), models[1].reasoningEfforts)
        assertEquals(false, models[1].supportsReasoningSummaries)
        assertNull(models[2].reasoningEfforts)
        assertNull(models[2].supportsReasoningSummaries)
        assertEquals(listOf("none", "high"), models[3].reasoningEfforts)
        assertEquals("high", models[3].defaultReasoningEffort)
        assertNull(models[4].reasoningEfforts)
    }

    @Test fun `errors retain safe diagnostic identifiers without server messages or credentials`() {
        val error = OpenAiErrors.http(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","param":"model","message":"Bearer secret-token"}}""", "req_123")
        assertTrue(error.message!!.contains("额度"))
        assertTrue(error.message!!.contains("HTTP 429"))
        assertTrue(error.message!!.contains("code=subscription_sharing_usage_limit_exceeded"))
        assertTrue(error.message!!.contains("request_id=req_123"))
        assertFalse(error.message!!.contains("secret-token"))
        val detail = OpenAiErrors.http(401, """{"detail":"private-account-and-token"}""", "req_safe")
        assertTrue(detail.message!!.contains("response=detail"))
        assertFalse(detail.message!!.contains("private-account-and-token"))
    }

    private fun parse(sse: String, tools: Set<String> = emptySet()) = ResponsesCodec.readStream(Buffer().writeUtf8(sse), tools, "req_test")

    private fun itemEvent(type: String, index: Int, item: JsonObject) = "data: " + buildJsonObject {
        put("type", type); put("output_index", index); put("item", item)
    } + "\n\n"

    private fun summaryEvent(type: String, index: Int, delta: String? = null, text: String? = null, partText: String? = null) =
        "data: " + buildJsonObject {
            put("type", type); put("item_id", "rs_1"); put("output_index", 0); put("summary_index", index)
            delta?.let { put("delta", it) }
            text?.let { put("text", it) }
            partText?.let { put("part", buildJsonObject { put("type", "summary_text"); put("text", it) }) }
        } + "\n\n"

    companion object {
        val tool = ToolDefinition(function = FunctionDef("query_transactions", "查询交易", buildJsonObject { put("type", "object") }))
        val toolOutput = Json.parseToJsonElement("""[
            {"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"opaque-reasoning"},
            {"id":"fc_1","type":"function_call","namespace":"pennypilot","call_id":"call_expense","name":"query_transactions","arguments":"{\"month\":\"2026-10\"}","status":"completed"}
        ]""").jsonArray
        fun textOutput(text: String): JsonArray = JsonArray(listOf(buildJsonObject {
            put("type", "message"); put("id", "msg_1"); put("role", "assistant"); put("status", "completed")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "output_text"); put("text", text) })))
        }))
        fun completed(output: JsonArray) = buildJsonObject {
            put("type", "response.completed")
            put("response", buildJsonObject { put("status", "completed"); put("output", output) })
        }.toString()
        fun stream(output: JsonArray) = "data: ${completed(output)}\n\n"
    }
}

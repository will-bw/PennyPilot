package com.pennypilot.app.data.llm

import com.pennypilot.app.data.openai.ResponsesCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AgentMessageCodecTest {
    private val call = ToolCall("compare-call", function = FunctionCallData("compare_periods", "{}"))
    private val items = Json.parseToJsonElement("""[
        {"type":"reasoning","id":"r1","encrypted_content":"opaque","summary":[]},
        {"type":"function_call","namespace":"pennypilot","id":"f1","call_id":"compare-call","name":"compare_periods","arguments":"{}","status":"completed"}
    ]""").jsonArray

    @Test fun toolOnlyReplyRoundTripsIntoTheNextModelRequest() {
        val reply = AssistantMessage(toolCalls = listOf(call), responsesItems = items,
            responsesAccountId = "account")
        val encoded = AgentMessageCodec.encodeReply(reply)
        val wire = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive("assistant"), wire["role"])
        assertEquals(JsonPrimitive(""), wire["content"])
        val messages = AgentMessageCodec.decodeMessages("""[
            {"role":"user","content":"对比本月和上月"},
            $encoded,
            {"role":"tool","tool_call_id":"compare-call","content":"结果"}
        ]""")
        assertEquals("", messages[1].content)
        assertEquals(listOf(call), messages[1].toolCalls)
        assertEquals(items, messages[1].responsesItems)
        assertEquals("account", messages[1].responsesAccountId)
        val input = ResponsesCodec.request("model", messages)["input"]!!.jsonArray
        assertEquals(items[0], input[1])
        assertEquals(items[1], input[2])
        assertEquals(JsonPrimitive("compare-call"), input.last().jsonObject["call_id"])
    }

    @Test fun persistedToolRepliesWithMissingOrNullContentRemainUsable() {
        for (content in listOf("", "\"content\":null,")) {
            val messages = AgentMessageCodec.decodeMessages("""[{
                "role":"assistant",$content
                "tool_calls":[{"id":"compare-call","type":"function","function":{"name":"compare_periods","arguments":"{}"}}]
            }]""")
            assertEquals("", messages.single().content)
            assertEquals(listOf(call), messages.single().toolCalls)
        }
    }

    @Test fun missingContentIsNotSilentlyAcceptedForOrdinaryMessages() {
        for (role in listOf("user", "system", "tool", "assistant")) {
            for (content in listOf("", ",\"content\":null")) {
                try {
                    AgentMessageCodec.decodeMessages("""[{"role":"$role"$content}]""")
                    fail("Accepted missing content for $role")
                } catch (_: SerializationException) { }
            }
        }
    }

    @Test fun ordinaryReplyAndToolResultKeepTheirText() {
        val encoded = AgentMessageCodec.encodeReply(AssistantMessage(content = "支出增长了"))
        assertEquals("支出增长了", AgentMessageCodec.decodeMessages("[$encoded]").single().content)
        val tool = AgentMessageCodec.decodeMessages("""[
            {"role":"tool","tool_call_id":"compare-call","content":""}
        ]""").single()
        assertEquals("", tool.content)
        assertEquals("compare-call", tool.toolCallId)
    }
}

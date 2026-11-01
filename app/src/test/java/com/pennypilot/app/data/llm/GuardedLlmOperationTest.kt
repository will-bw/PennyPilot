package com.pennypilot.app.data.llm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuardedLlmOperationTest {
    @Test fun `reasoning callbacks and frozen request options use the guarded operation`() = runTest {
        var current = true
        val visible = mutableListOf<String>()
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean) = "unused"
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>) = ChatResponseWithTools()
            override suspend fun chatWithReasoning(
                messages: List<Message>, tools: List<ToolDefinition>, onReasoningDelta: (String) -> Unit,
                onTextDelta: (String) -> Unit, reasoningEffort: String?, requestReasoningSummary: Boolean
            ): ChatResponseWithTools {
                assertEquals("high", reasoningEffort)
                assertTrue(requestReasoningSummary)
                onReasoningDelta("公开摘要")
                current = false
                onReasoningDelta("旧账号内容")
                return ChatResponseWithTools()
            }
        }
        try {
            GuardedLlmOperation(client) { current }.chatWithReasoning(
                listOf(Message("user", "分析")), emptyList(), { visible.add(it) }, {}, "high", true)
            fail("Changed account must stop the summary stream")
        } catch (_: UserFacingLlmException) { }
        assertEquals(listOf("公开摘要"), visible)
    }

    @Test fun `changed service cannot receive the local tool result`() = runTest {
        var current = true
        var sent = 0
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String { sent++; return "answer" }
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>) = ChatResponseWithTools()
        }
        val operation = GuardedLlmOperation(client) { current }
        operation.chatWithTools(listOf(Message("user", "查询账本")), emptyList())
        current = false
        try {
            operation.chat(listOf(Message("tool", "sensitive ledger result", "call")), false)
            fail("Changed configuration must not send a follow-up")
        } catch (e: UserFacingLlmException) { assertTrue(e.message!!.contains("已切换")) }
        assertEquals(0, sent)
    }

    @Test fun `response is discarded if configuration changes while request is running`() = runTest {
        var current = true
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String { current = false; return "old answer" }
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>) = ChatResponseWithTools()
        }
        try {
            GuardedLlmOperation(client) { current }.chat(listOf(Message("user", "hello")), false)
            fail("Old response must be discarded")
        } catch (_: UserFacingLlmException) { }
    }
}

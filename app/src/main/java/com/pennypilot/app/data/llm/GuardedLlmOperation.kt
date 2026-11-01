package com.pennypilot.app.data.llm

/** Keeps an entire tool round on its original service, account and model. */
internal class GuardedLlmOperation(
    private val client: LLMClient,
    private val isCurrent: () -> Boolean
) : LLMClient {
    private fun checkConfiguration() {
        if (!isCurrent()) throw UserFacingLlmException("AI 服务、账号或模型已切换，本次查询已停止。请使用当前配置重新提问。")
    }

    override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
        checkConfiguration()
        return client.chat(messages, jsonMode).also { checkConfiguration() }
    }

    override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools {
        checkConfiguration()
        return client.chatWithTools(messages, tools).also { checkConfiguration() }
    }

    override suspend fun chatWithToolsStreaming(
        messages: List<Message>, tools: List<ToolDefinition>, onTextDelta: (String) -> Unit
    ): ChatResponseWithTools {
        checkConfiguration()
        return client.chatWithToolsStreaming(messages, tools) { delta ->
            checkConfiguration()
            onTextDelta(delta)
        }.also { checkConfiguration() }
    }

    override suspend fun chatWithReasoning(
        messages: List<Message>, tools: List<ToolDefinition>,
        onReasoningDelta: (String) -> Unit, onTextDelta: (String) -> Unit,
        reasoningEffort: String?, requestReasoningSummary: Boolean
    ): ChatResponseWithTools {
        checkConfiguration()
        return client.chatWithReasoning(messages, tools,
            onReasoningDelta = { delta -> checkConfiguration(); onReasoningDelta(delta) },
            onTextDelta = { delta -> checkConfiguration(); onTextDelta(delta) },
            reasoningEffort = reasoningEffort, requestReasoningSummary = requestReasoningSummary
        ).also { checkConfiguration() }
    }
}

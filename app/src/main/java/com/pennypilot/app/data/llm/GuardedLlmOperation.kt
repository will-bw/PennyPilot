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
}

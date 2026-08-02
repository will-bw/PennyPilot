package com.pennypilot.app.data.llm

/**
 * LLM 服务抽象接口。
 * 实现类：DeepSeekClient（主力）、QwenClient（备用）。
 */
interface LLMClient {
    /**
     * 发送对话请求。
     * @param messages 对话消息列表
     * @param jsonMode 是否强制 JSON 输出
     * @return 助手回复文本内容，失败抛异常
     */
    suspend fun chat(messages: List<Message>, jsonMode: Boolean = false): String

    /**
     * 携带工具定义的对话请求（Function Calling）。
     * 返回原始响应，由调用方解析 tool_calls。
     */
    suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools
}

class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)

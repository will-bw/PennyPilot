package com.pennypilot.app.data.llm

/**
 * LLM 服务抽象接口。
 * 实现类：DeepSeekClient（主力）、QwenClient（备用）。
 */
enum class ModelRole { TEXT, VISION, REVIEW }

interface LLMClient {
    /** 不包含凭据的配置身份；持久任务只允许原配置执行。 */
    suspend fun operationIdentity(role: ModelRole = ModelRole.TEXT): String = javaClass.name
    suspend fun forRole(role: ModelRole): LLMClient = forOperation()

    /** 多阶段工具查询绑定一次配置；中途切换服务时必须终止，不能转发到新服务。 */
    suspend fun forOperation(): LLMClient = this

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

open class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 已脱敏且适合展示给用户的错误，不能直接展示供应商的原始响应。 */
class UserFacingLlmException(message: String, cause: Throwable? = null) : LLMException(message, cause)

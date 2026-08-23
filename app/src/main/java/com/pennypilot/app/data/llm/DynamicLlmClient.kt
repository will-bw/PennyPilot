package com.pennypilot.app.data.llm

import com.pennypilot.app.data.settings.SecureKeyStore

/**
 * 动态 LLM 路由器：每次调用时实时读取 [SecureKeyStore]，
 * 将请求转发给用户当前手动选择的供应商实现（DeepSeek / Qwen）。
 *
 * 与「启动时固定单例」不同，切换供应商、模型或 Base URL 后
 * 下一次请求立即生效，无需重启 App。
 * 内部按「provider|key|model|baseUrl」签名缓存实例，避免重复创建 OkHttpClient。
 */
class DynamicLlmClient(private val keyStore: SecureKeyStore) : LLMClient {

    private var cacheKey: String? = null
    private var cached: LLMClient? = null

    @Synchronized
    private fun resolve(): LLMClient {
        val provider = keyStore.activeProvider
        if (provider.isBlank()) {
            throw LLMException("尚未选择 AI 供应商，请到「设置 → AI 设置」手动选择 DeepSeek 或通义千问")
        }
        val apiKey = when (provider) {
            "deepseek" -> keyStore.deepSeekKey
            "qwen" -> keyStore.qwenKey
            else -> throw LLMException("未知 AI 供应商：$provider")
        }
        if (apiKey.isBlank()) {
            throw LLMException("当前供应商的 API Key 未配置，请到「设置 → AI 设置」填写")
        }

        val signature = buildString {
            append(provider).append('|').append(apiKey).append('|')
            append(keyStore.deepSeekModel).append('|')
            append(keyStore.qwenModel).append('|')
            append(keyStore.qwenBaseUrl)
        }
        cached?.let { if (signature == cacheKey) return it }

        val client = when (provider) {
            "deepseek" -> DeepSeekClient(apiKey = apiKey, model = keyStore.deepSeekModel)
            else -> QwenClient(
                apiKey = apiKey,
                baseUrl = keyStore.qwenBaseUrl,
                model = keyStore.qwenModel
            )
        }
        cached = client
        cacheKey = signature
        return client
    }

    override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String =
        resolve().chat(messages, jsonMode)

    override suspend fun chatWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>
    ): ChatResponseWithTools = resolve().chatWithTools(messages, tools)
}

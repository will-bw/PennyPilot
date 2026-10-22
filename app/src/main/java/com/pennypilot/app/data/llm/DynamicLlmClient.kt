package com.pennypilot.app.data.llm

import com.pennypilot.app.data.settings.SecureKeyStore
import com.pennypilot.app.data.openai.ChatGptClient
import com.pennypilot.app.data.openai.auth.ChatGptAuthManager

/**
 * 动态 LLM 路由器：每次调用时实时读取 [SecureKeyStore]，
 * 将请求转发给用户选择的服务（ChatGPT 套餐 / DeepSeek / Qwen）。
 *
 * 与「启动时固定单例」不同，切换供应商、模型或 Base URL 后
 * 下一次请求立即生效，无需重启 App。
 * 内部按「provider|key|model|baseUrl」签名缓存实例，避免重复创建 OkHttpClient。
 */
class DynamicLlmClient(
    private val keyStore: SecureKeyStore,
    private val chatGptAuth: ChatGptAuthManager
) : LLMClient {

    private var cacheKey: String? = null
    private var cached: LLMClient? = null

    override suspend fun forOperation(): LLMClient {
        if (keyStore.activeProvider == "chatgpt") chatGptAuth.awaitReady()
        val expected = configuration()
        val delegate = resolve()
        return GuardedLlmOperation(delegate) { configuration() == expected }
    }

    // 只在内存比较；不能日志输出包含旧 API Key 的配置指纹。
    private fun configuration(): List<String> = when (val provider = keyStore.activeProvider) {
        "chatgpt" -> chatGptAuth.state.value.activeAccount.let { account ->
            listOf(provider, account?.id.orEmpty(), account?.isSignedIn.toString(), account?.canUsePlan.toString(),
                account?.id?.let(keyStore::chatGptModel).orEmpty())
        }
        "deepseek" -> listOf(provider, keyStore.deepSeekKey, keyStore.deepSeekModel)
        "qwen" -> listOf(provider, keyStore.qwenKey, keyStore.qwenModel, keyStore.qwenBaseUrl)
        else -> listOf(provider)
    }

    @Synchronized
    private fun resolve(): LLMClient {
        val provider = keyStore.activeProvider
        if (provider.isBlank()) {
            throw UserFacingLlmException("请先到「我的 → AI 设置」登录 ChatGPT，或配置其他 AI 服务。")
        }
        if (provider == "chatgpt") {
            // OAuth 的凭据和刷新由同一个单例管理，不放进缓存签名或日志。
            return ChatGptClient(chatGptAuth) {
                val account = chatGptAuth.state.value.activeAccount
                    ?: throw UserFacingLlmException("请到「我的 → AI 设置」登录 ChatGPT。")
                keyStore.chatGptModel(account.id).ifBlank {
                    throw UserFacingLlmException("请到「我的 → AI 设置」选择并保存此账号的模型。")
                }
            }
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

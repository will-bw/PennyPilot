package com.pennypilot.app.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class QwenClient(
    private val apiKey: String,
    private val baseUrl: String = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    private val model: String = "qwen-plus",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : LLMClient {

    // explicitNulls = false：普通消息不序列化空的 tool_call_id / tool_calls 字段
    // encodeDefaults = true：序列化有默认值的必填字段（如 ToolDefinition.type / ResponseFormat.type），否则 Qwen 会校验报错
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()

    override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String =
        withContext(Dispatchers.IO) {
            val body = MultimodalMessages.request(json, model, messages, jsonMode)

            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                response.close()
                throw UserFacingLlmException("AI 请求失败（HTTP ${response.code}），请检查模型、密钥与服务额度")
            }
            val responseBody = response.body?.string()
                ?: throw LLMException("Qwen API returned empty body")

            val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
            chatResponse.choices.firstOrNull()?.message?.content
                ?: throw LLMException("Qwen API returned no content")
        }

    override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools =
        withContext(Dispatchers.IO) {
            MultimodalMessages.validate(messages)
            val requestJson = buildJsonObject {
                put("model", model)
                put("temperature", 0.1)
                putJsonArray("messages") {
                    messages.forEach { msg ->
                        // 完整序列化消息（含 tool_call_id / tool_calls），保证工具调用协议正确
                        add(MultimodalMessages.compatibleMessage(json, msg))
                    }
                }
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(json.encodeToJsonElement(ToolDefinition.serializer(), tool))
                    }
                }
            }

            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                response.close()
                throw UserFacingLlmException("AI 请求失败（HTTP ${response.code}），请检查模型、密钥与服务额度")
            }
            val responseBody = response.body?.string()
                ?: throw LLMException("Qwen API returned empty body")

            json.decodeFromString(ChatResponseWithTools.serializer(), responseBody)
        }
}

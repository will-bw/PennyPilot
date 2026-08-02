package com.pennypilot.app.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DeepSeekClient(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : LLMClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String =
        withContext(Dispatchers.IO) {
            val request = ChatRequest(
                model = model,
                messages = messages,
                responseFormat = if (jsonMode) ResponseFormat() else null
            )
            val body = json.encodeToString(ChatRequest.serializer(), request)

            val httpRequest = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw LLMException("DeepSeek API error: ${response.code} ${response.message}")
            }
            val responseBody = response.body?.string()
                ?: throw LLMException("DeepSeek API returned empty body")

            val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
            chatResponse.choices.firstOrNull()?.message?.content
                ?: throw LLMException("DeepSeek API returned no content")
        }

    override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools =
        withContext(Dispatchers.IO) {
            val requestJson = buildJsonObject {
                put("model", model)
                put("temperature", 0.1)
                putJsonArray("messages") {
                    messages.forEach { msg ->
                        addJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    }
                }
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(json.encodeToJsonElement(ToolDefinition.serializer(), tool))
                    }
                }
            }

            val httpRequest = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw LLMException("DeepSeek API error: ${response.code} ${response.message}")
            }
            val responseBody = response.body?.string()
                ?: throw LLMException("DeepSeek API returned empty body")

            json.decodeFromString(ChatResponseWithTools.serializer(), responseBody)
        }
}

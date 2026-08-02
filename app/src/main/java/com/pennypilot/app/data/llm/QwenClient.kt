package com.pennypilot.app.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class QwenClient(
    private val apiKey: String,
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
                model = "qwen-plus",
                messages = messages,
                responseFormat = if (jsonMode) ResponseFormat() else null
            )
            val body = json.encodeToString(ChatRequest.serializer(), request)

            val httpRequest = Request.Builder()
                .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(mediaType))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            if (!response.isSuccessful) {
                throw LLMException("Qwen API error: ${response.code} ${response.message}")
            }
            val responseBody = response.body?.string()
                ?: throw LLMException("Qwen API returned empty body")

            val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
            chatResponse.choices.firstOrNull()?.message?.content
                ?: throw LLMException("Qwen API returned no content")
        }
}

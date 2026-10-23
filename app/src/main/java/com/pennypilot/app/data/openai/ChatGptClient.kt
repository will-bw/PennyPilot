package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.AssistantMessage
import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.ChoiceWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.openai.auth.ChatGptAuthManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** Uses only the official ChatGPT-plan Responses route with the selected account and model. */
class ChatGptClient internal constructor(
    private val access: ChatGptAccess,
    private val model: () -> String,
    private val http: OpenAiHttp
) : LLMClient {
    constructor(auth: ChatGptAuthManager, model: () -> String) : this(AuthManagerAccess(auth), model, OpenAiHttp())

    override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
        val (_, result) = respond(messages, emptyList(), jsonMode)
        if (jsonMode) ResponsesCodec.validateJsonObject(result.text)
        return result.text
    }

    override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools {
        val (accountId, result) = respond(messages, tools, false)
        return ChatResponseWithTools(listOf(ChoiceWithTools(
            message = AssistantMessage(
                content = result.text.takeIf { it.isNotEmpty() },
                toolCalls = result.toolCalls.takeIf { it.isNotEmpty() },
                responsesItems = result.items,
                responsesAccountId = accountId
            ),
            finishReason = if (result.toolCalls.isEmpty()) "stop" else "tool_calls"
        )))
    }

    private suspend fun respond(
        messages: List<Message>, tools: List<ToolDefinition>, jsonMode: Boolean
    ): Pair<String, ResponseOutput> {
        access.awaitReady()
        val accountId = access.requireAccount()
        if (messages.any { it.responsesAccountId?.let { id -> id != accountId } == true }) {
            throw OpenAiErrors.accountChanged()
        }
        if (messages.any { it.responsesItems != null && it.responsesAccountId == null }) {
            throw OpenAiErrors.protocol("工具历史缺少所属账号")
        }
        val selectedModel = model().trim()
        val requestBody = ResponsesCodec.request(selectedModel, messages, tools, jsonMode).toString()
        if (requestBody.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024) throw OpenAiErrors.protocol("请求内容过大")
        val token = requireBearerToken(access.token())
        access.requireSameAccount(accountId)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(OneShotBody(requestBody.toRequestBody("application/json".toMediaType())))
            .build()
        val result = http.request(request) { response ->
            val source = response.body?.source() ?: throw OpenAiErrors.protocol("响应为空", response.header("x-request-id"))
            val contentType = response.body?.contentType()
            val declaredStream = contentType?.let { it.type == "text" && it.subtype == "event-stream" } == true
            // Some direct-route responses omit Content-Type. Inspect a bounded, unconsumed
            // prefix only in that case; the full SSE completion checks remain mandatory.
            if (!declaredStream && !(contentType == null && ResponsesCodec.looksLikeEventStream(source))) {
                val preview = source.let {
                    if (it.request(64 * 1024L + 1)) "" else it.readUtf8()
                }
                throw OpenAiErrors.unexpectedResponse(response.code, contentType?.let {
                    "${it.type}/${it.subtype}"
                }, preview, response.header("x-request-id"))
            }
            ResponsesCodec.readStream(source, tools.map { it.function.name }.toSet(), response.header("x-request-id"))
        }
        access.requireSameAccount(accountId)
        return accountId to result
    }
}

/** Also prevents status-based OkHttp follow-ups from replaying a potentially billed POST. */
private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    override fun isOneShot() = true
}

package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.UserFacingLlmException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatGptClientTest {
    @Test fun `inference sends bearer to public endpoint once with one-shot body`() = runTest {
        val factory = FakeCalls { call, callback -> callback.onResponse(call, response(call, answer("完成"))) }
        val client = ChatGptClient(FakeAccess(), { "selected-model" }, OpenAiHttp(factory))
        assertEquals("完成", client.chat(listOf(Message("user", "测试"))))
        val request = factory.calls.single().request()
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-credential", request.header("Authorization"))
        assertTrue(request.body!!.isOneShot())
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("selected-model", Json.parseToJsonElement(body).jsonObject.string("model"))
    }

    @Test fun `json mode never returns incomplete or nonobject text as success`() = runTest {
        val factory = FakeCalls { call, callback -> callback.onResponse(call, response(call, answer("{\"transactions\":["))) }
        val client = ChatGptClient(FakeAccess(), { "m" }, OpenAiHttp(factory))
        val result = runCatching { client.chat(listOf(Message("user", "测试")), jsonMode = true) }
        assertTrue(result.exceptionOrNull() is UserFacingLlmException)
    }

    @Test fun `signed out or ungranted accounts are blocked before token and network`() = runTest {
        listOf(null, AccountAccess("a", false, true), AccountAccess("a", true, false)).forEach { state ->
            val access = FakeAccess(state)
            val factory = FakeCalls { _, _ -> fail("network must not start") }
            val result = runCatching { ChatGptClient(access, { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
            assertTrue(result.exceptionOrNull() is UserFacingLlmException)
            assertEquals(0, access.tokenCalls)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test fun `request waits for encrypted state initialization before checking account`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val access = object : FakeAccess(null) {
            override suspend fun awaitReady() { ready.await() }
        }
        val factory = FakeCalls { call, callback -> callback.onResponse(call, response(call, answer("已加载"))) }
        val task = async { ChatGptClient(access, { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "测试"))) }
        runCurrent()
        assertTrue(factory.calls.isEmpty())
        access.current = AccountAccess("a", true, true)
        ready.complete(Unit)
        assertEquals("已加载", task.await())
    }

    @Test fun `account change during refresh blocks inference before network`() = runTest {
        val access = FakeAccess().apply { onToken = { current = AccountAccess("another", true, true) } }
        val factory = FakeCalls { _, _ -> fail("network must not start") }
        val result = runCatching { ChatGptClient(access, { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
        assertTrue(result.exceptionOrNull() is UserFacingLlmException)
        assertTrue(factory.calls.isEmpty())
    }

    @Test fun `account change while streaming discards the completed response`() = runTest {
        val access = FakeAccess()
        val factory = FakeCalls { call, callback ->
            access.current = AccountAccess("another", true, true)
            callback.onResponse(call, response(call, answer("旧账号结果")))
        }
        val result = runCatching { ChatGptClient(access, { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
        assertTrue(result.exceptionOrNull() is UserFacingLlmException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("账号"))
    }

    @Test fun `cross-account and unbound raw tool history is blocked before credential use`() = runTest {
        val access = FakeAccess()
        val factory = FakeCalls { _, _ -> fail("network must not start") }
        val client = ChatGptClient(access, { "m" }, OpenAiHttp(factory))
        listOf(
            Message("assistant", "private", responsesAccountId = "another"),
            Message("assistant", "private", responsesItems = JsonArray(emptyList()))
        ).forEach { message -> assertTrue(runCatching { client.chat(listOf(message)) }.exceptionOrNull() is UserFacingLlmException) }
        assertEquals(0, access.tokenCalls)
        assertTrue(factory.calls.isEmpty())
    }

    @Test fun `tool response binds original account for the next tool round`() = runTest {
        val factory = FakeCalls { call, callback -> callback.onResponse(call, response(call, ResponsesCodecTest.stream(ResponsesCodecTest.toolOutput))) }
        val result = ChatGptClient(FakeAccess(), { "m" }, OpenAiHttp(factory))
            .chatWithTools(listOf(Message("user", "本月支出")), listOf(ResponsesCodecTest.tool))
            .choices.single().message!!
        assertEquals("account-a", result.responsesAccountId)
        assertEquals(ResponsesCodecTest.toolOutput, result.responsesItems)
        assertEquals("query_transactions", result.toolCalls!!.single().function.name)
    }

    @Test fun `HTTP admission errors preserve status without retry or credential disclosure`() = runTest {
        val factory = FakeCalls { call, callback -> callback.onResponse(call,
            response(call, "{\"detail\":\"Bearer test-credential\"}", 503, "application/json")) }
        val result = runCatching { ChatGptClient(FakeAccess(), { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
        val error = result.exceptionOrNull() as UserFacingLlmException
        assertTrue(error.message!!.contains("HTTP 503"))
        assertTrue(error.message!!.contains("response=detail"))
        assertFalse(error.message!!.contains("test-credential"))
        assertEquals(1, factory.calls.size)
    }

    @Test fun `broken SSE produces a safe failure and does not retry`() = runTest {
        val factory = FakeCalls { call, callback -> callback.onResponse(call, response(call, "data: [DONE]\n\n")) }
        val result = runCatching { ChatGptClient(FakeAccess(), { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
        assertTrue(result.exceptionOrNull() is UserFacingLlmException)
        assertEquals(1, factory.calls.size)
    }

    @Test fun `coroutine cancellation cancels pending HTTP and closes a late response`() = runTest {
        val factory = FakeCalls { _, _ -> Unit }
        val task = async { ChatGptClient(FakeAccess(), { "m" }, OpenAiHttp(factory)).chat(listOf(Message("user", "test"))) }
        runCurrent()
        val call = factory.calls.single()
        task.cancelAndJoin()
        assertTrue(call.isCanceled())
        val tracked = TrackedBody(answer("late"))
        call.callback!!.onResponse(call, response(call, "").newBuilder().body(tracked).build())
        assertTrue(tracked.closed)
    }

    @Test fun `models use selected account catalog and reject a switch before completion`() = runTest {
        val access = FakeAccess()
        val factory = FakeCalls { call, callback -> callback.onResponse(call,
            response(call, """{"models":[{"slug":"chosen","display_name":"可选模型","visibility":"list"}]}""", mediaType = "application/json")) }
        val repository = ChatGptModelRepository(access, OpenAiHttp(factory))
        assertEquals(listOf(ChatGptModel("chosen", "可选模型")), repository.listModels())
        assertEquals("https://api.openai.com/v1/models", factory.calls.single().request().url.toString())
        assertEquals("GET", factory.calls.single().request().method)
        val switched = FakeCalls { call, callback ->
            access.current = AccountAccess("another", true, true)
            callback.onResponse(call, response(call, "{\"models\":[]}", mediaType = "application/json"))
        }
        assertTrue(runCatching { ChatGptModelRepository(access, OpenAiHttp(switched)).listModels() }.exceptionOrNull() is UserFacingLlmException)
    }

    private open class FakeAccess(var current: AccountAccess? = AccountAccess("account-a", true, true)) : ChatGptAccess {
        var tokenCalls = 0
        var onToken: () -> Unit = {}
        override fun account() = current
        override suspend fun token(): String { tokenCalls++; onToken(); return "test-credential" }
    }

    private class FakeCalls(private val handler: (FakeCall, Callback) -> Unit) : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request, handler).also { calls.add(it) }
    }

    private class FakeCall(private val original: Request, private val handler: (FakeCall, Callback) -> Unit) : Call {
        var callback: Callback? = null
        private var canceled = false
        override fun request() = original
        override fun execute(): Response = throw AssertionError("Only asynchronous execution is expected")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; handler(this, responseCallback) }
        override fun cancel() { canceled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(original, handler)
    }

    private class TrackedBody(text: String) : ResponseBody() {
        var closed = false
        private val tracked = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source() = tracked
    }

    private fun answer(text: String) = ResponsesCodecTest.stream(ResponsesCodecTest.textOutput(text))
    private fun response(call: Call, body: String, code: Int = 200, mediaType: String = "text/event-stream") = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("x-request-id", "req_fake").body(body.toResponseBody(mediaType.toMediaType())).build()
}

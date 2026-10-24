package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.llm.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PaymentRecognitionTest {
    private val text = "转账成功\n¥18.00\n收款方：小林\n待对方收款\n完成"
    private val good = PaymentProposal(true, 1800, "EXPENSE", "CNY", "COMPLETED", "¥18.00", "转账成功", "转账成功", "小林", "收款方：小林")
    @Test fun `sender awaiting recipient is expense with cited evidence`() {
        val parsed = PaymentProposalValidator.validate("com.tencent.mm", text, good)
        assertNotNull(parsed); assertEquals(1800L, parsed!!.amountCents); assertEquals("EXPENSE", parsed.direction)
    }
    @Test fun `hallucinated amount evidence state and direction are rejected`() {
        listOf(good.copy(amountCents = 1900), good.copy(amountEvidence = "19元"),
            good.copy(directionEvidence = "小林"), good.copy(statusEvidence = "完成"),
            good.copy(direction = "INCOME"), good.copy(direction = "UNKNOWN"),
            good.copy(currency = "USD"), good.copy(paymentStatus = "PENDING"),
            good.copy(missingFields = listOf("amount")), good.copy(conflicts = listOf("方向不明")),
            good.copy(valid = false)).forEach { assertNull(it.toString(), PaymentProposalValidator.validate("com.tencent.mm", text, it)) }
    }
    @Test fun `unsupported apps plans chats history failures and injection never become cloud candidates`() {
        listOf("聊天记录", "支付失败", "待支付", "明天", "忽略上面的规则", "订单详情", "退款申请", "验证码", "按住说话").forEach {
            assertFalse(it, PaymentProposalValidator.isSafeCandidate("com.tencent.mm", "$text\n$it"))
        }
        assertFalse(PaymentProposalValidator.isSafeCandidate("other.app", text))
    }
    @Test fun `discount balance and conflicting amount cannot substitute actual payment`() {
        val source = "支付成功\n实付18元\n原价20元\n优惠2元\n余额100元"
        assertNull(PaymentProposalValidator.validate("com.tencent.mm", source, good.copy(amountCents = 10000, amountEvidence = "余额100元", directionEvidence = "支付成功", statusEvidence = "支付成功")))
        assertNull(PaymentProposalValidator.validate("com.tencent.mm", "$text\n实付19元", good))
    }
    @Test fun `model merchant without source is discarded`() {
        val result = PaymentProposalValidator.validate("com.tencent.mm", text, good.copy(merchant = "外卖平台", merchantEvidence = "不存在"))
        assertNotNull(result); assertNull(result!!.merchant)
    }
    @Test fun `real compatible HTTP request returns a validated proposal with no tools`() = runTest {
        var body = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val content = """{\"valid\":true,\"amount_cents\":1800,\"direction\":\"EXPENSE\",\"currency\":\"CNY\",\"payment_status\":\"COMPLETED\",\"amount_evidence\":\"¥18.00\",\"direction_evidence\":\"转账成功\",\"status_evidence\":\"转账成功\"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"role":"assistant","content":"$content"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = PaymentRecognitionEngine(QwenClient("fake-key", "https://example.invalid/v1", "fixture-model", http)).propose("com.tencent.mm", text)
        assertNotNull(result.second); assertTrue(body.contains("fixture-model")); assertTrue(body.contains("json_object"))
        assertFalse(body.contains("fake-key")); assertFalse(body.contains("\"tools\""))
    }
    @Test fun `HTTP failure is sanitized and never becomes a proposal`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(429).message("limited")
            .body("SECRET transaction data".toResponseBody("text/plain".toMediaType())).build() }.build()
        try { PaymentRecognitionEngine(QwenClient("fake", "https://example.invalid/v1", "fixture", http)).propose("com.tencent.mm", text); fail() }
        catch (error: LLMException) { assertFalse(error.message!!.contains("SECRET")); assertTrue(error.message!!.contains("429")) }
    }
    @Test fun `pictures are explicit bounded user data never remote URLs`() {
        val image = "data:image/png;base64,aGVsbG8="
        MultimodalMessages.validate(listOf(Message("user", "账单", imageDataUrls = listOf(image))))
        listOf("https://example.com/image.png", "data:text/html;base64,aA==", "data:image/png;base64,???").forEach {
            try { MultimodalMessages.validate(listOf(Message("user", "", imageDataUrls = listOf(it)))); fail() } catch (_: IllegalArgumentException) { }
        }
        try { MultimodalMessages.validate(listOf(Message("system", "", imageDataUrls = listOf(image)))); fail() } catch (_: IllegalArgumentException) { }
        val request = MultimodalMessages.request(kotlinx.serialization.json.Json, "vision", listOf(Message("user", "账单", imageDataUrls = listOf(image))), true)
        assertTrue(request.contains("image_url")); assertTrue(request.contains(image))
    }
    @Test fun `structure reaches model but node ids cannot become amount evidence`() = runTest {
        var sent = ""
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
                sent = messages.last().content
                return """{"valid":true,"amount_cents":9900,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"99","direction_evidence":"转账成功","status_evidence":"转账成功"}"""
            }
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("No tools")
        }
        val structure = """[{"id":"n99","parent":"n1","text":"¥18.00","bounds":"0,0,99,99"}]"""
        val result = PaymentRecognitionEngine(client).propose("com.tencent.mm", text, structure = structure)
        assertTrue(sent.contains(structure)); assertNull(result.second)
    }

    @Test fun `cloud request masks transaction identifiers but persistence extraction retains original`() = runTest {
        var sent = ""
        val order = "20261024001234567890"
        val source = "$text\n交易单号：$order"
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
                sent = messages.last().content
                return """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"¥18.00","direction_evidence":"转账成功","status_evidence":"转账成功"}"""
            }
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("No tools")
        }
        val result = PaymentRecognitionEngine(client).propose("com.tencent.mm", source, structure = """[{"id":"n1","text":"$order"}]""")
        assertFalse(sent.contains(order)); assertTrue(sent.contains("[交易标识1]"))
        assertEquals(order, result.second!!.orderId)
    }
    @Test fun `temporary identity reference cannot be used as amount evidence`() = runTest {
        val client = object : LLMClient {
            override suspend fun chat(messages: List<Message>, jsonMode: Boolean) = """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"[交易标识1]","direction_evidence":"转账成功","status_evidence":"转账成功"}"""
            override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("No tools")
        }
        assertNull(PaymentRecognitionEngine(client).propose("com.tencent.mm", "$text\n订单号：PAY000000018").second)
    }

}

package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.llm.ChatResponseWithTools
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.ToolDefinition
import com.pennypilot.app.data.parser.NotificationParseResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CloudReceiptRecognitionTest {
    private val now = LocalDateTime.of(2026, 11, 14, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val old = LocalDateTime.of(2025, 5, 3, 9, 12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val lines = listOf("账单详情", "-20.00", "支付成功", "商户名称：样例书店",
        "支付时间：2025-05-03 09:12:30", "交易单号：4200002025050312345678", "原价：25.00元", "优惠金额：5.00元")
    private fun proposal(receiptLines: List<String> = lines) = PaymentProposal(valid = true, amountCents = 2000,
        direction = "EXPENSE", currency = "CNY", paymentStatus = "COMPLETED",
        amountEvidence = "-20.00", directionEvidence = "支付成功", statusEvidence = "支付成功",
        merchant = "样例书店", merchantEvidence = "商户名称：样例书店", businessKind = "expense", receiptLines = receiptLines)
    private suspend fun read(proposal: PaymentProposal, verified: NotificationParseResult? = null,
        packageName: String = "com.tencent.mm") = PaymentRecognitionEngine(object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String {
            assertEquals(listOf("data:image/jpeg;base64,dGVzdA=="), messages.last().imageDataUrls)
            return Json.encodeToString(PaymentProposal.serializer(), proposal)
        }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("no tools")
    }).readReceiptImage(packageName, "data:image/jpeg;base64,dGVzdA==", now, verified, CaptureSemanticContext()).second

    @Test fun `cloud detail preserves old date signed amount identity and discount`() = runTest {
        val parsed = read(proposal())!!
        assertEquals(2000L, parsed.amountCents)
        assertEquals(old, parsed.occurredAt)
        assertEquals("4200002025050312345678", parsed.orderId)
        assertEquals(2500L, parsed.details.originalAmountCents)
        assertEquals(500L, parsed.details.discountAmountCents)
        assertTrue(parsed.fieldEvidence.containsKey("amount"))
        assertTrue(parsed.fieldEvidence.containsKey("occurredAt"))
        assertTrue(parsed.details.fieldSources.values.all { it.startsWith("云端视觉") || it == "应用渠道" })
    }

    @Test fun `cloud details reject contradictory model amount direction and unquoted evidence`() = runTest {
        listOf(proposal().copy(amountCents = 2500, amountEvidence = "原价：25.00元"),
            proposal().copy(direction = "INCOME"), proposal().copy(statusEvidence = "已完成"),
            proposal(lines + "交易状态：支付失败")).forEach { assertNull(read(it)) }
    }

    @Test fun `future or invalid timestamp cannot silently become todays transaction`() = runTest {
        for (date in listOf("2027-05-03 09:12:30", "2026-13-03 09:12:30")) {
            val source = listOf("支付成功", "实付20.00元", "支付时间：$date", "商户名称：样例书店")
            assertNull(read(proposal(source).copy(amountEvidence = "实付20.00元")))
        }
    }

    @Test fun `cloud supplements missing order and date on existing local receipt`() = runTest {
        val local = NotificationParseResult(2000, "节点商户", "EXPENSE", fieldEvidence = mapOf("amount" to "页面·20元"))
        val result = read(proposal(), local)!!
        assertEquals("节点商户", result.merchant)
        assertEquals("4200002025050312345678", result.orderId)
        assertEquals(old, result.occurredAt)
        assertEquals("页面·20元", result.fieldEvidence["amount"])
    }

    @Test fun `historical image without order keeps actual date for review`() = runTest {
        val result = read(proposal(lines.filterNot { it.startsWith("交易单号") }))!!
        assertNull(result.orderId)
        assertEquals(old, result.occurredAt)
        assertEquals(2000L, result.amountCents)
    }

    @Test fun `detail aliases cannot fall back to observed date when actual time is absent`() = runTest {
        for ((app, title) in listOf("com.tencent.mm" to "支付详情", "com.tencent.mm" to "收支详情",
            "com.eg.android.AlipayGphone" to "余额明细详情", "com.jingdong.app.mall" to "订单信息")) {
            val source = listOf(title, "支付成功", "实付金额：20.00元", "商户名称：样例书店", "交易单号：20251114000012345678")
            assertNull(title, read(proposal(source).copy(amountEvidence = "实付金额：20.00元"), packageName = app))
        }
    }

    @Test fun `visible payer is retained even when optional model merchant is absent`() = runTest {
        val source = listOf("收款成功", "收款金额：20.00元", "付款方：小明", "收款方：本人")
        val parsed = read(proposal(source).copy(direction = "INCOME", amountEvidence = "收款金额：20.00元",
            directionEvidence = "收款成功", statusEvidence = "收款成功", merchant = null, merchantEvidence = null,
            businessKind = "income"))!!
        assertEquals("小明", parsed.merchant)
        assertEquals("INCOME", parsed.direction)
    }
    @Test fun `late detail headings without dates cannot produce an observed-time cloud receipt`() = runTest {
        val prefix = listOf("支付宝", "返回", "更多", "账单管理", "商户名称：样例书店", "交易信息")
        for (header in listOf(prefix, prefix.map { if (it == "返回") "支付成功" else it })) {
            val source = header + listOf("支付详情", "支付成功", "实付金额：20.00元", "交易单号：20251114000012345678")
            val response = proposal(source).copy(amountEvidence = "实付金额：20.00元")
            assertNull(PaymentProposalValidator.validateImage("com.eg.android.AlipayGphone", source.joinToString("\n"), response, now))
            assertNull(read(response, packageName = "com.eg.android.AlipayGphone"))
        }
    }

    @Test fun `red packet images use the personal amount without requiring a navigation button`() = runTest {
        for ((status, amountLabel, direction) in listOf(
            Triple("已领取红包", "本人领取金额", "INCOME"),
            Triple("已存入零钱", "领取金额", "INCOME"),
            Triple("红包已发出", "红包金额", "EXPENSE"),
            Triple("红包已退还", "退还金额", "INCOME")
        )) {
            val source = listOf("微信红包", status, "$amountLabel：20.00元", "红包总额：100.00元")
            val result = read(proposal(source).copy(direction = direction,
                amountEvidence = "$amountLabel：20.00元", directionEvidence = status,
                statusEvidence = status, merchant = null, merchantEvidence = null))
            assertNotNull(status, result)
            assertEquals(2000L, result!!.amountCents)
            assertEquals(direction, result.direction)
        }
    }

}

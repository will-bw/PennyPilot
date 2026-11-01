package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.parser.NotificationParseResult
import org.junit.Assert.*
import org.junit.Test

class CaptureSemanticResolverTest {
    private val categories = listOf(
        CaptureSemanticCategory(1, "餐饮", "EXPENSE"),
        CaptureSemanticCategory(2, "其他", "EXPENSE"),
        CaptureSemanticCategory(3, "工资", "INCOME"),
        CaptureSemanticCategory(4, "红包", "INCOME"),
        CaptureSemanticCategory(5, "人情", "EXPENSE"),
        CaptureSemanticCategory(6, "购物", "EXPENSE")
    )
    private val context = CaptureSemanticContext("2026-11-01T14:00:00+08:00", categories)
    private fun parsed(merchant: String? = "小林", direction: String = "EXPENSE", scene: String? = null) =
        NotificationParseResult(1700, merchant, direction, scene = scene)

    @Test fun `unknown completed transfer records money movement without counting consumption`() {
        val result = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功 17元 收款方小林", context)
        assertEquals("transfer", result.businessKind)
        assertEquals(2L, result.categoryId)
        assertEquals("微信", result.accountTag)
        assertTrue(result.autoEligible)
        assertTrue(result.assumptions.any { it.contains("不计入消费") })
    }

    @Test fun `merchant consumption and salary classify without another confirmation`() {
        val meal = CaptureSemanticResolver.resolve(parsed("盖饭店"), "com.tencent.mm", "支付成功17元", context)
        assertEquals("expense", meal.businessKind)
        assertEquals(1L, meal.categoryId)
        val salary = CaptureSemanticResolver.resolve(parsed("工资代发", "INCOME"), "com.eg.android.AlipayGphone", "到账成功", context)
        assertEquals("income", salary.businessKind)
        assertEquals(3L, salary.categoryId)
        assertEquals("支付宝", salary.accountTag)
    }

    @Test fun `self transfers stored value and credit card repayment do not become expenses`() {
        listOf("转给自己的支付宝", "本人账户互转", "余额充值", "信用卡还款成功").forEach { description ->
            val result = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", description, context)
            assertEquals(description, "transfer", result.businessKind)
            assertTrue(result.autoEligible)
        }
    }

    @Test fun `loans advances reimbursements and refunds retain their accounting meaning`() {
        val cases = listOf(
            Triple("帮小林垫付", "EXPENSE", "receivable"),
            Triple("借给小林", "EXPENSE", "receivable"),
            Triple("借款到账", "INCOME", "payable"),
            Triple("报销到账", "INCOME", "transfer"),
            Triple("退款到账", "INCOME", "refund")
        )
        cases.forEach { (text, direction, expected) ->
            assertEquals(text, expected, CaptureSemanticResolver.resolve(parsed(direction = direction), "com.tencent.mm", text, context).businessKind)
        }
    }

    @Test fun `red packet personal receipt label is not mistaken for a self transfer`() {
        val received = CaptureSemanticResolver.resolve(parsed("小林", "INCOME", "red_packet_received"), "com.tencent.mm", "本人领取金额17元", context)
        assertEquals("income", received.businessKind)
        val sent = CaptureSemanticResolver.resolve(parsed(scene = "red_packet_sent"), "com.tencent.mm", "红包发送成功", context)
        assertEquals("expense", sent.businessKind)
        assertEquals(5L, sent.categoryId)
    }

    @Test fun `stable same merchant history supplies meaning category and account without model recommendation`() {
        val history = List(3) { CaptureSemanticHistory("小林", "EXPENSE", "expense", 6, "招商银行") }
        val resolution = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history),
            PaymentProposal())
        assertEquals("expense", resolution.businessKind)
        assertEquals(6L, resolution.categoryId)
        assertEquals("招商银行", resolution.accountTag)
        assertTrue(resolution.assumptions.any { it.contains("稳定记账习惯") })
    }

    @Test fun `current AI context can change a previous merchant habit and a refund overrides income preference`() {
        val history = List(3) { CaptureSemanticHistory("小林", "EXPENSE", "transfer", 2) }
        val meal = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history),
            PaymentProposal(businessKind = "expense", categoryId = 1, note = "本次聚餐AA"))
        assertEquals("expense", meal.businessKind)
        assertEquals(1L, meal.categoryId)
        val refund = CaptureSemanticResolver.resolve(parsed(direction = "INCOME").copy(businessKind = "refund"),
            "com.tencent.mm", "退款已到账17元", context.copy(preferences = listOf(
                CaptureSemanticPreference("小林", "INCOME", businessKind = "income"))))
        assertEquals("refund", refund.businessKind)
    }

    @Test fun `explicit preference overrides model and mixed history without importing unrelated people`() {
        val history = listOf(
            CaptureSemanticHistory("小林", "EXPENSE", "expense", 1),
            CaptureSemanticHistory("小林", "EXPENSE", "transfer", 2),
            CaptureSemanticHistory("小王", "EXPENSE", "receivable", 6)
        )
        val preference = CaptureSemanticPreference("小林", "EXPENSE", 6, "receivable", "建设银行")
        val result = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history, preferences = listOf(preference)),
            PaymentProposal(businessKind = "expense", categoryId = 1, accountTag = "零钱"))
        assertEquals("receivable", result.businessKind)
        assertEquals(6L, result.categoryId)
        assertEquals("建设银行", result.accountTag)
    }

    @Test fun `model semantic inference does not require verbatim purpose quotes or invented categories`() {
        val result = CaptureSemanticResolver.resolve(parsed().copy(paymentMethod = "零钱"), "com.tencent.mm", "转账成功17元", context,
            PaymentProposal(businessKind = "expense", categoryId = 999, accountTag = "银行卡", note = "晚餐AA", assumptions = listOf("同伴聚餐分摊")))
        assertEquals("expense", result.businessKind)
        assertEquals(2L, result.categoryId)
        assertEquals("零钱", result.accountTag)
        assertEquals("晚餐AA", result.note)
        assertTrue(result.assumptions.contains("同伴聚餐分摊"))
    }

    @Test fun `semantic context remains bounded`() {
        val bounded = CaptureSemanticContext(
            "x".repeat(1000), List(200) { CaptureSemanticCategory(it.toLong(), "n".repeat(1000), "EXPENSE") },
            List(100) { CaptureSemanticHistory("m".repeat(1000), "EXPENSE", "expense", 1, "a".repeat(1000), "n".repeat(1000)) },
            List(100) { CaptureSemanticPreference("m".repeat(1000), "EXPENSE", 1) }
        ).bounded()
        assertEquals(100, bounded.allowedCategories.size)
        assertEquals(12, bounded.relatedHistory.size)
        assertEquals(12, bounded.preferences.size)
        assertEquals(80, bounded.deviceTime.length)
        assertEquals(120, bounded.relatedHistory.first().note!!.length)
    }
}

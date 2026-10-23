package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity
import org.junit.Assert.*
import org.junit.Test

class ParseRuleEngineTest {

    private val wechatRules = PresetParseRules.rules.filter { it.appPackage == "com.tencent.mm" }
    private val alipayRules = PresetParseRules.rules.filter { it.appPackage == "com.eg.android.AlipayGphone" }

    @Test
    fun `wechat pay to merchant`() {
        val text = "微信支付: 你向沙县小吃付款18.00元"
        val result = ParseRuleEngine.parse(text, wechatRules)
        assertNotNull(result)
        assertEquals(1800L, result!!.amountCents)
        assertEquals("沙县小吃", result.merchant)
        assertEquals("EXPENSE", result.direction)
    }

    @Test
    fun `wechat generic pay`() {
        val text = "微信支付￥35.50"
        val result = ParseRuleEngine.parse(text, wechatRules)
        assertNotNull(result)
        assertEquals(3550L, result!!.amountCents)
        assertEquals("EXPENSE", result.direction)
    }

    @Test
    fun `wechat income`() {
        val text = "微信收款到账8000.00元"
        val result = ParseRuleEngine.parse(text, wechatRules)
        assertNotNull(result)
        assertEquals(800000L, result!!.amountCents)
        assertEquals("INCOME", result.direction)
    }

    @Test
    fun `non-transaction notification returns null`() {
        val text = "张三邀请你加入群聊"
        val result = ParseRuleEngine.parse(text, wechatRules)
        assertNull(result)
    }

    @Test
    fun `alipay expense`() {
        val text = "支付宝支付成功，在美团外卖消费86.20元"
        val result = ParseRuleEngine.parse(text, alipayRules)
        assertNotNull(result)
        assertEquals(8620L, result!!.amountCents)
        assertEquals("美团外卖", result.merchant)
    }

    @Test
    fun `alipay income`() {
        val text = "支付宝到账1000.00元"
        val result = ParseRuleEngine.parse(text, alipayRules)
        assertNotNull(result)
        assertEquals(100000L, result!!.amountCents)
        assertEquals("INCOME", result.direction)
    }

    @Test
    fun `empty rules returns null`() {
        assertNull(ParseRuleEngine.parse("微信支付￥35.50", emptyList()))
    }

    @Test
    fun `zero amount rule is skipped`() {
        val rules = listOf(
            ParseRuleEntity(id = 6, appPackage = "com.tencent.mm",
                regexAmount = """付款[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = null,
                directionHint = "EXPENSE", priority = 1),
        )
        // 金额 0 不算交易，应返回 null（避免脏数据入库）
        assertNull(ParseRuleEngine.parse("你向某商户付款0.00元", rules))
    }

    @Test
    fun `payment title cannot override income direction`() {
        val result = ParseRuleEngine.parse("微信支付 微信收款到账8000.00元", wechatRules)
        assertEquals("INCOME", result?.direction)
        assertEquals(800000L, result?.amountCents)
    }

    @Test
    fun `merchant digits cannot become payment amount`() {
        val result = ParseRuleEngine.parse("微信支付 你向7-Eleven付款18.00元", wechatRules)
        assertEquals(1800L, result?.amountCents)
        assertEquals("7-Eleven", result?.merchant)
    }

    @Test
    fun `grouped currency is parsed as a complete exact amount`() {
        assertEquals(180000L,
            ParseRuleEngine.parse("微信支付 你向沙县小吃付款1,800.00元", wechatRules)?.amountCents)
        assertEquals(1990L, ParseRuleEngine.parse("付款19.90元", wechatRules)?.amountCents)
    }

    @Test
    fun `partial malformed and out of range amounts are rejected`() {
        listOf("1,80.00", "1.234", "10000000.00", "0.00").forEach { amount ->
            assertNull(amount, ParseRuleEngine.parse("付款${amount}元", wechatRules))
        }
        // 用户旧规则仍可能只捕获前缀，解析器必须二次检查 token 边界。
        assertNull(ParseRuleEngine.parse("付款1,800.00元", PresetParseRules.legacyRules.take(1)))
    }

    @Test
    fun `failed and pending payments are not captured`() {
        listOf("支付宝 付款失败，本次金额18.00元", "支付宝 待支付18.00元",
            "微信支付 支付失败18.00元").forEach { text ->
            assertNull(text, ParseRuleEngine.parse(text, alipayRules))
        }
    }

    @Test
    fun `invalid and disabled rules cannot block remaining rules`() {
        val invalid = ParseRuleEntity(appPackage = "com.tencent.mm", regexAmount = "(", priority = -2)
        val disabled = ParseRuleEntity(appPackage = "com.tencent.mm",
            regexAmount = """付款(\d+)""", directionHint = "INCOME", enabled = false, priority = -1)
        assertEquals("EXPENSE", ParseRuleEngine.parse("付款18.00元",
            listOf(invalid, disabled) + wechatRules)?.direction)
    }

    @Test
    fun `rules cannot select original amount over actual amount`() {
        assertNull(ParseRuleEngine.parse("支付成功，原支付金额30元，实付18元", wechatRules))
    }

    @Test
    fun `rules cannot select one of conflicting payment amounts`() {
        assertNull(ParseRuleEngine.parse("付款18元，付款28元", wechatRules))
    }

    @Test
    fun `expense rule cannot misclassify a completed refund`() {
        assertNull(ParseRuleEngine.parse("退款成功，退款5元，原支付金额30元", wechatRules))
    }
}

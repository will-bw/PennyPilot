package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity
import org.junit.Assert.*
import org.junit.Test

class ParseRuleEngineTest {

    private val wechatRules = listOf(
        ParseRuleEntity(id = 1, appPackage = "com.tencent.mm",
            regexAmount = """(?:付款|支付)[^\d]*(\d+(?:\.\d{1,2})?)""",
            regexMerchant = """(?:向|给)(.+?)(?:付款|支付)""",
            directionHint = "EXPENSE", priority = 1),
        ParseRuleEntity(id = 2, appPackage = "com.tencent.mm",
            regexAmount = """微信支付[^\d]*(\d+(?:\.\d{1,2})?)""",
            regexMerchant = null,
            directionHint = "EXPENSE", priority = 2),
        ParseRuleEntity(id = 3, appPackage = "com.tencent.mm",
            regexAmount = """收款[^\d]*(\d+(?:\.\d{1,2})?)""",
            regexMerchant = null,
            directionHint = "INCOME", priority = 3),
    )

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
        val alipayRules = listOf(
            ParseRuleEntity(id = 4, appPackage = "com.eg.android.AlipayGphone",
                regexAmount = """(?:付款|支付|消费)[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = """(?:向|在)(.+?)(?:付款|支付|消费)""",
                directionHint = "EXPENSE", priority = 1),
        )
        val text = "支付宝支付成功，在美团外卖消费86.20元"
        val result = ParseRuleEngine.parse(text, alipayRules)
        assertNotNull(result)
        assertEquals(8620L, result!!.amountCents)
        assertEquals("美团外卖", result.merchant)
    }
}

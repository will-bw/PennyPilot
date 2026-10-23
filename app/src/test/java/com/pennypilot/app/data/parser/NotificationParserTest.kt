package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationParserTest {
    @Test
    fun `explicit transfer receipt works locally with cloud disabled`() = runTest {
        var calls = 0
        val result = NotificationParser.parse("com.tencent.mm", "微信支付", "转账成功，金额18.00元",
            emptyList(), cloudParsingEnabled = false) { calls++; null }
        assertEquals(1800L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
        assertEquals(0, calls)
    }

    @Test
    fun `ordinary chats and advertisements cannot match even permissive custom rules`() = runTest {
        var calls = 0
        val rules = listOf(ParseRuleEntity(appPackage = "com.tencent.mm", regexAmount = """(\d+(?:\.\d{1,2})?)"""))
        listOf("张三" to "我今天支付了18元", "张三" to "付款成功18元", "张三" to "明天的会议改到下午",
            "微信支付" to "优惠券支付立减18元", "微信支付" to "付款失败18元",
            "微信支付" to "明天的会议改到下午").forEach { (title, content) ->
            assertNull(NotificationParser.parse("com.tencent.mm", title, content,
                rules, cloudParsingEnabled = true) { calls++; NotificationParseResult(1800, null, "EXPENSE") })
        }
        assertEquals(0, calls)
    }

    @Test
    fun `known receipt uses local parser before cloud provider`() = runTest {
        var calls = 0
        val result = NotificationParser.parse("com.tencent.mm", "微信支付", "转账成功，金额18.00元",
            emptyList(), cloudParsingEnabled = true) { calls++; NotificationParseResult(99900, null, "EXPENSE") }
        assertEquals(1800L, result?.amountCents)
        assertEquals(0, calls)
        val rules = PresetParseRules.rules.filter { it.appPackage == "com.tencent.mm" }
        assertEquals(1800L, NotificationParser.parse("com.tencent.mm", "微信支付", "付款18.00元", rules,
            cloudParsingEnabled = true) { calls++; null }?.amountCents)
        assertEquals(0, calls)
    }

    @Test
    fun `meituan and douyin receipts do not require installed regex rules`() = runTest {
        listOf("com.sankuai.meituan" to "美团", "com.sankuai.meituan.takeoutnew" to "美团外卖",
            "com.ss.android.ugc.aweme" to "抖音支付", "com.ss.android.ugc.aweme.lite" to "抖音极速版").forEach { (pkg, title) ->
            val result = NotificationParser.parse(pkg, title, "订单支付成功，商家：喜茶，原价25元，实付18.00元，订单号：MT20261023123456",
                emptyList(), cloudParsingEnabled = false) { fail("本地凭证不应请求云端"); null }
            assertEquals(pkg, 1800L, result?.amountCents)
            assertEquals("喜茶", result?.merchant)
            assertEquals("MT20261023123456", result?.orderId)
        }
    }

    @Test
    fun `refund and incoming notifications preserve direction`() = runTest {
        val refund = NotificationParser.parse("com.sankuai.meituan", "退款成功", "退款金额：5.00元，原支付金额：30.00元",
            emptyList(), false) { null }
        assertEquals(500L, refund?.amountCents)
        assertEquals("INCOME", refund?.direction)
        val income = NotificationParser.parse("com.tencent.mm", "微信支付", "微信收款到账88.80元", emptyList(), false) { null }
        assertEquals(8880L, income?.amountCents)
        assertEquals("INCOME", income?.direction)
    }

    @Test
    fun `ambiguous and malformed amounts never reach cloud or permissive rules`() = runTest {
        var calls = 0
        val rules = PresetParseRules.rules.filter { it.appPackage == "com.tencent.mm" }
        listOf("支付成功18元，支付成功28元", "支付成功，实付18元，另收5元", "支付成功，付款1.234元",
            "支付成功，付款0元", "支付成功，付款10000000元").forEach { content ->
            assertNull(content, NotificationParser.parse("com.tencent.mm", "微信支付", content, rules, true) {
                calls++; NotificationParseResult(1800, null, "EXPENSE")
            })
        }
        assertEquals(0, calls)
    }

    @Test
    fun `marketplace pending orders and promotions are not payment notifications`() = runTest {
        listOf("订单待支付，实付18元", "立即支付18元", "支付成功后可领取18元优惠券", "退款申请中，退款金额18元",
            "订单已取消，支付金额18元", "支付立减18元", "订单金额18元").forEach { content ->
            assertNull(content, NotificationParser.parse("com.ss.android.ugc.aweme", "抖音", content, emptyList(), true) {
                fail("非凭证不应请求云端"); null
            })
        }
    }

    @Test
    fun `unknown package cannot use payment titles to bypass source checks`() = runTest {
        assertNull(NotificationParser.parse("com.example.chat", "微信支付", "付款18.00元", PresetParseRules.rules, true) {
            fail("未知应用不应请求云端"); null
        })
    }

    @Test
    fun `custom rules can still supply merchant when amount and direction agree`() = runTest {
        val rule = ParseRuleEntity(appPackage = "com.tencent.mm", regexAmount = """付款(\d+\.\d{2})""",
            regexMerchant = """交易对象：(\S+)""")
        val result = NotificationParser.parse("com.tencent.mm", "微信支付", "付款18.00元\n交易对象：自定义小店",
            listOf(rule), false) { null }
        assertEquals("自定义小店", result?.merchant)
        assertEquals(1800L, result?.amountCents)
        val inconsistent = rule.copy(directionHint = "INCOME")
        assertNull(NotificationParser.parse("com.tencent.mm", "微信支付", "付款18.00元\n交易对象：自定义小店",
            listOf(inconsistent), false) { null }?.merchant)
    }
}

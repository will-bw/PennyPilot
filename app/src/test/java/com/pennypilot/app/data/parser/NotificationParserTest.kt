package com.pennypilot.app.data.parser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationParserTest {
    @Test
    fun `disabled cloud parsing never calls provider`() = runTest {
        var calls = 0
        val result = NotificationParser.parse("com.tencent.mm", "微信支付", "转账成功，金额18.00元",
            emptyList(), cloudParsingEnabled = false) { calls++; null }
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `ordinary chats and advertisements never call enabled cloud parser`() = runTest {
        var calls = 0
        listOf("张三" to "我今天支付了18元", "张三" to "明天的会议改到下午",
            "微信支付" to "优惠券支付立减18元", "微信支付" to "付款失败18元",
            "微信支付" to "明天的会议改到下午").forEach { (title, content) ->
            assertNull(NotificationParser.parse("com.tencent.mm", title, content,
                emptyList(), cloudParsingEnabled = true) { calls++; null })
        }
        assertEquals(0, calls)
    }

    @Test
    fun `explicit receipt may use enabled cloud fallback only after rule miss`() = runTest {
        var calls = 0
        val expected = NotificationParseResult(1800, null, "EXPENSE")
        val result = NotificationParser.parse("com.tencent.mm", "微信支付", "转账成功，金额18.00元",
            emptyList(), cloudParsingEnabled = true) { calls++; expected }
        assertEquals(expected, result)
        assertEquals(1, calls)
        val rules = PresetParseRules.rules.filter { it.appPackage == "com.tencent.mm" }
        NotificationParser.parse("com.tencent.mm", "微信支付", "付款18.00元", rules,
            cloudParsingEnabled = true) { calls++; null }
        assertEquals(1, calls)
    }

}

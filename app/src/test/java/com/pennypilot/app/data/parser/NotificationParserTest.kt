package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NotificationParserTest {
    @Test
    fun `wechat wallet labels supply an order only for an actual wechat receipt`() = runTest {
        val order = "4200000000202611070000000001"
        suspend fun parse(content: String, packageName: String = "com.tencent.mm", title: String = "微信支付") =
            NotificationParser.parse(packageName, title, content, emptyList(), cloudParsingEnabled = false) { null }

        val payment = "支付成功，实付18.00元，商户：样例咖啡店"
        for (label in listOf("微信支付单号", "微信支付交易单号", "微信支付订单号")) {
            val body = "$payment，$label：$order"
            assertEquals(label, order, parse(body)?.orderId)
            assertEquals(label, "样例咖啡店", parse(body)?.merchant)
            assertNull(label, parse(body, title = "普通聊天"))
            assertNull(label, parse(body, "com.eg.android.AlipayGphone", "支付宝")!!.orderId)
        }
        assertEquals(order, parse("$payment，商品说明：手冲咖啡，微信支付单号：$order")?.orderId)
        assertNull(parse("$payment，备注：微信支付单号：$order")!!.orderId)
        assertNull(parse("$payment，微信支付单号：123456789012")!!.orderId)
        assertNull(parse("$payment，微信支付单号：$order，交易单号：4200000000202611070000000002"))
        assertEquals("SHOP202611070123", parse("$payment，微信支付单号：$order，商户订单号：SHOP202611070123")?.orderId)
    }

    @Test
    fun `unionpay records the unique visible transaction time after validating payment facts`() = runTest {
        val postedAt = LocalDateTime.of(2026, 11, 7, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val body = "支付成功，实付18.00元，商户：样例门店，交易单号：2026110700001234"
        val time = "交易时间：2026-11-07 11:58:30"
        suspend fun parse(content: String) = NotificationParser.parse("com.unionpay", "云闪付", content,
            emptyList(), cloudParsingEnabled = false, observedAt = postedAt) { error("仅验证本地通知") }

        val result = parse("$body，$time")!!
        assertEquals(1800L, result.amountCents)
        assertEquals("2026110700001234", result.orderId)
        assertEquals(postedAt - 90_000, result.occurredAt)
        assertEquals("通知·$time", result.fieldEvidence["occurredAt"])
        assertEquals(result.occurredAt, parse("$body\n交易时间\n2026-11-07\n11:58:30")!!.occurredAt)
        assertNull(parse("$body，$time".replace("支付成功", "支付失败")))

        for (invalid in listOf("", "交易时间：11月7日 11:58:30", "交易时间：2026-02-30 11:58:30",
                "交易时间：2026-11-07 12:00:01", "交易时间：2026-11-07 11:29:59",
                "$time\n付款时间：2026-11-07 11:59:00", "备注：$time", "订单创建时间：2026-11-07 11:58:30")) {
            val receipt = parse("$body\n$invalid")!!
            assertNull(invalid, receipt.occurredAt)
            assertFalse(invalid, "occurredAt" in receipt.fieldEvidence)
        }
    }

    @Test
    fun `refund notification only uses its own completion time`() = runTest {
        val postedAt = LocalDateTime.of(2026, 11, 7, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val body = "退款已到账\n退款金额：5元\n退款单号：RF2026110701234\n原支付时间：2026-11-07 11:45:00"
        suspend fun parse(content: String) = NotificationParser.parse("com.unionpay", "退款提醒", content,
            emptyList(), cloudParsingEnabled = false, observedAt = postedAt) { null }
        assertNull(parse(body)!!.occurredAt)
        val result = parse("$body\n退款到账时间：2026-11-07 11:58:30")!!
        assertEquals("refund", result.businessKind)
        assertEquals("RF2026110701234", result.orderId)
        assertEquals(postedAt - 90_000, result.occurredAt)
    }

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
    fun `alipay and meituan completed receipt titles keep transaction evidence`() = runTest {
        listOf("com.eg.android.AlipayGphone", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew").forEach { pkg ->
            listOf("交易成功", "交易已成功通知", "付款完成", "扣款成功通知").forEach { title ->
                val result = NotificationParser.parse(pkg, title, "实付18.00元\n商家：便利店",
                    emptyList(), true) { fail("完整凭证应在本机识别"); null }
                assertEquals("$pkg $title", 1800L, result?.amountCents)
                assertEquals("EXPENSE", result?.direction)
                assertEquals("便利店", result?.merchant)
            }
            val income = NotificationParser.parse(pkg, "收款已到账通知", "收款金额18元",
                emptyList(), false) { null }
            assertEquals("INCOME", income?.direction)
        }
    }

    @Test
    fun `complete amount in notification title does not require a separate body`() = runTest {
        listOf("com.eg.android.AlipayGphone", "com.sankuai.meituan").forEach { pkg ->
            listOf("支付成功18.00元", "付款成功：￥18.00", "订单支付成功 18.00元").forEach { title ->
                val result = NotificationParser.parse(pkg, title, "", emptyList(), false) { null }
                assertEquals(title, 1800L, result?.amountCents)
                assertEquals("EXPENSE", result?.direction)
            }
        }
    }

    @Test
    fun `receipt title expansion cannot accept chats promotions pending or directionless trades`() = runTest {
        listOf("com.eg.android.AlipayGphone", "com.sankuai.meituan").forEach { pkg ->
            val rules = listOf(ParseRuleEntity(appPackage = pkg, regexAmount = """(\d+(?:\.\d{1,2})?)"""))
            listOf("张三" to "交易成功，实付18元", "交易成功" to "￥18.00",
                "支付成功后可领取18元优惠券" to "实付18元", "交易成功" to "待支付18元",
                "付款完成" to "付款失败18元", "扣款成功通知" to "实付18元，另收28元",
                "交易成功" to "收款金额18元\n支付成功").forEach { (title, content) ->
                assertNull("$pkg $title $content", NotificationParser.parse(pkg, title, content, rules, true) {
                    fail("非凭证不能交给云端猜测"); null
                })
            }
        }
        assertNull(NotificationParser.parse("com.tencent.mm", "交易成功", "实付18元", emptyList(), true) {
            fail("微信聊天仍需可信来源标题"); null
        })
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

    @Test
    fun `alipay transaction statement supplies direction without inventing a success title`() = runTest {
        val pkg = "com.eg.android.AlipayGphone"
        listOf("支出" to "EXPENSE", "收入" to "INCOME").forEach { (action, direction) ->
            val body = "你有一笔1,234.56元的$action。\n交易单号：AP202611060001"
            val result = NotificationParser.parse(pkg, "交易提醒", body, emptyList(), true) {
                fail("完整动账语句应本机解析"); null
            }!!
            assertEquals(123456L, result.amountCents)
            assertEquals(direction, result.direction)
            assertEquals("AP202611060001", result.orderId)
            assertTrue(result.fieldEvidence["direction"]!!.contains("你有一笔1,234.56元的$action"))
            assertTrue(result.fieldEvidence["paymentStatus"]!!.contains("交易动账语句"))
        }
    }

    @Test
    fun `transaction statement expansion rejects quoted hypothetical conflicting and malformed evidence`() = runTest {
        val pkg = "com.eg.android.AlipayGphone"
        val rules = listOf(ParseRuleEntity(appPackage = pkg, regexAmount = """(\d+)"""))
        listOf("如果你有一笔18元的支出", "明天你有一笔18元的支出", "备注：你有一笔18元的支出",
            "你有一笔1,80.00元的支出", "你有一笔-18元的支出", "你有一笔0元的支出",
            "你有一笔18元的支出\n另收28元", "你有一笔18元的支出\n收款成功18元",
            "你有一笔18元的收入\n付款金额18元", "你有一笔18元的支出\n交易状态：处理中",
            "你有一笔18元的支出\n你有一笔28元的支出", "支出金额18元").forEach { body ->
            assertNull(body, NotificationParser.parse(pkg, "交易提醒", body, rules, true) {
                fail("不完整或矛盾语句不得交给云端"); null
            })
        }
        assertNull(NotificationParser.parse(pkg, "好友消息", "你有一笔18元的支出", rules, true) {
            fail("仍需可信标题"); null
        })
        assertNull(NotificationParser.parse("com.tencent.mm", "交易提醒", "你有一笔18元的支出", emptyList(), true) {
            fail("不能扩大为聊天消息识别"); null
        })
    }

    @Test
    fun `wallet reminder titles require settled evidence and keep separate refund IDs`() = runTest {
        for (pkg in listOf("com.eg.android.AlipayGphone", "com.unionpay")) {
            val payment = NotificationParser.parse(pkg, "交易提醒",
                "消费成功，消费金额1,234.56元，商家：测试商户，交易单号：PAY202611060001", emptyList(), false) { null }!!
            assertEquals(123456L, payment.amountCents)
            assertEquals("EXPENSE", payment.direction)
            val refund = NotificationParser.parse(pkg, "退款提醒",
                "退款已到账，退款金额5元，原支付金额30元，交易单号：PAY202611060001，退款单号：RF202611060001",
                emptyList(), false) { null }!!
            assertEquals(500L, refund.amountCents)
            assertEquals("INCOME", refund.direction)
            assertEquals("refund", refund.businessKind)
            assertEquals("RF202611060001", refund.orderId)
            assertEquals("refund", refund.orderIdKind)
            assertTrue(refund.fieldEvidence.containsKey("originalOrderId"))
            listOf("退款受理，退款金额5元", "退款提醒，退款金额5元", "退款到账中，退款金额5元",
                "支付成功，支付金额18元，交易状态：处理中", "支付成功，实付18元，支付未成功",
                "支付成功,实付18元,交易状态:处理中", "支付成功;实付18元;付款状态:未成功",
                "退款成功，退款金额5元，退款状态：受理").forEach { body ->
                assertNull(body, NotificationParser.parse(pkg, "退款提醒", body, emptyList(), true) {
                    fail("未完成状态不得交给云端"); null
                })
            }
        }
    }

    @Test
    fun `successful alipay collection and unionpay receipt notifications remain local`() = runTest {
        val income = NotificationParser.parse("com.eg.android.AlipayGphone", "成功收款",
            "你已成功收款28.50元", emptyList(), true) { fail("应本机识别"); null }!!
        assertEquals(2850L, income.amountCents)
        assertEquals("INCOME", income.direction)
        val paid = NotificationParser.parse("com.unionpay", "云闪付", "支付成功，实付18元",
            emptyList(), false) { null }!!
        assertEquals("云闪付", paid.paymentMethod)
        assertEquals("EXPENSE", paid.direction)
        listOf("消费18元", "交易金额18元", "订单金额18元", "退款金额5元", "支付成功后可领18元").forEach { body ->
            assertNull(body, NotificationParser.parse("com.unionpay", "云闪付", body, emptyList(), true) {
                fail("新增应用不放宽完成门槛"); null
            })
        }
    }

    @Test
    fun `transaction statement cannot hide another same amount transaction or a denied outcome`() = runTest {
        val pkg = "com.eg.android.AlipayGphone"
        val rules = listOf(ParseRuleEntity(appPackage = pkg, regexAmount = """(\d+)"""))
        listOf("支付宝", "支付宝通知", "交易提醒").forEach { title ->
            listOf(
                "你有一笔18元的收入，另有一笔18元的支出",
                "你有一笔18元的支出，另有一笔18元的支出",
                "你有一笔18元的支出\n另有一笔18元的支出",
                "你有一笔18元的支出\n你有一笔18元的支出",
                "你有一笔18元的支出，尚未成功",
                "你有一笔18元的支出，实际未扣款",
                "你有一笔18元的支出\n尚未成功",
                "你有一笔18元的支出\n实际未扣款",
                "你有一笔18元的支出\n收款方：未扣款",
                "你有一笔18元的支出\n收款方：另有一笔18元的支出",
                "你有一笔18元的支出，付款18元",
                "你有一笔18元的支出\n支付成功18元"
            ).forEach { body ->
                assertNull("$title $body", NotificationParser.parse(pkg, title, body, rules, true) {
                    fail("不完整动账模板不得回退自定义或云端规则"); null
                })
                assertFalse(NotificationParser.isCloudCandidate(pkg, title, body))
            }
        }
    }

    @Test
    fun `single transaction statement permits identified supplemental fields and disregards remarks`() = runTest {
        val body = """
            你有一笔1,234.56元的支出。
            收款方
            测试商户
            付款方式：银行卡
            商户订单号：SHOP202611060001
            交易单号：WALLET202611060001
            备注：未扣款只是测试用语
        """.trimIndent()
        val result = NotificationParser.parse("com.eg.android.AlipayGphone", "支付宝", body, emptyList(), false) { null }!!
        assertEquals(123456L, result.amountCents)
        assertEquals("测试商户", result.merchant)
        assertEquals("银行卡", result.paymentMethod)
        assertEquals("SHOP202611060001", result.orderId)
        val income = NotificationParser.parse("com.eg.android.AlipayGphone", "支付宝", body.replace("支出。", "收入。"),
            emptyList(), false) { null }!!
        assertEquals("INCOME", income.direction)
    }

    @Test
    fun `Alipay automatic debit notice records the merchant and ignores loyalty points`() = runTest {
        val body = "你在示例便利店有一笔18.50元的免密/自动扣款支付，点击领取18个支付宝积分。"
        val result = NotificationParser.parse("com.eg.android.AlipayGphone", "交易提醒", body,
            emptyList(), false) { null }!!
        assertEquals(1850L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("示例便利店", result.merchant)
        assertEquals("示例便利店", result.details.merchantName)
        assertTrue(result.fieldEvidence.getValue("paymentStatus").contains("自动扣款支付"))
        assertFalse(result.fieldEvidence.getValue("paymentStatus").contains("积分"))
        assertNull(result.orderId)
    }

    @Test
    fun `Alipay automatic debit requires a single completed transaction statement`() = runTest {
        val pkg = "com.eg.android.AlipayGphone"
        val debit = "你在示例便利店有一笔18.50元的免密/自动扣款支付"
        val rule = ParseRuleEntity(appPackage = pkg, regexAmount = """(\d+)""")
        val rejected = listOf("点击领取18个支付宝积分。", "$debit，实际未扣款", "$debit\n$debit",
            "$debit，另有一笔18.50元的支出", "如果$debit", debit.replace("18.50", "-18.50"),
            debit.replace("18.50", "1,8.50"), "$debit\n交易状态：处理中")
        for (body in rejected) {
            assertNull(NotificationParser.parse(pkg, "交易提醒", body, listOf(rule), true) {
                fail("未完成或不明确的自动扣款不得回退识别"); null
            })
        }
        assertNull(NotificationParser.parse("com.tencent.mm", "交易提醒", debit, emptyList(), false) { null })
    }

    @Test
    fun `UnionPay assistant completed charge preserves actual funding tail without guessing the date`() = runTest {
        val body = "本次消费1,234.56元，于7日9时5分完成，尾号为0000的银行卡。"
        val result = NotificationParser.parse("com.unionpay", "银联支付助手", body, emptyList(), false) { null }!!
        assertEquals(123456L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("银行卡（尾号 0000）", result.details.fundingAccount)
        assertEquals("云闪付", result.details.paymentChannel)
        assertNull(result.occurredAt)
        val invalid = listOf(body.replace("完成", "处理中"), body.replace("消费", "退款受理"),
            body + "另有一笔付款18元", body.replace("0000", "00000000"), body.replace("9时", "25时"))
        for (sample in invalid) {
            assertNull(NotificationParser.parse("com.unionpay", "银联支付助手", sample, emptyList(), true) {
                fail("不能将未完成或冲突通知交给模型猜测"); null
            })
        }
        assertNull(NotificationParser.parse("com.tencent.mm", "银联支付助手", body, emptyList(), false) { null })
    }

    @Test
    fun `same amount multiple payment identities cannot collapse into one notification`() = runTest {
        val pkg = "com.unionpay"
        val rules = listOf(ParseRuleEntity(appPackage = pkg, regexAmount = """(\d+)"""))
        listOf(
            "支付成功，实付18元，交易单号：UP202611060001\n支付成功，实付18元，交易单号：UP202611060002",
            "支付成功，实付18元，商户订单号：SHOP202611060001\n商家订单号：SHOP202611060002"
        ).forEach { body ->
            assertNull(body, NotificationParser.parse(pkg, "云闪付", body, rules, true) {
                fail("多个同类身份不得猜选一笔"); null
            })
            assertFalse(NotificationParser.isCloudCandidate(pkg, "云闪付", body))
        }
        val identified = "支付成功，实付18元，商户订单号：SHOP202611060001，交易单号：UP202611060001，交易号：UP202611060001"
        assertEquals("SHOP202611060001", NotificationParser.parse(pkg, "云闪付", identified, emptyList(), false) { null }?.orderId)
    }

    @Test
    fun `inline free text cannot create transaction evidence or override completed facts`() = runTest {
        val pkg = "com.eg.android.AlipayGphone"
        listOf("备注", "商品说明", "留言", "付款说明").forEach { label ->
            assertNull(NotificationParser.parse(pkg, "支付宝", "系统提示，$label：支付成功18元", emptyList(), true) {
                fail("行内自由文字不能证明交易"); null
            })
            assertNull(NotificationParser.parse(pkg, "支付宝", "支付失败，支付金额18元，$label：支付成功", emptyList(), true) {
                fail("备注不能覆盖失败状态"); null
            })
            val body = "支付成功，实付18元，$label：退款受理，收款成功28元，交易单号：FAKE202611060001"
            val result = NotificationParser.parse(pkg, "支付宝", body, emptyList(), false) { null }!!
            assertEquals(1800L, result.amountCents)
            assertEquals("EXPENSE", result.direction)
            assertNull(result.orderId)
            assertFalse(result.fieldEvidence.containsKey("orderId"))
        }
        assertNull(NotificationParser.parse(pkg, "支付宝", "备注：\n支付成功18元", emptyList(), true) {
            fail("空冒号标签的下一行仍是备注值"); null
        })
    }
}

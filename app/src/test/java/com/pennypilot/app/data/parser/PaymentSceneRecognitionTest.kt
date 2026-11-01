package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test

/** Versioned synthetic mechanism fixtures. Real WeChat node exports remain device acceptance work. */
class PaymentSceneRecognitionTest {
    private val wechat = "com.tencent.mm"
    private fun scene(text: String) = PaymentTextParser.analyzeScreen(wechat, text)

    @Test fun `outgoing transfer keeps payee receipt status distinct from own money direction`() {
        listOf("待对方收款", "对方已收款", "对方已收钱", "等待对方确认收钱").forEach { status ->
            val result = scene("已转账\n￥18.00\n转账给小明\n$status\n完成")
            assertEquals(status, "EXPENSE", result.parsed?.direction)
            assertEquals("小明", result.parsed?.merchant)
            assertEquals("transfer_sent", result.scene)
            assertEquals("unknown", result.parsed?.businessKind)
        }
    }

    @Test fun `received transfer is captured only after own confirmation`() {
        val result = scene("微信转账\n已收钱\n￥18.00\n付款方\n小明\n完成")
        assertEquals(1800L, result.parsed?.amountCents)
        assertEquals("INCOME", result.parsed?.direction)
        assertEquals("小明", result.parsed?.merchant)
        assertEquals("transfer_received", result.scene)
        listOf("待确认收款", "请收钱", "确认收款", "对方已收钱", "红包已领取").forEach { status ->
            val candidate = scene("微信转账\n$status\n￥18.00\n完成")
            assertNull(status, candidate.parsed)
            assertFalse(status, candidate.modelCandidate)
        }
    }

    @Test fun `outgoing red packet and explicit own red packet receipt are distinct`() {
        val sent = scene("微信红包\n红包发送成功\n￥20.00\n完成")
        assertEquals("EXPENSE", sent.parsed?.direction)
        assertEquals("red_packet_sent", sent.scene)
        val received = scene("微信红包\n红包已存入零钱\n￥20.00\n完成")
        assertEquals("INCOME", received.parsed?.direction)
        assertEquals("red_packet_received", received.scene)
        assertNull(scene("微信红包\n待领取\n￥20.00\n完成").parsed)
    }

    @Test fun `refund arrival is separate from refund application and original amount`() {
        val receipt = scene("退款已到账\n退款金额 5.00元\n原支付金额 20.00元\n完成")
        assertEquals(500L, receipt.parsed?.amountCents)
        assertEquals("refund_received", receipt.scene)
        assertEquals("refund", receipt.parsed?.businessKind)
        listOf("退款申请中", "退款审核中", "退款处理中", "退款失败").forEach { status ->
            assertNull(scene("$status\n退款金额 5.00元\n完成").parsed)
            assertFalse(scene("$status\n退款金额 5.00元\n完成").modelCandidate)
        }
    }

    @Test fun `opposite successful directions are a conflict without an explicit sender status scope`() {
        assertNull(scene("支付成功\n收款成功\n￥18.00\n完成").parsed)
    }

    @Test fun `unknown readonly payment completion is a model candidate without becoming a rule receipt`() {
        val result = scene("本次付款已成功\n￥18.00\n收款方\n小明\n完成")
        assertNull(result.parsed)
        assertTrue(result.modelCandidate)
        assertTrue(result.visualCandidate)
    }

    @Test fun `completed result with image-only amount can use separately authorized visual recovery`() {
        val result = scene("转账成功\n转账给小明\n完成")
        assertNull(result.parsed)
        assertFalse(result.modelCandidate)
        assertTrue(result.visualCandidate)
    }

    @Test fun `history chat future failed and conflicting money are never model evidence`() {
        listOf("账单详情\n", "聊天记录\n", "明天准备\n", "退款申请中\n", "验证码\n").forEach { prefix ->
            assertFalse(prefix, scene(prefix + "本次付款成功\n￥18.00\n完成").modelCandidate)
        }
        assertFalse(scene("本次付款成功\n￥18.00\n￥28.00\n完成").modelCandidate)
        assertFalse(scene("我刚才付款成功\n￥18.00").modelCandidate)
        assertFalse(PaymentTextParser.isScreenModelCandidate("untrusted.app", "本次付款成功\n￥18.00\n完成"))
    }

    @Test fun `wechat historical transfer collection and red packet details are not new payments`() {
        listOf("转账详情", "收款详情", "红包详情", "红包记录").forEach { title ->
            val result = scene("$title\n已转账\n￥18.00\n完成")
            assertNull(title, result.parsed)
            assertFalse(title, result.modelCandidate)
            assertFalse(title, result.visualCandidate)
        }
        assertEquals(1800L, scene("转账成功\n￥18.00\n查看转账详情\n完成").parsed?.amountCents)
    }

    @Test fun `structural rows explain amount-first nodes without accepting unrelated root amounts`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null),
            PaymentScreenNode(2, 1, className = "android.widget.LinearLayout"),
            PaymentScreenNode(3, 2, text = "18.00"),
            PaymentScreenNode(4, 2, text = "实付金额"),
            PaymentScreenNode(5, 1, text = "支付成功")
        ))
        assertEquals(listOf("实付金额：18.00元"), structure.labelledAmounts())
        val result = PaymentTextParser.analyzeScreen(wechat, "支付成功\n18.00\n实付金额\n完成", structure)
        assertEquals(1800L, result.parsed?.amountCents)
        assertEquals("expense", result.parsed?.businessKind)
        assertNotNull(result.parsed?.ruleVersion)
        val ambiguous = PaymentScreenStructure(structure.nodes + PaymentScreenNode(6, 2, text = "28.00"))
        assertTrue(ambiguous.labelledAmounts().isEmpty())
    }

    @Test fun `structural additions cannot erase conflicting or failed original text`() {
        val structure = PaymentScreenStructure(listOf(PaymentScreenNode(1, null),
            PaymentScreenNode(2, 1, text = "实付"), PaymentScreenNode(3, 1, text = "18.00")))
        assertNull(PaymentTextParser.analyzeScreen(wechat, "支付成功\n实付\n18.00\n支付失败", structure).parsed)
        assertNull(PaymentTextParser.analyzeScreen(wechat, "支付成功\n实付\n18.00\n另付28.00元", structure).parsed)
    }

    @Test fun `alipay and meituan amount-first payment rows retain all explicit expense labels`() {
        val packages = listOf("com.eg.android.AlipayGphone", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew")
        val labels = listOf("支付金额", "消费金额", "交易金额", "扣款金额", "实付款", "实扣金额", "实扣")
        for (packageName in packages) for (label in labels) {
            val structure = amountFirstRow(label)
            val text = "支付成功\n18.50\n$label\n完成"
            assertNull("plain text must not guess the bare amount", PaymentTextParser.parseScreen(packageName, text))
            val result = PaymentTextParser.analyzeScreen(packageName, text, structure).parsed
            assertEquals("$packageName $label", 1850L, result?.amountCents)
            assertEquals("EXPENSE", result?.direction)
            assertEquals("expense", result?.businessKind)
        }
    }

    @Test fun `amount-first structural payment labels preserve failure history and amount boundaries`() {
        val packageName = "com.sankuai.meituan"
        val structure = amountFirstRow("支付金额")
        val text = "支付成功\n18.50\n支付金额\n完成"
        for (suffix in listOf("支付失败", "待支付", "订单详情", "另付28.00元")) {
            assertNull(suffix, PaymentTextParser.analyzeScreen(packageName, "$text\n$suffix", structure).parsed)
        }
        val differentParent = PaymentScreenStructure(structure.nodes.map {
            if (it.id == 4) it.copy(parentId = 1) else it
        })
        val ambiguous = PaymentScreenStructure(structure.nodes + PaymentScreenNode(6, 2, text = "28.00"))
        val hiddenAmount = PaymentScreenStructure(structure.nodes.map {
            if (it.id == 3) it.copy(visible = false) else it
        })
        for (invalid in listOf(differentParent, ambiguous, hiddenAmount)) {
            assertTrue(invalid.labelledAmounts().isEmpty())
            assertNull(PaymentTextParser.analyzeScreen(packageName, text, invalid).parsed)
        }
        for (label in listOf("应付金额", "订单金额", "原价", "优惠金额", "账户余额")) {
            val ignored = amountFirstRow(label)
            assertTrue(label, ignored.labelledAmounts().isEmpty())
            assertNull(label, PaymentTextParser.analyzeScreen(packageName,
                "支付成功\n18.50\n$label\n完成", ignored).parsed)
        }
    }

    private fun amountFirstRow(label: String) = PaymentScreenStructure(listOf(
        PaymentScreenNode(1, null),
        PaymentScreenNode(2, 1, className = "android.widget.LinearLayout"),
        PaymentScreenNode(3, 2, text = "18.50"),
        PaymentScreenNode(4, 2, text = label),
        PaymentScreenNode(5, 1, text = "支付成功")
    ))
}

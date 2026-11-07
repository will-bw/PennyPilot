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

    @Test fun `account movement is a receipt action and not a keyword in a shop or payment account`() {
        val payment = scene("支付成功\n￥18.00\n商户：便民充值店\n付款方式：零钱通\n完成")
        assertEquals("merchant_payment", payment.parsed?.scene)
        val transfer = scene("转账成功\n￥18.00\n转账给小明\n转账备注：还款\n完成")
        assertEquals("transfer_sent", transfer.parsed?.scene)
        val ownAccount = scene("转账成功\n￥18.00\n交易类型：本人账户互转\n完成")
        assertEquals("account_transfer", ownAccount.parsed?.scene)
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

    @Test fun `completed field amount and order are bound in distinct reversed rows`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null),
            PaymentScreenNode(2, 1), PaymentScreenNode(3, 2, text = "成功"), PaymentScreenNode(4, 2, text = "支付状态："),
            PaymentScreenNode(5, 1), PaymentScreenNode(6, 5, text = "1,234.50"), PaymentScreenNode(7, 5, text = "实付金额"),
            PaymentScreenNode(8, 1), PaymentScreenNode(9, 8, text = "DEMO202611070001"), PaymentScreenNode(10, 8, text = "交易单号")
        ))
        val text = "成功\n支付状态：\n1,234.50\n实付金额\nDEMO202611070001\n交易单号\n完成"
        val receipt = PaymentTextParser.analyzeScreen("com.eg.android.AlipayGphone", text, structure).parsed!!
        assertEquals(123450L, receipt.amountCents)
        assertEquals("DEMO202611070001", receipt.orderId)
        assertTrue(receipt.fieldEvidence.getValue("paymentStatus").contains("支付状态：成功"))
        val failed = PaymentScreenStructure(structure.nodes + listOf(PaymentScreenNode(11, 1),
            PaymentScreenNode(12, 11, text = "关闭"), PaymentScreenNode(13, 11, text = "交易状态")))
        assertNull(PaymentTextParser.analyzeScreen("com.eg.android.AlipayGphone", "$text\n关闭\n交易状态", failed).parsed)
        assertNull(PaymentTextParser.analyzeScreen("com.eg.android.AlipayGphone", "$text\n交易单号：DIFF202611070001", structure).parsed)
        val orderless = PaymentTextParser.analyzeScreen("com.eg.android.AlipayGphone",
            "支付状态：成功\n实付金额：18.50元\n完成")
        assertNull(orderless.parsed)
        assertFalse(orderless.modelCandidate)
    }

    @Test fun `amount row shared with an order total is not promoted to actual payment`() {
        val structure = PaymentScreenStructure(amountFirstRow("实付金额").nodes + PaymentScreenNode(6, 2, text = "应付金额"))
        assertTrue(structure.labelledAmounts().isEmpty())
        assertNull(PaymentTextParser.analyzeScreen(wechat, "支付成功\n18.50\n实付金额\n应付金额\n完成", structure).parsed)
    }

    @Test fun `flat WebView fields bind by row despite reversed traversal order`() {
        val (text, structure) = flatWebReceipt()
        val alipay = "com.eg.android.AlipayGphone"
        assertNull(PaymentTextParser.analyzeScreen(alipay, text).parsed)
        val receipt = PaymentTextParser.analyzeScreen(alipay, text, structure).parsed!!
        assertEquals(1800L, receipt.amountCents)
        assertEquals("DEMO202611070031", receipt.orderId)
        assertEquals("样例商家", receipt.details.merchantName)
        assertEquals("午餐套餐", receipt.details.productDescription)
        assertEquals("零钱", receipt.details.fundingAccount)
        assertNull(PaymentTextParser.analyzeScreen(alipay, "$text\n支付失败", structure).parsed)
    }

    @Test fun `two flat WebView rows still bind amount and merchant separately`() {
        val (_, full) = flatWebReceipt()
        val fields = setOf("18.00", "实付金额", "样例商家", "商户名称")
        val nodes = full.nodes.filter { it.id <= 2 || it.text in fields }
        val text = (listOf("支付成功") + nodes.mapNotNull { it.text } +
            listOf("交易单号：DEMO202611070031", "完成")).joinToString("\n")
        val alipay = "com.eg.android.AlipayGphone"
        val receipt = PaymentTextParser.analyzeScreen(alipay, text, PaymentScreenStructure(nodes)).parsed!!
        assertEquals(1800L, receipt.amountCents)
        assertEquals("样例商家", receipt.details.merchantName)

        val native = nodes.map { if (it.id == 2) it.copy(className = "android.widget.LinearLayout") else it }
        assertNull(PaymentTextParser.analyzeScreen(alipay, text, PaymentScreenStructure(native)).parsed)
        val ambiguous = nodes.map { node ->
            if (node.text !in setOf("样例商家", "商户名称")) node else node.copy(bounds =
                listOf(node.bounds[0], 192, node.bounds[2], 220))
        }
        assertNull(PaymentTextParser.analyzeScreen(alipay, text, PaymentScreenStructure(ambiguous)).parsed)
    }

    @Test fun `flat field fallback needs one value in the same WebView row`() {
        val (text, structure) = flatWebReceipt()
        val alipay = "com.eg.android.AlipayGphone"
        val ambiguous = PaymentScreenStructure(structure.nodes +
            PaymentScreenNode(30, 2, text = "28.00", bounds = listOf(650, 192, 750, 220)))
        assertNull(PaymentTextParser.analyzeScreen(alipay, "$text\n28.00", ambiguous).parsed)
        val missing = PaymentScreenStructure(structure.nodes.map {
            if (it.text == "18.00") it.copy(parentId = 1) else it
        })
        assertNull(PaymentTextParser.analyzeScreen(alipay, text, missing).parsed)
        val native = PaymentScreenStructure(structure.nodes.map {
            if (it.id == 2) it.copy(className = "android.widget.LinearLayout") else it
        })
        assertNull(PaymentTextParser.analyzeScreen(alipay, text, native).parsed)
    }

    private fun flatWebReceipt(): Pair<String, PaymentScreenStructure> {
        val rows = listOf("支付状态" to "成功", "实付金额" to "18.00", "交易单号" to "DEMO202611070031",
            "商户名称" to "样例商家", "商品说明" to "午餐套餐", "付款方式" to "零钱")
        val nodes = listOf(PaymentScreenNode(1, null), PaymentScreenNode(2, 1, className = "android.webkit.WebView")) +
            rows.flatMapIndexed { index, (label, value) ->
                val top = 140 + index * 52
                val id = 3 + index * 2
                listOf(PaymentScreenNode(id, 2, text = value, bounds = listOf(400, top + 2, 620, top + 28)),
                    PaymentScreenNode(id + 1, 2, text = label, bounds = listOf(48, top, 200, top + 28)))
            }
        return nodes.mapNotNull { it.text }.plus("完成").joinToString("\n") to PaymentScreenStructure(nodes)
    }

    @Test fun `verified receipt can ignore a separate coupon button without relaxing other gates`() {
        val structure = PaymentScreenStructure(listOf(PaymentScreenNode(1, null),
            PaymentScreenNode(2, 1, text = "点击领取优惠券", className = "android.widget.Button")))
        val text = "支付成功\n实付金额：18.50元\n交易单号：DEMO202611070002\n点击领取优惠券"
        assertEquals(1850L, PaymentTextParser.analyzeScreen(wechat, text, structure).parsed?.amountCents)
        assertNull(PaymentTextParser.analyzeScreen(wechat, text).parsed)
        assertNull(PaymentTextParser.analyzeScreen(wechat, "$text\n支付失败", structure).parsed)
        assertNull(PaymentTextParser.analyzeScreen(wechat, "$text\n另付28.00元", structure).parsed)
        val orderless = PaymentTextParser.analyzeScreen(wechat, text.replace("交易单号：DEMO202611070002\n", ""), structure)
        assertNull(orderless.parsed)
        assertFalse(orderless.modelCandidate)
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

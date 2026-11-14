package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WalletReceiptDetailsParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 24, 17, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun parse(text: String, pkg: String = "com.eg.android.AlipayGphone", at: Long = now) =
        WalletReceiptDetailsParser.parse(pkg, text, at, zone = zone)
    private val income = "账单详情\n二维码收款\n交易成功\n+18.00\n收支类型\n收入\n交易时间\n2026-10-24 16:58:30\n交易单号20261024000012345678\n付款方：小明"

    @Test fun `recent identified alipay QR receipt preserves signed raw amount and actual transaction time`() {
        val result = parse(income)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("INCOME", result.direction)
        assertEquals("qr_payment_received", result.scene)
        assertEquals("unknown", result.businessKind)
        assertEquals("小明", result.merchant)
        assertEquals(now - 90_000, result.occurredAt)
        assertTrue(result.fieldEvidence["amount"]!!.endsWith("+18.00"))
        assertNull(PaymentTextParser.parseScreen("com.eg.android.AlipayGphone", income))
        assertEquals("INCOME",parse(income.replace("+18.00","18.00"))!!.direction)
        assertNull(parse(income+"\n资金方向\n支出"))
    }

    @Test fun `identified alipay signed receipt supplies direction but rejects explicit conflicts`() {
        val text = income.replace("二维码收款", "商品购买").replace("+18.00", "-18.00")
            .replace("收入", "支出").replace("付款方：小明", "收款方：便利店")
        assertEquals("EXPENSE", parse(text)!!.direction)
        assertEquals("expense", parse(text)!!.businessKind)
        assertNull(parse(text.replace("支出", "收入")))
        assertNull(parse(text.replace("-18.00", "+18.00")))
        assertEquals("EXPENSE", parse(text.replace("收支类型\n支出", ""))!!.direction)
        assertNull(parse(text.replace("-18.00", "-1,80.00")))
    }

    @Test fun `refund details require their own identity and settlement timestamp not original payment time`() {
        val text = "退款详情\n退款已到账\n退款金额5元\n原支付金额30元\n退款到账时间：2026-10-24 16:58:30\n退款单号RF202610240123\n交易单号PAY202610240123"
        for (pkg in listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay")) {
            val result = parse(text, pkg)!!
            assertEquals(500L, result.amountCents)
            assertEquals("refund", result.businessKind)
            assertEquals("RF202610240123", result.orderId)
            assertEquals("refund", result.orderIdKind)
            assertNull(parse(text.replace("退款到账时间", "支付时间"), pkg))
            assertNull(parse(text.replace("退款单号RF202610240123", ""), pkg))
            assertNull(parse(text.replace("退款已到账", "退款已受理"), pkg))
        }
    }

    @Test fun `historical receipt preserves time while future missing ambiguous or malformed time is excluded`() {
        assertEquals(now - 90_000, parse(income, at = now + 31 * 60_000)!!.occurredAt)
        assertNull(parse(income.replace("16:58:30", "17:01:00")))
        assertNull(parse(income.replace("2026-10-24 16:58:30", "2026-02-30 16:58:30")))
        assertNull(parse(income.replace("2026-10-24 16:58:30", "今天16:58")))
        assertNull(parse(income + "\n收款时间2026-10-24 16:59:00"))
        assertNull(parse(income.replace("交易时间", "创建时间")))
        assertNull(parse(income.replace("交易时间\n2026-10-24 16:58:30", "备注：交易时间2026-10-24 16:58:30")))
        assertNull(parse(income.replace("交易单号20261024000012345678", "")))
    }

    @Test fun `user selected older receipt retains verified date without assuming a wallet`() {
        val later = now + 2 * 60 * 60_000L
        assertEquals(now - 90_000, parse(income, at = later)!!.occurredAt)
        val selected = WalletReceiptDetailsParser.parseSelectedReceipt(income, later, zone)!!
        assertEquals(1800L, selected.amountCents)
        assertEquals(now - 90_000, selected.occurredAt)
        assertNull(selected.details.paymentChannel)
        assertTrue(selected.fieldEvidence.getValue("occurredAt").startsWith("图片凭证"))
        assertNull(WalletReceiptDetailsParser.parseSelectedReceipt(income + "\n交易失败", later, zone))
        assertNull(WalletReceiptDetailsParser.parseSelectedReceipt(income, now - 120_000L, zone))
    }

    @Test fun `timestamps and IDs never override chat input failure or another history list`() {
        for (extra in listOf("聊天记录", "订单列表", "支付失败", "收款失败", "退款申请中", "验证码", "收款金额28元"))
            assertNull(extra, parse(income + "\n" + extra))
        assertNull(parse(income, "com.example.chat"))
    }

    @Test fun `unionpay details require real transaction time order and completion`() {
        val text = income.replace("账单详情", "交易详情").replace("二维码收款", "商品购买")
            .replace("+18.00", "-1,234.56").replace("收入", "支出").replace("付款方：小明", "收款方：测试商户")
        val result = parse(text, "com.unionpay")!!
        assertEquals(123456L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("云闪付", result.paymentMethod)
        assertEquals(now - 90_000, result.occurredAt)
        assertEquals("20261024000012345678", result.orderId)
        assertEquals(now - 90_000, parse(text, "com.unionpay", now + 31 * 60_000)!!.occurredAt)
        assertNull(parse(text.replace("交易时间", "创建时间"), "com.unionpay"))
        assertNull(parse(text.replace("交易成功", "退款受理"), "com.unionpay"))
        assertNull(parse(text.replace("交易单号20261024000012345678", ""), "com.unionpay"))
        assertNull(parse(text + "\n交易状态：处理中", "com.unionpay"))
        assertNull(parse(text + "\n已全额退款", "com.unionpay"))
    }

    @Test fun `receipt remarks cannot change settlement type identity or timestamp`() {
        val withRemark = income + "\n备注：退款成功，退款金额88元，退款单号FAKE202611060001"
        val result = parse(withRemark)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("20261024000012345678", result.orderId)
        assertNull(result.orderIdKind)
        assertEquals(now - 90_000, result.occurredAt)
        assertNull(parse(income.replace("交易时间\n2026-10-24 16:58:30", "提示，备注：交易时间2026-10-24 16:58:30")))
    }

    @Test fun `validated details page restores descriptions after financial validation`() {
        val text = income.replace("二维码收款", "商品购买").replace("+18.00", "-18.00")
            .replace("收入", "支出").replace("付款方：小明", "收款方：样例商家") +
            "\n商品说明：午餐套餐\n备注：退款成功99元\n付款方式：招商银行储蓄卡(1234)"
        val result = parse(text)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("20261024000012345678", result.orderId)
        assertEquals(now - 90_000, result.occurredAt)
        assertEquals("午餐套餐", result.details.productDescription)
        assertEquals("退款成功99元", result.details.remark)
        assertEquals("样例商家", result.details.payeeName)
        assertEquals("招商银行储蓄卡(1234)", result.details.fundingAccount)
        assertEquals("支付宝", result.details.paymentChannel)
        assertNull(parse(text.replace("交易成功", "支付失败")))
    }

    @Test fun `wallet detail recognizes labelled completion and signed amount without dropping conflicting facts`() {
        val receipt = income.replace("交易成功", "交易状态：成功").replace("+18.00", "交易金额：+18.00")
        val parsed = parse(receipt)!!
        assertEquals(1800L, parsed.amountCents)
        assertEquals("INCOME", parsed.direction)
        assertEquals("20261024000012345678", parsed.orderId)
        assertTrue(parsed.fieldEvidence.getValue("paymentStatus").endsWith("交易状态：成功"))
        assertTrue(parsed.fieldEvidence.getValue("amount").endsWith("交易金额：+18.00"))
        assertNull(PaymentTextParser.parseScreen("com.eg.android.AlipayGphone", receipt))
        for (badStatus in listOf("未完成", "已关闭", "待支付", "退款受理")) {
            assertNull(badStatus, parse(receipt.replace("交易状态：成功", "交易状态：$badStatus")))
            assertNull(badStatus, parse(receipt + "\n交易状态：$badStatus"))
        }
        assertNull(parse(receipt.replace("交易金额：+18.00", "交易金额：-18.00")))
        assertNull(parse(receipt + "\n付款金额：18.00元"))
        assertNull(parse(receipt + "\n交易金额：28.00元"))
        assertNull(parse(receipt + "\n交易单号：20261024000012345679"))
    }

    @Test fun `recent wallet detail uses same-row value before label for time direction status and order`() {
        val structureFields = listOf("成功" to "交易状态", "-18.00" to "交易金额", "支出" to "资金方向",
            "2026-10-24\n16:58:30" to "交易时间", "20261024000012345678" to "交易单号")
        val nodes = listOf(PaymentScreenNode(1, null), PaymentScreenNode(2, 1, text = "账单详情")) +
            structureFields.flatMapIndexed { index, (value, label) ->
                val id = 3 + index * 3
                listOf(PaymentScreenNode(id, 1), PaymentScreenNode(id + 1, id, text = value),
                    PaymentScreenNode(id + 2, id, text = label))
            }
        val structure = PaymentScreenStructure(nodes)
        val receipt = nodes.mapNotNull(PaymentScreenNode::text).joinToString("\n")
        assertNull(parse(receipt))
        val parsed = WalletReceiptDetailsParser.parse("com.eg.android.AlipayGphone", receipt, now, structure, zone)!!
        assertEquals("EXPENSE", parsed.direction)
        assertEquals(1800L, parsed.amountCents)
        assertEquals("20261024000012345678", parsed.orderId)
        assertEquals(now - 90_000L, parsed.occurredAt)
        for (extra in listOf("资金方向：收入", "交易时间：2026-10-24 16:59:00", "交易状态：待支付", "账单列表")) {
            assertNull(extra, WalletReceiptDetailsParser.parse("com.eg.android.AlipayGphone", "$receipt\n$extra", now, structure, zone))
        }
        assertEquals(now - 90_000, WalletReceiptDetailsParser.parse("com.eg.android.AlipayGphone", receipt, now + 31 * 60_000, structure, zone)!!.occurredAt)
    }

    @Test fun `refund detail only uses completed refund status time and actual refund amount`() {
        val receipt = "退款详情\n退款状态：成功\n退款金额：5.00元\n原支付金额：30.00元" +
            "\n退款到账时间：2026-10-24 16:58:30\n退款单号：RF202610240123"
        val parsed = parse(receipt)!!
        assertEquals("refund", parsed.businessKind)
        assertEquals(500L, parsed.amountCents)
        assertEquals("refund", parsed.orderIdKind)
        assertNull(parse(receipt.replace("退款状态：成功", "退款状态：已受理")))
        assertNull(parse(receipt.replace("退款到账时间", "交易时间")))
        assertNull(parse(receipt.replace("退款金额：5.00元", "付款金额：30.00元")))
    }
    @Test fun `refund headline sign must agree with incoming settlement direction`() {
        val text = "退款详情\n退款成功\n+5.00\n退款到账时间：2026-10-24 16:58:30\n退款单号：RF202610240123"
        assertEquals("INCOME", parse(text)!!.direction)
        assertEquals(500L, parse(text)!!.amountCents)
        for (negative in listOf("-5.00", "−5.00")) {
            assertNull(parse(text.replace("+5.00", negative)))
            assertNull(WalletReceiptDetailsParser.parseForReview("com.tencent.mm", text.replace("+5.00", negative), now, zone = zone))
        }
    }

}

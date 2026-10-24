package com.pennypilot.app.data.parser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic semantic fixtures, deliberately separate from device/version acceptance. */
class WalletSceneMatrixTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private fun screen(text: String, pkg: String = wechat) = PaymentTextParser.analyzeScreen(pkg, text)

    @Test fun `red packet send receive and returned funds are three distinct scenes`() {
        val cases = listOf(
            Triple("红包发送成功\n红包金额3.00元\n完成", "EXPENSE", "red_packet_sent"),
            Triple("微信红包\n红包已发出\n￥3.00\n完成", "EXPENSE", "red_packet_sent"),
            Triple("微信红包\n已领取红包\n本人领取金额3.00元\n红包总额100.00元", "INCOME", "red_packet_received"),
            Triple("微信红包\n已存入零钱\n3.00\n元\n完成", "INCOME", "red_packet_received"),
            Triple("红包已退还\n退还金额3.00元\n完成", "INCOME", "red_packet_returned"),
            Triple("红包退回成功\n红包金额3.00元\n完成", "INCOME", "red_packet_returned")
        )
        for ((text, direction, scene) in cases) {
            val result = screen(text).parsed
            assertEquals(text, 300L, result?.amountCents)
            assertEquals(text, direction, result?.direction)
            assertEquals(text, scene, result?.scene)
            assertEquals(text, "unknown", result?.businessKind)
        }
    }

    @Test fun `unopened expired others claimed and unsent red packets cannot become received income`() {
        for (text in listOf(
            "微信红包\n待领取\n￥3.00\n开", "微信红包\n红包已过期\n￥3.00",
            "微信红包\n对方已领取红包\n￥3.00\n完成", "微信红包\n红包已被小明领取\n￥3.00",
            "发红包\n红包金额3.00元\n塞钱进红包", "微信红包\n已领取红包\n本人领取金额3元\n红包已过期"
        )) {
            assertNull(text, screen(text).parsed)
            assertFalse(text, screen(text).modelCandidate)
        }
    }

    @Test fun `wallet refunds prefer this installment over original payment and cumulative total`() {
        for (pkg in listOf(wechat, alipay)) {
            for (status in listOf("退款成功", "退款已到账", "退款完成")) {
                val result = screen("$status\n本次退款金额5.00元\n累计退款金额12.00元\n原支付金额30.00元\n完成", pkg).parsed
                assertEquals("$pkg $status", 500L, result?.amountCents)
                assertEquals("INCOME", result?.direction)
                assertEquals("refund", result?.businessKind)
            }
        }
    }

    @Test fun `accepted or expected refunds are not settled receipts even when another clause says success`() {
        for (pkg in listOf(wechat, alipay)) {
            for (status in listOf("退款申请已提交", "退款已受理", "退款处理中", "退款申请中", "退款审核中",
                "退款成功，预计1-3个工作日到账", "退款成功，将于明天到账", "退款失败")) {
                val result = screen("$status\n退款金额5.00元\n原支付金额30.00元\n完成", pkg)
                assertNull(status, result.parsed)
                assertFalse(status, result.modelCandidate)
            }
        }
    }

    @Test fun `refund identities never reuse original payment IDs and keep partial installments separate`() {
        val first = screen("退款已到账\n退款金额5元\n交易单号PAY202610240001\n退款单号RF202610240001\n完成").parsed!!
        val second = screen("退款已到账\n退款金额5元\n交易单号PAY202610240001\n退款单号RF202610240002\n完成").parsed!!
        assertEquals("RF202610240001", first.orderId)
        assertEquals("RF202610240002", second.orderId)
        assertEquals("refund", first.orderIdKind)
        assertTrue(first.fieldEvidence["originalOrderId"]!!.contains("PAY202610240001"))
        val merchant = screen("退款成功\n退款金额5元\n商户退款单号MR202610240001\n完成").parsed!!
        assertEquals("merchant_refund", merchant.orderIdKind)
        assertNull(screen("退款成功\n退款金额5元\n退款单号RF202610240001\n退款单号RF202610240002\n完成").parsed)
        assertNull(screen("退款成功\n退款金额5元\n交易单号PAY202610240001\n完成").parsed!!.orderId)
    }

    @Test fun `neutral alipay trade success requires an independently supported funds direction`() {
        val incoming = screen("交易成功\n收款金额18.00元\n收款方：本人\n付款方：小明\n完成", alipay).parsed!!
        assertEquals("INCOME", incoming.direction)
        assertEquals("小明", incoming.merchant)
        val outgoing = screen("交易成功\n实付金额18.00元\n收款方：便利店\n完成", alipay).parsed!!
        assertEquals("EXPENSE", outgoing.direction)
        assertNull(screen("交易成功\n￥18.00\n完成", alipay).parsed)
        assertNull(screen("支付宝\n交易成功\n￥18.00\n完成", alipay).parsed)
        assertNull(screen("微信支付\n交易成功\n￥18.00\n完成", wechat).parsed)
        assertNull(screen("支付成功\n收款金额18元\n完成", alipay).parsed)
    }

    @Test fun `QR receipts use the current receipt and never daily income or code setup values`() = runTest {
        for ((pkg, title) in listOf(wechat to "微信收款助手", alipay to "收钱码")) {
            val text = "二维码收款\n收款成功\n收款金额18.00元\n今日收款100.00元\n累计收款金额500.00元\n完成"
            val result = screen(text, pkg).parsed!!
            assertEquals(1800L, result.amountCents)
            assertEquals("INCOME", result.direction)
            assertEquals("qr_payment_received", result.scene)
            assertEquals("unknown", result.businessKind)
            val notice = NotificationParser.parse(pkg, title, "二维码收款成功，收款金额18元，今日收款100元", emptyList(), false) { error("no cloud") }!!
            assertEquals(1800L, notice.amountCents)
            assertEquals("qr_payment_received", notice.scene)
            assertNull(screen("收款码\n设置金额18元\n保存收款码", pkg).parsed)
            assertNull(screen("收款码\n今日收款100元", pkg).parsed)
        }
    }

    @Test fun `wallet notifications retain completed payment income and refund evidence locally`() = runTest {
        val cases = listOf(
            Triple("支付成功，实付18元，商户：便利店", "EXPENSE", "merchant_payment"),
            Triple("成功收款，收款金额18元，付款方：小明", "INCOME", "payment_received"),
            Triple("收到小明的转账18元", "INCOME", "transfer_received"),
            Triple("退款成功，退款金额18元，退款单号RF202610240009", "INCOME", "refund_received")
        )
        for ((pkg, title) in listOf(wechat to "微信支付", alipay to "支付宝")) {
            for ((text, direction, scene) in cases) {
                val result = NotificationParser.parse(pkg, title, text, emptyList(), true) { error("no cloud") }!!
                assertEquals(text, 1800L, result.amountCents)
                assertEquals(text, direction, result.direction)
                assertEquals(text, scene, result.scene)
                assertTrue(text, result.fieldEvidence.values.all { it.startsWith("通知") })
            }
            assertNull(NotificationParser.parse(pkg, title, "对方已收款18元", emptyList(), true) { error("unsafe") })
            assertNull(NotificationParser.parse(pkg, title, "收款失败，收款金额18元", emptyList(), true) { error("unsafe") })
        }
    }

    @Test fun `red packet discounts are merchant consumption rather than a sent social red packet`() {
        val paid=screen("支付成功\n实际支付18元\n红包抵扣2元\n完成").parsed!!
        assertEquals("merchant_payment",paid.scene)
        assertEquals("expense",paid.businessKind)
        val refund=screen("退款成功\n退款金额5元\n红包抵扣2元\n完成").parsed!!
        assertEquals("refund_received",refund.scene)
        assertEquals("refund",refund.businessKind)
    }

    @Test fun `free form remarks cannot supply refund status or missing amounts and identities`() = runTest {
        val payment=screen("支付成功\n实付18元\n备注：退款成功").parsed!!
        assertEquals("EXPENSE",payment.direction)
        assertEquals("merchant_payment",payment.scene)
        assertNull(screen("支付成功\n备注：实付18元\n完成").parsed)
        assertNull(screen("支付成功\n备注\n实付18元\n完成").parsed)
        val refund=screen("退款成功\n退款金额5元\n备注：退款单号RF202610240099\n完成").parsed!!
        assertNull(refund.orderId)
        assertNull(NotificationParser.parse(wechat,"微信支付","备注：收款成功18元",emptyList(),false) { error("no cloud") })
    }
}

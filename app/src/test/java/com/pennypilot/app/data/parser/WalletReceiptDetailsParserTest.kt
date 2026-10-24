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

    @Test fun `alipay outgoing signed receipt needs independent expense direction`() {
        val text = income.replace("二维码收款", "商品购买").replace("+18.00", "-18.00")
            .replace("收入", "支出").replace("付款方：小明", "收款方：便利店")
        assertEquals("EXPENSE", parse(text)!!.direction)
        assertEquals("expense", parse(text)!!.businessKind)
        assertNull(parse(text.replace("支出", "收入")))
        assertNull(parse(text.replace("-18.00", "+18.00")))
        assertNull(parse(text.replace("收支类型\n支出", "")))
        assertNull(parse(text.replace("-18.00", "-1,80.00")))
    }

    @Test fun `refund details require their own identity and settlement timestamp not original payment time`() {
        val text = "退款详情\n退款已到账\n退款金额5元\n原支付金额30元\n退款到账时间：2026-10-24 16:58:30\n退款单号RF202610240123\n交易单号PAY202610240123"
        for (pkg in listOf("com.tencent.mm", "com.eg.android.AlipayGphone")) {
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

    @Test fun `historical future missing ambiguous or malformed receipt time remains excluded`() {
        assertNull(parse(income, at = now + 31 * 60_000))
        assertNull(parse(income.replace("16:58:30", "17:01:00")))
        assertNull(parse(income.replace("2026-10-24 16:58:30", "2026-02-30 16:58:30")))
        assertNull(parse(income.replace("2026-10-24 16:58:30", "今天16:58")))
        assertNull(parse(income + "\n收款时间2026-10-24 16:59:00"))
        assertNull(parse(income.replace("交易时间", "创建时间")))
        assertNull(parse(income.replace("交易时间\n2026-10-24 16:58:30", "备注：交易时间2026-10-24 16:58:30")))
        assertNull(parse(income.replace("交易单号20261024000012345678", "")))
    }

    @Test fun `timestamps and IDs never override chat input failure or another history list`() {
        for (extra in listOf("聊天记录", "订单列表", "支付失败", "收款失败", "退款申请中", "验证码", "收款金额28元"))
            assertNull(extra, parse(income + "\n" + extra))
        assertNull(parse(income, "com.example.chat"))
    }
}

package com.pennypilot.app.service

import android.app.Application
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WechatOpaqueMerchantReceiptReaderTest {
    private val order = "SYNTHETICORDER1800"
    private val shop = "松林饭馆"

    private fun line(text: String, top: Int) = PaymentImageReader.OcrLine(text, Rect(260, top, 680, top + 45))

    // Synthetic receipt shape, independent of a particular released WeChat layout.
    private fun receipt() = listOf(line("支付成功", 180), line("实付金额：￥18.00", 430),
        line("商户：$shop", 580), line("商户订单号：$order", 750), line("付款方式：零钱", 830),
        line("商品说明：午餐套餐", 920), line("完成", 1680))

    private fun parse(lines: List<PaymentImageReader.OcrLine> = receipt()) =
        WechatOpaqueMerchantReceiptReader.parse(lines, 1000, 2000)

    @Test fun `merchant receipt candidate keeps paid amount same role identity and descriptive facts`() {
        val candidate = parse()!!
        assertEquals(order, candidate.merchantOrderId)
        assertEquals(shop, candidate.merchant)
        assertEquals(1800L, candidate.receipt.amountCents)
        assertEquals("EXPENSE", candidate.receipt.direction)
        assertEquals("merchant_payment", candidate.receipt.scene)
        assertEquals("午餐套餐", candidate.receipt.details.productDescription)
        assertEquals("零钱", candidate.receipt.details.fundingAccount)
        assertTrue(candidate.receipt.fieldEvidence.getValue("paymentStatus").startsWith("本地OCR"))
    }

    @Test fun `candidate rejects wrong surface ambiguous facts and missing merchant order proof`() {
        for (extra in listOf("截图预览", "历史订单", "请输入密码", "待付款", "支付失败", "收款成功",
                "实付金额：￥28.00", "商户：另一家餐馆", "商家订单号：SYNTHETICORDER2800")) {
            assertNull(extra, parse(receipt() + line(extra, 1100)))
        }
        assertNull(parse(receipt().map { if (it.text.startsWith("商户订单号")) it.copy(text = "交易单号：$order") else it }))
        assertNull(parse(receipt().map { if (it.text.startsWith("商户：")) it.copy(text = "商户：微信支付") else it }))
        assertNull(parse(receipt().map { if (it.text.startsWith("实付金额")) it.copy(text = "应付金额：￥18.00") else it }))
        assertNull(parse(receipt().map { if (it.text == "支付成功") line(it.text, 1100) else it }))
        assertNull(parse(receipt().map { if (it.text == "完成") line(it.text, 1100) else it }))
    }

    @Test fun `split OCR columns bind only an unambiguous value in the same row`() {
        fun column(text: String, x: Int, top: Int) = PaymentImageReader.OcrLine(text, Rect(x, top, x + 210, top + 40))
        val split = receipt().filterNot { it.text.startsWith("商户") || it.text.startsWith("实付") } + listOf(
            column("实付金额", 80, 430), column("￥18.00", 670, 434),
            column("商户", 80, 580), column(shop, 670, 580),
            column("商户订单号", 80, 750), column(order, 670, 744))
        assertEquals(order, parse(split.reversed())?.merchantOrderId)
        assertNull(parse(split + column("￥28.00", 520, 431)))
        assertNull(parse(split.map { if (it.text == order) column(order, 670, 795) else it }))
    }

    @Test fun `direct merchant receipt needs its own wallet transaction id and recent exact payment time`() {
        val now = LocalDateTime.of(2026, 11, 7, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val walletId = "4200001234202611070000012345"
        val direct = receipt().filterNot { it.text.startsWith("商户订单号") } +
            listOf(line("微信支付单号：$walletId", 750), line("支付时间：2026-11-07 11:59:30", 875))
        fun directParse(lines: List<PaymentImageReader.OcrLine>) =
            WechatOpaqueMerchantReceiptReader.parse(lines, 1000, 2000, now)

        val result = directParse(direct)!!
        assertTrue(result.independentlyVerified)
        assertNull(result.merchantOrderId)
        assertEquals(walletId, result.receipt.orderId)
        assertEquals(now - 30_000, result.receipt.occurredAt)
        assertEquals("本地OCR·微信支付单号：$walletId", result.receipt.fieldEvidence["orderId"])
        assertTrue(directParse(direct.map {
            if (it.text.startsWith("微信支付单号")) it.copy(text = "交易单号：$walletId") else it
        })!!.independentlyVerified)
        for (badTime in listOf("2026-11-07 11:57:59", "2026-11-07 12:00:01", "11月07日 11:59:30", "2026-02-30 11:59:30")) {
            assertNull(badTime, directParse(direct.map {
                if (it.text.startsWith("支付时间")) it.copy(text = "支付时间：$badTime") else it
            }))
        }
        assertNull(directParse(direct.filterNot { it.text.startsWith("支付时间") }))
        assertNull(directParse(direct + line("交易时间：2026-11-07 11:59:40", 1110)))
        assertNull(directParse(direct + line("交易单号：4200001234202611070000019999", 1110)))
        assertNull(directParse(direct + line("实扣金额：28.00元", 1110)))
        assertNull(directParse(direct.map {
            if (it.text.startsWith("微信支付单号")) it.copy(text = "订单号：$walletId") else it
        }))
        val withMerchantOrder = directParse(direct + line("商户订单号：$order", 1110))!!
        assertFalse(withMerchantOrder.independentlyVerified)
        assertEquals(order, withMerchantOrder.merchantOrderId)
        assertEquals(order, withMerchantOrder.receipt.orderId)
    }
}

package com.pennypilot.app.service

import android.app.Application
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WechatReceivedTransferReaderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 24, 15, 55, 0, 0, zone).toInstant().toEpochMilli()
    private val receivedAt = ZonedDateTime.of(2026, 10, 24, 15, 49, 46, 0, zone).toInstant().toEpochMilli()
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PaymentImageReader.OcrLine(text, Rect(left, top, right, bottom))
    // Synthetic OCR lines transcribed from the actual 1264 x 2780 receipt, no private names.
    private fun receipt() = listOf(
        line("15:55", 60, 48, 180, 92),
        line("你已收款，资金已存入零钱", 326, 752, 941, 799),
        line("¥5.00", 438, 882, 820, 998),
        line("零钱余额", 544, 1089, 719, 1137),
        line("转账时间", 100, 1295, 280, 1333),
        line("2026年10月24日 15:49:01", 645, 1295, 1164, 1333),
        line("收款时间", 100, 1371, 280, 1411),
        line("2026年10月24日 15:49:46", 645, 1371, 1164, 1411),
        line("零钱通 七日年化0.91%", 280, 1540, 730, 1580),
        line("给小金库再添一笔", 280, 1605, 710, 1650),
        line("转入", 1015, 1570, 1125, 1620),
        line("账单详情", 545, 2657, 720, 2703))
    private fun parse(lines: List<PaymentImageReader.OcrLine> = receipt(), at: Long = now) =
        WechatReceivedTransferReader.parse(lines, 1264, 2780, at, zone)

    @Test fun `observed own receipt records incoming funds at the actual receipt time`() {
        val result = parse()!!
        assertEquals(500L, result.amountCents)
        assertEquals("INCOME", result.direction)
        assertEquals("transfer_received", result.scene)
        assertEquals("unknown", result.businessKind)
        assertEquals(receivedAt, result.occurredAt)
        assertNull(result.merchant)
        assertNull(result.orderId)
        assertTrue(result.fieldEvidence.values.all { it.startsWith("本地OCR") })
    }

    @Test fun `readable nodes use the same confirmed receipt and timestamp validation`() {
        val text = receipt().drop(1).joinToString("\n") { it.text }
        assertEquals(receivedAt, WechatReceivedTransferReader.parseText(text, now, zone)?.occurredAt)
        assertNull(WechatReceivedTransferReader.parseText("聊天记录\n$text", now, zone))
        assertNull(WechatReceivedTransferReader.parseText(text + "\n对方已收款", now, zone))
    }

    @Test fun `actual phone OCR omissions in static labels preserve financial evidence verbatim`() {
        val actual = receipt().filterNot { it.text == "15:55" || it.text.contains("零钱通") ||
            it.text.contains("小金库") || it.text == "转入" }.map {
            when (it.text) {
                "你已收款，资金已存入零钱" -> it.copy(text = "你已收款,资金已入零钱")
                "零钱余额" -> it.copy(text = "零线余额")
                "账单详情" -> it.copy(text = "账单详惰")
                "¥5.00" -> it.copy(text = "5.00", bounds = Rect(535, 874, 815, 1001))
                else -> it
            }
        }
        val result = parse(actual)!!
        assertEquals(500L, result.amountCents)
        assertEquals(receivedAt, result.occurredAt)
        assertTrue(result.fieldEvidence["paymentStatus"]!!.endsWith("你已收款,资金已入零钱"))
        assertFalse(result.fieldEvidence["paymentStatus"]!!.contains("已存入"))
        assertNull(parse(actual.map { if (it.text == "5.00") it.copy(text = "S.00") else it }))
        assertNull(parse(actual.map { if (it.text.endsWith("15:49:46")) it.copy(text = "2026年1O月24日15:49:46") else it }))
    }

    @Test fun `currency omission splitting and full width digits stay inside the dedicated amount region`() {
        assertEquals(500L, parse(receipt().map { if (it.text == "¥5.00") it.copy(text = "５．００") else it })?.amountCents)
        val split = receipt().filterNot { it.text == "¥5.00" } +
            line("￥", 438, 882, 490, 998) + line("5.00", 510, 882, 820, 998)
        assertEquals(500L, parse(split)?.amountCents)
        assertEquals(500L, parse(receipt().filterNot { it.text.contains("零钱通") || it.text == "转入" || it.text.contains("小金库") })?.amountCents)
    }

    @Test fun `pending counterparty expired refunded and failed transfers never become income`() {
        for (status in listOf("待确认收款", "请确认收款", "对方已收款，资金已存入零钱", "已退还", "收款失败",
                "转账已过期", "你已收款，资金尚未存入零钱", "你已收款，资金已存入零钱，退款中")) {
            assertNull(status, parse(receipt().map { if (it.text.startsWith("你已")) it.copy(text = status) else it }))
        }
        for (extra in listOf("确认收款", "请输入支付密码", "聊天记录", "支付失败", "退款处理中", "到账5元")) {
            assertNull(extra, parse(receipt() + line(extra, 100, 1700, 700, 1750)))
        }
    }

    @Test fun `every financial anchor and its spatial region is required`() {
        for (index in listOf(1, 2, 3, 4, 5, 6, 7)) {
            assertNull("missing $index", parse(receipt().filterIndexed { i, _ -> i != index }))
        }
        assertNotNull(parse(receipt().filterNot { it.text == "账单详情" }))
        assertNull(parse(receipt().map { if (it.text == "¥5.00") it.copy(bounds = Rect(438, 1700, 820, 1816)) else it }))
        assertNull(parse(receipt().map { if (it.text == "¥5.00") it.copy(bounds = null) else it }))
        assertNull(parse(receipt().map { if (it.text == "¥5.00") it.copy(bounds = Rect(-1, 882, 820, 998)) else it }))
    }

    @Test fun `historical future malformed and inverted timestamps cannot create a fresh transaction`() {
        assertNull(parse(at = receivedAt + 30 * 60_000 + 1))
        assertNotNull(parse(at = receivedAt + 30 * 60_000))
        assertNull(parse(at = receivedAt - 1))
        for (bad in listOf("2026年2月30日15:49:46", "2026年10月24日15:49", "2026年10月24日25:49:46",
                "2026年10月24日15:48:00")) {
            assertNull(bad, parse(receipt().map { if (it.text.endsWith("15:49:46")) it.copy(text = bad) else it }))
        }
    }

    @Test fun `invalid ambiguous and foreign amounts cannot be repaired by taking numeric fragments`() {
        for (bad in listOf("5", "¥0.00", "¥-5.00", "¥5.001", "¥1,23.00", "HK$5.00", "Y5.00", "¥5.00元人民币10元")) {
            assertNull(bad, parse(receipt().map { if (it.text == "¥5.00") it.copy(text = bad) else it }))
        }
        assertNull(parse(receipt() + line("¥8.00", 840, 882, 1050, 998)))
        assertNull(parse(receipt() + line("¥8.00", 438, 930, 820, 1040)))
        assertNull(parse(receipt() + line("余额8.00元", 100, 1700, 700, 1750)))
    }
}

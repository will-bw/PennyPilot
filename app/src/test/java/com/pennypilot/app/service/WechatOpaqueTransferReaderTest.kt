package com.pennypilot.app.service

import android.app.Application
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WechatOpaqueTransferReaderTest {
    private val snapshot = PaymentScreenReader.Snapshot(null, false, "empty_page", mapOf(
        "visitedNodes" to 1, "hiddenNodes" to 1, "textNodes" to 0, "editableNodes" to 0,
        "passwordNodes" to 0, "missingChildren" to 0, "foreignNodes" to 0))
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PaymentImageReader.OcrLine(text, Rect(left, top, right, bottom))
    // Coordinates transcribed from the 1264 x 2780 screenshot observed on the user's phone.
    private fun receipt() = listOf(
        line("13:07", 60, 48, 180, 92),
        line("支付成功", 570, 192, 780, 245),
        line("待测试收款人确认收款", 370, 570, 895, 625),
        line("¥3.00", 440, 700, 825, 824),
        line("完成", 579, 2380, 685, 2430))
    private fun parse(lines: List<PaymentImageReader.OcrLine> = receipt()) =
        WechatOpaqueTransferReader.parse(lines, 1264, 2780)

    @Test fun `observed completed transfer is expense even while payee confirmation is pending`() {
        val parsed = parse()!!
        assertEquals(300L, parsed.amountCents)
        assertEquals("EXPENSE", parsed.direction)
        assertEquals("测试收款人", parsed.merchant)
        assertEquals("unknown", parsed.businessKind)
        assertEquals("transfer_sent", parsed.scene)
        assertTrue(parsed.fieldEvidence.values.all { it.startsWith("本地OCR") })
    }

    @Test fun `split currency and full width digits can retain a single unambiguous amount`() {
        val lines = receipt().filterNot { it.text == "¥3.00" } +
            line("￥", 440, 708, 500, 798) + line("３.００", 530, 700, 825, 824)
        assertEquals(300L, parse(lines)!!.amountCents)
        assertEquals(300L, parse(receipt().map { if (it.text == "¥3.00") it.copy(text = "3.00") else it })!!.amountCents)
        assertNull(parse(receipt().map { if (it.text == "¥3.00") it.copy(text = "3") else it }))
    }

    @Test fun `each completion anchor and spatial relationship is required`() {
        for (index in 1..4) assertNull(parse(receipt().filterIndexed { i, _ -> i != index }))
        assertNull(parse(receipt().map { if (it.text == "完成") it.copy(bounds = Rect(579, 300, 685, 350)) else it }))
        assertNull(parse(receipt().map { if (it.text == "支付成功") it.copy(bounds = Rect(570, 1700, 780, 1753)) else it }))
    }

    @Test fun `password input chat history failed and pending contexts never match the result template`() {
        for (extra in listOf("请输入支付密码", "转账金额", "发送消息", "账单详情", "支付失败", "等待付款", "截图示例")) {
            assertNull(extra, parse(receipt() + line(extra, 100, 1400, 600, 1450)))
        }
        assertNull(parse(receipt().map { if (it.text == "支付成功") it.copy(text = "支付失败") else it }))
        assertNull(parse(receipt().map { if (it.text == "支付成功") it.copy(text = "待支付") else it }))
    }

    @Test fun `ambiguous invalid or missing OCR geometry never yields a transaction`() {
        for (money in listOf("¥0.00", "¥-3.00", "¥3.001", "¥1,23.00", "HK$3.00")) {
            assertNull(money, parse(receipt().map { if (it.text == "¥3.00") it.copy(text = money) else it }))
        }
        assertNull(parse(receipt() + line("¥8.00", 400, 1000, 800, 1120)))
        assertNull(parse(receipt().map { if (it.text == "¥3.00") it.copy(bounds = null) else it }))
        assertNull(parse(receipt().map { if (it.text == "¥3.00") it.copy(bounds = Rect(-1, 700, 825, 824)) else it }))
    }

    @Test fun `only the observed opaque window may enter local visual recovery`() {
        assertTrue(WechatOpaqueTransferReader.accepts("com.tencent.mm", WechatOpaqueTransferReader.ACTIVITY, snapshot))
        assertFalse(WechatOpaqueTransferReader.accepts("other.app", WechatOpaqueTransferReader.ACTIVITY, snapshot))
        assertFalse(WechatOpaqueTransferReader.accepts("com.tencent.mm", null, snapshot))
        assertFalse(WechatOpaqueTransferReader.accepts("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", snapshot))
        for (status in listOf("input_page", "password_page", "incomplete_tree", "unreadable", "ready")) {
            assertFalse(WechatOpaqueTransferReader.accepts("com.tencent.mm", WechatOpaqueTransferReader.ACTIVITY,
                snapshot.copy(status = status)))
        }
        assertFalse(WechatOpaqueTransferReader.accepts("com.tencent.mm", WechatOpaqueTransferReader.ACTIVITY,
            snapshot.copy(diagnostics = snapshot.diagnostics + ("missingChildren" to 1))))
    }
}

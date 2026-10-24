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
class WechatRedPacketReaderTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = PaymentImageReader.OcrLine(text, Rect(l,t,r,b))
    // Detail geometry transcribed from the user's observed sender page; names are synthetic.
    private fun detail() = listOf(
        line("测试发件人的红包", 480, 495, 855, 550),
        line("恭喜发财，大吉大利", 440, 595, 840, 645),
        line("红包金额3.00元，等待对方领取", 50, 825, 680, 865),
        line("未领取的红包，将于24小时后发起退款", 250, 2650, 1015, 2705))
    private fun result() = listOf(
        line("支付成功", 520, 250, 740, 310),
        line("微信红包", 500, 520, 760, 580),
        line("¥3.00", 440, 900, 825, 1030),
        line("完成", 580, 2390, 700, 2440))
    private fun received() = listOf(
        line("测试发送方的红包",480,495,855,550),
        line("3.00元",450,850,800,960),
        line("已存入零钱，可直接使用",500,1050,950,1090),
        line("红包总额100.00元",100,1400,650,1450),
        line("其他成员",100,1500,450,1550),
        line("¥10.00",900,1500,1150,1550))

    @Test fun `observed sender detail is outgoing despite pending payee and conditional refund footer`() {
        val parsed = WechatRedPacketReader.parseSentDetail(detail(),1264,2780)!!
        assertEquals(300L, parsed.amountCents)
        assertEquals("EXPENSE", parsed.direction)
        assertEquals("red_packet_sent", parsed.scene)
        assertEquals("unknown", parsed.businessKind)
        assertNull(parsed.merchant)
        assertFalse(parsed.fieldEvidence.values.any { it.contains("测试发件人") || it.contains("24小时") })
    }

    @Test fun `a red packet flow requires real completion and expires with no guessed detail payment`() {
        val flow = WechatRedPacketFlow()
        assertNull(flow.keyFor(300,0))
        flow.start(1000)
        assertTrue(flow.isPreparing(1100))
        assertFalse(flow.hasPaid(1100))
        assertNull(flow.keyFor(300,1100))
        flow.leaveUnpaid()
        assertNull(flow.confirm(300,1200))
        flow.start(2000)
        val key = flow.confirm(300,2100)!!
        flow.leaveUnpaid()
        assertEquals(key,flow.keyFor(300,2200))
        assertNull(flow.keyFor(500,2200))
        assertNull(flow.keyFor(300,12*60_000))
        flow.start(3000)
        assertNotEquals(key,flow.confirm(300,3100))
    }

    @Test fun `input missing anchors multiple amounts and invalid geometry cannot become sent payments`() {
        for (extra in listOf("输入金额", "支付失败", "聊天记录", "退款成功", "¥5.00")) {
            assertNull(extra, WechatRedPacketReader.parsePaymentResult(result()+line(extra,100,1500,800,1560),1264,2780))
            assertNull(extra, WechatRedPacketReader.parseSentDetail(detail()+line(extra,100,1500,800,1560),1264,2780))
        }
        assertEquals(300L,WechatRedPacketReader.parsePaymentResult(result(),1264,2780)!!.amountCents)
        assertNull(WechatRedPacketReader.parsePaymentResult(result().dropLast(1),1264,2780))
        assertNull(WechatRedPacketReader.parseSentDetail(detail().map { if(it.text.startsWith("红包金额")) it.copy(text="红包金额0元，等待对方领取") else it },1264,2780))
        assertNull(WechatRedPacketReader.parseSentDetail(detail().map { if(it.text.startsWith("红包金额")) it.copy(bounds=null) else it },1264,2780))
        assertNull(WechatRedPacketReader.parseSentDetail(detail().map { if(it.text.startsWith("红包金额")) it.copy(text="红包金额3.00元，对方已领取") else it },1264,2780))
    }

    @Test fun `own credited amount is separate from group totals and other recipients amounts`() {
        val parsed=WechatRedPacketReader.parseReceivedDetail(received(),1264,2780)!!
        assertEquals(300L,parsed.amountCents)
        assertEquals("INCOME",parsed.direction)
        assertEquals("red_packet_received",parsed.scene)
        assertEquals("测试发送方",parsed.merchant)
        assertTrue(parsed.fieldEvidence.values.none { it.contains("100.00") || it.contains("10.00") || it.contains("其他成员") })
        for(status in listOf("对方已存入零钱","待领取","领取失败","已被其他人领取")) {
            assertNull(status,WechatRedPacketReader.parseReceivedDetail(received().map { if(it.text.startsWith("已存入")) it.copy(text=status) else it },1264,2780))
        }
        assertNull(WechatRedPacketReader.parseReceivedDetail(received()+line("5.00元",850,850,1150,960),1264,2780))
        assertNull(WechatRedPacketReader.parseReceivedDetail(received().map { if(it.text=="3.00元") it.copy(text="3.001元") else it },1264,2780))
    }
}

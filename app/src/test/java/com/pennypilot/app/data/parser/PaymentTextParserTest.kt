package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test

/** 合成样本模拟通知/可访问节点结构，不代表已覆盖所有厂商版本或真实设备。 */
class PaymentTextParserTest {
    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"
    private val meituan = "com.sankuai.meituan"
    private val douyin = "com.ss.android.ugc.aweme"

    @Test
    fun `registry includes marketplace and lite apps`() {
        assertEquals(6, PaymentAppRegistry.packages.size)
        assertEquals("美团外卖", PaymentAppRegistry.displayName("com.sankuai.meituan.takeoutnew"))
        assertEquals("抖音极速版", PaymentAppRegistry.displayName("com.ss.android.ugc.aweme.lite"))
        assertEquals("unknown.app", PaymentAppRegistry.displayName("unknown.app"))
    }

    @Test
    fun `wechat completion reads counterparty without treating payee as income`() {
        val result = PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n收款方\n星巴克\n完成")
        assertEquals(1800L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
        assertEquals("星巴克", result?.merchant)
        assertEquals("微信支付", result?.paymentMethod)
    }

    @Test
    fun `payee fields never become income actions`() {
        listOf("收款方", "收款商户", "收款账户", "收款人", "收款码").forEach { field ->
            assertEquals(field, "EXPENSE", PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n$field\n星巴克")?.direction)
        }
    }

    @Test
    fun `actual amount wins over original total discounts and delivery fee`() {
        val result = PaymentTextParser.parseScreen(meituan, """
            支付成功
            商家：蜀香小馆
            订单金额：￥35.00
            配送费：￥3.00
            优惠：￥5.00
            实付款：￥30.00
            支付方式：微信支付
            订单号：MT20261023123456
            查看订单详情
        """.trimIndent())
        assertEquals(3000L, result?.amountCents)
        assertEquals("EXPENSE", result?.direction)
        assertEquals("蜀香小馆", result?.merchant)
        assertEquals("MT20261023123456", result?.orderId)
        assertEquals("微信支付", result?.paymentMethod)
    }

    @Test
    fun `douyin and lite split accessibility nodes are supported`() {
        listOf(douyin, "com.ss.android.ugc.aweme.lite").forEach { pkg ->
            val result = PaymentTextParser.parseScreen(pkg, "支付成功\n实付金额\n￥\n１９.９０\n商家名称\n测试食品旗舰店\n订单编号\n202610239876543210")
            assertEquals(pkg, 1990L, result?.amountCents)
            assertEquals("测试食品旗舰店", result?.merchant)
            assertEquals("202610239876543210", result?.orderId)
        }
    }

    @Test
    fun `symbol and prominent amount may be split across nodes`() {
        assertEquals(1850L, PaymentTextParser.parseScreen(alipay, "付款成功\n¥\n18.50\n完成")?.amountCents)
        assertEquals(1850L, PaymentTextParser.parseScreen(alipay, "付款成功\n付款金额\n18.50\n完成")?.amountCents)
    }

    @Test
    fun `incoming receipt is income despite wallet name`() {
        val result = PaymentTextParser.parseScreen(wechat, "收款到账\n￥88.80\n完成")
        assertEquals(8880L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
    }

    @Test
    fun `refund captures returned amount rather than original expense`() {
        val result = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n原支付金额：30.00元\n订单号：MT20261023123456")
        assertEquals(500L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
        assertNull(result?.orderId)
    }

    @Test
    fun `partial refund never inherits original merchant order identity`() {
        val first = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n商户订单号：MT20261023123456\n退款编号：RF20261023000001")
        val second = PaymentTextParser.parseScreen(meituan, "退款成功\n退款金额：5.00元\n商户订单号：MT20261023123456\n退款编号：RF20261023000002")
        assertEquals(500L, first?.amountCents)
        assertEquals(500L, second?.amountCents)
        assertNull(first?.orderId)
        assertNull(second?.orderId)
    }

    @Test
    fun `refund never inherits original wallet transaction identity`() {
        val result = PaymentTextParser.parseScreen(alipay, "退款已到账\n退款金额：5.00元\n原支付金额：30.00元\n交易单号：2026102322001234567890123456")
        assertEquals(500L, result?.amountCents)
        assertEquals("INCOME", result?.direction)
        assertNull(result?.orderId)
    }

    @Test
    fun `duplicate visible amount is one transaction`() {
        assertEquals(1800L, PaymentTextParser.parseScreen(douyin, "支付成功\n¥18.00\n实付金额：18.00元")?.amountCents)
    }

    @Test
    fun `account balances do not conflict with actual payment amount`() {
        listOf("账户余额", "可用余额", "余额").forEach { label ->
            val result = PaymentTextParser.parseScreen(alipay, "支付成功\n实付18元\n$label：100元")
            assertEquals(label, 1800L, result?.amountCents)
            assertEquals("EXPENSE", result?.direction)
        }
    }

    @Test
    fun `balance only insufficient balance and failed payment are not receipts`() {
        listOf("账户余额100元", "支付成功\n账户余额100元", "支付成功\n实付18元\n余额不足",
            "支付失败\n支付金额18元\n账户余额100元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(alipay, it)) }
    }

    @Test
    fun `different unexplained amounts and conflicting actual amounts are rejected`() {
        listOf("支付成功\n¥18.00\n¥28.00", "支付成功\n实付18.00元\n实付28.00元",
            "支付成功\n实付18.00元\n另收5.00元", "退款成功\n退款5.00元\n退款10.00元",
            "收款成功\n收款金额28元\n付款金额18元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(meituan, it)) }
    }

    @Test
    fun `invalid or signed currency cannot be partially captured`() {
        listOf("0.00", "10000000.00", "1.234", "1,80.00", "1e3", "-18.00", "+18.00", "−18.00").forEach { amount ->
            assertNull(amount, PaymentTextParser.parseScreen(wechat, "支付成功\n￥$amount"))
        }
        assertEquals(180000L, PaymentTextParser.parseScreen(wechat, "支付成功\n￥1,800.00")?.amountCents)
        assertEquals(999999999L, PaymentTextParser.parseScreen(wechat, "支付成功\n￥9,999,999.99")?.amountCents)
    }

    @Test
    fun `wrong currency and unsupported app are rejected`() {
        assertNull(PaymentTextParser.parseScreen("org.example.chat", "支付成功\n¥18.00"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n支付金额：18.00美元"))
        assertNull(PaymentTextParser.parseScreen(alipay, "支付成功\n￥1800\n币种：日元"))
    }

    @Test
    fun `order amounts alone do not establish what was actually paid`() {
        assertNull(PaymentTextParser.parseScreen(meituan, "支付成功\n订单金额18元\n优惠5元"))
        assertNull(PaymentTextParser.parseScreen(meituan, "实付18元\n提交订单"))
        assertNull(PaymentTextParser.parseScreen(meituan, "支付成功\n18.00"))
    }

    @Test
    fun `pending failed cancelled advertising and refund requests are not receipts`() {
        listOf("待支付\n实付18元", "支付失败\n￥18.00", "订单已取消\n实付18元",
            "退款申请中\n退款金额18元", "退款处理中\n退款金额18元", "申请退款\n原支付18元",
            "支付成功后可享优惠\n￥18.00", "支付成功\n请支付18元", "优惠券已到账\n18元"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(meituan, it)) }
    }

    @Test
    fun `history pages never become fresh transactions`() {
        listOf("账单详情", "交易详情", "订单详情", "退款详情", "我的订单", "全部订单", "订单列表", "交易记录", "历史订单").forEach { header ->
            assertNull(header, PaymentTextParser.parseScreen(douyin, "$header\n支付成功\n实付18元"))
        }
    }

    @Test
    fun `payment wording in chats and video comments is not a receipt screen`() {
        listOf("张三：支付成功 ¥18.00", "支付成功 ¥18.00", "我刚才支付成功\n¥18.00",
            "聊天记录\n支付成功\n¥18.00", "支付成功\n¥18.00\n按住说话", "视频评论\n支付成功\n¥18.00",
            "支付成功\n¥18.00\n说点什么", "推荐\n支付成功\n¥18.00\n评论"
        ).forEach { assertNull(it, PaymentTextParser.parseScreen(wechat, it)) }
    }

    @Test
    fun `only complete nonmasked identifiers become dedup evidence`() {
        listOf("12345", "123456****7890", "123456••7890", "123456…7890", "1234567890复制", "A".repeat(20), "1".repeat(65)).forEach { id ->
            assertNull(id, PaymentTextParser.parseScreen(meituan, "支付成功\n实付18元\n订单号：$id")?.orderId)
        }
        val result = PaymentTextParser.parseScreen(meituan, "支付成功\n实付18元\n交易单号：4200001234567890\n商户订单号：MT20261023123456")
        assertEquals("MT20261023123456", result?.orderId)
    }

    @Test
    fun `oversized screen is rejected`() {
        assertNull(PaymentTextParser.parseScreen(wechat, "支付成功\n¥18.00\n" + "长页面".repeat(6000)))
    }
}

package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Page/field contracts from the application families, not claims of current-device acceptance. */
class PaymentSceneAdaptersTest {
    private val now = ZonedDateTime.of(2026, 11, 14, 17, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun parse(pkg: String, text: String) = PaymentSceneAdapters.parse(pkg, text, now)
    private val historicalExpense = "账单详情\n便利店\n支付成功\n-18.00\n支付时间：2025-10-24 16:58:30\n交易单号：20251024000012345678\n收款方：便利店"

    @Test fun `profiles carry wallet commerce food and limited platform capabilities`() {
        assertEquals(23, PaymentAppRegistry.packages.size)
        assertEquals(PaymentAppRegistry.Family.WALLET, PaymentAppRegistry.profile("com.jd.jrapp")!!.family)
        assertEquals(PaymentAppRegistry.Family.SHOPPING, PaymentAppRegistry.profile("com.jingdong.app.mall")!!.family)
        assertEquals(PaymentAppRegistry.Family.FOOD, PaymentAppRegistry.profile("me.ele")!!.family)
        assertTrue(PaymentAppRegistry.profile("com.taobao.taobao")!!.limitedByPlatform)
        assertTrue(PaymentAppRegistry.profile("com.ss.android.ugc.aweme")!!.limitedByPlatform)
        assertNull(PaymentSceneAdapters.match("com.example.chat", "支付成功\n￥18.00\n完成"))
    }

    @Test fun `historical wallet details retain signed direction timestamp and receipt identity`() {
        val receipt = parse("com.tencent.mm", historicalExpense)!!
        assertEquals(1800L, receipt.amountCents)
        assertEquals("EXPENSE", receipt.direction)
        assertEquals("20251024000012345678", receipt.orderId)
        assertTrue(receipt.occurredAt!! < now - 365L * 24 * 60 * 60 * 1000)
        assertEquals("页面适配·微信:账单详情", receipt.fieldEvidence["page"])
        assertNull(parse("com.tencent.mm", historicalExpense.replace("支付成功", "支付失败")))
    }

    @Test fun `detail without reliable identity is eligible for cloud review but never a local entry`() {
        val missingId = historicalExpense.replace("交易单号：20251024000012345678", "")
        val match = PaymentSceneAdapters.match("com.tencent.mm", missingId)!!
        assertTrue(match.historicalDetail)
        assertTrue(match.allowsCloudImage)
        assertNull(parse("com.tencent.mm", missingId))
        assertNull(parse("com.tencent.mm", historicalExpense.replace("支付时间：2025-10-24 16:58:30", "")))
    }

    @Test fun `commerce paid order detail uses payment date and actual amount rather than product original price`() {
        val receipt = "订单详情\n交易已完成\n商品名称：电热水壶\n商家名称：示例家电店\n实付款：89.00元\n商品原价：109.00元\n优惠合计：20.00元\n付款时间：2026-11-10 18:30\n订单编号：JD2026111000001"
        val parsed = parse("com.jingdong.app.mall", receipt)!!
        assertEquals(8900L, parsed.amountCents)
        assertEquals("电热水壶", parsed.details.productDescription)
        assertEquals(10900L, parsed.details.originalAmountCents)
        assertEquals(2000L, parsed.details.discountAmountCents)
        assertEquals("示例家电店", parsed.merchant)
        assertNotNull(parsed.occurredAt)
        assertNull(parse("com.jingdong.app.mall", receipt.replace("交易已完成", "待付款")))
        assertNull(parse("com.jingdong.app.mall", receipt.replace("实付款：89.00元", "应付金额：89.00元")))
    }

    @Test fun `food delivered order requires paid amount timestamp and order identity`() {
        val text = "订单详情\n订单已送达\n实付金额：26.80元\n店铺名称：示例面馆\n支付时间：2026-11-13 12:04:10\n订单号：ELE202611130001"
        assertEquals(2680L, parse("me.ele", text)!!.amountCents)
        assertEquals(2680L, parse("me.ele", text.replace("订单已送达", "订单状态：已送达"))!!.amountCents)
        assertNull(parse("me.ele", text.replace("支付时间：2026-11-13 12:04:10", "")))
        assertNull(parse("me.ele", text.replace("订单号：ELE202611130001", "")))
        assertNull(parse("me.ele", text + "\n订单状态：待支付"))
    }

    @Test fun `order refunds keep individual refund identities for partial installments`() {
        val text = "退款详情\n退款已到账\n本次退款金额：5.00元\n原支付金额：30.00元\n退款到账时间：2026-11-10 18:30:00\n退款单号：RF202611100001\n订单编号：PDD202611100001"
        val first = parse("com.xunmeng.pinduoduo", text)!!
        val next = parse("com.xunmeng.pinduoduo", text.replace("RF202611100001", "RF202611100002"))!!
        assertEquals(500L, first.amountCents)
        assertEquals("refund", first.orderIdKind)
        assertNotEquals(first.orderId, next.orderId)
        assertNull(parse("com.xunmeng.pinduoduo", text.replace("退款已到账", "退款已受理")))
        assertNull(parse("com.xunmeng.pinduoduo", text.replace("退款单号：RF202611100001", "")))
    }

    @Test fun `wallet account movement separates principal fee and received amount`() {
        val text = "提现成功\n提现金额：100.00元\n手续费：0.10元\n到账金额：99.90元\n转出账户：微信零钱\n转入账户：招商银行储蓄卡(1234)\n完成"
        val parsed = parse("com.tencent.mm", text)!!
        assertEquals(10000L, parsed.amountCents)
        assertEquals("transfer", parsed.businessKind)
        assertEquals("account_transfer", parsed.scene)
        assertEquals(10L, parsed.details.feeAmountCents)
        assertEquals(9990L, parsed.details.receivedAmountCents)
        assertEquals("微信零钱", parsed.details.transferFromAccount)
        assertEquals("招商银行储蓄卡(1234)", parsed.details.transferToAccount)
        assertNull(parse("com.tencent.mm", text.replace("提现成功", "提现申请已提交")))
        assertNull(parse("com.tencent.mm", text + "\n充值金额：200.00元"))
        assertNull(parse("com.tencent.mm", text.replace("提现金额：100.00元", "")))
    }

    @Test fun `account movement details need their actual completion time and identity`() {
        val text = "账单详情\n余额宝转出成功\n转出金额：88.00元\n转出账户：余额宝\n转入账户：支付宝余额\n交易时间：2025年10月24日 16点58分30秒\n交易单号：20251024000012345678"
        assertEquals(8800L, parse("com.eg.android.AlipayGphone", text)!!.amountCents)
        assertNull(parse("com.eg.android.AlipayGphone", text.replace("交易单号：20251024000012345678", "")))
        assertNull(parse("com.eg.android.AlipayGphone", text.replace("2025年10月24日", "2027年10月24日")))
    }

    @Test fun `cloud page qualification can lack amount but cannot use a chat or checkout screen`() {
        assertNotNull(PaymentSceneAdapters.match("com.tencent.mm", "支付成功\n收款方：示例商户\n完成"))
        for (extra in listOf("发送消息", "请输入支付密码", "确认付款", "订单列表", "视频评论", "交易失败")) {
            assertNull(extra, PaymentSceneAdapters.match("com.tencent.mm", historicalExpense + "\n$extra"))
        }
        assertNull(PaymentSceneAdapters.match("com.ss.android.ugc.aweme", "直播间\n支付成功\n￥88.00\n完成"))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", "备注：支付成功\n￥88.00\n完成"))
    }

    @Test fun `only payment specific activities qualify an empty accessibility tree`() {
        assertNotNull(PaymentSceneAdapters.match("com.tencent.mm", "", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", "", "com.tencent.mm.framework.app.UIPageFragmentActivity"))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", "", "com.tencent.mm.ui.chatting.ChattingUI"))
        assertNull(PaymentSceneAdapters.match("com.taobao.idlefish", "", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"))
    }
    @Test fun `alipay balance detail and transaction method labels use their own adapter`() {
        val text = "余额明细详情\n交易成功\n-18.00\n支付时间：2025-10-24 16:58:30\n交易单号：20251024000012345678\n交易方式：余额\n商品：早餐"
        val result = parse("com.eg.android.AlipayGphone", text)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("余额", result.details.fundingAccount)
        assertEquals("早餐", result.details.productDescription)
        assertTrue(result.fieldEvidence.getValue("pageMapping").contains("余额明细详情"))
        assertNull(parse("com.tencent.mm", text))
        assertNull(parse("com.eg.android.AlipayGphone", text.replace("交易成功", "交易失败")))
    }

    @Test fun `alipay nested bill headings keep one receipt while proxy payment uses its explicit status`() {
        val detail = historicalExpense.replace("账单详情", "账单详情\n交易详情")
        assertEquals(1800L, parse("com.eg.android.AlipayGphone", detail)!!.amountCents)
        val paid = "代付成功\n实付金额：28.00元\n交易方式：招商银行储蓄卡(1234)\n收款方：示例店铺\n完成"
        val result = parse("com.eg.android.AlipayGphone", paid)!!
        assertEquals(2800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("招商银行储蓄卡(1234)", result.details.fundingAccount)
    }

    @Test fun `meituan automatic debit and refund account labels preserve financial roles`() {
        val paid = "先用后付自动扣款成功\n实付金额：26.80元\n支付优惠：3.20元\n商家名称：示例面馆\n支付方式：美团月付\n完成"
        val result = parse("com.sankuai.meituan", paid)!!
        assertEquals(2680L, result.amountCents)
        assertEquals(320L, result.details.discountAmountCents)
        assertEquals("美团月付", result.details.fundingAccount)
        val refund = "退款详情\n退款处理完成，微信已入账\n退款金额：5.00元\n退款至：微信零钱\n退款到账时间：2026-11-13 12:04:10\n退款单号：MT202611130001"
        assertEquals(500L, parse("com.sankuai.meituan", refund)!!.amountCents)
        assertEquals("微信零钱", parse("com.sankuai.meituan", refund)!!.details.fundingAccount)
        assertNull(parse("com.sankuai.meituan", paid.replace("自动扣款成功", "自动扣款失败")))
    }

    @Test fun `jd combined credit payment row separates actual payment discount and funding account`() {
        val text = "支付成功\n白条付款 ¥89.00，共优惠 ¥20.00\n查看订单"
        val result = parse("com.jingdong.app.mall", text)!!
        assertEquals(8900L, result.amountCents)
        assertEquals(2000L, result.details.discountAmountCents)
        assertEquals("京东白条", result.details.fundingAccount)
        assertNull(parse("com.jingdong.app.mall", text.replace("支付成功", "待付款")))
        assertNull(parse("com.tencent.mm", text))
    }

    @Test fun `hippo inline successful amount is recognized without guessing the store`() {
        val result = parse("com.wudaokou.hippo", "支付成功：¥58.80\n完成")!!
        assertEquals(5880L, result.amountCents)
        assertNull(result.merchant)
        assertTrue(result.fieldEvidence.getValue("pageMapping").contains("支付成功：¥58.80"))
        assertNull(parse("com.wudaokou.hippo", "支付失败：¥58.80\n完成"))
    }

    @Test fun `pinduoduo current status and transaction number identify a titleless detail`() {
        val text = "示例商家\n-28.00\n当前状态\n支付成功\n支付时间\n2025-10-24 16:58:30\n交易单号\n20251024000012345678\n支付方式\n微信支付"
        val result = parse("com.xunmeng.pinduoduo", text)!!
        assertEquals(2800L, result.amountCents)
        assertEquals("20251024000012345678", result.orderId)
        assertEquals("微信支付", result.details.paymentChannel)
        assertNull(parse("com.xunmeng.pinduoduo", text.replace("支付成功", "支付处理中")))
        assertNull(parse("com.xunmeng.pinduoduo", text.replace("交易单号\n20251024000012345678", "")))
    }

    @Test fun `wechat content description combines merchant direction and money in one node`() {
        val text = "账单详情\n示例便利店,支出18.00元\n支付成功\n支付时间\n2025-10-24 16:58:30\n交易单号\n20251024000012345678\n商品\n早餐\n付款备注\n不要加辣"
        val result = parse("com.tencent.mm", text)!!
        assertEquals(1800L, result.amountCents)
        assertEquals("EXPENSE", result.direction)
        assertEquals("示例便利店", result.merchant)
        assertEquals("早餐", result.details.productDescription)
        assertEquals("不要加辣", result.details.remark)
        assertNull(parse("com.eg.android.AlipayGphone", text))
    }

    @Test fun `wechat own completed transfer remains outgoing while recipient is waiting`() {
        val text = "支付成功\n￥5.00\n待张三确认收款\n完成"
        val parsed = parse("com.tencent.mm", text)!!
        assertEquals(500L, parsed.amountCents)
        assertEquals("EXPENSE", parsed.direction)
        assertEquals("transfer_sent", parsed.scene)
        assertEquals("张三", parsed.details.payeeName)
        assertEquals(500L, parse("com.tencent.mm", text.removeSuffix("\n完成"))!!.amountCents)
        assertNull(parse("com.tencent.mm", text.replace("支付成功", "确认收款")))
        assertNull(parse("com.tencent.mm", text + "\n聊天记录"))
    }

    @Test fun `wechat transfer result accepts amount and currency exposed by separate nodes`() {
        val variants = listOf(
            "支付成功\n待测试收款人确认收款\n¥ 2.00\n完成",
            "支付成功\n待测试收款人确认收款\n¥\n2.00\n完成",
            "转账成功。\n等待测试收款人确认收钱\n转账金额：\n￥\n２．００\n查看账单",
            "已转账\n付款金额：2元\n待测试收款人确认收款\n完成\n查看账单详情"
        )
        for (text in variants) {
            val result = parse("com.tencent.mm", text)!!
            assertEquals(text, 200L, result.amountCents)
            assertEquals(text, "EXPENSE", result.direction)
            assertEquals(text, "transfer_sent", result.scene)
            assertEquals(text, "测试收款人", result.details.payeeName)
        }
    }

    @Test fun `wechat waiting marker is not a payment and receipt amount must be unambiguous`() {
        val invalid = listOf(
            "等待测试收款人确认收款\n¥\n2.00\n完成",
            "转账失败\n待测试收款人确认收款\n转账金额：¥2.00\n完成",
            "支付成功\n待测试收款人确认收款\n¥\n2.00\n￥18.00\n完成",
            "¥\n转账金额\n¥\n支付成功\n待测试收款人确认收款\n完成",
            "支付成功\n转账金额\n待测试收款人确认收款\n2.00\n完成",
            "支付成功\n待测试收款人确认收款\n¥\n2.00\n输入支付密码",
            "支付成功\n待测试收款人确认收款\n¥\n2.00\n发送消息",
            "账单详情\n支付成功\n待测试收款人确认收款\n¥\n2.00"
        )
        for (text in invalid) {
            assertNull(text, parse("com.tencent.mm", text))
            assertNull(text, PaymentSceneAdapters.parseForReview("com.tencent.mm", text, now))
        }
        val unidentified = parse("com.tencent.mm", "支付成功\n¥2.00\n等待对方确认收钱\n完成")!!
        assertNull(unidentified.merchant)
    }

    @Test fun `wechat partial result with missing amount can reach opt in vision only`() {
        val text = "支付成功\n待测试收款人确认收款\n转账金额：\n¥\n完成"
        val match = PaymentSceneAdapters.match("com.tencent.mm", text)!!
        assertEquals(PaymentSceneAdapters.Kind.PARTIAL_RESULT, match.kind)
        assertTrue(match.allowsCloudImage)
        assertNull(parse("com.tencent.mm", text))
        assertNull(PaymentSceneAdapters.parseForReview("com.tencent.mm", text, now))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", text.replace("支付成功", "确认支付")))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", text.replace("完成", "发送消息")))
        assertNull(PaymentSceneAdapters.match("com.tencent.mm", text + "\n¥2.00\n¥5.00"))
    }

    @Test fun `wechat actual receipt date differs from transfer initiation and historical review stays explicit`() {
        val text = "你已收款\n资金已存入零钱\n￥5.00\n零钱余额\n转账时间\n2026年11月14日 16:54:01\n收款时间\n2026年11月14日 16:55:00\n账单详情"
        val result = parse("com.tencent.mm", text)!!
        assertEquals(500L, result.amountCents)
        assertEquals("INCOME", result.direction)
        assertEquals(now - 5 * 60_000L, result.occurredAt)
        val historical = text.replace("2026年11月14日", "2025年11月14日")
        assertNull(parse("com.tencent.mm", historical))
        val review = PaymentSceneAdapters.parseForReview("com.tencent.mm", historical, now)!!
        assertTrue(review.occurredAt!! < now - 300L * 24 * 60 * 60_000)
        assertTrue("reviewRequired" in review.fieldEvidence)
        assertNull(PaymentSceneAdapters.parseForReview("com.tencent.mm", historical.replace("你已收款", "待确认收款"), now))
        assertNull(PaymentSceneAdapters.parseForReview("com.tencent.mm", historical.replace("16:55:00", "16:53:00"), now))
    }

    @Test fun `historical detail without order becomes a review draft but retains its real date`() {
        val text = historicalExpense.replace("交易单号：20251024000012345678", "")
        assertNull(parse("com.tencent.mm", text))
        val review = PaymentSceneAdapters.parseForReview("com.tencent.mm", text, now)!!
        assertEquals(1800L, review.amountCents)
        assertNull(review.orderId)
        assertNotNull(review.occurredAt)
        assertTrue("reviewRequired" in review.fieldEvidence)
        assertNull(PaymentSceneAdapters.parseForReview("com.tencent.mm", text.replace("支付时间：2025-10-24 16:58:30", ""), now))
    }

    @Test fun `detail classification survives missing or invalid date and cannot become a generic result`() {
        val cases = listOf("com.eg.android.AlipayGphone" to "支付详情", "com.tencent.mm" to "收支详情",
            "com.jingdong.app.mall" to "订单信息", "com.eg.android.AlipayGphone" to "余额明细详情")
        for ((pkg, title) in cases) {
            val text = "$title\n支付成功\n实付金额：18.00元\n交易单号：20251024000012345678"
            assertTrue(title, PaymentSceneAdapters.isDetailPage(pkg, text))
            assertNull(title, PaymentSceneAdapters.parse(pkg, text, now))
            assertNull(title, PaymentSceneAdapters.parseForReview(pkg, text, now))
            assertNull(title, PaymentSceneAdapters.parseForReview(pkg, "$text\n支付时间：2026-02-30 12:00:00", now))
        }
        assertFalse(PaymentSceneAdapters.isDetailPage("com.tencent.mm", "支付成功\n实付金额：18.00元\n完成"))
        assertFalse(PaymentSceneAdapters.isDetailPage("com.example.chat", "账单详情\n支付成功\n实付金额：18.00元"))
    }

    @Test fun `late detail heading blocks result fallback even with an earlier success title`() {
        val prefix = "支付宝\n返回\n更多\n账单管理\n商户名称：便利店\n交易信息"
        for (header in listOf(prefix, prefix.replace("返回", "支付成功"))) {
            val text = "$header\n支付详情\n支付成功\n实付金额：18.00元\n交易单号：20251024000012345678"
            assertTrue(PaymentSceneAdapters.isDetailPage("com.eg.android.AlipayGphone", text))
            assertNull(PaymentSceneAdapters.parse("com.eg.android.AlipayGphone", text, now))
            assertNull(PaymentSceneAdapters.parseForReview("com.eg.android.AlipayGphone", text, now))
        }
        // A product or user remark mentioning a detail title cannot change a result page's type.
        val result = "支付成功\n实付金额：18.00元\n备注：\n支付详情"
        assertFalse(PaymentSceneAdapters.isDetailPage("com.eg.android.AlipayGphone", result))
    }

}

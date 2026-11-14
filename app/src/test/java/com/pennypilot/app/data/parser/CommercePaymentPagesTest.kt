package com.pennypilot.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CommercePaymentPagesTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()
    private val douyinPackages = listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.livelite",
        "com.ss.android.ugc.aweme.lite", "com.ss.android.yumme.video")
    private val meituanPackages = listOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1")
    private val kuaishouPackages = listOf("com.smile.gifmaker", "com.kuaishou.nebula")

    @Test fun `each registered commerce package recognizes its completed result layout`() {
        val pages = buildMap {
            put("com.jingdong.app.mall", "付款成功：¥18.00\n查看订单")
            put("com.xunmeng.pinduoduo", "拼单成功\n实付金额：¥18.00\n查看订单详情")
            put("com.alibaba.wireless", "付款成功：¥18.00\n查看订单")
            put("com.taobao.idlefish", "支付成功：¥18.00\n返回首页")
            put("com.taobao.taobao", "免密支付成功\n实付价：¥18.00\n完成")
            meituanPackages.forEach { put(it, "交易成功 ¥18.00\n完成") }
            put("me.ele", "下单成功，商家已接单\n实付金额：¥18.00\n查看订单详情")
            put("com.wudaokou.hippo", "支付成功：¥18.00\n完成")
            put("com.ss.android.ugc.lifeservices", "支付成功\n¥\n18.00\n已优惠3.00元\n完成")
            kuaishouPackages.forEach { put(it, "购买成功\n¥\n18.00\n完成") }
            douyinPackages.forEach { put(it, "购买成功\n实付价\n¥\n18.00\n完成") }
        }
        assertEquals(PaymentAppRegistry.apps.filter { it.family != PaymentAppRegistry.Family.WALLET }
            .map { it.packageName }.toSet(), pages.keys)
        for ((pkg, text) in pages) {
            assertEquals(pkg, PaymentSceneAdapters.Kind.RESULT, PaymentSceneAdapters.match(pkg, text)?.kind)
            val receipt = PaymentSceneAdapters.parse(pkg, text, now)
            assertNotNull(pkg, receipt)
            assertEquals(pkg, 1800L, receipt?.amountCents)
            assertEquals(pkg, "EXPENSE", receipt?.direction)
            assertNull(pkg, receipt?.merchant)
            assertNull(pkg, PaymentSceneAdapters.parse(pkg, "$text\n付款状态：处理中", now))
            assertNull(pkg, PaymentSceneAdapters.parse(pkg, "$text\n实付金额：¥12.00", now))
        }
    }

    @Test fun `paid details preserve merchant name product order and discount without conflating prices`() {
        val detail = "账单详情\n支付成功\n商户名称：样例商铺\n商品名称：样例茶具\n" +
            "实付价：¥18.00\n券后价：¥30.00\n优惠金额：¥12.00\n交易方式：抖音月付\n" +
            "支付时间：2026-11-12 16:30:00\n交易单号：DX20261112000199"
        val result = requireNotNull(PaymentSceneAdapters.parse("com.ss.android.ugc.aweme", detail, now))
        assertEquals(1800L, result.amountCents)
        assertEquals("样例商铺", result.merchant)
        assertEquals("样例茶具", result.details.productDescription)
        assertEquals("抖音月付", result.details.fundingAccount)
        assertEquals(1200L, result.details.discountAmountCents)
        assertEquals("DX20261112000199", result.orderId)
        assertTrue(result.fieldEvidence.getValue("pageMapping").contains("实付价"))
        assertNull(PaymentSceneAdapters.parse("com.ss.android.ugc.aweme", detail.replace("支付成功", "退款处理中"), now))
    }

    @Test fun `checkout order lists promotions and conflicting results never generate a paid expense`() {
        val cases = listOf(
            "me.ele" to "下单成功，商家已接单\n订单金额：¥18.00\n查看订单详情",
            "com.taobao.taobao" to "下单成功，商家正在备餐\n预计送达19:30\n查看订单",
            "com.xunmeng.pinduoduo" to "拼单成功\n¥18.00\n查看订单详情",
            "com.smile.gifmaker" to "购买成功\n¥18.00",
            "com.smile.gifmaker" to "直播间\n购买成功\n¥18.00\n完成",
            "com.ss.android.ugc.aweme" to "购买成功\n券后价¥18.00\n完成",
            "com.ss.android.ugc.aweme" to "确认收货后付款\n支付成功\n实付价：¥18.00\n完成",
            "com.taobao.idlefish" to "确认订单\n现价¥18.00\n立即支付",
            "com.alibaba.wireless" to "付款成功：¥18.00\n付款失败\n返回首页",
            "com.jingdong.app.mall" to "我的订单\n付款成功：¥18.00\n查看订单",
            "com.wudaokou.hippo" to "支付成功\n返现¥3.00\n完成",
            "com.wudaokou.hippo" to "支付成功\n返现\n¥3.00\n完成",
        )
        for ((pkg, text) in cases) assertNull("$pkg: $text", PaymentSceneAdapters.parse(pkg, text, now))
        assertNull(PaymentSceneAdapters.parse("com.taobao.taobao", "购买成功\n¥\n18.00\n完成", now))
        assertNull(PaymentSceneAdapters.parse("com.eg.android.AlipayGphone", "购买成功\n¥\n18.00\n完成", now))
        val paidWithReward = "支付成功\n返现¥3.00\n实付金额：¥18.00\n完成"
        val receipt = requireNotNull(PaymentSceneAdapters.parse("com.wudaokou.hippo", paidWithReward, now))
        assertEquals(1800L, receipt.amountCents)
        assertNull(receipt.details.discountAmountCents)
    }

    @Test fun `product and remark text cannot supply app-specific completed labels or amounts`() {
        val quoted = "返回首页\n商品名称：购买成功\n备注：实付价：¥18.00"
        assertNull(PaymentSceneAdapters.parse("com.ss.android.ugc.aweme", quoted, now))
        val genuine = "购买成功\n商品名称：描述含实付价：¥12.00\n完成\n实付价：¥18.00"
        val result = requireNotNull(PaymentSceneAdapters.parse("com.ss.android.ugc.aweme", genuine, now))
        assertEquals(1800L, result.amountCents)
        assertTrue(result.details.productDescription.orEmpty().contains("¥12.00"))
    }

    @Test fun `a payment completion without amounts requires an independently recognized result`() {
        val app = "com.taobao.taobao"
        assertTrue(CommercePaymentPages.isPaymentCompletionWithoutAmount(app, "免密支付成功\n查看订单", null))
        assertTrue(CommercePaymentPages.isPaymentCompletionWithoutAmount("com.kuaishou.nebula", "购买成功\n完成", null))
        val excluded = listOf(
            "支付成功\n支付金额\n完成",
            "支付成功\n18.00\n完成",
            "支付成功\n原价：¥18.00\n完成",
            "支付成功\n优惠金额：3元\n完成",
            "支付成功\n不含税 ￥\n完成",
            "支付成功\n付款状态：待支付\n完成",
            "支付成功\n我的订单\n完成",
            "下单成功，商家已接单\n查看订单",
            "购买成功\n完成",
            "支付成功",
        )
        excluded.forEach { text ->
            assertEquals(text, false, CommercePaymentPages.isPaymentCompletionWithoutAmount(app, text, null))
        }
    }
}

package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentScreenNode
import com.pennypilot.app.data.parser.PaymentScreenStructure
import org.junit.Assert.*
import org.junit.Test

class CheckoutContextTest {
    private val pkg = "com.sankuai.meituan"
    private val order = "MT20261107001234"
    private val revision = 15L
    private val fields = listOf("确认订单", "提交订单", "商家订单号：$order", "应付金额：18.00元", "商户：街角面馆",
        "商品名称：红烧牛肉面", "数量：2", "收货人：测试姓名", "下单备注：送到测试路123号")

    private fun structure(lines: List<String>) = PaymentScreenStructure(lines.mapIndexed { i, text ->
        PaymentScreenNode(i + 1, null, text = text)
    })

    private fun preview(lines: List<String> = fields) = CheckoutContext.parse(pkg, lines.joinToString("\n"), structure(lines))

    private fun receipt(merchant: String? = null) = NotificationParseResult(1800, merchant, "EXPENSE",
        orderId = order, occurredAt = 500L, scene = "merchant_payment")

    @Test fun `same typed identity adds only missing local details to an already verified receipt`() {
        val checkout = CheckoutContext()
        assertTrue(checkout.remember(preview()!!, revision, 10L))
        val original = receipt().copy(details = PaymentReceiptDetails(productDescription = "凭证原商品"))
        val confirmed = structure(listOf("商户订单号：$order"))
        val enriched = checkout.enrich(pkg, confirmed, original, revision, 500L)
        assertEquals(original, enriched.copy(details = enriched.details.copy(context = emptyList())))
        assertEquals(listOf("数量"), enriched.details.context.map { it.label })
        assertEquals(CheckoutContext.LOCAL_SOURCE, enriched.details.context.single().source)
        assertEquals(original, checkout.enrich(pkg, confirmed, original, revision, 501L))
    }

    @Test fun `equal amount never bridges package type or identity and merchant conflict prevents enrichment`() {
        val checkout = CheckoutContext()
        val original = receipt()
        val confirmed = structure(listOf("商户订单号：$order"))
        assertTrue(checkout.remember(preview()!!, revision, 10L))
        assertEquals(original, checkout.enrich("com.sankuai.meituan.takeoutnew", confirmed, original, revision, 500L))
        assertEquals(original, checkout.enrich(pkg, structure(listOf("交易号：$order")), original, revision, 500L))
        assertEquals(original, checkout.enrich(pkg, structure(listOf("商户订单号：MT20261107009999")), original, revision, 500L))
        assertEquals(original.copy(amountCents = 1900), checkout.enrich(pkg, confirmed, original.copy(amountCents = 1900), revision, 500L))
        assertEquals(receipt("另一家店"), checkout.enrich(pkg, confirmed, receipt("另一家店"), revision, 500L))
        assertEquals(original, checkout.enrich(pkg, confirmed, original, revision, 501L))
    }

    @Test fun `redraw cannot prolong checkout context or survive a ledger revision change`() {
        val checkout = CheckoutContext()
        val confirmed = structure(listOf("商户订单号：$order"))
        val original = receipt()
        assertTrue(checkout.remember(preview()!!, revision, 10L))
        assertFalse(checkout.remember(preview()!!, revision, CheckoutContext.TTL_MS))
        assertEquals(original, checkout.enrich(pkg, confirmed, original, revision, CheckoutContext.TTL_MS + 10L))
        assertTrue(checkout.remember(preview()!!, revision, 1_000L))
        assertEquals(original, checkout.enrich(pkg, confirmed, original, revision + 1L, 1_100L))
    }

    @Test fun `multi order or sensitive surfaces do not cache and personal lines are excluded`() {
        assertNull(preview(fields + "订单号：MT20261107009999"))
        assertNull(preview(fields + "商户：另一家店"))
        assertNull(preview(fields + "请输入支付密码"))
        assertNull(preview(fields.filterNot { it.startsWith("商家订单号") } + "交易号：$order"))
        assertNull(preview(fields + listOf("应付金额", "19.00")))
        assertEquals(setOf("商品名称", "数量"), preview()!!.fields.map { it.label }.toSet())
    }

    @Test fun `direct wallet handoff requires same order amount and explicit identical merchant`() {
        val checkout = CheckoutContext()
        val wallet = "com.eg.android.AlipayGphone"
        val original = receipt("街角面馆")
        assertTrue(checkout.remember(preview()!!, revision, 10L))
        checkout.onWindowChanged(wallet, revision, 500L)
        val withoutMerchant = structure(listOf("商户订单号：$order"))
        assertEquals(original, checkout.enrich(wallet, withoutMerchant, original, revision, 600L))
        val withMerchant = structure(listOf("商户订单号：$order", "商户名称：街角面馆"))
        val enriched = checkout.enrich(wallet, withMerchant, original, revision, 800L)
        assertTrue(enriched.details.context.any { it.value == "红烧牛肉面" })
        assertEquals(original, enriched.copy(details = enriched.details.copy(context = emptyList())))
    }

    @Test fun `wallet handoff cannot match platform names conflicts or non adjacent supported windows`() {
        val checkout = CheckoutContext()
        val wallet = "com.eg.android.AlipayGphone"
        val original = receipt()
        val withMerchant = structure(listOf("商户订单号：$order", "商户名称：街角面馆"))
        assertTrue(checkout.remember(preview(fields.filterNot { it.startsWith("商户：") } + "商户：美团")!!, revision, 10L))
        checkout.onWindowChanged(wallet, revision, 500L)
        assertEquals(original, checkout.enrich(wallet, withMerchant, original, revision, 600L))
        assertTrue(checkout.remember(preview()!!, revision, 1_000L))
        checkout.onWindowChanged(wallet, revision, 1_500L)
        val conflict = structure(listOf("商户订单号：$order", "商户名称：街角面馆分店"))
        assertEquals(original, checkout.enrich(wallet, conflict, original, revision, 1_600L))
        assertEquals(original, checkout.enrich(wallet, withMerchant, original, revision, 1_700L))
        assertTrue(checkout.remember(preview()!!, revision, 2_000L))
        checkout.onWindowChanged("com.tencent.mm", revision, 2_500L)
        checkout.onWindowChanged(wallet, revision, 3_000L)
        assertEquals(original, checkout.enrich(wallet, withMerchant, original, revision, 3_100L))
    }

    @Test fun `live wallet notice requires both posting clocks and explicit identity before consuming checkout`() {
        val checkout = CheckoutContext()
        val wallet = "com.tencent.mm"
        val parsed = receipt("街角面馆")
        assertTrue(checkout.remember(preview()!!, revision, 100L))
        checkout.onWindowChanged(wallet, revision, 500L, wallAt = 10_500L)
        val notice = CapturedNotification(wallet, "checkout-notice", 10_600L, "微信支付",
            "支付成功\n实付18.00元\n商户名称：街角面馆\n商户订单号：$order")
        val arrival = CheckoutContext.LiveNotification(10_700L, 700L)
        assertNull(checkout.enrichNotification(notice.copy(postedAt = 10_499L), parsed, arrival, revision, 800L))
        assertNull(checkout.enrichNotification(notice, parsed, arrival.copy(wallAt = 11_000L), revision, 800L))
        assertNull(checkout.enrichNotification(notice.copy(content = notice.content.replace("商户订单号", "交易号")),
            parsed, arrival, revision, 800L))
        assertNull(checkout.enrichNotification(notice.copy(content = notice.content.replace("商户名称：街角面馆\n", "")),
            parsed, arrival, revision, 800L))
        val accepted = checkout.enrichNotification(notice, parsed, arrival, revision, 800L)!!
        assertTrue(accepted.receipt.details.context.any { it.value == "红烧牛肉面" })
        assertEquals(parsed, accepted.receipt.copy(details = accepted.receipt.details.copy(context = emptyList())))
        assertTrue(checkout.remainsAuthorized(accepted.authorization))
        checkout.clearAuthorization()
        assertFalse(checkout.remainsAuthorized(accepted.authorization))
    }

    @Test fun `checkout completion preserves local item facts for every shopping family`() {
        val shopping = PaymentAppRegistry.apps.filter { it.family != PaymentAppRegistry.Family.WALLET }
        assertEquals(17, shopping.size)
        for (app in shopping) {
            val boxHorse = app.packageName == "com.wudaokou.hippo"
            val activity = if (boxHorse) "com.wudaokou.hippo.pay.PayCodeActivity" else null
            val lines = when (app.packageName) {
                "com.wudaokou.hippo" -> listOf("付款码", "优先使用此付款方式：花呗")
                "com.jingdong.app.mall" -> listOf("填写订单", "提交订单", "应付金额", "¥", "18.00",
                    "商品名称", "便携桌灯", "商品规格", "暖白")
                else -> listOf("确认订单", "提交订单", "应付金额：18.00元",
                    "商品名称：便携桌灯", "商品规格：暖白")
            }
            val context = CheckoutContext()
            val preview = CheckoutContext.parse(app.packageName, lines.joinToString("\n"), structure(lines), activity)
            assertNotNull(app.name, preview)
            assertTrue(context.remember(preview!!, revision, 100L, windowId = 7))
            val original = receipt().copy(orderId = null)
            val result = context.enrich(app.packageName, structure(listOf("支付成功")), original,
                revision, 700L, windowId = 7, liveResult = true)
            assertEquals(app.name, 1800L, result.amountCents)
            assertNull(app.name, result.orderId)
            assertEquals(app.name, if (boxHorse) setOf("花呗") else setOf("便携桌灯", "暖白"),
                result.details.context.map { it.value }.toSet())
            assertTrue(result.details.context.all { it.source == CheckoutContext.LOCAL_SOURCE })
            assertEquals(original, context.enrich(app.packageName, structure(listOf("支付成功")), original,
                revision, 701L, windowId = 7, liveResult = true))
        }
    }

    @Test fun `idless checkout requires a live payment with matching amount and cannot enrich another app`() {
        val checkout = CheckoutContext()
        val noId = fields.filterNot { it.startsWith("商家订单号") }
        val original = receipt().copy(orderId = null)
        val snapshot = structure(listOf("支付成功"))
        val preview = preview(noId)!!
        checkout.remember(preview, revision, 10L, windowId = 7)
        assertEquals(original, checkout.enrich(pkg, snapshot, original, revision, 600L, windowId = 7))
        assertEquals(original, checkout.enrich(pkg, snapshot, original, revision, 600L, windowId = 8, liveResult = true))
        val otherAmount = original.copy(amountCents = 1801)
        assertEquals(otherAmount, checkout.enrich(pkg, snapshot, otherAmount, revision, 601L, windowId = 7, liveResult = true))
        checkout.onWindowChanged("com.eg.android.AlipayGphone", revision, 602L)
        assertEquals(original, checkout.enrich("com.eg.android.AlipayGphone", snapshot, original,
            revision, 603L, windowId = 7, liveResult = true))
    }

    @Test fun `different window requires matching merchant and product for an unnumbered checkout`() {
        val preview = preview(fields.filterNot { it.startsWith("商家订单号") })!!
        val checkout = CheckoutContext()
        checkout.remember(preview, revision, 10L, windowId = 7)
        val original = receipt("街角面馆").copy(orderId = null)
        val merchantOnly = structure(listOf("商户名称：街角面馆"))
        assertEquals(original, checkout.enrich(pkg, merchantOnly, original, revision, 600L, windowId = 8, liveResult = true))
        val receiptWithProduct = structure(listOf("商户名称：街角面馆", "商品名称：红烧牛肉面"))
        val enriched = checkout.enrich(pkg, receiptWithProduct, original, revision, 601L, windowId = 8, liveResult = true)
        assertEquals(setOf("红烧牛肉面", "2"), enriched.details.context.map { it.value }.toSet())
    }

    @Test fun `idless checkout clears after cancel navigation and conflicting repeated totals`() {
        val checkout = CheckoutContext()
        val noId = fields.filterNot { it.startsWith("商家订单号") }
        val preview = preview(noId)!!
        val original = receipt().copy(orderId = null)
        val snapshot = structure(listOf("支付成功"))
        fun enrich(now: Long) = checkout.enrich(pkg, snapshot, original, revision, now, windowId = 7, liveResult = true)
        checkout.remember(preview, revision, 10L, windowId = 7)
        checkout.observeNonResult(pkg, "付款取消", revision, 20L)
        assertEquals(original, enrich(21L))
        checkout.remember(preview, revision, 30L, windowId = 7)
        checkout.observeNonResult(pkg, "正在加载", revision, 40L)
        assertFalse(checkout.remember(preview.copy(amountCents = 1900), revision, 50L, windowId = 7))
        assertEquals(original, enrich(51L))
        checkout.observeNonResult(pkg, "我的订单", revision, 60L)
        checkout.remember(preview, revision, 70L, windowId = 7)
        assertEquals(original, enrich(70L + CheckoutContext.retentionMillis(preview)))
    }

    @Test fun `same platform order identity can carry a local merchant but cannot enter wallet handoff`() {
        val context = CheckoutContext()
        val merchant = "街角面馆"
        val genericOrder = fields.map { it.replace("商家订单号", "订单号") }
        val preview = preview(genericOrder)!!
        assertEquals(order, preview.platformOrderId)
        assertNull(preview.merchantOrderId)
        val original = receipt()
        val result = structure(listOf("订单号：$order"))
        context.remember(preview, revision, 100L, windowId = 7)
        val enriched = context.enrich(pkg, result, original, revision, 150L, windowId = 8, liveResult = true)
        assertNull(enriched.merchant)
        assertTrue(enriched.details.context.any { it.label == "结账页商户" && it.value == merchant })
        context.remember(preview, revision, 200L, windowId = 7)
        val wallet = "com.eg.android.AlipayGphone"
        context.onWindowChanged(wallet, revision, 250L)
        assertEquals(original, context.enrich(wallet, result, original, revision, 300L, windowId = 8, liveResult = true))
    }

    @Test fun `successful amountless result can seed only a local review in the same window`() {
        val checkout = CheckoutContext()
        val noId = preview(fields.filterNot { it.startsWith("商家订单号") })!!
        checkout.remember(noId, revision, 100L, windowId = 5)
        val completed = "支付成功\n完成"
        assertNull(checkout.pendingResult(pkg, completed, revision, 110L, windowId = 6))
        assertNull(checkout.pendingResult("com.eg.android.AlipayGphone", completed, revision, 110L, windowId = 5))
        for (text in listOf("下单成功，商家已接单\n完成", "支付成功\n完成\n支付失败", "支付成功\n完成\n点击领取3元红包",
            "确认订单\n立即支付", "支付成功\n实付18元\n完成", "购买成功\n完成")) {
            assertNull(text, checkout.pendingResult(pkg, text, revision, 110L, windowId = 5))
        }
        val draft = checkout.pendingResult(pkg, completed, revision, 120L, windowId = 5)!!
        assertEquals(1800L, draft.amountCents)
        assertNull(draft.orderId)
        assertNull(draft.merchant)
        assertEquals("checkout_payment_review", draft.scene)
        assertTrue(draft.fieldEvidence.keys.containsAll(listOf("reviewRequired", "checkoutAmountEstimate")))
        assertFalse(draft.fieldEvidence.keys.any { it in setOf("amount", "paymentStatus") })
        assertEquals(draft, checkout.pendingResult(pkg, completed, revision, 130L, windowId = 5))
        checkout.consumePendingResult(pkg, revision, windowId = 5)
        assertNull(checkout.pendingResult(pkg, completed, revision, 140L, windowId = 5))
    }
}

package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
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
}

package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentReceiptContext
import org.junit.Assert.*
import org.junit.Test

class CaptureSemanticResolverTest {
    private val categories = listOf(
        CaptureSemanticCategory(1, "餐饮", "EXPENSE"),
        CaptureSemanticCategory(2, "其他", "EXPENSE"),
        CaptureSemanticCategory(3, "工资", "INCOME"),
        CaptureSemanticCategory(4, "红包", "INCOME"),
        CaptureSemanticCategory(5, "人情", "EXPENSE"),
        CaptureSemanticCategory(6, "购物", "EXPENSE")
    )
    private val context = CaptureSemanticContext("2026-11-01T14:00:00+08:00", categories)
    private fun parsed(merchant: String? = "小林", direction: String = "EXPENSE", scene: String? = null) =
        NotificationParseResult(1700, merchant, direction, scene = scene)

    @Test fun `model other and historical other cannot mask specific current receipt`() {
        val history = List(2) { CaptureSemanticHistory("小林", "EXPENSE", "expense", 2) }
        val receipt = parsed().copy(details = PaymentReceiptDetails(productDescription = "鸡腿饭"))
        val classified = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "支付成功17元",
            context.copy(relatedHistory = history), PaymentProposal(businessKind = "expense", categoryId = 2))
        assertEquals(1L, classified.categoryId)
        assertFalse(classified.categoryIsFallback)
        val sparse = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "支付成功17元",
            context.copy(relatedHistory = history))
        assertEquals(2L, sparse.categoryId)
        assertTrue(sparse.categoryIsFallback)
        assertFalse(sparse.assumptions.any { it.contains("稳定记账习惯") })
    }

    @Test fun `ordinary outgoing transfer to a counterparty is an expense with its actual funding account`() {
        val receipt = parsed(scene = "transfer_sent").copy(details = PaymentReceiptDetails(
            payeeName = "小林", remark = "今天还款", fundingAccount = "零钱通"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "转账成功 17元\n收款方：小林\n付款方式：零钱通", context)
        assertEquals("expense", result.businessKind)
        assertEquals(2L, result.categoryId)
        assertEquals("零钱通", result.accountTag)
        assertTrue(result.autoEligible)
        assertEquals("备注：今天还款", result.note)
    }

    @Test fun `ordinary incoming transfer uses the known direction and unknown counterpart waits for review`() {
        val receipt = parsed(direction = "INCOME", scene = "transfer_received")
        val incoming = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "收到小林的转账17元", context)
        assertEquals("income", incoming.businessKind)
        assertTrue(incoming.autoEligible)
        val incomplete = CaptureSemanticResolver.resolve(parsed(merchant = null, scene = "transfer_sent"),
            "com.tencent.mm", "转账成功 17元", context)
        assertEquals("unknown", incomplete.businessKind)
        assertFalse(incomplete.autoEligible)
        assertTrue(incomplete.assumptions.any { it.contains("用途待确认") })
    }

    @Test fun `old model defaults and transfer history cannot label a peer payment as an internal move`() {
        val receipt = parsed(scene = "transfer_sent").copy(businessKind = "transfer")
        val history = List(2) { CaptureSemanticHistory("小林", "EXPENSE", "transfer", 2) }
        val oldDefault = PaymentProposal(businessKind = "transfer", note = "本人账户互转",
            assumptions = listOf("不计入消费"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "转账成功 17元", context.copy(relatedHistory = history), oldDefault)
        assertEquals("expense", result.businessKind)
        assertNull(result.note)
        assertFalse(result.assumptions.any { it.contains("不计入消费") })
    }

    @Test fun `merchant consumption and salary classify without another confirmation`() {
        val meal = CaptureSemanticResolver.resolve(parsed("盖饭店"), "com.tencent.mm", "支付成功17元", context)
        assertEquals("expense", meal.businessKind)
        assertEquals(1L, meal.categoryId)
        val salary = CaptureSemanticResolver.resolve(parsed("工资代发", "INCOME"), "com.eg.android.AlipayGphone", "到账成功", context)
        assertEquals("income", salary.businessKind)
        assertEquals(3L, salary.categoryId)
        assertEquals("支付宝", salary.accountTag)
    }

    @Test fun `receipt keywords match common equivalent category names in existing ledgers`() {
        val categories = listOf(CaptureSemanticCategory(20, "其他", "EXPENSE"),
            CaptureSemanticCategory(21, "餐饮美食", "EXPENSE"),
            CaptureSemanticCategory(22, "交通出行", "EXPENSE"))
        val context = CaptureSemanticContext(allowedCategories = categories)
        val result = CaptureSemanticResolver.resolve(parsed("示例面馆"), "com.tencent.mm", "支付成功17元", context)
        assertEquals(21L, result.categoryId)
        assertFalse(result.categoryIsFallback)
    }

    @Test fun `self transfers stored value and credit card repayment do not become expenses`() {
        listOf("转给自己的支付宝", "转账给自己", "收款方：本人账户", "本人账户互转", "余额充值", "信用卡还款成功").forEach { description ->
            val result = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", description, context)
            assertEquals(description, "transfer", result.businessKind)
            assertTrue(result.autoEligible)
        }
    }

    @Test fun `payment channel and status cannot establish a peer transfer counterparty`() {
        listOf("微信支付", "转账成功", "对方").forEach { label ->
            val result = CaptureSemanticResolver.resolve(parsed(label, scene = "transfer_sent"),
                "com.tencent.mm", "转账成功17元", context)
            assertEquals("unknown", result.businessKind)
            assertFalse(result.autoEligible)
        }
    }

    @Test fun `incoming transfer payee is not evidence of an external sender`() {
        val receipt = parsed("示例收款人", "INCOME", "transfer_received")
            .copy(details = PaymentReceiptDetails(payeeName = "示例收款人"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "转账到账17元", context)
        assertEquals("unknown", result.businessKind)
        assertFalse(result.autoEligible)
        val withSender = CaptureSemanticResolver.resolve(receipt.copy(merchant = "示例付款人"),
            "com.tencent.mm", "转账到账17元", context)
        assertEquals("income", withSender.businessKind)
        assertTrue(withSender.autoEligible)
    }

    @Test fun `loans advances reimbursements and refunds retain their accounting meaning`() {
        val cases = listOf(
            Triple("帮小林垫付", "EXPENSE", "receivable"),
            Triple("借给小林", "EXPENSE", "receivable"),
            Triple("借款到账", "INCOME", "payable"),
            Triple("报销到账", "INCOME", "transfer"),
            Triple("退款到账", "INCOME", "refund")
        )
        cases.forEach { (text, direction, expected) ->
            assertEquals(text, expected, CaptureSemanticResolver.resolve(parsed(direction = direction), "com.tencent.mm", text, context).businessKind)
        }
    }

    @Test fun `red packet personal receipt label is not mistaken for a self transfer`() {
        val received = CaptureSemanticResolver.resolve(parsed("小林", "INCOME", "red_packet_received"), "com.tencent.mm", "本人领取金额17元", context)
        assertEquals("income", received.businessKind)
        val sent = CaptureSemanticResolver.resolve(parsed(scene = "red_packet_sent"), "com.tencent.mm", "红包发送成功", context)
        assertEquals("expense", sent.businessKind)
        assertEquals(5L, sent.categoryId)
    }

    @Test fun `stable same merchant history supplies meaning category and account without model recommendation`() {
        val history = List(3) { CaptureSemanticHistory("小林", "EXPENSE", "expense", 6, "招商银行") }
        val resolution = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history),
            PaymentProposal())
        assertEquals("expense", resolution.businessKind)
        assertEquals(6L, resolution.categoryId)
        assertEquals("招商银行", resolution.accountTag)
        assertTrue(resolution.assumptions.any { it.contains("稳定记账习惯") })
    }

    @Test fun `current AI context can change a previous merchant habit and a refund overrides income preference`() {
        val history = List(3) { CaptureSemanticHistory("小林", "EXPENSE", "transfer", 2) }
        val meal = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history),
            PaymentProposal(businessKind = "expense", categoryId = 1, note = "本次聚餐AA"))
        assertEquals("expense", meal.businessKind)
        assertEquals(1L, meal.categoryId)
        val refund = CaptureSemanticResolver.resolve(parsed(direction = "INCOME").copy(businessKind = "refund"),
            "com.tencent.mm", "退款已到账17元", context.copy(preferences = listOf(
                CaptureSemanticPreference("小林", "INCOME", businessKind = "income"))))
        assertEquals("refund", refund.businessKind)
    }

    @Test fun `explicit preference overrides model and mixed history without importing unrelated people`() {
        val history = listOf(
            CaptureSemanticHistory("小林", "EXPENSE", "expense", 1),
            CaptureSemanticHistory("小林", "EXPENSE", "transfer", 2),
            CaptureSemanticHistory("小王", "EXPENSE", "receivable", 6)
        )
        val preference = CaptureSemanticPreference("小林", "EXPENSE", 6, "receivable", "建设银行")
        val result = CaptureSemanticResolver.resolve(parsed(), "com.tencent.mm", "转账成功17元", context.copy(relatedHistory = history, preferences = listOf(preference)),
            PaymentProposal(businessKind = "expense", categoryId = 1, accountTag = "零钱"))
        assertEquals("receivable", result.businessKind)
        assertEquals(6L, result.categoryId)
        assertEquals("建设银行", result.accountTag)
    }

    @Test fun `model semantic inference does not require verbatim purpose quotes or invented categories`() {
        val result = CaptureSemanticResolver.resolve(parsed().copy(paymentMethod = "零钱"), "com.tencent.mm", "转账成功17元", context,
            PaymentProposal(businessKind = "expense", categoryId = 999, accountTag = "银行卡", note = "晚餐AA", assumptions = listOf("同伴聚餐分摊")))
        assertEquals("expense", result.businessKind)
        assertEquals(2L, result.categoryId)
        assertEquals("零钱", result.accountTag)
        assertEquals("晚餐AA", result.note)
        assertTrue(result.assumptions.contains("同伴聚餐分摊"))
    }

    @Test fun `semantic context remains bounded`() {
        val bounded = CaptureSemanticContext(
            "x".repeat(1000), List(200) { CaptureSemanticCategory(it.toLong(), "n".repeat(1000), "EXPENSE") },
            List(100) { CaptureSemanticHistory("m".repeat(1000), "EXPENSE", "expense", 1, "a".repeat(1000), "n".repeat(1000)) },
            List(100) { CaptureSemanticPreference("m".repeat(1000), "EXPENSE", 1) }
        ).bounded()
        assertEquals(100, bounded.allowedCategories.size)
        assertEquals(12, bounded.relatedHistory.size)
        assertEquals(12, bounded.preferences.size)
        assertEquals(80, bounded.deviceTime.length)
        assertEquals(120, bounded.relatedHistory.first().note!!.length)
    }

    @Test fun `receipt product and remark inform category and note while funding account outranks channel`() {
        val receipt = parsed("小林").copy(paymentMethod = "微信支付",
            details = PaymentReceiptDetails(productDescription = "鸡腿饭套餐", remark = "少辣",
                paymentChannel = "微信支付", fundingAccount = "招商银行储蓄卡(1234)"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "支付成功17元", context)
        assertEquals("expense", result.businessKind)
        assertEquals(1L, result.categoryId)
        assertEquals("招商银行储蓄卡(1234)", result.accountTag)
        assertEquals("商品：鸡腿饭套餐；备注：少辣", result.note)
        assertFalse(result.assumptions.any { it.contains("支付账户") })
        assertEquals(1700L, receipt.amountCents)
        assertEquals("EXPENSE", receipt.direction)
    }

    @Test fun `receipt detail context retains model summary and adds uncovered details without inventing an account`() {
        val receipt = parsed("小林").copy(details = PaymentReceiptDetails(
            productDescription = "聚餐套餐", remark = "同事AA", paymentChannel = "微信"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "支付成功17元", context,
            PaymentProposal(businessKind = "expense", categoryId = 1, note = "工作日晚餐AA"))
        assertEquals("工作日晚餐AA；商品：聚餐套餐；备注：同事AA", result.note)
        assertEquals("微信", result.accountTag)
        assertNull(receipt.details.fundingAccount)
    }

    @Test fun `late store name informs category while the original payee remains the transaction identity`() {
        val receipt = parsed("晨光经营有限公司").copy(details = PaymentReceiptDetails(
            merchantName = "街角面馆", payeeName = "晨光经营有限公司"))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "支付成功17元", context)
        assertEquals(1L, result.categoryId)
        assertEquals("expense", result.businessKind)
        assertEquals("晨光经营有限公司", receipt.merchant)
    }

    @Test fun `AI meaning takes precedence over broad consumption keywords while explicit scenes remain authoritative`() {
        val receipt = parsed("小林", scene = "merchant_payment").copy(details = PaymentReceiptDetails(
            productDescription = "午餐套餐", context = listOf(PaymentReceiptContext("用途", "替同事垫付", "页面"))))
        val result = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "支付成功17元", context,
            PaymentProposal(businessKind = "receivable", categoryId = 1, note = "替同事垫付午餐"))
        assertEquals("receivable", result.businessKind)
        assertEquals(1L, result.categoryId)
        assertEquals("替同事垫付午餐；商品：午餐套餐", result.note)
        val redPacket = CaptureSemanticResolver.resolve(parsed(direction = "INCOME", scene = "red_packet_received"),
            "com.tencent.mm", "收款成功17元", context, PaymentProposal(businessKind = "transfer"))
        assertEquals("income", redPacket.businessKind)
        val selfTransfer = CaptureSemanticResolver.resolve(parsed(scene = "account_transfer"),
            "com.tencent.mm", "支付成功17元", context, PaymentProposal(businessKind = "expense"))
        assertEquals("transfer", selfTransfer.businessKind)
        val retainedScene = CaptureSemanticResolver.resolve(parsed(direction = "INCOME", scene = "payment_received"),
            "com.tencent.mm", "收款成功17元", context.copy(receipt = CaptureSemanticReceipt(scene = "red_packet_received")),
            PaymentProposal(businessKind = "transfer"))
        assertEquals("income", retainedScene.businessKind)
    }

    @Test fun `captured semantic snapshot informs classification and rich note without modifying parsed facts`() {
        val receipt = parsed("经营公司")
        val snapshot = CaptureSemanticReceipt(details = mapOf("productDescription" to "牛肉面", "remark" to "少辣"),
            context = listOf(PaymentReceiptContext("门店名称", "街角面馆", "本地OCR")))
        val local = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "转账成功17元", context.copy(receipt = snapshot))
        assertEquals(1L, local.categoryId)
        assertEquals("商品：牛肉面；备注：少辣", local.note)
        val modeled = CaptureSemanticResolver.resolve(receipt, "com.tencent.mm", "转账成功17元", context.copy(receipt = snapshot),
            PaymentProposal(businessKind = "expense", categoryId = 1, note = "街角面馆午餐，牛肉面，少辣"))
        assertEquals("街角面馆午餐，牛肉面，少辣", modeled.note)
        assertTrue(receipt.details.isEmpty())
        assertEquals(1700L, receipt.amountCents)
    }

    @Test fun `receipt snapshot bounds and redacts identifying values while retaining semantic context`() {
        val receipt = CaptureSemanticReceipt(
            details = mapOf("merchantName" to "街角面馆", "fundingAccount" to "招商银行(1234) 6222021234567890123",
                "remark" to "联系13800138000，订单号ORDER1234567890AB，邮箱hello@example.invalid", "rawPage" to "不得保留的整页内容"),
            context = listOf(PaymentReceiptContext("订单号", "ORDER1234567890AB", "页面")) +
                List(30) { PaymentReceiptContext("附加字段$it", "明细".repeat(250), "页面") },
            occurredAt = "2026-11-07T12:30:00+08:00", scene = "merchant_payment")
        val bounded = context.copy(receipt = receipt).bounded().receipt!!
        val serialized = kotlinx.serialization.json.Json.encodeToString(CaptureSemanticReceipt.serializer(), bounded)
        listOf("6222021234567890123", "13800138000", "ORDER1234567890AB", "hello@example.invalid", "不得保留的整页内容")
            .forEach { assertFalse(it, serialized.contains(it)) }
        assertTrue(serialized.contains("招商银行(1234)"))
        assertTrue(serialized.contains("2026-11-07T12:30:00+08:00"))
        assertTrue(bounded.context.size <= 16)
        assertTrue(bounded.context.all { it.label.length <= 40 && it.value.length <= 300 })
        assertTrue(bounded.context.sumOf { it.label.length + it.value.length } <= 2400)
        assertEquals(bounded, bounded.bounded())
    }

    @Test fun `rich note reserves space for uncovered receipt details within five hundred characters`() {
        val result = CaptureSemanticResolver.resolve(parsed().copy(details = PaymentReceiptDetails(
            productDescription = "牛肉面", remark = "不要香菜")), "com.tencent.mm", "转账成功17元", context,
            PaymentProposal(businessKind = "expense", note = "长摘要".repeat(200)))
        assertTrue(result.note!!.length <= 500)
        assertTrue(result.note!!.startsWith("长摘要"))
        assertTrue(result.note!!.contains("商品：牛肉面"))
        assertTrue(result.note!!.contains("备注：不要香菜"))
    }
}

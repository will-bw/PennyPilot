package com.pennypilot.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WalletPaymentPageAdapterTest {
    private val unionPay = "com.unionpay"
    private val huawei = "com.huawei.wallet"
    private val ccb = "com.ccb.longjiLife"
    private val jdFinance = "com.jd.jrapp"
    private val time = "2025-10-24 16:58:30"
    private val occurredAt = LocalDateTime.of(2025, 10, 24, 16, 58, 30)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val now = occurredAt + 86_400_000
    private val id = "20251024000012345678"

    @Test fun `unionpay branded details parse true debit suffix and both time forms without reusing list titles`() {
        val page = """
            云闪付交易详情
            当前状态：交易成功
            支出金额
            18.00
            商户名称：样例商家
            付款卡
            招商银行 6225 8000 1234 5678
            扣款时间
            延迟扣费|2025.10.24 16:58:30
            交易单号：$id
        """.trimIndent()
        for (title in listOf("云闪付交易详情", "银联交易详情", "交易详情")) {
            val result = parse(unionPay, page.replace("云闪付交易详情", title))
            assertEquals(1800L, result?.amountCents)
            assertEquals("EXPENSE", result?.direction)
            assertEquals("样例商家", result?.merchant)
            assertEquals("招商银行 尾号5678", result?.details?.fundingAccount)
            assertEquals(occurredAt, result?.occurredAt)
            assertEquals(id, result?.orderId)
            assertFalse(result!!.fieldEvidence.values.any { "6225" in it })
        }
        assertNull(parse("com.tencent.mm", page))
        assertNull(parse(unionPay, page.replace("云闪付交易详情", "账单列表")))
        assertNull(parse(unionPay, page.replace("支出金额", "订单金额")))
        assertNull(parse(unionPay, page.replace("交易成功", "交易失败")))
        assertNull(parse(unionPay, page + "\n实付金额：99.00元"))
        val inlineCard = page.replace("付款卡\n", "付款卡：")
        assertEquals("招商银行 尾号5678", parse(unionPay, inlineCard)?.details?.fundingAccount)
        assertFalse(parse(unionPay, inlineCard)!!.fieldEvidence.values.any { "6225" in it })
    }

    @Test fun `unionpay receipts separate signed incoming and refund amounts from original purchase`() {
        val receipt = """
            银联交易详情
            当前状态：收款成功
            实收金额（元）：88.00
            商户名称：样例门店
            收款卡：中国银行储蓄卡尾号1234
            交易时间：$time
            交易单号：$id
        """.trimIndent()
        val incoming = parse(unionPay, receipt)
        assertEquals("INCOME", incoming?.direction)
        assertEquals(8800L, incoming?.amountCents)
        assertEquals("中国银行储蓄卡尾号1234", incoming?.details?.transferToAccount)
        val refund = """
            银联交易详情
            退款成功
            退款金额：25.00元
            原支付金额：88.00元
            商户名称：样例门店
            退款时间：$time
            退款单号：RF202510240001
            交易单号：$id
        """.trimIndent()
        val returned = parse(unionPay, refund)
        assertEquals("INCOME", returned?.direction)
        assertEquals(2500L, returned?.amountCents)
        assertEquals("RF202510240001", returned?.orderId)
        assertNull(parse(unionPay, refund.replace("退款成功", "退款受理")))
    }

    @Test fun `unionpay self transfer remains net zero and confirmation cannot become a payment`() {
        val receipt = """
            银联交易详情
            转出成功
            转给本人
            转账金额：88.00元
            付款卡：招商银行储蓄卡尾号1234
            收款卡：中国银行储蓄卡尾号5678
            交易时间：$time
            交易单号：$id
        """.trimIndent()
        val transfer = parse(unionPay, receipt)
        assertEquals("account_transfer", transfer?.scene)
        assertEquals("transfer", transfer?.businessKind)
        assertEquals(8800L, transfer?.amountCents)
        assertEquals("招商银行储蓄卡尾号1234", transfer?.details?.transferFromAccount)
        assertEquals("中国银行储蓄卡尾号5678", transfer?.details?.transferToAccount)
        assertNull(parse(unionPay, receipt.replace("转出成功", "确认转账")))
    }

    @Test fun `huawei dated bill reads the account and ignores wallet payment code screens`() {
        val page = """
            账单详情
            -18.00
            交易成功
            商家
            样例商家
            商品说明
            早餐
            付款银行
            交通银行信用卡(8888)
            日期
            $time
            交易单号：$id
        """.trimIndent()
        val result = parse(huawei, page)
        assertEquals("样例商家", result?.merchant)
        assertEquals("早餐", result?.details?.productDescription)
        assertEquals("交通银行信用卡(8888)", result?.details?.fundingAccount)
        assertEquals(occurredAt, result?.occurredAt)
        assertEquals(id, result?.orderId)
        assertEquals(1800L, result?.amountCents)
        assertNull(parse(huawei, "二维码付款码\n交通银行信用卡(8888)\n¥\n18.00\n请出示付款码"))
        assertNull(parse(huawei, page.replace("交易成功", "待付款")))
        assertNull(parse(huawei, page.replace(time, "2032-10-24 16:58:30")))
    }

    @Test fun `ccb signed bill identifies the merchant and leaves a durable identityless receipt for review`() {
        val page = """
            交易详情
            -18.00
            交易成功
            商户名
            样例烘焙
            支付方式
            建设银行
            交易时间
            2025/10/24 16:58:30
        """.trimIndent()
        val fallback = parse(ccb, page)
        assertEquals(1800L, fallback?.amountCents)
        assertEquals("样例烘焙", fallback?.merchant)
        assertEquals(occurredAt, fallback?.occurredAt)
        assertNull(fallback?.orderId)
        assertTrue(fallback!!.fieldEvidence.containsKey("reviewRequired"))
        val identified = parse(ccb, "$page\n交易单号：$id")
        assertEquals(id, identified?.orderId)
        assertFalse(identified!!.fieldEvidence.containsKey("reviewRequired"))
        assertNull(parse(ccb, "$page\n交易单号：$id\n交易单号：20251024000000001111"))
        assertNull(parse(ccb, page.replace("-18.00", "-18.00\n-19.00")))
        assertNull(parse(ccb, page.replace("交易成功", "确认付款")))
    }

    @Test fun `huawei and ccb exact receipt activities qualify their titleless success screens only`() {
        val huaweiDetail = "交易成功\n-¥\n18.00\n商家\n样例商家\n日期\n$time\n付款银行\n信用卡8888"
        val huaweiActivity = "com.huawei.wallet.nfc.bankcard.transactionhistory.ui.activity.UnCardTransRecordDetailActivity"
        val payment = PaymentSceneAdapters.parse(huawei, huaweiDetail, now, activityName = huaweiActivity)
        assertEquals(1800L, payment?.amountCents)
        assertEquals(occurredAt, payment?.occurredAt)
        assertEquals("样例商家", payment?.merchant)
        assertTrue(payment!!.fieldEvidence.containsKey("reviewRequired"))
        assertNull(PaymentSceneAdapters.parse(huawei, huaweiDetail, now))
        assertTrue(PaymentSceneAdapters.isDetailPage(huawei, "付款成功\n¥18.00", huaweiActivity))
        assertNull(PaymentSceneAdapters.parse(huawei, "付款成功\n¥18.00", now, activityName = huaweiActivity))
        assertNull(PaymentSceneAdapters.parse(huawei, huaweiDetail.replace("交易成功", "交易处理中"), now,
            activityName = huaweiActivity))

        val ccbResult = "支付成功\n¥\n18.00\n商户名\n样例书店"
        val ccbActivity = "com.ccb.cloudmerchant.wallet.newOne.view.CodeResultActivity"
        val ccbPayment = PaymentSceneAdapters.parse(ccb, ccbResult, now, activityName = ccbActivity)
        assertEquals("样例书店", ccbPayment?.merchant)
        assertEquals(1800L, ccbPayment?.amountCents)
        assertNull(PaymentSceneAdapters.parse(ccb, "去付款\n¥\n18.00\n商户名\n样例书店",
            now, activityName = ccbActivity))
    }

    @Test fun `jd finance signed headline uses actual time first and creation time only as a dated draft`() {
        val page = """
            样例书店
            -18.00
            交易成功
            支付方式：招商银行储蓄卡(1234)
            创建时间
            2025-10-24
            16:58:30
            支付立减：2.00元
            交易说明：图书订单
            订单号：$id
        """.trimIndent()
        val approximate = parse(jdFinance, page)
        assertEquals(1800L, approximate?.amountCents)
        assertEquals("样例书店", approximate?.merchant)
        assertEquals(200L, approximate?.details?.discountAmountCents)
        assertEquals(occurredAt, approximate?.occurredAt)
        assertTrue(approximate!!.occurredAtIsCreationTime)
        val exact = parse(jdFinance, "$page\n支付时间：2025-10-24 16:58:40")
        assertEquals(occurredAt + 10_000, exact?.occurredAt)
        assertFalse(exact!!.occurredAtIsCreationTime)
        assertNull(parse(jdFinance, "$page\n支付时间：2025-20-24 16:58:40"))
        assertNull(parse(jdFinance, page.replace("交易成功", "等待收货")))
    }

    @Test fun `jd finance white strip repayment remains a transfer with exact timestamp and principal`() {
        val receipt = """
            账单详情
            京东白条
            -58.00
            还款成功
            支付方式：招商银行储蓄卡(1234)
            交易时间：$time
            订单号：$id
        """.trimIndent()
        val result = parse(jdFinance, receipt)
        assertNotNull(result)
        assertEquals("transfer", result?.businessKind)
        assertEquals("account_transfer", result?.scene)
        assertEquals(5800L, result?.amountCents)
        assertEquals("京东白条", result?.details?.transferToAccount)
        assertEquals(occurredAt, result?.occurredAt)
        val approximate = parse(jdFinance, receipt.replace("交易时间", "创建时间"))
        assertEquals("transfer", approximate?.businessKind)
        assertTrue(approximate!!.occurredAtIsCreationTime)
        assertNull(parse(jdFinance, receipt.replace("还款成功", "还款处理中")))
    }

    private fun parse(packageName: String, text: String) = PaymentSceneAdapters.parse(packageName, text, now)
}

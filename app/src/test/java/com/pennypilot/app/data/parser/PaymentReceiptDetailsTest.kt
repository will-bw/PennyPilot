package com.pennypilot.app.data.parser

import org.junit.Assert.*
import org.junit.Test

class PaymentReceiptDetailsTest {
    @Test fun `merge fills missing values and carries only the selected field sources`() {
        val original = PaymentReceiptDetails(merchantName = "原商户", productDescription = " ",
            fieldSources = mapOf("merchantName" to "节点", "productDescription" to "过期来源", "unused" to "无字段"))
        val incoming = PaymentReceiptDetails(merchantName = "其他商户", productDescription = "午餐", fundingAccount = "零钱",
            fieldSources = mapOf("merchantName" to "OCR", "productDescription" to "OCR", "fundingAccount" to "通知"))
        val merged = original.mergeMissing(incoming)
        assertEquals("原商户", merged.merchantName)
        assertEquals("午餐", merged.productDescription)
        assertEquals("零钱", merged.fundingAccount)
        assertEquals(mapOf("merchantName" to "节点", "productDescription" to "OCR", "fundingAccount" to "通知"), merged.fieldSources)
        assertTrue(PaymentReceiptDetails(fieldSources = mapOf("merchantName" to "节点")).isEmpty())
        assertTrue(PaymentReceiptDetails(merchantName = " ").isEmpty())
        assertFalse(merged.isEmpty())
    }

    @Test fun `missing source never inherits the discarded field source`() {
        val merged = PaymentReceiptDetails(merchantName = "商户").mergeMissing(
            PaymentReceiptDetails(merchantName = "另一商户", fieldSources = mapOf("merchantName" to "OCR")))
        assertEquals("商户", merged.merchantName)
        assertFalse(merged.fieldSources.containsKey("merchantName"))
    }

    @Test fun `context normalization bounds fields without flattening product lines`() {
        val fields = listOf(
            PaymentReceiptContext(" 商品明细 ", " 面包 × 2\r\n牛奶 × 1 ", "页面·明确标签"),
            PaymentReceiptContext("商品明细", "面包 × 2\n牛奶 × 1", "本地OCR"),
            PaymentReceiptContext("", "忽略"), PaymentReceiptContext("订单描述", " ")
        )
        val normalized = PaymentReceiptDetails.normalizeContext(fields)
        assertEquals(listOf(PaymentReceiptContext("商品明细", "面包 × 2\n牛奶 × 1", "页面·明确标签")), normalized)
        val many = PaymentReceiptDetails.normalizeContext((0..20).map {
            PaymentReceiptContext("字段$it", "内容$it")
        })
        assertEquals(16, many.size)
        val large = PaymentReceiptDetails.normalizeContext((0..20).map {
            PaymentReceiptContext("$it" + "标".repeat(50), "内".repeat(400))
        })
        assertTrue(large.all { it.label.length <= 40 && it.value.length <= 300 })
        assertTrue(large.sumOf { it.label.length + it.value.length } <= 2_400)
    }

    @Test fun `context merge preserves multiple goods and makes context only details nonempty`() {
        val first = PaymentReceiptContext("商品名称", "面包", "页面·明确标签")
        val second = PaymentReceiptContext("商品名称", "牛奶", "本地OCR·明确标签")
        val original = PaymentReceiptDetails(context = listOf(first))
        val merged = original.mergeMissing(PaymentReceiptDetails(context = listOf(first.copy(source = "通知"), second)))
        assertEquals(listOf(first, second), merged.context)
        assertFalse(original.isEmpty())
        assertTrue(PaymentReceiptDetails(context = listOf(PaymentReceiptContext("", ""))).isEmpty())
    }

    @Test fun `store fallback identifies its role without hiding actual merchant conflicts`() {
        val store = PaymentReceiptDetailsReader.read("com.tencent.mm", "门店名称：广场店", null, "本地OCR")
        assertTrue(store.merchantFromStore)
        assertEquals("广场店", store.details.merchantName)
        assertTrue(store.details.context.any { it.label == "门店名称" && it.value == "广场店" })
        val merchant = PaymentReceiptDetailsReader.read("com.tencent.mm",
            "商户名称：样例餐饮公司\n门店名称：广场店", null, "页面")
        assertFalse(merchant.merchantFromStore)
        assertEquals("样例餐饮公司", merchant.details.merchantName)
        val conflict = PaymentReceiptDetailsReader.read("com.tencent.mm",
            "商户名称：第一公司\n商户名称：第二公司\n门店名称：广场店", null, "页面")
        assertFalse(conflict.merchantFromStore)
        assertTrue(conflict.ambiguousParty)
        assertNull(conflict.details.merchantName)
    }

    @Test fun `receipt rows bind wrapped values and labels ending in a colon`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1), PaymentScreenNode(3, 1),
            PaymentScreenNode(4, 2), PaymentScreenNode(5, 2, description = "商户名称："),
            PaymentScreenNode(6, 4, text = "示例餐厅"),
            PaymentScreenNode(7, 3, text = "午餐套餐"), PaymentScreenNode(8, 3),
            PaymentScreenNode(9, 8, description = "商品说明:")
        ))
        val result = PaymentReceiptDetailsReader.read("com.tencent.mm", "示例餐厅\n商户名称：\n午餐套餐\n商品说明:",
            structure, "页面").details
        assertEquals("示例餐厅", result.merchantName)
        assertEquals("午餐套餐", result.productDescription)
        assertEquals("页面·同一字段行", result.fieldSources["merchantName"])
        assertEquals("页面·同一字段行", result.fieldSources["productDescription"])
    }

    @Test fun `a row with two different field roles does not assign its value to both`() {
        val structure = PaymentScreenStructure(listOf(
            PaymentScreenNode(1, null), PaymentScreenNode(2, 1),
            PaymentScreenNode(3, 2, text = "收款方："), PaymentScreenNode(4, 2, text = "示例商户"),
            PaymentScreenNode(5, 2, text = "商品说明：")
        ))
        val result = PaymentReceiptDetailsReader.read("com.tencent.mm", "收款方：\n示例商户\n商品说明：", structure, "页面")
        assertTrue(result.ambiguousParty)
        assertNull(result.details.payeeName)
        assertNull(result.details.productDescription)
    }
    @Test fun `financial details keep exact cents zero fees and source accounts without replacing principal`() {
        val text = "支付成功\n实付金额：90.00元\n原价：100.00元\n优惠金额：10.00元\n手续费：0.00元" +
            "\n转出账户：支付宝余额\n转入账户：本人储蓄卡(1234)"
        val details = PaymentReceiptDetailsReader.read("com.eg.android.AlipayGphone", text, null, "页面").details
        assertEquals(10000L, details.originalAmountCents)
        assertEquals(1000L, details.discountAmountCents)
        assertEquals(0L, details.feeAmountCents)
        assertEquals("支付宝余额", details.transferFromAccount)
        assertEquals("本人储蓄卡(1234)", details.transferToAccount)
        assertEquals("页面·明确标签", details.fieldSources["feeAmountCents"])
        assertFalse(PaymentReceiptDetails(feeAmountCents = 0).isEmpty())
    }

    @Test fun `financial detail conflicts invalid decimals and remark claims do not become facts`() {
        val text = "原价：100.00元\n优惠金额：10.00元\n优惠合计：20.00元\n手续费：1.234元\n备注：到账金额：900元，转入账户：骗子"
        val result = PaymentReceiptDetailsReader.read("com.tencent.mm", text, null, "页面")
        assertEquals(10000L, result.details.originalAmountCents)
        assertNull(result.details.discountAmountCents)
        assertNull(result.details.feeAmountCents)
        assertNull(result.details.receivedAmountCents)
        assertNull(result.details.transferToAccount)
        assertTrue("discountAmountCents" in result.ambiguousFields)
        assertTrue("feeAmountCents" in result.ambiguousFields)
    }

    @Test fun `financial merge keeps original observed values and only fills missing fields`() {
        val original = PaymentReceiptDetails(feeAmountCents = 0, transferFromAccount = "零钱",
            fieldSources = mapOf("feeAmountCents" to "页面", "transferFromAccount" to "页面"))
        val incoming = PaymentReceiptDetails(feeAmountCents = 10, receivedAmountCents = 990,
            transferFromAccount = "余额", transferToAccount = "本人储蓄卡",
            fieldSources = mapOf("feeAmountCents" to "云图", "receivedAmountCents" to "云图", "transferToAccount" to "云图"))
        val merged = original.mergeMissing(incoming)
        assertEquals(0L, merged.feeAmountCents)
        assertEquals(990L, merged.receivedAmountCents)
        assertEquals("零钱", merged.transferFromAccount)
        assertEquals("本人储蓄卡", merged.transferToAccount)
        assertEquals("页面", merged.fieldSources["feeAmountCents"])
        assertEquals("云图", merged.fieldSources["receivedAmountCents"])
    }

}

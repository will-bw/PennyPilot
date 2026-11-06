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
}

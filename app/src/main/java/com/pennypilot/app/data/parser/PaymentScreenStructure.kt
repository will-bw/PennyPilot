package com.pennypilot.app.data.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Immutable, bounded facts; never holds an Android node or an editable value. */
@Serializable
data class PaymentScreenNode(
    val id: Int,
    val parentId: Int?,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val bounds: List<Int> = emptyList(),
    val visible: Boolean = true
) {
    val labels: List<String> get() = listOfNotNull(text, description).distinct()
}

@Serializable
data class PaymentScreenStructure(val nodes: List<PaymentScreenNode>) {
    fun toJson(): String = Json.encodeToString(this)

    /** Field values may precede their label in accessibility traversal order. */
    internal fun labelledTextFields(labels: Set<String>): Map<String, List<String>> {
        val siblings = nodes.filter { it.visible }.groupBy { it.parentId }
        val fields = nodes.filter { it.visible && it.labels.any(labels::contains) }.mapNotNull { label ->
            val row = siblings[label.parentId].orEmpty()
            if (label.parentId == null || row.size !in 2..4) return@mapNotNull null
            val values = row.filter { it.id != label.id }.flatMap { it.labels }
                .map(String::trim).filter { it.isNotBlank() && it !in labels }.distinct()
            label.labels.first(labels::contains) to values
        }
        return fields.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.flatten().distinct() }
    }

    /** A semantic resource ID on a verified receipt is evidence; an unlabelled heading alone is not. */
    internal fun namedReceiptField(resourceNames: Set<String>): String? = nodes.asSequence()
        .filter { it.visible && it.resourceId?.substringAfterLast('/')?.let(resourceNames::contains) == true }
        .flatMap { it.labels.asSequence() }.map(String::trim)
        .filter { it.length in 1..120 && !it.all(Char::isDigit) }.distinct().toList().singleOrNull()

    /** Labels in the same small row may arrive amount-first or through contentDescription. */
    fun labelledAmounts(): List<String> {
        val labels = setOf("实付", "实付金额", "实际支付", "实际付款", "实付款", "实扣金额", "实扣",
            "支付金额", "付款金额", "消费金额", "交易金额", "扣款金额", "转账金额",
            "到账金额", "实际到账金额", "收款金额", "收钱金额", "入账金额", "收入金额",
            "退款金额", "退回金额", "退还金额", "本次退款金额", "实际退款金额", "退款到账金额",
            "本次退款", "实际退款", "本次退回金额", "本人领取金额", "领取金额", "收到红包金额", "红包金额")
        val money = Regex("""^(?:[¥￥]\s*)?([0-9]+(?:\.[0-9]{1,2})?)(?:\s*元)?$""")
        val siblings = nodes.filter { it.visible }.groupBy { it.parentId }
        return nodes.filter { it.visible && it.labels.any(labels::contains) }.mapNotNull { label ->
            val row = siblings[label.parentId].orEmpty()
            // A whole root is not a row: unrelated prices must not become a labelled fact.
            if (label.parentId == null || row.size !in 2..4) return@mapNotNull null
            val values = row.filter { it.id != label.id }.flatMap { it.labels }
                .mapNotNull { money.matchEntire(it.trim())?.groupValues?.get(1) }.distinct()
            if (values.size != 1) null else "${label.labels.first(labels::contains)}：${values.single()}元"
        }.distinct()
    }
}

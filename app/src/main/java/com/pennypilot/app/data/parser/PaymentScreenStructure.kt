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

    /** Labels in the same small row may arrive amount-first or through contentDescription. */
    fun labelledAmounts(): List<String> {
        val labels = setOf("实付", "实付金额", "实际支付", "实际付款", "付款金额", "转账金额",
            "到账金额", "收款金额", "退款金额", "退回金额")
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

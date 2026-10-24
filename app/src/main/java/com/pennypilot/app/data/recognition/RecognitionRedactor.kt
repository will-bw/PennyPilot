package com.pennypilot.app.data.recognition

/** Financial identities stay local; each request receives temporary references only. */
object RecognitionRedactor {
    private val identifier = Regex("""(?:商户订单号|商家订单号|商户单号|支付订单号|交易订单号|退款订单号|退款单号|转账单号|订单编号|订单号|交易单号|交易号|流水号|transaction_id|order_id|out_trade_no|trade_no)\s*[:：]?\s*([A-Za-z0-9０-９][A-Za-z0-9０-９_-]{7,127})(?![A-Za-z0-9０-９_-])""", RegexOption.IGNORE_CASE)
    data class Redacted(val text: String, val structure: String?, private val values: Map<String, String>) {
        fun restoreEvidence(value: String?): String? = value?.let { quoted -> values.entries.fold(quoted) { result, (reference, original) -> result.replace(reference, original) } }
        fun containsReference(value: String?): Boolean = value != null && values.keys.any(value::contains)
    }
    fun redact(text: String, structure: String?): Redacted {
        val ids = identifier.findAll(text).map { it.groupValues[1] }.distinct().toList()
        val values = ids.mapIndexed { index, id -> "[交易标识${index + 1}]" to id }.toMap()
        fun masked(source: String): String = values.entries.sortedByDescending { it.value.length }
            .fold(source) { result, (reference, original) -> result.replace(original, reference) }
        return Redacted(masked(text), structure?.let(::masked), values)
    }
}

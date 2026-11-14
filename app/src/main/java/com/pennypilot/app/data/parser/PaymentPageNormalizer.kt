package com.pennypilot.app.data.parser

/** Small, app-owned label/layout translations observed in payment receipts. No inferred prices. */
internal object PaymentPageNormalizer {
    data class Result(val text: String, val evidence: List<String>)
    private val jd = setOf("com.jd.jrapp", "com.jingdong.app.mall")
    private val meituan = setOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1")
    private const val DECIMAL = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?"

    fun normalize(packageName: String, text: String): Result {
        val changes = mutableListOf<String>()
        // Descriptions retain their original wording and can never establish a financial fact.
        val factLines = PaymentTextParser.receiptFacts(text).lines().map(String::trim).toSet()
        val original = text.lines().map(String::trim)
        val hasDetail = original.any { it in setOf("账单详情", "交易详情", "余额明细详情", "账单管理") }
        val lines = original.flatMap { line ->
            if (line !in factLines) return@flatMap listOf(line)
            fun replace(vararg values: String): List<String> {
                changes += line
                return values.toList()
            }
            when {
                packageName == "com.eg.android.AlipayGphone" && line == "余额明细详情" -> replace("账单详情")
                packageName == "com.eg.android.AlipayGphone" && line == "代付成功" -> replace("支付成功")
                packageName == "com.eg.android.AlipayGphone" && Regex("^交易方式(?:[:：]|$)").containsMatchIn(line) ->
                    replace(line.replaceFirst("交易方式", "付款方式"))
                packageName in meituan && line in setOf("先用后付自动扣款成功", "极速支付成功", "自动扣款成功") -> replace("支付成功")
                packageName in meituan && line in setOf("退款处理完成，微信已入账", "退款处理完成，支付宝已入账") -> replace("退款到账")
                (packageName in jd || packageName in meituan) && Regex("^退款至(?:[:：]|$)").containsMatchIn(line) ->
                    replace(line.replaceFirst("退款至", "退款方式"))
                packageName in jd && Regex("^支付立减(?:[:：\\s]|[¥￥0-9])").containsMatchIn(line) ->
                    replace(line.replaceFirst("支付立减", "优惠金额"))
                packageName == "com.wudaokou.hippo" -> {
                    val paid = Regex("^支付成功[:：]\\s*[¥￥]\\s*($DECIMAL)(?:元)?$").matchEntire(line)
                    if (paid == null) listOf(line) else replace("支付成功", "实付金额：${paid.groupValues[1]}元")
                }
                packageName == "com.tencent.mm" && hasDetail -> {
                    val summary = Regex("^([^,，]{1,80})[,，](支出|收入)\\s*[¥￥]?\\s*($DECIMAL)元$").matchEntire(line)
                    if (summary == null) listOf(line) else replace("商户名称：${summary.groupValues[1]}",
                        "收支类型：${summary.groupValues[2]}",
                        "${if (summary.groupValues[2] == "收入") "收款金额" else "实付金额"}：${summary.groupValues[3]}元")
                }
                packageName in jd -> {
                    val paid = Regex("^(白条付款|数字人民币|微信支付|京东支付)\\s*[¥￥]\\s*($DECIMAL)(?:元)?(?:[,，]?\\s*共优惠\\s*[¥￥]?\\s*($DECIMAL)(?:元)?)?$").matchEntire(line)
                    if (paid == null) listOf(line) else {
                        val method = if (paid.groupValues[1] == "白条付款") "京东白条" else paid.groupValues[1]
                        replace("实付金额：${paid.groupValues[2]}元", "付款方式：$method") +
                            paid.groupValues[3].takeIf(String::isNotBlank)?.let { listOf("优惠金额：${it}元") }.orEmpty()
                    }
                }
                else -> listOf(line)
            }
        }.toMutableList()
        if (packageName == "com.xunmeng.pinduoduo" && !hasDetail &&
            original.any { Regex("^当前状态(?:[:：]|$)").containsMatchIn(it) } &&
            original.any { Regex("^交易单号(?:[:：\\s]|[A-Za-z0-9]|$)").containsMatchIn(it) }) {
            lines.add(0, "账单详情")
            changes += "当前状态＋交易单号"
        }
        // Alipay exposes an outer bill heading and an inner transaction heading in one receipt.
        if (packageName == "com.eg.android.AlipayGphone" && "账单详情" in lines && "交易详情" in lines) {
            lines.removeAll { it == "交易详情" || it == "账单管理" }
            changes += "账单详情／交易详情"
        }
        return Result(lines.joinToString("\n"), changes.distinct())
    }
}

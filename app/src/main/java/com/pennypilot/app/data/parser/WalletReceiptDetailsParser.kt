package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Recent, identified wallet receipts only. The generic history-page exclusion stays in place. */
object WalletReceiptDetailsParser {
    private val packages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    private val titles = setOf("账单详情", "交易详情", "退款详情", "收款详情")
    private val completedRefund = Regex("退款(?:已)?(?:成功|到账|完成)|退还成功|红包已退还")
    private val date = "[0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\\s+[0-9]{1,2}:[0-9]{2}:[0-9]{2}"
    private val signedMoney = Regex("^(?:[¥￥]\\s*)?([-+−]?)\\s*([0-9]+(?:,[0-9]{3})*\\.[0-9]{2})\\s*(?:元)?$")
    private val directionLabel = Regex("(?:^|\\n)(?:收支类型|资金方向)\\s*[:：]?\\s*(收入|支出)(?=\\s|$)")
    private val formatters = listOf("uuuu-M-d H:mm:ss", "uuuu/M/d H:mm:ss", "uuuu年M月d日 H:mm:ss")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    const val RECOVERY_WINDOW_MS = 30 * 60_000L

    fun parse(packageName: String, text: String, now: Long,
        structure: PaymentScreenStructure? = null, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (packageName !in packages || text.length !in 1..12_000) return null
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        val titleIndex = lines.take(4).indexOfFirst { it in titles }
        if (titleIndex < 0 || lines.count { it in titles } != 1) return null
        val refund = completedRefund.containsMatchIn(text)
        val labels = if (refund) "退款到账时间|退款成功时间|退款完成时间|到账时间" else
            "交易时间|支付时间|付款时间|收款时间|到账时间|完成时间"
        val facts = PaymentTextParser.receiptFacts(text)
        val times = Regex("(?:^|\\n)($labels)\\s*[:：]?\\s*($date)(?=\\s|$)").findAll(facts).toList()
        val values = times.map { it.groupValues[2] }.distinct()
        if (values.size != 1) return null
        val rawTime = values.single().replace(Regex("\\s+"), " ")
        val occurredAt = formatters.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(rawTime, format).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        } ?: return null
        if (occurredAt > now || now - occurredAt > RECOVERY_WINDOW_MS) return null
        val body = lines.filterIndexed { index, _ -> index != titleIndex }.toMutableList()
        var originalMoney: String? = null
        val directions = directionLabel.findAll(facts).map { it.groupValues[1] }.distinct().toList()
        if (directions.size > 1) return null
        val explicitDirection = directions.singleOrNull()
        // Signed headline amounts are meaningful only with an independent income/expense label.
        // Never strip arbitrary negative values to make a parser accept them.
        val headline = body.withIndex().filter { signedMoney.matches(it.value) }
        if (headline.any { signedMoney.matchEntire(it.value)!!.groupValues[1].isNotEmpty() } ||
            explicitDirection != null && headline.size == 1) {
            if (headline.size != 1 || explicitDirection == null) return null
            val line = headline.single()
            val match = signedMoney.matchEntire(line.value)!!
            val incoming = explicitDirection == "收入"
            if (match.groupValues[1].isNotEmpty() && match.groupValues[1] != (if (incoming) "+" else "-") &&
                !(match.groupValues[1] == "−" && !incoming)) return null
            if (MoneyUtil.parsePositiveYuanToCents(match.groupValues[2]) == null) return null
            originalMoney = line.value
            body[line.index] = "${if (refund) "退款金额" else if (incoming) "收款金额" else "实付金额"}：${match.groupValues[2]}元"
        }
        val recognition = PaymentTextParser.analyzeScreen(packageName, body.joinToString("\n"), structure)
        val parsed = recognition.parsed ?: return null
        if (parsed.orderId == null || refund && parsed.orderIdKind == null) return null
        if (explicitDirection != null && (explicitDirection == "收入") != (parsed.direction == "INCOME")) return null
        return parsed.copy(occurredAt = occurredAt, ruleVersion = "wallet-receipt-details-2026-10-24.1",
            fieldEvidence = parsed.fieldEvidence + buildMap {
                put("occurredAt", "页面·${times.first().groupValues[1]}：$rawTime")
                originalMoney?.let { put("amount", "页面·带方向的凭证金额：$it") }
                explicitDirection?.let { put("direction", "页面·收支类型：$it") }
            })
    }
}

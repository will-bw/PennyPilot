package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Recent, identified wallet receipts only. The generic history-page exclusion stays in place. */
object WalletReceiptDetailsParser {
    private val packages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay")
    private val titles = setOf("账单详情", "交易详情", "退款详情", "收款详情")
    private val completedRefund = Regex("退款(?:已)?(?:成功|到账|完成)|退还成功|已退款|已原路退回|款项已退回|红包已退还")
    private val date = "[0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\\s+[0-9]{1,2}:[0-9]{2}:[0-9]{2}"
    private val signedMoney = Regex("^(?:[¥￥]\\s*)?([-+−]?)\\s*([0-9]+(?:,[0-9]{3})*\\.[0-9]{2})\\s*(?:元)?$")
    private val paymentTimes = setOf("交易时间", "支付时间", "付款时间", "收款时间", "到账时间", "完成时间")
    private val refundTimes = setOf("退款到账时间", "退款成功时间", "退款完成时间", "到账时间")
    private val directionLabels = setOf("收支类型", "资金方向")
    private val directionLabel = Regex("(?:^|\\n)(?:${directionLabels.joinToString("|")})\\s*[:：]?\\s*(收入|支出)(?=\\s|$)")
    private val statusLabels = PaymentTextParser.completedStatusByLabel.keys
    private val statusLine = Regex("^(${statusLabels.joinToString("|")})(?:(?:\\s*[:：]\\s*|\\s+)(.*))?$")
    private val expenseAmounts = setOf("实付", "实付金额", "实际支付", "实际付款", "支付金额", "付款金额", "扣款金额", "消费金额")
    private val incomeAmounts = setOf("到账金额", "实际到账金额", "收款金额", "收钱金额", "收入金额", "入账金额")
    private val refundAmounts = setOf("退款金额", "实际退款金额", "退款到账金额", "本次退款金额", "退回金额", "退还金额",
        "本次退款", "实际退款", "本次退回金额", "退款", "退回", "退还")
    private val generalAmounts = setOf("交易金额", "转账金额")
    private val labelledMoney = Regex("^(${(expenseAmounts + incomeAmounts + refundAmounts + generalAmounts).joinToString("|")})" +
        "\\s*[:：]?\\s*(?:[¥￥]\\s*)?([-+−]?)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:元)?$")
    private val formatters = listOf("uuuu-M-d H:mm:ss", "uuuu/M/d H:mm:ss", "uuuu年M月d日 H:mm:ss")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    const val RECOVERY_WINDOW_MS = 30 * 60_000L

    internal data class CompletedTime(val occurredAt: Long, val evidence: String)

    /** A visible receipt time is optional for notifications, but required to recover a detail page. */
    internal fun completedTime(facts: String, refund: Boolean, observedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(), allowHistorical: Boolean = false): CompletedTime? {
        val labels = (if (refund) refundTimes else paymentTimes).joinToString("|")
        val matches = Regex("(?:^|[\\n，,；;])\\s*($labels)\\s*[:：]?\\s*($date)(?=[ \\t]*(?:[\\n，,；;。！!]|$))")
            .findAll(facts).toList()
        val rawTime = matches.map { it.groupValues[2].replace(Regex("\\s+"), " ") }.distinct().singleOrNull()
            ?: return null
        val local = formatters.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(rawTime, format) }.getOrNull()
        } ?: return null
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
        val occurredAt = local.toInstant(offset).toEpochMilli()
        if (occurredAt < 0L || occurredAt > observedAt ||
            (!allowHistorical && observedAt - occurredAt > RECOVERY_WINDOW_MS)) return null
        return CompletedTime(occurredAt, "${matches.first().groupValues[1]}：$rawTime")
    }

    fun parse(packageName: String, text: String, now: Long,
        structure: PaymentScreenStructure? = null, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (packageName !in packages) return null
        return parseReceipt(packageName, text, now, structure, zone)
    }

    internal fun parseSelectedReceipt(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? =
        parseReceipt("", text, now, null, zone)

    private fun parseReceipt(packageName: String, text: String, now: Long, structure: PaymentScreenStructure?,
        zone: ZoneId): NotificationParseResult? {
        if (text.length !in 1..12_000) return null
        val selectedImage = packageName.isEmpty()
        val source = if (selectedImage) "本地OCR" else "页面"
        val facts = PaymentTextParser.receiptFacts(text)
        val lines = facts.lines().map(String::trim).filter(String::isNotEmpty)
        val titleIndex = lines.take(4).indexOfFirst { it in titles }
        if (titleIndex < 0 || lines.count { it in titles } != 1) return null
        // Bind only values already present outside descriptions in this receipt. A WebView can
        // expose the value before its label, so traversal order alone is insufficient.
        val fieldLabels = paymentTimes + refundTimes + directionLabels + statusLabels
        val factLines = lines.toSet()
        val rows = structure?.labelledTextFields(fieldLabels).orEmpty().filterKeys { label ->
            "$label：" in factLines || "$label:" in factLines || label in factLines
        }.mapValues { (_, values) -> values.mapNotNull { value ->
            val parts = value.lines().map(String::trim).filter(String::isNotEmpty)
            when (parts.size) {
                1 -> parts.single().takeIf(factLines::contains)
                2 -> parts.joinToString(" ").takeIf { lines.zipWithNext().any { (first, second) ->
                    first == parts[0] && second == parts[1]
                } }
                else -> null
            }
        } }.filterValues(List<String>::isNotEmpty)
        val structuredFields = rows.flatMap { (label, values) -> values.map { "$label：$it" } }
        val statuses = statusFields(lines, rows)
        val completionTitles = statuses.map { (label, raw) -> PaymentTextParser.completedStatus(label, raw) ?: return null }.distinct()
        val refund = (lines + completionTitles).any { completedRefund.matches(it.trimEnd('。', '!', '！')) }
        if (lines[titleIndex] == "退款详情" && !refund) return null
        val allFacts = (lines + structuredFields).joinToString("\n")
        val time = completedTime(allFacts, refund, now, zone, allowHistorical = selectedImage) ?: return null
        val body = (lines.filterIndexed { index, _ -> index != titleIndex } + structuredFields + completionTitles).toMutableList()
        var originalMoney: String? = null
        val directions = directionLabel.findAll(allFacts).map { it.groupValues[1] }.distinct().toList()
        if (directions.size > 1) return null
        val explicitDirection = directions.singleOrNull()
        // Signed headline amounts are meaningful only with an independent income/expense label.
        // Never strip arbitrary negative values to make a parser accept them.
        val headline = body.withIndex().filter { signedMoney.matches(it.value) }
        val amountLabels = body.mapNotNull { labelledMoney.matchEntire(it)?.groupValues?.get(1) }.toSet()
        if (refund && amountLabels.any(expenseAmounts::contains) && amountLabels.none(refundAmounts::contains) &&
            headline.size != 1) return null
        if (headline.any { signedMoney.matchEntire(it.value)!!.groupValues[1].isNotEmpty() } ||
            explicitDirection != null && headline.size == 1) {
            if (headline.size != 1 || explicitDirection == null) return null
            val line = headline.single()
            val match = signedMoney.matchEntire(line.value)!!
            val incoming = explicitDirection == "收入"
            if (!signMatchesDirection(match.groupValues[1], incoming)) return null
            if (MoneyUtil.parsePositiveYuanToCents(match.groupValues[2]) == null) return null
            originalMoney = line.value
            body[line.index] = "${if (refund) "退款金额" else if (incoming) "收款金额" else "实付金额"}：${match.groupValues[2]}元"
        }
        for (index in body.indices) {
            val match = labelledMoney.matchEntire(body[index]) ?: continue
            val (label, sign, value) = match.destructured
            if (sign.isEmpty() && (explicitDirection == null || label !in generalAmounts)) continue
            if (explicitDirection == null) return null
            val incoming = explicitDirection == "收入"
            if (incoming && label in expenseAmounts || !incoming && label in incomeAmounts + refundAmounts) return null
            if (!signMatchesDirection(sign, incoming)) return null
            if (MoneyUtil.parsePositiveYuanToCents(value) == null) return null
            originalMoney = originalMoney ?: body[index]
            body[index] = "${if (refund) "退款金额" else if (incoming) "收款金额" else "实付金额"}：${value}元"
        }
        val receiptText = body.joinToString("\n")
        val parsed = (if (selectedImage) PaymentTextParser.analyzeSelectedReceipt(receiptText)
            else PaymentTextParser.analyzeScreen(packageName, receiptText, structure).parsed) ?: return null
        if (parsed.orderId == null || refund && parsed.orderIdKind == null) return null
        if (refund != (parsed.scene in setOf("refund_received", "red_packet_returned"))) return null
        if (explicitDirection != null && (explicitDirection == "收入") != (parsed.direction == "INCOME")) return null
        if (!refund && (parsed.direction == "INCOME" && amountLabels.any(expenseAmounts::contains) ||
                parsed.direction == "EXPENSE" && amountLabels.any(incomeAmounts::contains))) return null
        // Financial parsing intentionally excludes free-form descriptions. Restore only their
        // descriptive fields after the receipt's time, amount and identity have been validated.
        return PaymentTextParser.withReceiptDetails(packageName, text, parsed, structure, source)
            .copy(occurredAt = time.occurredAt, ruleVersion = "wallet-receipt-details-2026-11-07.1",
            fieldEvidence = parsed.fieldEvidence + buildMap {
                put("occurredAt", "$source·${time.evidence}")
                originalMoney?.let { put("amount", "$source·带方向的凭证金额：$it") }
                explicitDirection?.let { put("direction", "$source·收支类型：$it") }
                statuses.firstOrNull()?.let { (label, value) -> put("paymentStatus", "$source·$label：$value") }
            })
    }

    private fun signMatchesDirection(sign: String, incoming: Boolean): Boolean =
        sign.isEmpty() || if (incoming) sign == "+" else sign in setOf("-", "−")

    private fun statusFields(lines: List<String>, rows: Map<String, List<String>>): List<Pair<String, String>> {
        return lines.flatMapIndexed { index, line ->
            val match = statusLine.matchEntire(line) ?: return@flatMapIndexed emptyList()
            val label = match.groupValues[1]
            val inline = match.groupValues[2]
            val values = if (inline.isNotBlank()) listOf(inline) else rows[label] ?: listOf(lines.getOrNull(index + 1).orEmpty())
            values.map { label to it }
        }.distinct()
    }
}

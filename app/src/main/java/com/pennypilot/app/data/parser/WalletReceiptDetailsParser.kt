package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Single completed wallet/order receipts. Historical dates require a reliable transaction identity. */
object WalletReceiptDetailsParser {
    private val selectedTitles = setOf("账单详情", "交易详情", "退款详情", "收款详情", "转账详情", "支付详情", "订单详情", "订单信息")
    private val completedRefund = Regex("退款(?:已)?(?:成功|到账|完成)|退还成功|已退款|已原路退回|款项已退回|红包已退还")
    private val date = "[0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\\s+[0-9]{1,2}[:点][0-9]{2}(?:[:分][0-9]{2}秒?|分?)"
    private val signedMoney = Regex("^(?:[¥￥]\\s*)?([-+−]?)\\s*([0-9]+(?:,[0-9]{3})*\\.[0-9]{2})\\s*(?:元)?$")
    private val paymentTimes = setOf("交易时间", "支付时间", "付款时间", "收款时间", "到账时间", "完成时间", "转账时间")
    private val refundTimes = setOf("退款到账时间", "退款成功时间", "退款完成时间", "退款时间", "到账时间")
    private val directionLabels = setOf("收支类型", "资金方向")
    private val directionLabel = Regex("(?:^|\\n)(?:${directionLabels.joinToString("|")})\\s*[:：]?\\s*(收入|支出)(?=\\s|$)")
    private val statusLabels = PaymentTextParser.completedStatusByLabel.keys + setOf("当前状态", "状态", "订单状态")
    private val statusLine = Regex("^(${statusLabels.joinToString("|")})(?:(?:\\s*[:：]\\s*|\\s+)(.*))?$")
    private val expenseAmounts = setOf("实付", "实付金额", "实际支付", "实际付款", "支付金额", "付款金额", "扣款金额", "消费金额")
    private val incomeAmounts = setOf("到账金额", "实际到账金额", "收款金额", "收钱金额", "收入金额", "入账金额")
    private val refundAmounts = setOf("退款金额", "实际退款金额", "退款到账金额", "本次退款金额", "退回金额", "退还金额",
        "本次退款", "实际退款", "本次退回金额", "退款", "退回", "退还")
    private val generalAmounts = setOf("交易金额", "转账金额")
    private val labelledMoney = Regex("^(${(expenseAmounts + incomeAmounts + refundAmounts + generalAmounts).joinToString("|")})" +
        "\\s*[:：]?\\s*(?:[¥￥]\\s*)?([-+−]?)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:元)?$")
    private val formatters = listOf("uuuu-M-d H:mm:ss", "uuuu/M/d H:mm:ss", "uuuu年M月d日 H:mm:ss",
        "uuuu-M-d H:mm", "uuuu/M/d H:mm", "uuuu年M月d日 H:mm")
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
        val normalizedTime = rawTime.replace('点', ':').replace('分', ':').removeSuffix("秒").removeSuffix(":")
        val local = formatters.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(normalizedTime, format) }.getOrNull()
        } ?: return null
        val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
        val occurredAt = local.toInstant(offset).toEpochMilli()
        if (occurredAt < 0L || occurredAt > observedAt ||
            (!allowHistorical && observedAt - occurredAt > RECOVERY_WINDOW_MS)) return null
        return CompletedTime(occurredAt, "${matches.first().groupValues[1]}：$rawTime")
    }

    fun parse(packageName: String, text: String, now: Long,
        structure: PaymentScreenStructure? = null, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (packageName !in PaymentAppRegistry.packages) return null
        return parseReceipt(packageName, text, now, structure, zone)
    }

    /** Only a user-reviewed/cloud draft may omit an order ID; transaction time remains mandatory. */
    fun parseForReview(packageName: String, text: String, now: Long,
        structure: PaymentScreenStructure? = null, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (packageName !in PaymentAppRegistry.packages) return null
        return parseReceipt(packageName, text, now, structure, zone, requireIdentity = false)
    }

    internal fun parseSelectedReceipt(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? =
        parseReceipt("", text, now, null, zone)

    private fun parseReceipt(packageName: String, text: String, now: Long, structure: PaymentScreenStructure?,
        zone: ZoneId, requireIdentity: Boolean = true): NotificationParseResult? {
        if (text.length !in 1..12_000) return null
        val selectedImage = packageName.isEmpty()
        val source = if (selectedImage) "图片凭证" else "页面"
        val titles = if (selectedImage) selectedTitles else PaymentSceneAdapters.detailTitles(packageName)
        val facts = PaymentTextParser.receiptFacts(text)
        val lines = facts.lines().map(String::trim).filter(String::isNotEmpty)
        val titleIndex = lines.take(6).indexOfFirst { it in titles }
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
        val commerce = !selectedImage && PaymentAppRegistry.profile(packageName)?.family != PaymentAppRegistry.Family.WALLET
        val orderStatus = lines.firstOrNull { it in setOf("交易完成", "交易已完成", "订单已完成", "订单已送达", "已完成", "已送达") ||
            Regex("^(?:订单状态|交易状态)[:：]\\s*(?:已完成|已送达|交易完成|交易已完成)$").matches(it) }
        val hasPaidAmount = lines.any { Regex("^(?:实际支付|实付款|实付金额|实付)(?:[:：\\s]|[¥￥0-9])").containsMatchIn(it) }
        val commerceCompletion = if (commerce && orderStatus != null && hasPaidAmount) listOf("支付成功") else emptyList()
        val statuses = statusFields(lines, rows)
        val completionTitles = statuses.map { (label, raw) ->
            if (commerce && hasPaidAmount && label == "订单状态" && raw in setOf("已送达", "交易完成", "交易已完成")) "支付成功"
            else PaymentTextParser.completedStatus(label, raw) ?: return null
        }.distinct()
        val refund = (lines + completionTitles).any { completedRefund.matches(it.trimEnd('。', '!', '！')) }
        if (lines[titleIndex] == "退款详情" && !refund) return null
        val allFacts = (lines + structuredFields).joinToString("\n")
        val time = completedTime(allFacts, refund, now, zone, allowHistorical = true) ?: return null
        if (!selectedImage && !refund) PaymentTextParser.parseAccountMovement(packageName, text, structure)?.let { transfer ->
            if (requireIdentity && transfer.orderId == null) return null
            return transfer.copy(occurredAt = time.occurredAt,
                fieldEvidence = transfer.fieldEvidence + buildMap {
                    put("occurredAt", "$source·${time.evidence}")
                    if (transfer.orderId == null) put("reviewRequired", "凭证未提供可靠订单标识")
                })
        }
        val body = (lines.filterIndexed { index, _ -> index != titleIndex } + structuredFields + completionTitles + commerceCompletion).toMutableList()
        var originalMoney: String? = null
        var headlineDirection: String? = null
        val directions = directionLabel.findAll(allFacts).map { it.groupValues[1] }.distinct().toList()
        if (directions.size > 1) return null
        val explicitDirection = directions.singleOrNull()
        // A single signed amount on an identified detail page is direct direction evidence.
        // Explicit direction and labelled financial facts must still agree with that sign.
        val headline = body.withIndex().filter { signedMoney.matches(it.value) }
        val amountLabels = body.mapNotNull { labelledMoney.matchEntire(it)?.groupValues?.get(1) }.toSet()
        if (refund && amountLabels.any(expenseAmounts::contains) && amountLabels.none(refundAmounts::contains) &&
            headline.size != 1) return null
        if (headline.any { signedMoney.matchEntire(it.value)!!.groupValues[1].isNotEmpty() } ||
            explicitDirection != null && headline.size == 1) {
            if (headline.size != 1) return null
            val line = headline.single()
            val match = signedMoney.matchEntire(line.value)!!
            val incoming = explicitDirection?.let { it == "收入" }
                ?: when (match.groupValues[1]) { "+" -> true; "-", "−" -> false; else -> return null }
            if (!signMatchesDirection(match.groupValues[1], incoming)) return null
            headlineDirection = if (incoming) "INCOME" else "EXPENSE"
            if (MoneyUtil.parsePositiveYuanToCents(match.groupValues[2]) == null) return null
            originalMoney = line.value
            body[line.index] = "${if (refund) "退款金额" else if (incoming) "收款金额" else "实付金额"}：${match.groupValues[2]}元"
        }
        for (index in body.indices) {
            val match = labelledMoney.matchEntire(body[index]) ?: continue
            val (label, sign, value) = match.destructured
            if (sign.isEmpty() && (explicitDirection == null || label !in generalAmounts)) continue
            val incoming = explicitDirection?.let { it == "收入" }
                ?: when (sign) { "+" -> true; "-", "−" -> false; else -> return null }
            if (incoming && label in expenseAmounts || !incoming && label in incomeAmounts + refundAmounts) return null
            if (!signMatchesDirection(sign, incoming)) return null
            if (MoneyUtil.parsePositiveYuanToCents(value) == null) return null
            originalMoney = originalMoney ?: body[index]
            body[index] = "${if (refund) "退款金额" else if (incoming) "收款金额" else "实付金额"}：${value}元"
        }
        val receiptText = body.joinToString("\n")
        val parsed = (if (selectedImage) PaymentTextParser.analyzeSelectedReceipt(receiptText)
            else PaymentTextParser.analyzeScreen(packageName, receiptText, structure).parsed) ?: return null
        if (requireIdentity && (parsed.orderId == null || refund && parsed.orderIdKind == null)) return null
        if (refund != (parsed.scene in setOf("refund_received", "red_packet_returned"))) return null
        if (headlineDirection != null && headlineDirection != parsed.direction) return null
        if (explicitDirection != null && (explicitDirection == "收入") != (parsed.direction == "INCOME")) return null
        if (!refund && (parsed.direction == "INCOME" && amountLabels.any(expenseAmounts::contains) ||
                parsed.direction == "EXPENSE" && amountLabels.any(incomeAmounts::contains))) return null
        // Financial parsing intentionally excludes free-form descriptions. Restore only their
        // descriptive fields after the receipt's time, amount and identity have been validated.
        return PaymentTextParser.withReceiptDetails(packageName, text, parsed, structure, source)
            .copy(occurredAt = time.occurredAt, ruleVersion = "app-receipt-details-2026-11-14.1",
            fieldEvidence = parsed.fieldEvidence + buildMap {
                put("occurredAt", "$source·${time.evidence}")
                if (parsed.orderId == null) put("reviewRequired", "凭证未提供可靠订单标识")
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

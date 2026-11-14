package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** WeChat's own completed transfer receipts; a chat bubble or a claim button is never a receipt. */
internal object WechatTransferReceiptAdapter {
    private const val MONEY = "(?:[¥￥]?[0-9]+(?:,[0-9]{3})*\\.[0-9]{2}|[¥￥][0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
    private const val DATE = "[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日[0-9]{1,2}:[0-9]{2}:[0-9]{2}"
    private val received = Regex("^(你已收款(?:[,，]?资金已(?:存)?入零钱)?)(?:收款金额:?)?($MONEY)(?:零钱余额)?转账时间:?($DATE)收款时间:?($DATE)(.*?)(?:账单详情|帐单详情)?$")
    private val promotion = Regex("^(?:零钱通|七日年化[0-9]+(?:\\.[0-9]+)?%|给小金库再添一笔|转入)*$")
    private val timestamp = DateTimeFormatter.ofPattern("uuuu年M月d日H:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val sentStatus = Regex("^(支付成功|转账(?:已)?(?:成功|完成)|已转账)[！!。]?$")
    private val waiting = Regex("^(?:待|等待)\\s*(.{1,40}?)\\s*确认(?:收款|收钱)[！!。]?$")
    private val amountLabel = Regex("^(?:转账金额|支付金额|付款金额)\\s*[:：]?\\s*$")
    private val moneyLine = Regex("^(?:(转账金额|支付金额|付款金额)\\s*:?\\s*)?([¥￥]\\s*)?([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(元)?$")
    private val currencySymbols = setOf("¥", "￥")
    private val completionActions = setOf("完成", "查看账单", "查看账单详情")
    private val receiptActions = completionActions + setOf("返回", "关闭")
    private val invalidRecipient = Regex("密码|验证码|输入|失败|取消|待支付|聊天|历史|账单|教程|示例|备注")
    private val genericRecipient = setOf("对方", "收款人", "收款方", "本人", "自己")

    private data class SentReceipt(val status: String, val amount: String, val cents: Long,
        val recipient: String?, val recipientStatus: String)

    fun isReceived(text: String): Boolean = received.matches(compact(text))
    fun isSent(text: String): Boolean = sentReceipt(text) != null

    /** A partial result may reach opt-in receipt vision, never the local ledger parser. */
    fun isSentResultWithoutAmount(text: String): Boolean {
        if (text.length !in 1..12_000) return false
        val rows = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (rows.none { it in completionActions }) return false
        val facts = rows.filter { it !in receiptActions }
        val status = facts.singleOrNull(sentStatus::matches) ?: return false
        val recipientStatus = facts.singleOrNull(waiting::matches) ?: return false
        if (recipient(recipientStatus) == null) return false
        val placeholders = facts.filter { it != status && it != recipientStatus }
        return placeholders.size <= 2 && placeholders.count(amountLabel::matches) <= 1 &&
            placeholders.count { it in currencySymbols } <= 1 &&
            placeholders.all { amountLabel.matches(it) || it in currencySymbols }
    }

    private fun sentReceipt(text: String): SentReceipt? {
        if (text.length !in 1..12_000) return null
        val lines = text.lines().map(String::trim).filter { it.isNotBlank() && it !in receiptActions }
        // Accessibility can expose the amount label, currency symbol and number
        // as separate adjacent nodes. The remaining rows must identify exactly one receipt.
        if (lines.size !in 3..5) return null
        val status = lines.singleOrNull(sentStatus::matches) ?: return null
        val recipientStatus = lines.singleOrNull(waiting::matches) ?: return null
        val amountLines = lines.filter { it != status && it != recipientStatus }
        val rawAmount = amountLines.last()
        val prefixes = amountLines.dropLast(1)
        if (prefixes.isNotEmpty()) {
            val amountIndex = lines.lastIndexOf(rawAmount)
            if (lines.subList(amountIndex - prefixes.size, amountIndex) != prefixes ||
                prefixes.size == 2 && (!amountLabel.matches(prefixes[0]) || prefixes[1] !in currencySymbols) ||
                prefixes.size == 1 && !amountLabel.matches(prefixes.single()) && prefixes.single() !in currencySymbols
            ) return null
        }
        val amount = moneyLine.matchEntire(normalizeDigits(amountLines.joinToString("")).replace('，', ',')) ?: return null
        val (label, symbol, number, unit) = amount.destructured
        if (label.isEmpty() && symbol.isEmpty() && unit.isEmpty() && number.substringAfter('.', "").length != 2) return null
        val cents = MoneyUtil.parsePositiveYuanToCents(number) ?: return null
        val party = recipient(recipientStatus) ?: return null
        return SentReceipt(status, amountLines.joinToString(" "), cents, party.takeUnless { it in genericRecipient }, recipientStatus)
    }

    private fun recipient(status: String): String? = waiting.matchEntire(status)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) && !invalidRecipient.containsMatchIn(it) }

    fun parse(text: String, now: Long, allowHistorical: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (text.length !in 1..12_000) return null
        sentReceipt(text)?.let { (status, amount, cents, recipient, recipientStatus) ->
            return NotificationParseResult(cents, recipient, "EXPENSE", paymentMethod = "微信支付",
                businessKind = "unknown", scene = "transfer_sent", ruleVersion = "wechat-transfer-receipt-2026-11-14.2",
                details = PaymentReceiptDetails(payeeName = recipient, paymentChannel = "微信支付",
                    fieldSources = buildMap {
                        if (recipient != null) put("payeeName", "页面·待收款人")
                        put("paymentChannel", "应用渠道")
                    }),
                fieldEvidence = buildMap {
                    put("amount", "页面·转账金额：$amount")
                    put("direction", "页面·本人付款完成：$status")
                    put("paymentStatus", "页面·付款完成：$status")
                    if (recipient != null) put("merchant", "页面·待收款人：$recipientStatus")
                })
        }
        val match = received.matchEntire(compact(text)) ?: return null
        val (status, money, sent, credited, extra) = match.destructured
        if (!promotion.matches(extra)) return null
        val cents = MoneyUtil.parsePositiveYuanToCents(money.removePrefix("¥").removePrefix("￥")) ?: return null
        fun time(value: String): Long? {
            val local = runCatching { LocalDateTime.parse(value, timestamp) }.getOrNull() ?: return null
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
            return local.toInstant(offset).toEpochMilli()
        }
        val sentAt = time(sent) ?: return null
        val creditedAt = time(credited) ?: return null
        if (sentAt > creditedAt || creditedAt > now || creditedAt < 0L ||
            !allowHistorical && now - creditedAt > WalletReceiptDetailsParser.RECOVERY_WINDOW_MS) return null
        return NotificationParseResult(cents, null, "INCOME", paymentMethod = "微信支付", businessKind = "unknown",
            scene = "transfer_received", ruleVersion = "wechat-transfer-receipt-2026-11-14.1", occurredAt = creditedAt,
            fieldEvidence = buildMap {
                put("amount", "页面·本人收款金额：$money")
                put("direction", "页面·本人收款完成：$status")
                put("paymentStatus", "页面·收款状态：$status")
                put("occurredAt", "页面·收款时间：$credited")
                put("transferTime", "页面·转账时间：$sent")
                if (allowHistorical) put("reviewRequired", "凭证未提供可靠订单标识")
            })
    }

    private fun compact(text: String): String = normalizeDigits(text.filterNot(Char::isWhitespace))

    private fun normalizeDigits(text: String): String = text.map {
        when (it) {
            in '０'..'９' -> '0' + (it - '０')
            '．' -> '.'
            '：' -> ':'
            '％' -> '%'
            else -> it
        }
    }.joinToString("")
}

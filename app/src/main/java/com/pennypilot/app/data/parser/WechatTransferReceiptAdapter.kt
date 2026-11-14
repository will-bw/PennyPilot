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
    private val waiting = Regex("^待(.{1,40})确认收款$")
    private val moneyLine = Regex("^(?:[¥￥]\\s*)?([0-9]+(?:,[0-9]{3})*\\.[0-9]{2})\\s*(?:元)?$")

    fun isReceived(text: String): Boolean = received.matches(compact(text))
    fun isSent(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        return lines.size in 3..4 && lines.count { it == "支付成功" } == 1 &&
            lines.count { it == "完成" } == lines.size - 3 &&
            lines.count(waiting::matches) == 1 && lines.count(moneyLine::matches) == 1
    }

    fun parse(text: String, now: Long, allowHistorical: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault()): NotificationParseResult? {
        if (text.length !in 1..12_000) return null
        if (isSent(text)) {
            val lines = text.lines().map(String::trim).filter(String::isNotBlank)
            val amount = lines.mapNotNull { moneyLine.matchEntire(it)?.groupValues?.get(1) }.single()
            val recipient = lines.mapNotNull { waiting.matchEntire(it)?.groupValues?.get(1) }.single()
            val cents = MoneyUtil.parsePositiveYuanToCents(amount) ?: return null
            return NotificationParseResult(cents, recipient, "EXPENSE", paymentMethod = "微信支付",
                businessKind = "unknown", scene = "transfer_sent", ruleVersion = "wechat-transfer-receipt-2026-11-14.1",
                details = PaymentReceiptDetails(payeeName = recipient, paymentChannel = "微信支付",
                    fieldSources = mapOf("payeeName" to "页面·待收款人", "paymentChannel" to "应用渠道")),
                fieldEvidence = mapOf("amount" to "页面·转账金额：$amount", "direction" to "页面·本人付款完成：支付成功",
                    "paymentStatus" to "页面·付款完成：支付成功", "merchant" to "页面·待收款人：待${recipient}确认收款"))
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

    private fun compact(text: String): String = text.filterNot(Char::isWhitespace).map {
        when (it) {
            in '０'..'９' -> '0' + (it - '０')
            '．' -> '.'
            '：' -> ':'
            '％' -> '%'
            else -> it
        }
    }.joinToString("")
}

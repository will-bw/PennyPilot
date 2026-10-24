package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** WeChat 8.0.78's own confirmed receipt. Pending and sender-side details are different scenes. */
internal object WechatReceivedTransferReader {
    const val ACTIVITY = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"
    private const val OWN_COMPLETED = "你已收款[,，]?资金已(?:存)?入零钱"
    private const val MONEY = "(?:[¥￥]?[0-9]+(?:,[0-9]{3})*\\.[0-9]{2}|[¥￥][0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
    private const val DATE = "[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日[0-9]{1,2}:[0-9]{2}:[0-9]{2}"
    private val timestamp = DateTimeFormatter.ofPattern("uuuu年M月d日H:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    // A bounded recovery window covers recent missed receipts, not browsing old transfers.
    private const val RECENT_MS = 30 * 60_000L
    private val receipt = Regex("^($OWN_COMPLETED)($MONEY)零钱余额转账时间($DATE)收款时间($DATE)(.*?)(?:账单详情|帐单详情)?$")
    private val promotion = Regex("^(?:零钱通|七日年化[0-9]+(?:\\.[0-9]+)?%|给小金库再添一笔|转入)*$")

    private fun normalize(text: String): String = text.filterNot(Char::isWhitespace).map {
        when (it) {
            in '０'..'９' -> '0' + (it - '０')
            '．' -> '.'
            '：' -> ':'
            '％' -> '%'
            else -> it
        }
    }.joinToString("")

    fun parseText(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault(),
        source: String = "页面"): NotificationParseResult? {
        if (text.length !in 1..12_000) return null
        val match = receipt.matchEntire(normalize(text)) ?: return null
        val (status, money, sent, received, extra) = match.destructured
        if (!promotion.matches(extra)) return null
        val cents = MoneyUtil.parsePositiveYuanToCents(money.removePrefix("¥").removePrefix("￥")) ?: return null
        fun time(value: String) = runCatching { LocalDateTime.parse(value, timestamp).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        val sentAt = time(sent) ?: return null
        val receivedAt = time(received) ?: return null
        if (sentAt > receivedAt || receivedAt > now || now - receivedAt > RECENT_MS) return null
        return NotificationParseResult(cents, null, "INCOME", paymentMethod = "微信支付",
            businessKind = "unknown", scene = "transfer_received", ruleVersion = "wechat-received-transfer-2026-10-24.1",
            occurredAt = receivedAt, fieldEvidence = mapOf(
                "amount" to "$source·收款金额区域：$money",
                "direction" to "$source·本人收款完成：$status",
                "paymentStatus" to "$source·收款状态：$status",
                "occurredAt" to "$source·收款时间：$received",
                "transferTime" to "$source·转账时间：$sent"
            ))
    }

    fun diagnostics(lines: List<PaymentImageReader.OcrLine>): Map<String, Int> = mapOf(
        "ocrReceivedStatusCount" to lines.count { normalize(it.text).contains("你已收款") },
        "ocrReceivedTimeCount" to lines.count { normalize(it.text).contains("收款时间") },
        "ocrTransferTimeCount" to lines.count { normalize(it.text).contains("转账时间") },
        "ocrReceiptLinkCount" to lines.count { normalize(it.text) == "账单详情" }
    )

    fun parse(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int, now: Long,
        zone: ZoneId = ZoneId.systemDefault(), diagnostics: MutableMap<String, Int> = mutableMapOf()): NotificationParseResult? {
        diagnostics["ocrReceivedStage"] = 0
        if (width <= 0 || height <= width || lines.size !in 6..40) return null
        val content = mutableListOf<Pair<String, Rect>>()
        for (line in lines) {
            val text = normalize(line.text)
            if (text.isEmpty()) continue
            val r = line.bounds ?: return null
            if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height || r.width() <= 0 || r.height() <= 0) return null
            if (r.bottom <= height * .05 || r.top >= height * .985) continue
            content += text to r
        }
        fun takeRow(top: Double, bottom: Double, centered: Boolean = false): String? {
            val row = content.filter { (_, r) -> r.top >= height * top && r.bottom <= height * bottom }
            if (row.isEmpty()) return null
            if (centered && row.any { (_, r) -> r.centerX() !in (width * .25).toInt()..(width * .75).toInt() }) return null
            // A currency glyph can be separate, but two vertically unrelated values are not one row.
            if (row.maxOf { it.second.top } >= row.minOf { it.second.bottom }) return null
            content.removeAll(row.toSet())
            return row.sortedBy { it.second.left }.joinToString("") { it.first }
        }
        diagnostics["ocrReceivedStage"] = 1
        val status = takeRow(.24, .31, true) ?: return null
        diagnostics["ocrReceivedStatusExact"] = if (Regex(OWN_COMPLETED).matches(status)) 1 else 0
        diagnostics["ocrReceivedStage"] = 2
        val money = takeRow(.31, .38, true) ?: return null
        diagnostics["ocrReceivedStage"] = 3
        val balance = takeRow(.38, .43, true)?.let {
            // Observed ML Kit confusion in this non-financial navigation label only.
            if (it == "零线余额") "零钱余额" else it
        } ?: return null
        diagnostics["ocrReceivedBalanceExact"] = if (balance == "零钱余额") 1 else 0
        diagnostics["ocrReceivedStage"] = 4
        val sent = takeRow(.44, .49) ?: return null
        diagnostics["ocrReceivedSentExact"] = if (Regex("转账时间$DATE").matches(sent)) 1 else 0
        diagnostics["ocrReceivedStage"] = 5
        val received = takeRow(.49, .535) ?: return null
        diagnostics["ocrReceivedTimeExact"] = if (Regex("收款时间$DATE").matches(received)) 1 else 0
        diagnostics["ocrReceivedStage"] = 6
        // Navigation links are not financial evidence and may be omitted by OCR.
        val detail = takeRow(.93, .985, true).orEmpty().let {
            if (it == "账单详惰") "账单详情" else it
        }
        // Only the observed optional savings banner is outside the financial fields.
        if (content.any { (_, r) -> r.top < height * .535 || r.bottom > height * .66 }) return null
        diagnostics["ocrReceivedStage"] = 7
        val extra = content.sortedWith(compareBy({ it.second.top }, { it.second.left })).joinToString("") { it.first }
        return parseText(status + money + balance + sent + received + extra + detail, now, zone, "本地OCR")
            .also { diagnostics["ocrReceivedStage"] = if (it != null) 9 else 8 }
    }
}

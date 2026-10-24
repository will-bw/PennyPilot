package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.util.MoneyUtil

/** The four-anchor outgoing result observed on WeChat 8.0.78; never a generic screenshot parser. */
internal object WechatOpaqueTransferReader {
    const val PACKAGE = "com.tencent.mm"
    const val ACTIVITY = "com.tencent.mm.framework.app.UIPageFragmentActivity"
    private val recipient = Regex("^待(.{1,40})确认收款$")
    // ML Kit may omit the separately drawn yuan glyph. A bare value is accepted only with
    // exactly two decimal places in this template's dedicated amount region, never arbitrary text.
    private val amount = Regex("^(?:[¥￥]([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)|([0-9]+(?:,[0-9]{3})*\\.[0-9]{2}))$")

    /** Numeric-only diagnostics for template maintenance; never export names or recognized text. */
    fun diagnostics(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): Map<String, Int> = buildMap {
        put("ocrLines", lines.size)
        put("imageWidth", width)
        put("imageHeight", height)
        val moneyLines = lines.filter { it.bounds?.let { r -> r.top >= height * .22 && r.bottom <= height * .43 } == true }
        put("ocrMoneyRegionLines", moneyLines.size)
        put("ocrMoneyRegionDigits", moneyLines.sumOf { it.text.count(Char::isDigit) })
        put("ocrMoneyRegionLetters", moneyLines.sumOf { it.text.count(Char::isLetter) })
        put("ocrMoneyRegionYen", moneyLines.sumOf { it.text.count { c -> c in "¥￥" } })
        put("ocrMoneyRegionLatinY", moneyLines.sumOf { it.text.count { c -> c == 'Y' || c == 'y' } })
        for ((key, matching) in listOf(
            "status" to lines.filter { it.text.filterNot(Char::isWhitespace) == "支付成功" },
            "recipient" to lines.filter { recipient.matches(it.text.filterNot(Char::isWhitespace)) },
            "amount" to lines.filter { amount.matches(it.text.filterNot(Char::isWhitespace)) },
            "done" to lines.filter { it.text.filterNot(Char::isWhitespace) == "完成" }
        )) {
            put("ocr${key}Count", matching.size)
            if (height > 0) matching.singleOrNull()?.bounds?.let { rect ->
                put("ocr${key}Top", rect.top * 1000 / height)
                put("ocr${key}Bottom", rect.bottom * 1000 / height)
            }
        }
    }

    fun accepts(packageName: String, className: String?, snapshot: PaymentScreenReader.Snapshot): Boolean =
        packageName == PACKAGE && className == ACTIVITY && snapshot.status == "empty_page" &&
            !snapshot.hasInput && snapshot.diagnostics["visitedNodes"] == 1 &&
            snapshot.diagnostics["hiddenNodes"] == 1 && snapshot.diagnostics["textNodes"] == 0 &&
            listOf("editableNodes", "passwordNodes", "missingChildren", "foreignNodes")
                .all { snapshot.diagnostics[it] == 0 }

    fun parse(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): NotificationParseResult? {
        if (width <= 0 || height <= width || lines.size !in 4..40) return null
        fun normalized(text: String) = text.filterNot(Char::isWhitespace)
            .map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        val content = mutableListOf<Pair<String, Rect>>()
        for (line in lines) {
            val text = normalized(line.text)
            if (text.isEmpty()) continue
            val bounds = line.bounds ?: return null
            if (bounds.left < 0 || bounds.top < 0 || bounds.right > width || bounds.bottom > height ||
                bounds.width() <= 0 || bounds.height() <= 0) return null
            // Only status/navigation bars are excluded. All application text must match the template.
            if (bounds.bottom <= height * .045 || bounds.top >= height * .97) continue
            content += text to bounds
        }
        // Some OCR versions split the currency symbol from the adjacent digits.
        val currency = content.singleOrNull { it.first in setOf("¥", "￥") }
        if (currency != null) {
            val digits = content.filter { Regex("^[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?$").matches(it.first) }
                .singleOrNull { (_, rect) ->
                    val r = currency.second
                    rect.left >= r.right && rect.left - r.right < width * .12 &&
                        minOf(rect.bottom, r.bottom) > maxOf(rect.top, r.top)
                } ?: return null
            content.remove(currency)
            content.remove(digits)
            content += (currency.first + digits.first) to Rect(currency.second).apply { union(digits.second) }
        }
        if (content.size != 4) return null
        val status = content.singleOrNull { it.first == "支付成功" } ?: return null
        val waiting = content.singleOrNull { recipient.matches(it.first) } ?: return null
        val money = content.singleOrNull { amount.matches(it.first) } ?: return null
        val done = content.singleOrNull { it.first == "完成" } ?: return null
        fun centered(rect: Rect, top: Double, bottom: Double): Boolean =
            rect.centerX() in (width * .25).toInt()..(width * .75).toInt() &&
                rect.top >= height * top && rect.bottom <= height * bottom
        if (!centered(status.second, .045, .16) || !centered(waiting.second, .16, .32) ||
            !centered(money.second, .22, .43) || !centered(done.second, .74, .95) ||
            status.second.bottom >= waiting.second.top || waiting.second.bottom >= money.second.top ||
            money.second.bottom >= done.second.top) return null
        val name = recipient.matchEntire(waiting.first)!!.groupValues[1]
        if (Regex("密码|输入|失败|取消|待支付|聊天|历史|账单|教程|示例|备注").containsMatchIn(name)) return null
        val rawAmount = amount.matchEntire(money.first)!!.groupValues.drop(1).first(String::isNotBlank)
        val cents = MoneyUtil.parsePositiveYuanToCents(rawAmount) ?: return null
        return NotificationParseResult(amountCents = cents, merchant = name, direction = "EXPENSE",
            paymentMethod = "微信支付", businessKind = "unknown", scene = "transfer_sent",
            ruleVersion = "wechat-opaque-transfer-2026-10-24.1", fieldEvidence = mapOf(
                "amount" to "本地OCR·金额区域：${money.first}",
                "direction" to "本地OCR·本人付款完成：支付成功",
                "paymentStatus" to "本地OCR·顶部状态：支付成功",
                "merchant" to "本地OCR·收款人状态：${waiting.first}"
            ))
    }
}

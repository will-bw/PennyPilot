package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.util.MoneyUtil

/** Only WeChat's wallet result/detail activities. Chat bubbles and amount input pages are excluded. */
internal object WechatRedPacketReader {
    const val PREPARE_ACTIVITY = "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
    const val DETAIL_ACTIVITY = "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI"
    const val RECEIVE_ACTIVITY = "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI"
    private val money = Regex("^(?:[¥￥]([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)|([0-9]+(?:,[0-9]{3})*\\.[0-9]{2}))$")
    private val summary = Regex("^红包金额([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)元[,，]等待对方领取$")
    private fun normalize(value: String) = value.filterNot(Char::isWhitespace)

    fun accepts(packageName: String, className: String?, snapshot: PaymentScreenReader.Snapshot): Boolean =
        packageName == WechatOpaqueTransferReader.PACKAGE && className == DETAIL_ACTIVITY &&
            snapshot.status == "empty_page" && !snapshot.hasInput &&
            snapshot.diagnostics["visitedNodes"] == 1 && snapshot.diagnostics["hiddenNodes"] == 1 &&
            snapshot.diagnostics["textNodes"] == 0 &&
            listOf("editableNodes", "passwordNodes", "missingChildren", "foreignNodes").all { snapshot.diagnostics[it] == 0 }

    private fun content(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): List<Pair<String, Rect>>? {
        if (width <= 0 || height <= width || lines.size !in 3..40) return null
        val result = mutableListOf<Pair<String, Rect>>()
        for (line in lines) {
            val value = normalize(line.text)
            if (value.isBlank()) continue
            val r = line.bounds ?: return null
            if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height || r.width() <= 0 || r.height() <= 0) return null
            if (r.bottom <= height * .05 || r.top >= height * .985) continue
            result += value to r
        }
        return result
    }

    /** Mechanism fixtures only until this payment-result layout receives device acceptance. */
    fun parsePaymentResult(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): NotificationParseResult? {
        val parts = content(lines, width, height) ?: return null
        if (parts.size !in 3..4) return null
        val status = parts.singleOrNull { it.first == "支付成功" } ?: return null
        val amount = parts.singleOrNull { money.matches(it.first) } ?: return null
        val done = parts.singleOrNull { it.first in setOf("完成", "返回聊天") } ?: return null
        val other = parts - setOf(status, amount, done)
        if (other.any { it.first != "微信红包" }) return null
        if (status.second.top < height * .05 || status.second.bottom > height * .3 ||
            amount.second.top < height * .2 || amount.second.bottom > height * .6 ||
            done.second.top < height * .72 || done.second.bottom > height * .97 ||
            status.second.bottom >= amount.second.top || amount.second.bottom >= done.second.top) return null
        val raw = money.matchEntire(amount.first)!!.groupValues.drop(1).first(String::isNotBlank)
        val cents = MoneyUtil.parsePositiveYuanToCents(raw) ?: return null
        return sent(cents, amount.first, "支付成功")
    }

    /** Layout transcribed from the observed 1264 x 2780 sender detail. Requires a paid flow externally. */
    fun parseSentDetail(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): NotificationParseResult? {
        val parts = content(lines, width, height) ?: return null
        val sender = parts.singleOrNull { it.first.endsWith("的红包") && it.second.top >= height * .15 && it.second.bottom <= height * .22 }
            ?: return null
        val amountRow = parts.filter { it.second.top >= height * .26 && it.second.bottom <= height * .34 }
        if (amountRow.isEmpty() || amountRow.maxOf { it.second.top } >= amountRow.minOf { it.second.bottom }) return null
        val row = amountRow.sortedBy { it.second.left }.joinToString("") { it.first }
        val match = summary.matchEntire(row) ?: return null
        val cents = MoneyUtil.parsePositiveYuanToCents(match.groupValues[1]) ?: return null
        for ((text, r) in parts - amountRow.toSet() - sender) {
            // Greeting text is separate from the receipt, never used as status or amount evidence.
            if (r.top >= height * .21 && r.bottom <= height * .25 && text.length <= 50) continue
            if (r.top >= height * .94 && text in setOf("未领取的红包，将于24小时后发起退款", "未领取的红包,将于24小时后发起退款")) continue
            return null
        }
        return sent(cents, match.groupValues[1] + "元", row)
    }

    /** Own credited strip only. Group totals/other recipients below it cannot supply the amount. */
    fun parseReceivedDetail(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int): NotificationParseResult? {
        val parts = content(lines, width, height) ?: return null
        if (parts.any { Regex("请输入支付密码|输入金额|聊天记录|发送消息|红包已过期|领取失败").containsMatchIn(it.first) }) return null
        val sender = parts.singleOrNull { it.first.endsWith("的红包") && it.second.top >= height * .12 && it.second.bottom <= height * .28 }
            ?: return null
        val credited = parts.singleOrNull { Regex("已存入零钱(?:[,，]可直接(?:使用|消费))?").matches(it.first) &&
            it.second.top >= height * .22 && it.second.bottom <= height * .55 } ?: return null
        val ownMoney = parts.filter { (value, r) ->
            r.top > sender.second.bottom && r.bottom < credited.second.top &&
                credited.second.top - r.bottom <= height * .12 &&
                r.height() >= credited.second.height() * 1.2 &&
                money.matches(value.removeSuffix("元"))
        }.singleOrNull() ?: return null
        val raw = money.matchEntire(ownMoney.first.removeSuffix("元"))!!.groupValues.drop(1).first(String::isNotBlank)
        val cents = MoneyUtil.parsePositiveYuanToCents(raw) ?: return null
        return NotificationParseResult(cents, sender.first.removeSuffix("的红包"), "INCOME",
            paymentMethod="微信支付",businessKind="unknown",scene="red_packet_received",
            ruleVersion="wechat-red-packet-2026-10-24.1",fieldEvidence=mapOf(
                "amount" to "本地OCR·本人领取金额区域：${ownMoney.first}",
                "direction" to "本地OCR·本人领取到账：${credited.first}",
                "paymentStatus" to "本地OCR·本人领取到账：${credited.first}",
                "merchant" to "本地OCR·红包发送方：${sender.first}"
            ))
    }

    private fun sent(cents: Long, amount: String, state: String) = NotificationParseResult(cents, null, "EXPENSE",
        paymentMethod = "微信支付", businessKind = "unknown", scene = "red_packet_sent",
        ruleVersion = "wechat-red-packet-2026-10-24.1", fieldEvidence = mapOf(
            "amount" to "本地OCR·红包金额：$amount", "direction" to "本地OCR·本人红包发出：$state",
            "paymentStatus" to "本地OCR·本次已核验支付结果：$state"
        ))
}

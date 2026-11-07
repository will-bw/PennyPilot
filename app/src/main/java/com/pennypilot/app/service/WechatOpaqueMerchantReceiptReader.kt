package com.pennypilot.app.service

import android.graphics.Rect
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.data.parser.WalletReceiptDetailsParser
import com.pennypilot.app.util.MoneyUtil

/** Candidate from the already-authorized WeChat wallet window. */
internal object WechatOpaqueMerchantReceiptReader {
    data class Candidate(val receipt: NotificationParseResult, val merchantOrderId: String?, val merchant: String,
        val independentlyVerified: Boolean = false)

    private val statusTitles = setOf("支付成功", "付款成功")
    private val paidAmountLabels = setOf("实付金额", "实付", "实际支付", "实际付款", "实付款", "实扣金额", "实扣")
    private val paidAmount = Regex("^(${paidAmountLabels.joinToString("|")})\\s*[:：]\\s*(?:[¥￥]\\s*)?" +
        "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:元)?$")
    private val merchant = Regex("""^(?:商户名称|收款商户|商家名称|商户|商家|店铺名称|门店名称)\s*[:：]\s*(.{2,80})$""")
    private val merchantOrderLabels = setOf("商户订单号", "商家订单号", "商户单号")
    private val merchantOrder = Regex("^(?:${merchantOrderLabels.joinToString("|")})\\s*[:：]\\s*([A-Za-z0-9]{10,64})$")
    private val walletOrderLabels = setOf("微信支付单号", "微信支付交易单号", "微信支付订单号", "交易单号")
    private val walletOrder = Regex("^(${walletOrderLabels.joinToString("|")})\\s*[:：]\\s*([0-9]{20,32})$")
    private val payerTimeLabels = setOf("支付时间", "付款时间", "交易时间")
    private val payerTime = Regex("^(?:${payerTimeLabels.joinToString("|")})\\s*[:：]\\s*.+$")
    private const val DIRECT_WINDOW_MS = 120_000L
    private val proofLabels = setOf("商户名称", "收款商户", "商家名称", "商户", "商家", "店铺名称", "门店名称") +
        paidAmountLabels + merchantOrderLabels + walletOrderLabels + payerTimeLabels
    private val nonReceipt = Regex("截图|截屏|相册|图片预览|查看图片|保存图片|编辑图片|聊天记录|发送消息|请输入|支付密码|验证码|" +
        "账单列表|交易记录|历史订单|订单列表|教程|示例|待支付|待付款|支付失败|付款失败|支付中|未完成|已关闭|" +
        "收款成功|收款到账|已收钱|退款(?:成功|受理|处理中)|对方收款|确认收款")

    private data class VisibleLine(val text: String, val bounds: Rect)

    fun parse(lines: List<PaymentImageReader.OcrLine>, width: Int, height: Int, observedAt: Long? = null): Candidate? {
        if (width <= 0 || height <= width || lines.size !in 5..40) return null
        val visible = buildList {
            for (line in lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val bounds = line.bounds ?: return null
                if (bounds.left < 0 || bounds.top < 0 || bounds.right > width || bounds.bottom > height ||
                    bounds.width() <= 0 || bounds.height() <= 0) return null
                if (bounds.bottom <= height * .045 || bounds.top >= height * .97) continue
                if (nonReceipt.containsMatchIn(text)) return null
                add(VisibleLine(text, bounds))
            }
        }
        val content = bindSplitProofRows(visible, width) ?: return null
        val status = content.singleOrNull { it.text in statusTitles } ?: return null
        val done = content.singleOrNull { it.text == "完成" } ?: return null
        if (status.bounds.top < height * .045 || status.bounds.bottom > height * .24 ||
            done.bounds.top < height * .65 || done.bounds.bottom > height * .96) return null
        if (listOf(status, done).any { it.bounds.centerX() !in (width * .25).toInt()..(width * .75).toInt() }) return null

        val amountLine = content.singleOrNull { fieldLabel(it.text) in paidAmountLabels } ?: return null
        val merchantLine = content.singleOrNull { merchant.matches(it.text) } ?: return null
        fun inPanel(line: VisibleLine) = line.bounds.top >= status.bounds.bottom && line.bounds.bottom <= done.bounds.top
        if (!inPanel(amountLine) || !inPanel(merchantLine)) return null
        val merchantOrders = content.filter { fieldLabel(it.text) in merchantOrderLabels }
        if (merchantOrders.size > 1) return null
        val merchantId = merchantOrders.singleOrNull()?.takeIf(::inPanel)?.let {
            CaptureEventIdentity.reliableOrderId(merchantOrder.matchEntire(it.text)?.groupValues?.get(1))
        }
        val walletOrders = content.filter { fieldLabel(it.text) in walletOrderLabels }
        val walletLine = walletOrders.singleOrNull()?.takeIf(::inPanel)
        val walletId = walletLine?.let { walletOrder.matchEntire(it.text)?.groupValues?.get(2) }
        if (merchantId == null && walletId == null) return null
        val rawAmount = paidAmount.matchEntire(amountLine.text)?.groupValues?.get(2) ?: return null
        val cents = MoneyUtil.parsePositiveYuanToCents(rawAmount) ?: return null
        val name = merchant.matchEntire(merchantLine.text)!!.groupValues[1].trim()
        if (CaptureEventIdentity.merchantFingerprint(name) == null) return null

        val text = content.sortedBy { it.bounds.top }.joinToString("\n") { line ->
            // The generic parser recognizes the wallet's 交易单号 role. Keep the actual OCR
            // quote below, including WeChat's more explicit 微信支付单号 label when present.
            val normalizedId = walletOrder.matchEntire(line.text)?.groupValues?.get(2)
            if (normalizedId == null) line.text else "交易单号：$normalizedId"
        }
        val receipt = PaymentTextParser.analyzeScreen(WechatOpaqueTransferReader.PACKAGE, text,
            evidenceSource = "本地OCR").parsed ?: return null
        if (receipt.direction != "EXPENSE" || receipt.scene != "merchant_payment" || receipt.amountCents != cents ||
            receipt.details.merchantName != name) return null
        val handoffId = merchantId?.takeIf { receipt.orderId == it }
        // Keep the generic parser's identity choice. Different merchant and wallet IDs need
        // the checkout handoff, so a second source cannot create a new standalone identity.
        if (observedAt != null && walletLine != null && walletId != null && receipt.orderId == walletId &&
            (merchantOrders.isEmpty() || merchantId == walletId)) {
            val timeLine = content.filter { fieldLabel(it.text) in payerTimeLabels }.singleOrNull()
                ?.takeIf { inPanel(it) && payerTime.matches(it.text) }
            val time = timeLine?.let { WalletReceiptDetailsParser.completedTime(it.text, false, observedAt) }
            val factsTime = WalletReceiptDetailsParser.completedTime(PaymentTextParser.receiptFacts(text), false, observedAt)
            if (time != null && factsTime?.occurredAt == time.occurredAt &&
                observedAt - time.occurredAt in 0..DIRECT_WINDOW_MS) {
                return Candidate(receipt.copy(occurredAt = time.occurredAt,
                    ruleVersion = "wechat-opaque-merchant-direct-2026-11-07.1",
                    fieldEvidence = receipt.fieldEvidence + mapOf("orderId" to "本地OCR·${walletLine.text}",
                        "occurredAt" to "本地OCR·${time.evidence}")), handoffId, name, independentlyVerified = true)
            }
        }
        return handoffId?.let {
            Candidate(receipt.copy(ruleVersion = "wechat-opaque-merchant-2026-11-07.1"), it, name)
        }
    }

    private fun fieldLabel(text: String): String = text.substringBefore(':').substringBefore('：').trim()

    private fun bindSplitProofRows(lines: List<VisibleLine>, width: Int): List<VisibleLine>? {
        fun label(line: VisibleLine) = line.text.trimEnd(':', '：').trimEnd().takeIf(proofLabels::contains)
        val used = mutableSetOf<VisibleLine>()
        val joined = lines.mapNotNull { line ->
            val name = label(line) ?: return@mapNotNull null
            // OCR may split the two columns. A value must share this exact row, and
            // a neighbouring row or an extra value cannot supply any receipt proof.
            val value = lines.singleOrNull { other ->
                val left = line.bounds
                val right = other.bounds
                label(other) == null && left.right <= right.left && left.centerX() < width / 2 &&
                    right.centerX() > width / 2 &&
                    kotlin.math.abs(left.centerY() - right.centerY()) * 2 <= minOf(left.height(), right.height())
            }
            val optional = name in walletOrderLabels || name in payerTimeLabels
            if (value == null) {
                if (optional) return@mapNotNull null
                return null
            }
            val field = "$name：${value.text}"
            if (listOf(paidAmount, merchant, merchantOrder, walletOrder, payerTime).none { it.matches(field) } ||
                !used.add(line) || !used.add(value)) return null
            VisibleLine(field, Rect(line.bounds).apply { union(value.bounds) })
        }
        return lines.filterNot(used::contains) + joined
    }
}

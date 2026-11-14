package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity

/** 云解析必须同时满足用户启用和本地支付候选判断，聊天内容不用于云端分类。 */
object NotificationParser {
    fun receiptContent(packageName: String, title: String, content: String): String =
        PaymentTextParser.notificationReceiptContent(packageName, title, content)

    fun isCloudCandidate(packageName: String, title: String, content: String): Boolean {
        val receipt = receiptContent(packageName, title, content)
        return PaymentTextParser.isNotificationCandidate(packageName, title, receipt) &&
            PaymentTextParser.canUseFallback("$title\n$receipt", requireAmount = true)
    }

    suspend fun parse(
        packageName: String,
        title: String,
        content: String,
        rules: List<ParseRuleEntity>,
        cloudParsingEnabled: Boolean,
        observedAt: Long? = null,
        cloudParse: suspend (String) -> NotificationParseResult?
    ): NotificationParseResult? {
        val receiptContent = receiptContent(packageName, title, content)
        if (!PaymentTextParser.isNotificationCandidate(packageName, title, receiptContent)) return null
        val text = "$title\n$receiptContent".trim()
        val matchingRules = rules.filter { it.appPackage == packageName }
        PaymentTextParser.parseNotification(packageName, title, receiptContent)?.let { local ->
            // 自定义规则仍可补充商户，不能覆盖本地凭证已确认的金额和方向。
            val custom = if (local.merchant == null) ParseRuleEngine.parse(text, matchingRules) else null
            val receipt = if (custom != null && custom.amountCents == local.amountCents && custom.direction == local.direction) {
                local.copy(merchant = custom.merchant?.takeIf { it.isNotBlank() })
            } else local
            val refund = receipt.scene in setOf("refund_received", "red_packet_returned")
            val time = observedAt?.let {
                WalletReceiptDetailsParser.completedTime(PaymentTextParser.receiptFacts(text), refund, it)
            } ?: return receipt
            return receipt.copy(occurredAt = time.occurredAt,
                fieldEvidence = receipt.fieldEvidence + ("occurredAt" to "通知·${time.evidence}"))
        }
        if (!PaymentTextParser.canUseFallback(text)) return null
        val result = ParseRuleEngine.parse(text, matchingRules)
            ?: if (cloudParsingEnabled && PaymentTextParser.canUseFallback(text, requireAmount = true)) cloudParse(text) else null
        return result?.takeIf { PaymentTextParser.isConsistentFallback(text, it) }
            ?.let { PaymentTextParser.withEvidence(packageName, text, it) }
    }
}

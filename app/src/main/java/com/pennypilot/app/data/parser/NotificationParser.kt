package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity

/** 云解析必须同时满足用户启用和本地支付候选判断，聊天内容不用于云端分类。 */
object NotificationParser {
    fun isCloudCandidate(packageName: String, title: String, content: String): Boolean =
        PaymentTextParser.isNotificationCandidate(packageName, title, content) &&
            PaymentTextParser.canUseFallback("$title\n$content", requireAmount = true)

    suspend fun parse(
        packageName: String,
        title: String,
        content: String,
        rules: List<ParseRuleEntity>,
        cloudParsingEnabled: Boolean,
        cloudParse: suspend (String) -> NotificationParseResult?
    ): NotificationParseResult? {
        if (!PaymentTextParser.isNotificationCandidate(packageName, title, content)) return null
        val text = "$title\n$content".trim()
        val matchingRules = rules.filter { it.appPackage == packageName }
        PaymentTextParser.parseNotification(packageName, title, content)?.let { local ->
            // 自定义规则仍可补充商户，不能覆盖本地凭证已确认的金额和方向。
            val custom = if (local.merchant == null) ParseRuleEngine.parse(text, matchingRules) else null
            return if (custom != null && custom.amountCents == local.amountCents && custom.direction == local.direction) {
                local.copy(merchant = custom.merchant?.takeIf { it.isNotBlank() })
            } else local
        }
        if (!PaymentTextParser.canUseFallback(text)) return null
        val result = ParseRuleEngine.parse(text, matchingRules)
            ?: if (cloudParsingEnabled && isCloudCandidate(packageName, title, content)) cloudParse(text) else null
        return result?.takeIf { PaymentTextParser.isConsistentFallback(text, it) }
            ?.let { PaymentTextParser.withEvidence(packageName, text, it) }
    }
}

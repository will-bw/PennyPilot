package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.util.MoneyUtil

data class NotificationParseResult(
    val amountCents: Long,
    val merchant: String?,
    val direction: String,  // "EXPENSE" or "INCOME"
    val orderId: String? = null,
    val paymentMethod: String? = null
)

object ParseRuleEngine {
    private val completeAmount = Regex("""(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?""")
    private val unsuccessful = Regex("""(?:付款|支付|消费|收款|转账|交易)(?:已)?(?:失败|取消|中)|(?:未|待)(?:付款|支付|到账)""")

    /**
     * Try to parse notification text using ordered rules.
     * Returns null if no rule matches.
     */
    fun parse(text: String, rules: List<ParseRuleEntity>): NotificationParseResult? {
        if (unsuccessful.containsMatchIn(text) || !PaymentTextParser.canUseFallback(text)) return null
        for (rule in rules.filter { it.enabled }.sortedBy { it.priority }) {
            // 自定义/恢复的坏规则只跳过本条，不能让整个通知链路失效。
            val amountMatch = runCatching { Regex(rule.regexAmount).find(text) }.getOrNull() ?: continue
            val amountGroup = amountMatch.groups.let { if (it.size > 1) it[1] else null } ?: continue
            val amountStr = amountGroup.value
            if (!completeAmount.matches(amountStr)) continue
            // 旧规则/自定义规则也不能把 1,800 或 1.234 的前缀当作完整金额。
            val before = text.getOrNull(amountGroup.range.first - 1)
            val after = text.getOrNull(amountGroup.range.last + 1)
            if ((before != null && before in "0123456789.,") ||
                (after != null && after in "0123456789.,")) continue
            val amountCents = MoneyUtil.parsePositiveYuanToCents(amountStr.replace(",", "")) ?: continue
            if (rule.directionHint !in setOf("EXPENSE", "INCOME")) continue

            val merchant = rule.regexMerchant?.let { regex ->
                runCatching { Regex(regex).find(text)?.groupValues?.getOrNull(1)?.trim() }.getOrNull()
            }

            val result = NotificationParseResult(
                amountCents = amountCents,
                merchant = merchant,
                direction = rule.directionHint
            )
            if (PaymentTextParser.isConsistentFallback(text, result)) return result
        }
        return null
    }
}

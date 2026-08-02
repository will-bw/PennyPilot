package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity

data class NotificationParseResult(
    val amountCents: Long,
    val merchant: String?,
    val direction: String  // "EXPENSE" or "INCOME"
)

object ParseRuleEngine {

    /**
     * Try to parse notification text using ordered rules.
     * Returns null if no rule matches.
     */
    fun parse(text: String, rules: List<ParseRuleEntity>): NotificationParseResult? {
        for (rule in rules) {
            val amountMatch = Regex(rule.regexAmount).find(text) ?: continue
            val amountStr = amountMatch.groupValues.getOrNull(1) ?: continue
            val amountYuan = amountStr.toDoubleOrNull() ?: continue
            val amountCents = (amountYuan * 100).toLong()
            if (amountCents <= 0) continue

            val merchant = rule.regexMerchant?.let { regex ->
                Regex(regex).find(text)?.groupValues?.getOrNull(1)?.trim()
            }

            return NotificationParseResult(
                amountCents = amountCents,
                merchant = merchant,
                direction = rule.directionHint
            )
        }
        return null
    }
}

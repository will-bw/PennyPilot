package com.pennypilot.app.data.parser

import com.pennypilot.app.data.db.entity.ParseRuleEntity

/** 云解析必须同时满足用户启用和本地支付候选判断，聊天内容不用于云端分类。 */
object NotificationParser {
    private val paymentTitles = mapOf(
        "com.tencent.mm" to setOf("微信支付", "微信收款助手", "收款助手"),
        "com.eg.android.AlipayGphone" to setOf("支付宝", "支付宝通知", "支付助手", "收钱码")
    )
    private val transactionAction = Regex("""付款|支付|消费|收款|到账|收入|转账|退款|红包""")
    private val currencyAmount = Regex("""[¥￥]\s*\d|\d(?:[\d,.]*\d)?\s*元""")
    private val nonTransaction = Regex("""失败|取消|待支付|待付款|未付款|未支付|优惠|优惠券|立减|广告|验证码""")

    fun isCloudCandidate(packageName: String, title: String, content: String): Boolean =
        title.trim() in paymentTitles[packageName].orEmpty() &&
            transactionAction.containsMatchIn(content) &&
            currencyAmount.containsMatchIn(content) &&
            !nonTransaction.containsMatchIn(content)

    suspend fun parse(
        packageName: String,
        title: String,
        content: String,
        rules: List<ParseRuleEntity>,
        cloudParsingEnabled: Boolean,
        cloudParse: suspend (String) -> NotificationParseResult?
    ): NotificationParseResult? {
        val text = "$title $content".trim()
        return ParseRuleEngine.parse(text, rules)
            ?: if (cloudParsingEnabled && isCloudCandidate(packageName, title, content)) {
                cloudParse(text)
            } else null
    }
}

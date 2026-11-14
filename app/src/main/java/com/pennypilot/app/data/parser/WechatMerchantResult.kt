package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil

/** A short WeChat payment result can show the payee as the unlabelled amount heading. */
internal object WechatMerchantResult {
    private val completed = Regex("^(?:支付|付款)成功[。！!]?$")
    private val currencyAmount = Regex("^[¥￥]\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?:\\s*元)?$")
    private val endActions = setOf("完成", "查看账单", "查看账单详情")
    private val chrome = endActions + setOf("返回", "关闭", "微信支付", "微信")
    private val nonMerchant = Regex("收付款|收款方|付款方|商户名称|收款人|密码|验证码|二维码|收款码|转账|红包|优惠|订单|金额|付款方式|支付方式|已完成|处理中|待|失败|取消|通知|教程|示例|聊天|备注")

    fun enrich(text: String, receipt: NotificationParseResult): NotificationParseResult {
        if (receipt.scene != "merchant_payment" || receipt.direction != "EXPENSE" || receipt.merchant != null ||
            receipt.details.merchantName != null || receipt.details.payeeName != null) return receipt
        val rows = PaymentTextParser.receiptFacts(text).lines().map(String::trim).filter(String::isNotEmpty)
        if (rows.lastOrNull() !in endActions) return receipt
        val body = rows.filterNot(chrome::contains)
        if (body.size !in 3..4 || !completed.matches(body.first())) return receipt
        val merchant = body[1].takeIf {
            it.length in 2..60 && it.any(Char::isLetter) && it.none { character -> character in ":：¥￥" } &&
                !nonMerchant.containsMatchIn(it)
        } ?: return receipt
        val amount = currencyAmount.matchEntire(body.drop(2).joinToString(""))?.groupValues?.get(1)
            ?.let(MoneyUtil::parsePositiveYuanToCents)
        if (amount != receipt.amountCents) return receipt
        return receipt.copy(merchant = merchant, details = receipt.details.copy(merchantName = merchant,
            fieldSources = receipt.details.fieldSources + ("merchantName" to "页面·支付结果抬头")),
            fieldEvidence = receipt.fieldEvidence + ("merchant" to "页面·支付结果抬头：$merchant"))
    }
}

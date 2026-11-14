package com.pennypilot.app.data.parser

/**
 * Application/page routing before financial parsing. Result pages and single transaction details
 * are deliberately separate from order lists, checkout forms and chat/video content.
 */
object PaymentSceneAdapters {
    enum class Kind { RESULT, DETAIL, OPAQUE_RECEIPT, PARTIAL_RESULT }
    data class Match(val id: String, val kind: Kind, val allowsCloudImage: Boolean = true) {
        val historicalDetail: Boolean get() = kind == Kind.DETAIL
    }

    private val walletDetails = setOf("账单详情", "交易详情", "退款详情", "收款详情", "转账详情", "支付详情", "收支详情")
    private val shoppingDetails = walletDetails + setOf("订单详情", "订单信息", "退款进度")
    private val foodDetails = shoppingDetails
    private val title = Regex("^(?:支付|付款|消费|交易|转账|扣款|收款|收钱|退款|退回|退还)(?:已)?(?:成功|完成|到账)[!！。]?$|^(?:已支付|已付款|已转账|已收款|已收钱|已退款|已领取红包|已存入零钱|已发红包|红包已存入零钱|红包已发出|红包发送成功|红包已退还|已原路退回)[!！。]?$")
    private val field = Regex("^(?:实付金额|实付款|实际支付|付款金额|支付金额|到账金额|收款金额|转账金额|交易金额|退款金额|本人领取金额|领取金额|收到红包金额|红包金额|退还金额|提现金额|充值金额|转入金额|转出金额|还款金额|交易时间|支付时间|付款时间|收款时间|到账时间|交易单号|微信支付单号|订单编号|订单号|支付订单号|商户订单号|退款单号|收款方|收款人|商户名称|支付方式|付款方式|收支类型)(?:[：:\\s]|$)")
    private val money = Regex("^(?:[¥￥]\\s*)?[+−-]?\\s*[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?\\s*(?:元)?$")
    private val unrelated = Regex("聊天记录|群聊|按住说话|发送消息|发消息|说点什么|写评论|发布评论|全部评论|视频评论|直播间|条未读|朋友圈|(?:^|\\n)\\s*(?:评论|推荐|关注|朋友|消息|我的订单|全部订单|订单列表|账单列表|历史订单|交易记录|支付记录)\\s*(?:\\n|$)")
    private val inputPage = Regex("请输入(?:金额|支付密码|密码|验证码)|输入(?:支付密码|付款金额|转账金额)|确认付款|确认支付|立即支付|立即付款|去支付|去付款|确认收款|待确认收款|请收钱")
    private val failure = Regex("(?:支付|付款|转账|交易|退款|收款)(?:已)?(?:失败|取消|关闭|处理中|受理|受理中|审核中)|(?:未|待)(?:支付|付款|到账)|退款申请|申请退款|余额不足|(?:^|\\n)(?:示例|教程|截图示意)(?:[：:\\n]|$)")
    private val opaqueActivities = mapOf("com.tencent.mm" to setOf(
        "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI",
        "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI"
    ))

    private fun normalizePage(packageName: String, text: String, activityName: String? = null): PaymentPageNormalizer.Result =
        CommercePaymentPages.normalize(packageName, PaymentPageNormalizer.normalize(packageName, text, activityName), activityName)

    fun detailTitles(packageName: String): Set<String> = when (PaymentAppRegistry.profile(packageName)?.family) {
        PaymentAppRegistry.Family.WALLET -> walletDetails
        PaymentAppRegistry.Family.SHOPPING, PaymentAppRegistry.Family.VIDEO_SHOPPING -> shoppingDetails
        PaymentAppRegistry.Family.FOOD -> foodDetails
        null -> emptySet()
    }

    /** A detail heading keeps its time/identity requirements even when parsing fails. */
    fun isDetailPage(packageName: String, text: String, activityName: String? = null): Boolean {
        if (PaymentAppRegistry.profile(packageName) == null) return false
        if (WalletPaymentPageAdapter.isDedicatedDetailActivity(packageName, activityName)) return true
        if (packageName == "com.tencent.mm" && WechatTransferReceiptAdapter.isReceived(text)) return true
        val facts = PaymentTextParser.receiptFacts(normalizePage(packageName, text, activityName).text)
        // This is a fallback guard, not a template eligibility check: a later detail heading
        // must not turn a failed historical receipt into a newly observed payment.
        return facts.lineSequence().map(String::trim).filter(String::isNotBlank)
            .any { it in detailTitles(packageName) }
    }

    fun match(packageName: String, text: String, activityName: String? = null,
        structure: PaymentScreenStructure? = null): Match? {
        val profile = PaymentAppRegistry.profile(packageName) ?: return null
        if (text.length > 12_000 || unrelated.containsMatchIn(text) ||
            CommercePaymentPages.blocksReceipt(packageName, text)) return null
        if (packageName == "com.tencent.mm" && WechatTransferReceiptAdapter.isSent(text))
            return Match("微信:本人转账完成", Kind.RESULT)
        if (packageName == "com.tencent.mm" && WechatTransferReceiptAdapter.isSentResultWithoutAmount(text))
            return Match("微信:本人转账待补金额", Kind.PARTIAL_RESULT)
        if (packageName == "com.tencent.mm" && WechatTransferReceiptAdapter.isReceived(text))
            return Match("微信:本人收款详情", Kind.DETAIL)
        val facts = PaymentTextParser.receiptFacts(normalizePage(packageName, text, activityName).text)
        val outgoingTransfer = facts.lines().any { Regex("^(?:转账(?:已)?成功|转账(?:已)?完成|已转账)[！!。]?$").matches(it.trim()) }
        val gateFacts = if (outgoingTransfer) facts.replace(Regex("(?:待|等待)对方(?:确认)?收款|对方(?:待|未|尚未)收款"), "") else facts
        if (failure.containsMatchIn(gateFacts) || inputPage.containsMatchIn(gateFacts)) return null
        val lines = facts.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.isEmpty()) return if (activityName in opaqueActivities[packageName].orEmpty())
            Match("${profile.name}:wallet-image", Kind.OPAQUE_RECEIPT) else null
        val detail = lines.take(6).firstOrNull { it in detailTitles(packageName) }
        val anchors = lines.count { field.containsMatchIn(it) } +
            (structure?.labelledAmounts()?.size ?: 0)
        if (detail != null && anchors >= 2)
            return Match("${profile.name}:$detail", Kind.DETAIL)
        if (WalletPaymentPageAdapter.isDedicatedDetailActivity(packageName, activityName)) return null
        val result = lines.take(6).firstOrNull { title.matches(it) ||
            profile.family == PaymentAppRegistry.Family.WALLET && PaymentTextParser.accountMovementResult.matches(it) }
        if (result != null && (anchors > 0 || lines.any(money::matches) ||
                lines.any { it in setOf("完成", "返回", "关闭", "查看账单", "查看订单") }))
            return Match("${profile.name}:$result", Kind.RESULT)
        return if (activityName in opaqueActivities[packageName].orEmpty())
            Match("${profile.name}:wallet-image", Kind.OPAQUE_RECEIPT) else null
    }

    /** Cloud recognition / explicit supplementation only; callers must keep these results pending. */
    fun parseForReview(packageName: String, text: String, now: Long, structure: PaymentScreenStructure? = null,
        activityName: String? = null): NotificationParseResult? {
        val page = match(packageName, text, activityName, structure) ?: return null
        if (page.kind == Kind.PARTIAL_RESULT) return null
        if (packageName == "com.tencent.mm") WechatTransferReceiptAdapter.parse(text, now, allowHistorical = true)?.let { return it }
        val adapted = normalizePage(packageName, text, activityName)
        return if (page.kind == Kind.DETAIL || isDetailPage(packageName, text, activityName))
            WalletReceiptDetailsParser.parseForReview(packageName, adapted.text, now, structure)
            else parse(packageName, text, now, structure, activityName)
    }

    /** A cloud match may lack amount or identity; only this stricter parser can create a local entry. */
    fun parse(packageName: String, text: String, now: Long, structure: PaymentScreenStructure? = null,
        activityName: String? = null): NotificationParseResult? {
        val page = match(packageName, text, activityName, structure) ?: return null
        if (page.kind == Kind.PARTIAL_RESULT) return null
        if (packageName == "com.tencent.mm") WechatTransferReceiptAdapter.parse(text, now)?.let { return it }
        val adapted = normalizePage(packageName, text, activityName)
        return when {
            page.kind == Kind.DETAIL || isDetailPage(packageName, text, activityName) ->
                WalletReceiptDetailsParser.parse(packageName, adapted.text, now, structure)
                    ?: if (WalletPaymentPageAdapter.supportsIdentitylessReview(packageName, adapted.text))
                        WalletReceiptDetailsParser.parseForReview(packageName, adapted.text, now, structure)
                    else null
            page.kind == Kind.RESULT -> PaymentTextParser.parseAccountMovement(packageName, adapted.text, structure)
                ?: PaymentTextParser.analyzeScreen(packageName, adapted.text, structure).parsed
            else -> null
        }?.let { result ->
            val parsed = if (page.kind == Kind.RESULT && packageName == "com.tencent.mm")
                WechatMerchantResult.enrich(adapted.text, result) else result
            parsed.copy(fieldEvidence = parsed.fieldEvidence + buildMap {
                put("page", "页面适配·${page.id}")
                if (adapted.evidence.isNotEmpty())
                    put("pageMapping", "页面原文：${adapted.evidence.joinToString("；").take(600)}")
            })
        }
    }
}

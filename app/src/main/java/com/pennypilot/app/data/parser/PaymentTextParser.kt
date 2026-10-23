package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil

/**
 * 仅在本机分析支付凭证。金额不明确时不猜测；页面还必须有完成证据。
 * 页面文字无法证明发生时间，历史页面过滤与捕获层的订单号/时间去重需一起使用。
 */
object PaymentTextParser {
    private const val RAW_AMOUNT = """[+\-−]?[0-9][0-9,.]*(?:[eE][+\-]?[0-9]+)?"""
    private const val AMOUNT_LABEL = """原支付金额|原付款金额|原交易金额|退款前支付|原支付|原付款|实际支付|实际付款|实付款|实付金额|实付|实扣金额|实扣|退款金额|退回金额|到账金额|收款金额|收钱金额|入账金额|收入金额|支付金额|付款金额|消费金额|交易金额|扣款金额|转账金额|商品总价|商品金额|订单金额|应付金额|优惠金额|优惠券抵扣|红包抵扣|账户余额|可用余额|余额|已优惠|优惠|立减|折扣|原价|总价|应付|运费|配送费|补贴|手续费|退款|退回|收款|收钱|到账|入账|收入|付款|支付|消费|转账|扣款"""
    private val labelledAmount = Regex(
        """($AMOUNT_LABEL)(?:\s*(?:已)?成功)?\s*[:：]?\s*(?:人民币|RMB|CNY)?\s*[¥￥]?\s*($RAW_AMOUNT)(?:\s*元)?""",
        RegexOption.IGNORE_CASE
    )
    private val currencyAmount = Regex("""[¥￥]\s*($RAW_AMOUNT)|($RAW_AMOUNT)\s*元""")
    private val precedingLabel = Regex("""($AMOUNT_LABEL)(?:\s*(?:已)?成功)?\s*[:：]?\s*(?:人民币|RMB|CNY)?\s*$""", RegexOption.IGNORE_CASE)
    private val failedOrPending = Regex(
        """(?:付款|支付|消费|收款|收钱|转账|交易|退款|退回|到账|入账)(?:已)?(?:失败|取消|关闭|超时|处理中|处理失败|申请中|审核中)|(?:支付|付款|退款|转账)中|(?:未|待|尚未)(?:付款|支付|到账|收款)|(?:订单|交易)(?:已)?(?:取消|关闭)|(?:退款|退回)(?:申请|审核|处理中)|申请退款|(?:等待|等待买家|请|请先|继续|立即|去|需要|需)(?:完成)?(?:支付|付款)|支付尚未完成|付款尚未完成|余额不足"""
    )
    private val promotion = Regex(
        """验证码|领取优惠券|领券|立即领取|限时福利|最高可|支付立减|消费立减|(?:优惠券|优惠红包|优惠金)(?:已)?到账|(?:支付|付款|消费)成功(?:后|可|即可|有机会)|(?:支付|付款)可享|待领取"""
    )
    private val foreignCurrency = Regex("""美元|美金|港币|港元|日元|欧元|英镑|澳元|新加坡元|韩元|\b(?:USD|HKD|JPY|EUR|GBP|AUD|SGD|KRW)\b|US\$|HK\$""", RegexOption.IGNORE_CASE)
    private val expenseCompleted = Regex("""(?:支付|付款|消费|交易|转账|扣款)(?:已)?成功|(?:已|完成|成功)(?:支付|付款|转账|扣款)|(?:支付|付款|转账)(?:已)?完成""")
    private val incomeCompleted = Regex("""(?:收款|收钱)(?:已)?(?:到账|成功)|(?:转账|款项|资金)(?:已)?到账|(?:到账|入账)成功|(?:已收到|收到)[^\n，。；]{0,40}(?:转账|付款|红包)|已收款""")
    private val refundCompleted = Regex("""(?:退款|退回)(?:已)?(?:成功|到账|完成)|(?:已|成功)退款|(?:款项|资金)(?:已)?(?:原路)?退回|已原路退回""")
    private val incomeAction = Regex("""收款(?!方|商户|账户|人|码)|收钱(?!码)|到账|入账|收入|收到(?:转账|红包)""")
    private val paymentAction = Regex("""付款|支付|消费|转账|扣款""")
    private val historyScreen = Regex("""(?<!查看)(?:账单详情|交易详情|订单详情|退款详情)|历史订单|我的订单|全部订单|订单列表|交易记录|账单列表|收支明细|支付记录|交易账单""")
    private val screenTitle = Regex("""^(?:支付成功|付款成功|消费成功|交易成功|转账成功|转账已成功|已转账|转账完成|转账已完成|扣款成功|支付完成|付款完成|支付已完成|付款已完成|已支付|已付款|收款成功|收钱成功|收款到账|收钱到账|收款已到账|到账成功|入账成功|退款成功|退款到账|退款已到账|退款完成|退款已完成|已退款|款项已退回|已原路退回)[!！。]?$""")
    private val completedTransferTitle = Regex("""^(?:转账(?:已)?成功|已转账|转账(?:已)?完成)[!！。]?$""")
    private val recipientStatus = Regex("""(?:待|等待)对方(?:确认)?收款|对方(?:待|尚未|未|已)(?:确认)?收款""")
    private val inlineTransferStatus = Regex("""^(转账(?:已)?成功|已转账|转账(?:已)?完成)[，,：:]\s*((?:待|等待)对方(?:确认)?收款|对方(?:待|尚未|未)(?:确认)?收款)(?=\s|[。！!]|$)""")
    private val nonReceiptScreen = Regex("""聊天记录|群聊|按住说话|发送消息|发消息|说点什么|写评论|发布评论|全部评论|视频评论|直播间|条评论|条未读|朋友圈|(?:^|\n)\s*(?:评论|推荐|关注|朋友|消息)\s*(?:\n|$)""")
    private val actualLabels = setOf("实际支付", "实际付款", "实付款", "实付金额", "实付", "实扣金额", "实扣")
    private val screenAmountLabels = actualLabels + setOf("支付金额", "付款金额", "消费金额", "交易金额", "扣款金额", "转账金额", "退款金额", "退回金额", "到账金额", "收款金额", "收钱金额", "入账金额", "收入金额")
    private val refundLabels = setOf("退款金额", "退回金额", "退款", "退回")
    private val incomeLabels = setOf("到账金额", "收款金额", "收钱金额", "入账金额", "收入金额", "收款", "收钱", "到账", "入账", "收入")
    private val ignoredLabels = setOf("原支付金额", "原付款金额", "原交易金额", "退款前支付", "原支付", "原付款", "商品总价", "商品金额", "订单金额", "应付金额", "优惠金额", "优惠券抵扣", "红包抵扣", "账户余额", "可用余额", "余额", "已优惠", "优惠", "立减", "折扣", "原价", "总价", "应付", "运费", "配送费", "补贴", "手续费")
    private val trustedTitles = mapOf(
        "com.tencent.mm" to setOf("微信支付", "微信收款助手", "收款助手"),
        "com.eg.android.AlipayGphone" to setOf("支付宝", "支付宝通知", "支付助手", "收钱码", "收款到账"),
        "com.sankuai.meituan" to setOf("美团", "美团支付", "美团订单", "美团外卖"),
        "com.sankuai.meituan.takeoutnew" to setOf("美团", "美团外卖", "美团支付", "美团订单"),
        "com.ss.android.ugc.aweme" to setOf("抖音", "抖音支付", "抖音商城", "抖音订单", "抖音生活服务"),
        "com.ss.android.ugc.aweme.lite" to setOf("抖音", "抖音极速版", "抖音支付", "抖音商城", "抖音订单", "抖音生活服务")
    )
    private val receiptTitle = Regex("""^(?:订单)?(?:支付|付款|退款|收款)(?:成功|到账)(?:通知)?$""")
    private val orderToken = Regex("""(商户订单号|商家订单号|商户单号|支付订单号|交易订单号|订单编号|订单号|交易单号|交易号|流水号)\s*[:：]?\s*([^\s:：，,；;]+)""")
    private val validOrderId = Regex("""[A-Za-z0-9]{10,64}""")
    private val paymentMethod = Regex("""(?:支付方式|付款方式)\s*[:：]?\s*(微信支付|微信|支付宝|抖音支付|抖音月付|美团支付|美团月付|银行卡|零钱通|零钱|余额宝|余额|花呗|信用卡|储蓄卡|云闪付)""")
    private val merchantField = Regex("""(?:^|[\s，,；;])(?:商户名称|商户全称|收款商户|商家名称|店铺名称|收款方|商户|商家|店铺|付款对象|付款给)\s*[:：]\s*([^\n\r，,；;]{1,60})""")
    private val merchantLines = Regex("""(?:^|\n)(?:商户名称|商户全称|收款商户|商家名称|店铺名称|收款方|商户|商家|店铺|付款对象)\s*\n\s*([^\n\r]{1,60})""")
    private val actionMerchant = Regex("""(?:向|给|在)([^\n，。；]{1,40}?)(?:付款|支付|消费|转账)""")
    private val incomeMerchant = Regex("""(?:已收到|收到)([^\n，。；]{1,40}?)(?:的)?(?:转账|付款)""")

    private enum class Role { ACTUAL, REFUND, INCOME, PAYMENT, UNKNOWN, IGNORED }
    private data class Amount(val raw: String, val range: IntRange, val role: Role) {
        val cents: Long? get() = MoneyUtil.parsePositiveYuanToCents(raw)
    }
    private data class AmountDecision(val cents: Long?, val unsafe: Boolean)

    /** 无障碍入口仅接受当前支付结果页，不将页面文字发送到云端。 */
    fun parseScreen(packageName: String, text: String): NotificationParseResult? {
        if (packageName !in PaymentAppRegistry.packages) return null
        val normalized = normalize(text).replace(inlineTransferStatus, "$1\n$2")
        // 付款方已完成转账后，收款方可能尚未确认；这不代表付款仍待完成。
        // 必须同时存在独立完成标题，不能仅凭“待对方收款”或转账输入页判断已支出。
        val completedTransfer = normalized.lines().any { completedTransferTitle.matches(it.trim()) }
        val validationText = if (completedTransfer) normalized.replace(recipientStatus, "") else normalized
        if (normalized.length > 16_000 || rejects(validationText) || historyScreen.containsMatchIn(normalized) || nonReceiptScreen.containsMatchIn(normalized)) return null
        if (!hasScreenReceiptStructure(normalized)) return null
        return parseReceipt(packageName, validationText)
    }

    internal fun isNotificationCandidate(packageName: String, title: String, content: String): Boolean {
        if (packageName !in PaymentAppRegistry.packages) return false
        val normalizedTitle = normalize(title).trim()
        // 微信聊天和群聊可能包含真实的付款用语，必须先检查通知来源标题。
        val trusted = normalizedTitle in trustedTitles[packageName].orEmpty() ||
            (packageName != "com.tencent.mm" && receiptTitle.matches(normalizedTitle))
        if (!trusted) return false
        val body = normalize(content)
        val text = "$normalizedTitle\n$body"
        if (text.length > 16_000 || rejects(text)) return false
        if (hasCompletion(text)) return true
        // 钱包的支付通知允许“向商户付款 18 元”“到账 100 元”等已发生动作。
        if (packageName == "com.tencent.mm" || packageName == "com.eg.android.AlipayGphone") {
            if (labelledAmount.containsMatchIn(body) && (paymentAction.containsMatchIn(body) || incomeAction.containsMatchIn(body))) return true
            if (normalizedTitle == "微信支付" && Regex("""^\s*[¥￥]\s*$RAW_AMOUNT\s*(?:元)?\s*$""").matches(body)) return true
        }
        return false
    }

    internal fun parseNotification(packageName: String, title: String, content: String): NotificationParseResult? {
        if (!isNotificationCandidate(packageName, title, content)) return null
        return parseReceipt(packageName, normalize("$title\n$content"))
    }

    internal fun canUseFallback(text: String, requireAmount: Boolean = false): Boolean {
        val normalized = normalize(text)
        if (rejects(normalized)) return false
        val decision = chooseAmount(normalized)
        return !decision.unsafe && (!requireAmount || decision.cents != null)
    }

    /** 规则和云端结果也不能覆盖已知实付金额、退款方向或金额边界。 */
    internal fun isConsistentFallback(text: String, result: NotificationParseResult): Boolean {
        if (!MoneyUtil.isValidAmountCents(result.amountCents) || result.direction !in setOf("EXPENSE", "INCOME")) return false
        val normalized = normalize(text)
        if (rejects(normalized)) return false
        val decision = chooseAmount(normalized)
        if (decision.unsafe || (decision.cents != null && decision.cents != result.amountCents)) return false
        val knownDirection = direction(normalized)
        return knownDirection == null || knownDirection == result.direction
    }

    internal fun withEvidence(packageName: String, text: String, result: NotificationParseResult): NotificationParseResult = result.copy(
        orderId = extractOrderId(normalize(text)),
        paymentMethod = extractPaymentMethod(packageName, normalize(text))
    )

    private fun parseReceipt(packageName: String, text: String): NotificationParseResult? {
        val direction = direction(text) ?: return null
        val decision = chooseAmount(text)
        if (decision.unsafe) return null
        return NotificationParseResult(
            amountCents = decision.cents ?: return null,
            merchant = extractMerchant(text, direction),
            direction = direction,
            orderId = extractOrderId(text),
            paymentMethod = extractPaymentMethod(packageName, text)
        )
    }

    private fun normalize(text: String): String = buildString(text.length) {
        text.forEach { character ->
            when (character) {
                in '０'..'９' -> append('0' + (character - '０'))
                '\u00a0', '\u202f' -> append(' ')
                '\u200b', '\u200c', '\u200d', '\ufeff' -> Unit
                '．' -> append('.')
                else -> append(character)
            }
        }
    }.trim()

    private fun rejects(text: String): Boolean = failedOrPending.containsMatchIn(text) || promotion.containsMatchIn(text) || foreignCurrency.containsMatchIn(text)

    private fun hasCompletion(text: String): Boolean = expenseCompleted.containsMatchIn(text) || incomeCompleted.containsMatchIn(text) || refundCompleted.containsMatchIn(text)

    private fun hasScreenReceiptStructure(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.none(screenTitle::matches)) return false
        val isolatedMoney = Regex("""^(?:[¥￥]\s*$RAW_AMOUNT\s*(?:元)?|$RAW_AMOUNT\s*元)$""")
        if (lines.any(isolatedMoney::matches)) return true
        if (lines.zipWithNext().any { (first, second) ->
                first in setOf("¥", "￥") && Regex("""^$RAW_AMOUNT\s*(?:元)?$""").matches(second)
            }) return true
        return labelledAmount.findAll(text).any { match ->
            match.groupValues[1] in screenAmountLabels
        }
    }

    private fun direction(text: String): String? = when {
        refundCompleted.containsMatchIn(text) -> "INCOME"
        incomeCompleted.containsMatchIn(text) -> "INCOME"
        expenseCompleted.containsMatchIn(text) -> "EXPENSE"
        labelledAmount.findAll(text).any { role(it.groupValues[1]) == Role.INCOME } -> "INCOME"
        labelledAmount.findAll(text).any { role(it.groupValues[1]) in setOf(Role.ACTUAL, Role.PAYMENT) } -> "EXPENSE"
        incomeAction.containsMatchIn(text) -> "INCOME"
        paymentAction.containsMatchIn(text) -> "EXPENSE"
        else -> null
    }

    private fun role(label: String?): Role = when (label) {
        in ignoredLabels -> Role.IGNORED
        in actualLabels -> Role.ACTUAL
        in refundLabels -> Role.REFUND
        in incomeLabels -> Role.INCOME
        null -> Role.UNKNOWN
        else -> Role.PAYMENT
    }

    private fun amounts(text: String): List<Amount> {
        val found = linkedMapOf<IntRange, Amount>()
        labelledAmount.findAll(text).forEach { match ->
            val group = match.groups[2] ?: return@forEach
            found[group.range] = Amount(group.value, group.range, role(match.groupValues[1]))
        }
        currencyAmount.findAll(text).forEach { match ->
            val group = match.groups[1] ?: match.groups[2] ?: return@forEach
            val prefix = text.substring(maxOf(0, match.range.first - 28), match.range.first)
            val label = precedingLabel.find(prefix)?.groupValues?.get(1)
            found.putIfAbsent(group.range, Amount(group.value, group.range, role(label)))
        }
        return found.values.map { amount ->
            // 防止从数字串、负数、指数或不合法千分位中抽取看似合法的尾段。
            val before = text.getOrNull(amount.range.first - 1)
            val after = text.getOrNull(amount.range.last + 1)
            if (before != null && (before.isDigit() || before in ".,+-−") ||
                after != null && (after.isDigit() || after in ".,")) amount.copy(raw = "invalid") else amount
        }
    }

    private fun chooseAmount(text: String): AmountDecision {
        val all = amounts(text)
        val relevant = all.filter { it.role != Role.IGNORED }
        if (relevant.isEmpty()) return AmountDecision(null, all.isNotEmpty())
        if (relevant.any { it.cents == null }) return AmountDecision(null, true)
        if (!refundCompleted.containsMatchIn(text)) {
            val incoming = relevant.filter { it.role == Role.INCOME }.map { it.cents }.toSet()
            if (incoming.isNotEmpty() && relevant.any {
                    it.role in setOf(Role.ACTUAL, Role.PAYMENT) && it.cents !in incoming
                }) return AmountDecision(null, true)
        }
        val preferred = when {
            refundCompleted.containsMatchIn(text) -> relevant.filter { it.role == Role.REFUND }
            direction(text) == "INCOME" -> relevant.filter { it.role == Role.INCOME }
            else -> relevant.filter { it.role == Role.ACTUAL }
        }
        val selected = preferred.ifEmpty { relevant }
        val values = selected.mapNotNull { it.cents }.distinct()
        if (values.size != 1) return AmountDecision(null, true)
        // 实付/退款标签能解释原金额和优惠；无法解释的其他金额仍然拒绝。
        if (preferred.isNotEmpty() && relevant.any { it.role == Role.UNKNOWN && it.cents != values.single() }) return AmountDecision(null, true)
        return AmountDecision(values.single(), false)
    }

    private fun extractOrderId(text: String): String? {
        // 同一原订单可能分次退回相同金额，原支付单号不能作为退款唯一身份。
        // 未建模退款单号独立命名空间前，退款仅使用捕获层的弱重复提示。
        if (refundCompleted.containsMatchIn(text)) return null
        return orderToken.findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (_, id) -> validOrderId.matches(id) && id.any(Char::isDigit) }
            .sortedBy { (label, _) -> if (label in setOf("商户订单号", "商家订单号", "商户单号")) 0 else 1 }
            .firstOrNull()?.second
    }

    private fun extractPaymentMethod(packageName: String, text: String): String? = paymentMethod.find(text)?.groupValues?.get(1)
        ?: when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> null
        }

    private fun extractMerchant(text: String, direction: String): String? {
        val raw = merchantField.find(text)?.groupValues?.get(1)
            ?: merchantLines.find(text)?.groupValues?.get(1)
            ?: (if (direction == "INCOME") incomeMerchant.find(text)?.groupValues?.get(1) else null)
            ?: actionMerchant.find(text)?.groupValues?.get(1)
            ?: return null
        return raw.split(Regex("""\s+(?:订单号|交易号|支付方式|付款方式|实付|金额|支付金额|付款金额)"""), limit = 2)
            .first().trim().removeSuffix("的").takeIf { it.length in 1..50 && !it.all(Char::isDigit) }
    }
}

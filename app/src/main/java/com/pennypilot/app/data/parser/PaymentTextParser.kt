package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil

/**
 * 仅在本机分析支付凭证。金额不明确时不猜测；页面还必须有完成证据。
 * 页面文字无法证明发生时间，历史页面过滤与捕获层的订单号/时间去重需一起使用。
 */
object PaymentTextParser {
    private const val RULE_VERSION = "payment-scenes-2026-11-07.3"
    internal const val WECHAT_PAYMENT_ORDER_LABELS = "微信支付交易单号|微信支付订单号|微信支付单号"
    private const val RAW_AMOUNT = """[+\-−]?[0-9][0-9,.]*(?:[eE][+\-]?[0-9]+)?"""
    private const val AMOUNT_LABEL = """商品原价|订单原价|优惠合计|共优惠|总优惠|支付优惠|提现手续费|服务费|原支付金额|原付款金额|原交易金额|退款前支付|原支付|原付款|实际支付|实际付款|实付款|实付金额|实付|实扣金额|实扣|本次退款金额|实际退款金额|退款到账金额|本次退款|实际退款|本次退回金额|退还金额|退款金额|退回金额|本人领取金额|领取金额|收到红包金额|红包金额|红包总额|累计收款金额|累计退款金额|今日收款|今日收入|实际到账金额|到账金额|收款金额|收钱金额|入账金额|收入金额|支付金额|付款金额|消费金额|交易金额|扣款金额|转账金额|商品总价|商品金额|订单金额|应付金额|优惠金额|优惠券抵扣|红包抵扣|账户余额|可用余额|余额|已优惠|优惠|立减|折扣|原价|总价|应付|运费|配送费|补贴|手续费|退款|退回|退还|收款|收钱|到账|入账|收入|付款|支付|消费|转账|扣款"""
    private val labelledAmount = Regex(
        """($AMOUNT_LABEL)(?:\s*(?:已)?成功)?\s*[:：]?\s*(?:人民币|RMB|CNY)?\s*[¥￥]?\s*($RAW_AMOUNT)(?:\s*元)?""",
        RegexOption.IGNORE_CASE
    )
    private val currencyAmount = Regex("""[¥￥]\s*($RAW_AMOUNT)|($RAW_AMOUNT)\s*元""")
    private val precedingLabel = Regex("""($AMOUNT_LABEL)(?:\s*(?:已)?成功)?\s*[:：]?\s*(?:人民币|RMB|CNY)?\s*$""", RegexOption.IGNORE_CASE)
    private val failedOrPending = Regex(
        """(?:付款|支付|消费|收款|收钱|转账|交易|退款|退回|退还|到账|入账)(?:已)?(?:失败|取消|关闭|超时|处理中|处理失败|申请中|审核中|受理|受理中)|(?:支付|付款|退款|转账|到账|入账)中|(?:未|待|尚未)(?:付款|支付|到账|收款|收钱)|(?:订单|交易)(?:已)?(?:取消|关闭)|(?:退款|退回)(?:申请|审核|处理中)|申请退款|退款(?:申请)?(?:已)?(?:提交|受理)|(?:等待|等待买家|请|请先|继续|立即|去|需要|需)(?:完成)?(?:支付|付款)|支付尚未完成|付款尚未完成|余额不足|(?:预计|将于|将在)[^\n，。；]{0,30}(?:到账|退回|退还|退款)|(?:确认收款|请收钱|待确认收款)|(?:^|\n)(?:示例|教程|截图示意)(?:[：:\n]|$)"""
    )
    private val promotion = Regex(
        """验证码|领取优惠券|领券|立即领取|限时福利|最高可|支付立减|消费立减|(?:优惠券|优惠红包|优惠金)(?:已)?到账|(?:支付|付款|消费)成功(?:后|可|即可|有机会)|(?:支付|付款)可享|待领取"""
    )
    private val unresolvedStatus = Regex(
        """(?:付款|支付|消费|收款|收钱|转账|交易|退款|退回|到账|入账)(?:尚未|未|没有|不)(?:成功|完成)|(?:未|尚未)入账|(?:^|[\n，,。；;])(?:交易状态|支付状态|付款状态|收款状态|退款状态|转账状态|订单状态|当前状态|状态)\s*[:：]?\s*(?:未成功|未完成|失败|处理中|处理失败|受理中?|审核中|待支付|未到账|已取消|取消|已关闭|关闭)(?=\s|[，,。；;!！]|$)"""
    )
    private val foreignCurrency = Regex("""美元|美金|港币|港元|日元|欧元|英镑|澳元|新加坡元|韩元|\b(?:USD|HKD|JPY|EUR|GBP|AUD|SGD|KRW)\b|US\$|HK\$""", RegexOption.IGNORE_CASE)
    private val expenseCompleted = Regex("""(?:支付|付款|消费|转账|扣款)(?:已)?成功|(?:已|完成|成功)(?:支付|付款|消费|转账|扣款)|(?:支付|付款|转账)(?:已)?完成|红包(?:发送成功|已发出)|已发红包""")
    private val neutralCompleted = Regex("""交易(?:已)?成功""")
    private val incomeCompleted = Regex("""(?:收款|收钱)(?:已)?(?:到账|成功)|成功(?:收款|收钱)|(?:转账|款项|资金)(?:已)?到账|(?:到账|入账)成功|(?:已收到|收到)[^\n，。；]{0,40}(?:转账|付款|红包)|(?<!对方)已收款|(?<!对方)已收钱|已领取红包|红包已存入零钱|(?:^|\n)已存入零钱(?:\n|$)""")
    private val refundCompleted = Regex("""(?:退款|退回|退还)(?:已)?(?:成功|到账|完成)|(?:已|成功)退款|(?:款项|资金|红包)(?:已)?(?:原路)?(?:退回|退还)|已原路退回|红包已退还""")
    private val incomeAction = Regex("""收款(?!方|商户|账户|人|码)|收钱(?!码)|到账|入账|收入|收到(?:转账|红包)""")
    private val paymentAction = Regex("""付款|支付|消费|转账|扣款""")
    private val historyScreen = Regex("""(?<!查看)(?:账单详情|交易详情|订单详情|退款详情|转账详情|收款详情|红包详情)|历史订单|我的订单|全部订单|订单列表|交易记录|账单列表|收支明细|支付记录|交易账单|红包记录""")
    private val screenTitle = Regex("""^(?:支付成功|付款成功|消费成功|交易成功|转账成功|转账已成功|已转账|转账完成|转账已完成|扣款成功|支付完成|付款完成|支付已完成|付款已完成|已支付|已付款|收款成功|收钱成功|成功收款|成功收钱|已收钱|已收款|收款到账|收钱到账|收款已到账|到账成功|入账成功|退款成功|退款到账|退款已到账|退款完成|退款已完成|已退款|退还成功|款项已退回|已原路退回|红包已退还|红包退回成功|红包发送成功|红包已发出|已发红包|已领取红包|红包已存入零钱|已存入零钱)[!！。]?$""")
    private val completedTransferTitle = Regex("""^(?:转账(?:已)?成功|已转账|转账(?:已)?完成)[!！。]?$""")
    private val recipientStatus = Regex("""(?:待|等待)对方(?:确认)?(?:收款|收钱)|对方(?:待|尚未|未|已)(?:确认)?(?:收款|收钱)""")
    private val inlineTransferStatus = Regex("""^(转账(?:已)?成功|已转账|转账(?:已)?完成)[，,：:]\s*((?:待|等待)对方(?:确认)?收款|对方(?:待|尚未|未)(?:确认)?收款)(?=\s|[。！!]|$)""")
    private val nonReceiptScreen = Regex("""聊天记录|群聊|按住说话|发送消息|发消息|说点什么|写评论|发布评论|全部评论|视频评论|直播间|条评论|条未读|朋友圈|(?:^|\n)\s*(?:评论|推荐|关注|朋友|消息)\s*(?:\n|$)""")
    private val counterpartyCollection = Regex("""对方(?:已|成功|已确认)(?:收款|收钱|领取红包)|红包(?:已)?被[^\n，。；]{0,40}领取|你发出的红包[^\n，。；]{0,40}被领取""")
    private val actualLabels = setOf("实际支付", "实际付款", "实付款", "实付金额", "实付", "实扣金额", "实扣")
    private val screenAmountLabels = actualLabels + setOf("支付金额", "付款金额", "消费金额", "交易金额", "扣款金额", "转账金额", "退款金额", "退回金额", "退还金额", "本次退款金额", "实际退款金额", "退款到账金额", "本次退款", "实际退款", "本次退回金额", "到账金额", "实际到账金额", "收款金额", "收钱金额", "入账金额", "收入金额", "本人领取金额", "领取金额", "收到红包金额", "红包金额")
    private val actualRefundLabels = setOf("本次退款金额", "实际退款金额", "退款到账金额", "本次退款", "实际退款", "本次退回金额")
    private val refundLabels = setOf("退款金额", "退回金额", "退还金额", "退款", "退回", "退还") + actualRefundLabels
    private val incomeLabels = setOf("实际到账金额", "到账金额", "收款金额", "收钱金额", "入账金额", "收入金额", "收款", "收钱", "到账", "入账", "收入", "本人领取金额", "领取金额", "收到红包金额")
    private val ignoredLabels = setOf("商品原价", "订单原价", "优惠合计", "共优惠", "总优惠", "支付优惠", "提现手续费", "服务费", "原支付金额", "原付款金额", "原交易金额", "退款前支付", "原支付", "原付款", "商品总价", "商品金额", "订单金额", "应付金额", "优惠金额", "优惠券抵扣", "红包抵扣", "账户余额", "可用余额", "余额", "已优惠", "优惠", "立减", "折扣", "原价", "总价", "应付", "运费", "配送费", "补贴", "手续费", "红包总额", "累计收款金额", "累计退款金额", "今日收款", "今日收入")
    private val trustedTitles = mapOf(
        "com.tencent.mm" to setOf("微信支付", "微信收款助手", "收款助手"),
        "com.eg.android.AlipayGphone" to setOf("支付宝", "支付宝通知", "支付助手", "收钱码", "收款到账", "交易提醒", "退款提醒", "成功收款"),
        "com.unionpay" to setOf("云闪付", "云闪付通知", "交易提醒", "消费提醒", "付款提醒", "收款提醒", "退款提醒",
            "支付助手", "银联支付助手", "云闪付支付助手"),
        "com.sankuai.meituan" to setOf("美团", "美团支付", "美团订单", "美团外卖"),
        "com.sankuai.meituan.takeoutnew" to setOf("美团", "美团外卖", "美团支付", "美团订单"),
        "com.ss.android.ugc.aweme" to setOf("抖音", "抖音支付", "抖音商城", "抖音订单", "抖音生活服务"),
        "com.ss.android.ugc.aweme.lite" to setOf("抖音", "抖音极速版", "抖音支付", "抖音商城", "抖音订单", "抖音生活服务")
    )
    private val receiptTitle = Regex("""^(?:订单)?(?:支付|付款|消费|扣款|交易|退款|收款|收钱)(?:已)?(?:成功|到账|完成)(?:通知)?(?:\s*[:：]?\s*(?:[¥￥]\s*$RAW_AMOUNT(?:\s*元)?|$RAW_AMOUNT\s*元))?[!！。]?$""")
    private val alipayTransactionStatement = Regex(
        """^(你有一笔\s*$RAW_AMOUNT\s*元的(支出|收入))[。!！]?$"""
    )
    private val alipayAutoDebitStatement = Regex(
        """^(你在([^\n，,。；;]{1,64}?)有一笔\s*($RAW_AMOUNT)\s*元的免密[/／]自动扣款支付)(?:[，,]\s*点击领取[0-9]{1,6}个支付宝积分)?[。!！]?$"""
    )
    private val unionPayAssistantTitles = setOf("支付助手", "银联支付助手", "云闪付支付助手")
    private val unionPayCompletedCharge = Regex(
        """^(本次(?:消费|付款)\s*($RAW_AMOUNT)\s*元[，,]\s*于(?:0?[1-9]|[12][0-9]|3[01])日\s*(?:[01]?[0-9]|2[0-3])时\s*[0-5]?[0-9]分完成)[，,]\s*尾号(?:为|是)\s*([0-9]{4})的(银行卡|储蓄卡|信用卡|借记卡)[。!！]?$"""
    )
    private val statementMerchantLabels = setOf("商户名称", "商户全称", "收款商户", "商家名称", "店铺名称", "收款方", "付款方", "付款人", "商户", "商家", "店铺")
    private val statementOrderLabels = setOf("商户订单号", "商家订单号", "商户单号", "支付订单号", "交易订单号", "订单编号", "订单号", "交易单号", "交易号", "流水号")
    private val statementMethodLabels = setOf("支付方式", "付款方式")
    private val statementFieldValue = Regex("""[^\n，,。；;:：]{1,64}""")
    private val statementNegation = Regex("""(?:尚未|还未|未|没有|不曾)(?:成功|完成|发生|到账|入账|扣款|支付|付款|收款|退款)""")
    private val wechatOrderLabels = WECHAT_PAYMENT_ORDER_LABELS.split('|').toSet()
    // Match the full branded label before generic labels such as 交易单号, even on other sources.
    private val orderToken = Regex("""($WECHAT_PAYMENT_ORDER_LABELS|商户订单号|商家订单号|商户单号|支付订单号|交易订单号|订单编号|订单号|交易单号|交易号|流水号)\s*[:：]?\s*([^\s:：，,；;]+)""")
    private val wechatWalletOrderId = Regex("[0-9]{20,32}")
    private val validOrderId = Regex("""[A-Za-z0-9]{10,64}""")
    private val refundToken = Regex("""(商户退款单号|商家退款单号|退款交易单号|退款交易号|退款订单号|退款流水号|退款单号|退款编号)\s*[:：]?\s*([^\s:：，,；;]+)""")
    private val paymentMethod = Regex("""(?:支付方式|付款方式)\s*[:：]?\s*(微信支付|微信|支付宝|抖音支付|抖音月付|美团支付|美团月付|银行卡|零钱通|零钱|余额宝|余额|花呗|信用卡|储蓄卡|云闪付)""")
    private val merchantField = Regex("""(?:^|[\s，,；;])(?:商户名称|商户全称|收款商户|商家名称|店铺名称|收款方|商户|商家|店铺|付款对象|付款给)\s*[:：]\s*([^\n\r，,；;]{1,60})""")
    private val merchantLines = Regex("""(?:^|\n)(?:商户名称|商户全称|收款商户|商家名称|店铺名称|收款方|商户|商家|店铺|付款对象)\s*\n\s*([^\n\r]{1,60})""")
    private val actionMerchant = Regex("""(?:向|给|在)([^\n，。；]{1,40}?)(?:付款|支付|消费|转账)""")
    private val incomeMerchant = Regex("""(?:已收到|收到)([^\n，。；]{1,40}?)(?:的)?(?:转账|付款)""")
    private val transferMerchant = Regex("""(?:^|\n)转账给\s*([^\n，。；]{1,40})""")
    private val payerMerchant = Regex("""(?:^|\n)(?:付款方|付款人|转账方)\s*[:：\n]\s*([^\n，。；]{1,40})""")
    private val screenOrderLabels = statementOrderLabels + setOf("商户退款单号", "商家退款单号", "退款交易单号",
        "退款交易号", "退款订单号", "退款流水号", "退款单号", "退款编号")
    private val wechatScreenOrderLabels = screenOrderLabels + wechatOrderLabels
    internal val completedStatusByLabel = mapOf("支付状态" to "支付成功", "付款状态" to "付款成功",
        "收款状态" to "收款成功", "退款状态" to "退款成功", "转账状态" to "转账成功", "交易状态" to "交易成功")
    private val screenStatusLabels = completedStatusByLabel.keys + setOf("订单状态", "当前状态", "状态")
    private val statusValue = Regex("""^[\p{IsHan}]{1,12}[。！!]?$""")
    private val structuredMoney = Regex("""^(?:[¥￥]\s*)?([0-9][0-9,.]*)(?:\s*元)?$""")
    private val financialRowLabels = screenAmountLabels + ignoredLabels + screenOrderLabels + screenStatusLabels
    private val standaloneCouponActions = setOf("点击领取优惠券", "领取优惠券")

    internal fun structuredAmounts(structure: PaymentScreenStructure): List<String> =
        structuredAmounts(structure.labelledTextFields(financialRowLabels))

    private fun structuredAmounts(rows: Map<String, List<String>>): List<String> = rows.mapNotNull { (label, values) ->
        if (label !in screenAmountLabels) return@mapNotNull null
        val raw = values.singleOrNull()?.let(::normalize) ?: return@mapNotNull null
        val amount = structuredMoney.matchEntire(raw)?.groupValues?.get(1) ?: return@mapNotNull null
        if (MoneyUtil.parsePositiveYuanToCents(amount) == null) null else "$label：${amount}元"
    }

    internal fun completedStatus(label: String, value: String): String? {
        val title = completedStatusByLabel[label] ?: if (label in setOf("当前状态", "状态", "订单状态")) "交易成功" else return null
        val status = value.trimEnd('。', '！', '!')
        if (status in setOf("成功", "已成功", "完成", "已完成")) return title
        if (!screenTitle.matches(status)) return null
        return status.takeIf { label in setOf("交易状态", "当前状态", "状态", "订单状态") || it.startsWith(label.removeSuffix("状态")) }
    }

    private enum class Role { ACTUAL, ACTUAL_REFUND, REFUND, INCOME, PAYMENT, UNKNOWN, IGNORED }
    private data class Amount(val raw: String, val range: IntRange, val role: Role) {
        val cents: Long? get() = MoneyUtil.parsePositiveYuanToCents(raw)
    }
    private data class AmountDecision(val cents: Long?, val unsafe: Boolean)
    private data class NotificationStatement(val direction: String, val quote: String, val merchant: String? = null,
        val fundingAccount: String? = null)

    /** Strong built-in scenes only. Model candidates are classified separately below. */
    fun parseScreen(packageName: String, text: String): NotificationParseResult? {
        if (packageName !in PaymentAppRegistry.packages) return null
        return parseScreenReceipt(packageName, text)
    }

    private fun parseScreenReceipt(packageName: String, text: String): NotificationParseResult? {
        val normalized = normalize(text).replace(inlineTransferStatus, "$1\n$2")
        val facts = receiptFacts(normalized)
        // 付款方已完成转账后，收款方可能尚未确认；这不代表付款仍待完成。
        // 必须同时存在独立完成标题，不能仅凭“待对方收款”或转账输入页判断已支出。
        val completedTransfer = facts.lines().any { completedTransferTitle.matches(it.trim()) }
        val validationText = if (completedTransfer) facts.replace(recipientStatus, "") else facts
        if (normalized.length > 16_000 || rejects(validationText) || historyScreen.containsMatchIn(facts) || nonReceiptScreen.containsMatchIn(normalized)) return null
        if (conflictingDirections(validationText)) return null
        if (!hasScreenReceiptStructure(normalized)) return null
        // Opposite-party status is removed above only after an explicit outgoing-transfer title.
        // Otherwise competing completed directions are contradictory, never an income-first guess.
        if (!refundCompleted.containsMatchIn(validationText) && expenseCompleted.containsMatchIn(validationText) &&
            incomeCompleted.containsMatchIn(validationText)) return null
        return parseReceipt(packageName, validationText)?.let { withReceiptDetails(packageName, normalized, it) }
    }

    internal val accountMovementResult = Regex("^(?:提现|(?:零钱|余额|钱包|账户|储值)充值|(?:零钱通|余额宝)(?:转入|转出)|信用卡还款)(?:已)?(?:成功|完成|到账)[！!。]?$")
    private val accountPrincipalLabels = setOf("提现金额", "充值金额", "转入金额", "转出金额", "还款金额", "转账金额")

    /** Wallet account movements have separate principal, fee and credited amount roles. */
    internal fun parseAccountMovement(packageName: String, text: String,
        structure: PaymentScreenStructure? = null): NotificationParseResult? {
        if (PaymentAppRegistry.profile(packageName)?.family != PaymentAppRegistry.Family.WALLET) return null
        val facts = receiptFacts(normalize(text))
        if (rejects(facts) || nonReceiptScreen.containsMatchIn(text) || ambiguousOrderIds(facts, packageName)) return null
        val status = facts.lines().map(String::trim).filter(accountMovementResult::matches).distinct().singleOrNull()
            ?: return null
        val labels = accountPrincipalLabels.joinToString("|")
        val amountPattern = Regex("(?:^|\\n)($labels)\\s*[:：]?\\s*[¥￥]?\\s*($RAW_AMOUNT)\\s*(?:元)?(?=\\s|$)")
        val amounts = amountPattern.findAll(facts).map { it.groupValues[2] to it.value.trim() }.toMutableList()
        structure?.labelledTextFields(accountPrincipalLabels)?.forEach { (label, values) -> values.forEach { value ->
            val raw = value.trim().removePrefix("¥").removePrefix("￥").trim().removeSuffix("元").trim()
            amounts += raw to "$label：$value"
        } }
        if (amounts.isEmpty()) return null
        val cents = amounts.map { MoneyUtil.parsePositiveYuanToCents(it.first) }.distinct().singleOrNull() ?: return null
        // A normal payment/refund/collection state alongside this transfer is a mixed page.
        val other = facts.lines().filterNot { it.trim() == status }.joinToString("\n")
        if (refundCompleted.containsMatchIn(other) || expenseCompleted.containsMatchIn(other) || incomeCompleted.containsMatchIn(other)) return null
        val receipt = NotificationParseResult(cents, null, "EXPENSE", extractOrderId(packageName, facts),
            extractPaymentMethod(packageName, facts), businessKind = "transfer", scene = "account_transfer",
            ruleVersion = "wallet-account-movement-2026-11-14.1", fieldEvidence = buildMap {
                put("amount", "页面·账户划转本金：${amounts.first().second}")
                put("direction", "页面·转出账户视角：$status")
                put("paymentStatus", "页面·完成状态：$status")
                extractOrderId(packageName, facts)?.let { put("orderId", "页面·交易标识：$it") }
            })
        return withReceiptDetails(packageName, text, receipt, structure)
    }

    /** Unknown layouts may be sent for opt-in inference only after these local safety gates. */
    fun isScreenModelCandidate(packageName: String, text: String): Boolean {
        if (!isScreenVisualCandidate(packageName, text)) return false
        val amount = chooseAmount(receiptFacts(normalize(text)))
        return !amount.unsafe && amount.cents != null
    }

    /** Missing text amounts can be recovered visually, but only on a standalone result screen. */
    fun isScreenVisualCandidate(packageName: String, text: String): Boolean {
        if (packageName !in PaymentAppRegistry.packages) return false
        val normalized = normalize(text)
        if (normalized.length !in 1..12_000 || rejects(normalized) ||
            historyScreen.containsMatchIn(normalized) || nonReceiptScreen.containsMatchIn(normalized)) return false
        if (conflictingDirections(normalized) || ambiguousOrderIds(receiptFacts(normalized), packageName)) return false
        if (Regex("""明天|准备|打算|计划|预计|假如|如果|示例|教程|截图示意|确认收款|请收钱|待确认""").containsMatchIn(normalized)) return false
        val completedLine = receiptFacts(normalized).lines().any { line ->
            val value = line.trim()
            value.length <= 50 && hasCompletion(value) &&
                !Regex("""说|告诉|消息|描述|备注|留言|提醒|通知设置|后|即可""").containsMatchIn(value)
        }
        return completedLine && (receiptFacts(normalized).lines().any { screenTitle.matches(it.trim()) } ||
            Regex("""(?:^|\n)(?:完成|返回|关闭|支付方式|付款方式|收款方)(?:[：:\n]|$)""").containsMatchIn(normalized))
    }

    fun analyzeScreen(packageName: String, text: String, structure: PaymentScreenStructure? = null,
        evidenceSource: String = "页面"): ScreenRecognition {
        if (packageName !in PaymentAppRegistry.packages)
            return ScreenRecognition(null, "unknown", modelCandidate = false, evidenceText = text)
        return analyzeReceipt(packageName, text, structure, evidenceSource)
    }

    /** A user-selected image has no verified application identity and can only produce a draft. */
    internal fun analyzeSelectedReceipt(text: String): NotificationParseResult? =
        analyzeReceipt("", text, null, "图片凭证").parsed

    private fun analyzeReceipt(packageName: String, text: String, structure: PaymentScreenStructure?,
        evidenceSource: String): ScreenRecognition {
        val orderLabels = if (packageName == "com.tencent.mm") wechatScreenOrderLabels else screenOrderLabels
        val rows = structure?.labelledTextFields(if (packageName == "com.tencent.mm") financialRowLabels + wechatOrderLabels
            else financialRowLabels).orEmpty()
        val labelled = structuredAmounts(rows).toMutableList()
        val statusFields = mutableListOf<Pair<String, String>>()
        rows.forEach { (label, values) ->
            if (label in orderLabels) values.filter { validOrderId.matches(it) && it.any(Char::isDigit) }
                .forEach { labelled += "$label：$it" }
            if (label in screenStatusLabels) values.filter(statusValue::matches).forEach {
                // Preserve reversed negative status rows as well as completed ones.
                labelled += "$label：$it"
                statusFields += label to it
            }
        }
        val originalFacts = receiptFacts(normalize(text).replace(inlineTransferStatus, "$1\n$2"))
        originalFacts.lineSequence().forEach { line ->
            val parts = line.split(Regex("[:：]"), limit = 2).map(String::trim)
            if (parts.size == 2 && parts[0] in completedStatusByLabel && statusValue.matches(parts[1]))
                statusFields += parts[0] to parts[1]
        }
        val completionProofs = statusFields.mapNotNull { (label, value) ->
            completedStatus(label, value)?.let { it to "$label：$value" }
        }.distinct()
        labelled += completionProofs.map { it.first }.distinct()
        var evidenceText = if (labelled.isEmpty()) text else text + "\n" + labelled.distinct().joinToString("\n")
        // A generic status field is weaker than an independent success title. Without a
        // transaction identity it is not enough to turn an embedded page into a receipt.
        if (completionProofs.isNotEmpty() && originalFacts.lineSequence().none { screenTitle.matches(it.trim()) } &&
            extractOrderId(packageName, receiptFacts(normalize(evidenceText))) == null)
            return ScreenRecognition(null, "unknown", modelCandidate = false, evidenceText = text)
        var parsed = parseScreenReceipt(packageName, evidenceText)
        if (parsed == null && structure != null) {
            val actions = structure.standaloneActionLabels().intersect(standaloneCouponActions)
            if (actions.isNotEmpty()) {
                val receiptOnly = evidenceText.lineSequence().filterNot { it.trim() in actions }.joinToString("\n")
                // A separate coupon button may accompany a receipt. Never use this fallback
                // to make an unverified or orderless screen eligible for cloud recognition.
                if (receiptOnly != evidenceText) parseScreenReceipt(packageName, receiptOnly)
                    ?.takeIf { it.orderId != null && labelledAmount.findAll(receiptFacts(receiptOnly)).any { match ->
                        match.groupValues[1] in actualLabels && MoneyUtil.parsePositiveYuanToCents(match.groupValues[2]) == it.amountCents
                    } }?.let { parsed = it; evidenceText = receiptOnly }
            }
        }
        val decorated = parsed?.let { receipt ->
            val result = decorate(packageName, evidenceText, receipt, structure, evidenceSource)
            val statusEvidence = completionProofs.singleOrNull()?.second ?: return@let result
            result.copy(fieldEvidence = result.fieldEvidence + ("paymentStatus" to "$evidenceSource·明确状态字段：$statusEvidence"))
        }
        return ScreenRecognition(decorated,
            parsed?.let { scene(receiptFacts(evidenceText), it) } ?: "unknown",
            isScreenModelCandidate(packageName, evidenceText), evidenceText,
            visualCandidate = isScreenVisualCandidate(packageName, evidenceText))
    }

    private fun scene(text: String, parsed: NotificationParseResult): String = when {
            isRedPacket(text) && refundCompleted.containsMatchIn(text) -> "red_packet_returned"
            refundCompleted.containsMatchIn(text) -> "refund_received"
            isRedPacket(text) && parsed.direction == "EXPENSE" -> "red_packet_sent"
            isRedPacket(text) && parsed.direction == "INCOME" -> "red_packet_received"
            Regex("收款码|收钱码|二维码收款|扫码收款|微信收款助手|收款助手").containsMatchIn(text) && parsed.direction == "INCOME" -> "qr_payment_received"
            isAccountMovement(text) -> "account_transfer"
            "转账" in text && parsed.direction == "EXPENSE" -> "transfer_sent"
            "转账" in text && parsed.direction == "INCOME" -> "transfer_received"
            parsed.direction == "EXPENSE" -> "merchant_payment"
            parsed.direction == "INCOME" -> "payment_received"
            else -> "unknown"
        }

    // Match a receipt action or labelled transaction type. A funding account such as 零钱通,
    // a merchant name, or the word 还款 in a transfer remark is not proof of an account movement.
    private val accountMovement = Regex(
        """(?:^|[\n，,；;])\s*(?:(?:交易类型|业务类型|资金用途|交易场景)\s*[:：]\s*)?(?:(?:(?:本人)?账户(?:间)?(?:互转|调拨)|本人(?:账户|银行卡)(?:转入|转出)|转(?:账)?给自己(?:的)?(?:账户|银行卡|微信|支付宝)?|(?:零钱通|余额宝)(?:转入|转出)|(?:零钱|余额|储值|账户|钱包)充值|信用卡还款|还信用卡)(?:已)?(?:成功|完成|到账)?|提现(?:已)?(?:成功|完成|到账)|(?:收款方|收款人|转入账户)\s*[:：]\s*(?:本人|自己)(?:的)?(?:账户|银行卡|微信|支付宝)?)(?=$|[\s，,。；;！!])"""
    )

    internal fun isAccountMovement(facts: String): Boolean = accountMovement.containsMatchIn(facts)

    private fun isRedPacket(text: String): Boolean = Regex("""(?:^|\n)微信红包(?:\n|$)|红包(?:发送成功|已发出|已退还|退回成功|已存入零钱)|已发红包|已领取红包|本人领取金额|收到红包金额|(?:^|\n).{1,40}的红包(?:\n|$)|(?:^|\n)红包金额[：:\s0-9]""").containsMatchIn(text)

    private fun decorate(packageName: String, text: String, parsed: NotificationParseResult,
        structure: PaymentScreenStructure? = null, source: String = "通知"): NotificationParseResult {
        val scene = scene(receiptFacts(text), parsed)
        val businessKind = when (scene) {
            "merchant_payment" -> "expense"
            "payment_received" -> "income"
            "refund_received" -> "refund"
            else -> "unknown"
        }
        val detailed = withReceiptDetails(packageName, text, parsed, structure, source)
        return detailed.copy(businessKind = businessKind, scene = scene,
            ruleVersion = RULE_VERSION,
            fieldEvidence = fieldEvidence(packageName, detailed, text, structure, source))
    }

    data class ScreenRecognition(
        val parsed: NotificationParseResult?,
        val scene: String,
        val modelCandidate: Boolean,
        val evidenceText: String,
        val ruleVersion: String = RULE_VERSION,
        val visualCandidate: Boolean = false
    )

    private fun fieldEvidence(packageName: String, result: NotificationParseResult, text: String,
        structure: PaymentScreenStructure?, source: String): Map<String, String> {
        val normalized = receiptFacts(normalize(text))
        fun reference(quote: String): String {
            val node = structure?.nodes?.firstOrNull { it.labels.any { label -> normalize(label).contains(quote) } }
            return "$source·直接读取${node?.let { "·节点${it.id}" }.orEmpty()}：${quote.take(180)}"
        }
        val amount = amounts(normalized).firstOrNull { it.cents == result.amountCents && it.role != Role.IGNORED }
        val status = (if (refundCompleted.containsMatchIn(normalized)) refundCompleted
            else if (result.direction == "EXPENSE") expenseCompleted else incomeCompleted).find(normalized)?.value
            ?: neutralCompleted.find(normalized)?.value
        val labelledDirection = labelledAmount.findAll(normalized).firstOrNull { match ->
            (if (result.direction == "INCOME") role(match.groupValues[1]) == Role.INCOME
            else role(match.groupValues[1]) in setOf(Role.ACTUAL, Role.PAYMENT)) &&
                MoneyUtil.parsePositiveYuanToCents(match.groupValues[2]) == result.amountCents
        }?.value
        return buildMap {
            amount?.let { put("amount", reference(it.raw)) }
            (labelledDirection ?: status)?.let { put("direction", reference(it)) }
            status?.let { put("paymentStatus", reference(it)) }
            result.merchant?.takeIf(normalized::contains)?.let { put("merchant", reference(it)) }
            result.orderId?.let { put(if (result.orderIdKind != null) "refundId" else "orderId", reference(it)) }
            if (refundCompleted.containsMatchIn(normalized)) extractPaymentOrderId(packageName, normalized)?.let {
                put("originalOrderId", reference(it))
            }
        }
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
        if (text.length > 16_000 || rejects(text) || conflictingDirections(text) || ambiguousOrderIds(receiptFacts(text), packageName)) return false
        val facts = receiptFacts(text)
        val statement = notificationStatement(packageName, normalizedTitle, body)
        if (statement != null) return true
        if (packageName == "com.eg.android.AlipayGphone" &&
            ("你有一笔" in facts || ("有一笔" in facts && "自动扣款支付" in facts))) {
            // An incomplete statement must not fall through to the older wallet action fallback.
            return false
        }
        // “交易成功”只证明完成；没有独立收支依据时也不能交给自定义规则或云端猜方向。
        if (hasCompletion(facts)) return !neutralCompleted.containsMatchIn(facts) || direction(facts) != null
        // 新增的提醒标题和云闪付必须有完成事实，不能仅凭“退款金额”等字段认定入账。
        if (packageName == "com.unionpay" || normalizedTitle in setOf("交易提醒", "退款提醒")) return false
        // 钱包的支付通知允许“向商户付款 18 元”“到账 100 元”等已发生动作。
        if (packageName == "com.tencent.mm" || packageName == "com.eg.android.AlipayGphone") {
            val bodyFacts = receiptFacts(body)
            if (labelledAmount.containsMatchIn(bodyFacts) && (paymentAction.containsMatchIn(bodyFacts) || incomeAction.containsMatchIn(bodyFacts))) return true
            if (normalizedTitle == "微信支付" && Regex("""^\s*[¥￥]\s*$RAW_AMOUNT\s*(?:元)?\s*$""").matches(body)) return true
        }
        return false
    }

    internal fun parseNotification(packageName: String, title: String, content: String): NotificationParseResult? {
        if (!isNotificationCandidate(packageName, title, content)) return null
        val text = normalize("$title\n$content")
        val statement = notificationStatement(packageName, normalize(title).trim(), normalize(content))
        return parseReceipt(packageName, text, statement?.direction)?.let { parsed ->
            val decorated = decorate(packageName, text, parsed)
            if (statement == null) return@let decorated
            val evidence = decorated.fieldEvidence + mapOf(
                "direction" to "通知·交易动账语句：${statement.quote}",
                "paymentStatus" to "通知·交易动账语句：${statement.quote}"
            )
            val merchant = statement.merchant
            val details = decorated.details.mergeMissing(PaymentReceiptDetails(merchantName = merchant,
                fundingAccount = statement.fundingAccount, fieldSources = buildMap {
                    if (merchant != null) put("merchantName", "通知·交易动账语句")
                    if (statement.fundingAccount != null) put("fundingAccount", "通知·银行卡尾号")
                }))
            decorated.copy(merchant = if (merchant != null) details.merchantName else decorated.merchant, details = details,
                fieldEvidence = if (merchant != null && details.merchantName == merchant)
                    evidence + ("merchant" to "通知·交易动账语句：$merchant") else evidence)
        }
    }

    private fun notificationStatement(packageName: String, title: String, body: String): NotificationStatement? {
        if (packageName == "com.unionpay" && title in unionPayAssistantTitles) {
            val match = unionPayCompletedCharge.matchEntire(receiptFacts(body).trim()) ?: return null
            val (proof, amount, tail, card) = match.destructured
            if (MoneyUtil.parsePositiveYuanToCents(amount) == null) return null
            // The notice only gives day/hour/minute. Leave the year/month and actual timestamp unknown.
            return NotificationStatement("EXPENSE", proof, fundingAccount = "${card}（尾号 $tail）")
        }
        if (packageName != "com.eg.android.AlipayGphone" || title !in setOf("支付宝", "支付宝通知", "交易提醒")) return null
        val lines = receiptFacts(body).lines().map(String::trim).filter(String::isNotEmpty)
        val statements = lines.mapIndexedNotNull { index, line ->
            alipayTransactionStatement.matchEntire(line)?.let { match ->
                val direction = if (match.groupValues[2] == "收入") "INCOME" else "EXPENSE"
                return@mapIndexedNotNull index to NotificationStatement(direction, match.groupValues[1])
            }
            val match = alipayAutoDebitStatement.matchEntire(line) ?: return@mapIndexedNotNull null
            val merchant = match.groupValues[2].trim()
            if (merchant.isEmpty() || "有一笔" in merchant || currencyAmount.containsMatchIn(merchant) ||
                hasCompletion(merchant) || statementNegation.containsMatchIn(merchant) ||
                MoneyUtil.parsePositiveYuanToCents(match.groupValues[3]) == null) return@mapIndexedNotNull null
            index to NotificationStatement("EXPENSE", match.groupValues[1], merchant)
        }
        val (statementIndex, statement) = statements.singleOrNull() ?: return null
        val fields = lines.filterIndexed { index, _ -> index != statementIndex }
        var index = 0
        while (index < fields.size) {
            val parts = fields[index].split(Regex("[:：]"), limit = 2)
            val label = parts.first().trim()
            val value = if (parts.size == 2) parts[1].trim() else fields.getOrNull(++index) ?: return null
            if (!statementFieldValue.matches(value) || currencyAmount.containsMatchIn(value) ||
                hasCompletion(value) || statementNegation.containsMatchIn(value) || "一笔" in value) return null
            when (label) {
                in statementMerchantLabels -> if (value.all(Char::isDigit)) return null
                in statementOrderLabels -> if (!validOrderId.matches(value) || value.none(Char::isDigit)) return null
                in statementMethodLabels -> if (!paymentMethod.matches("$label：$value")) return null
                else -> return null
            }
            index++
        }
        return statement
    }

    internal fun canUseFallback(text: String, requireAmount: Boolean = false): Boolean {
        val normalized = normalize(text)
        if (rejects(normalized) || conflictingDirections(normalized) || ambiguousOrderIds(receiptFacts(normalized))) return false
        val decision = chooseAmount(receiptFacts(normalized))
        return !decision.unsafe && (!requireAmount || decision.cents != null)
    }

    /** 规则和云端结果也不能覆盖已知实付金额、退款方向或金额边界。 */
    internal fun isConsistentFallback(text: String, result: NotificationParseResult): Boolean {
        if (!MoneyUtil.isValidAmountCents(result.amountCents) || result.direction !in setOf("EXPENSE", "INCOME")) return false
        val normalized = normalize(text)
        if (rejects(normalized) || conflictingDirections(normalized) || ambiguousOrderIds(receiptFacts(normalized))) return false
        val decision = chooseAmount(receiptFacts(normalized))
        if (decision.unsafe || (decision.cents != null && decision.cents != result.amountCents)) return false
        val knownDirection = direction(receiptFacts(normalized))
        return knownDirection == null || knownDirection == result.direction
    }

    internal fun withEvidence(packageName: String, text: String, result: NotificationParseResult): NotificationParseResult = withReceiptDetails(
        packageName, normalize(text), result.copy(
            orderId = extractOrderId(packageName, receiptFacts(normalize(text))),
            orderIdKind = refundIdentity(receiptFacts(normalize(text)))?.second,
            paymentMethod = extractPaymentMethod(packageName, receiptFacts(normalize(text)))
        ), source = "通知")

    /** The caller must validate payment facts before enriching its descriptive fields. */
    internal fun withReceiptDetails(packageName: String, text: String, parsed: NotificationParseResult,
        structure: PaymentScreenStructure? = null, source: String = "页面"): NotificationParseResult {
        val extracted = PaymentReceiptDetailsReader.read(packageName, text, structure, source)
        val details = extracted.details
        val merchant = if (parsed.direction == "EXPENSE") details.merchantName ?: details.payeeName ?:
            parsed.merchant.takeUnless { extracted.ambiguousParty }
            else parsed.merchant ?: details.merchantName
        return parsed.copy(merchant = merchant, details = details)
    }

    private fun parseReceipt(packageName: String, text: String, statedDirection: String? = null): NotificationParseResult? {
        val facts = receiptFacts(text)
        val direction = statedDirection ?: direction(facts) ?: return null
        val decision = chooseAmount(facts)
        if (decision.unsafe || ambiguousOrderIds(facts, packageName)) return null
        return NotificationParseResult(
            amountCents = decision.cents ?: return null,
            merchant = extractMerchant(facts, direction),
            direction = direction,
            orderId = extractOrderId(packageName, facts),
            orderIdKind = refundIdentity(facts)?.second,
            paymentMethod = extractPaymentMethod(packageName, facts)
        )
    }

    /** Free-form remarks cannot provide payment state, amounts, time or identity evidence. */
    internal fun receiptFacts(text: String): String = PaymentReceiptSections.split(text).facts

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

    private fun rejects(text: String): Boolean {
        val facts = receiptFacts(text)
        return failedOrPending.containsMatchIn(facts) || unresolvedStatus.containsMatchIn(facts) ||
            promotion.containsMatchIn(facts) || foreignCurrency.containsMatchIn(facts) ||
            (Regex("""已(?:全额|全部)退款""").containsMatchIn(facts) && !refundCompleted.containsMatchIn(facts)) ||
            (counterpartyCollection.containsMatchIn(facts) && !expenseCompleted.containsMatchIn(facts) && !refundCompleted.containsMatchIn(facts)) ||
            (Regex("""红包(?:已)?(?:过期|失效|领完)|红包已被领取""").containsMatchIn(facts) && !refundCompleted.containsMatchIn(facts))
    }

    private fun hasCompletion(text: String): Boolean = expenseCompleted.containsMatchIn(text) || incomeCompleted.containsMatchIn(text) || refundCompleted.containsMatchIn(text) || neutralCompleted.containsMatchIn(text)

    private fun conflictingDirections(text: String): Boolean {
        val facts = receiptFacts(text)
        if (refundCompleted.containsMatchIn(facts)) return false
        val incoming = incomeCompleted.containsMatchIn(facts) || labelledAmount.findAll(facts).any { it.groupValues[1] in incomeLabels }
        // A generic transaction-success state proves completion, not who received the money.
        return incoming && expenseCompleted.containsMatchIn(facts)
    }

    private fun hasScreenReceiptStructure(text: String): Boolean {
        val lines = receiptFacts(text).lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.none(screenTitle::matches)) return false
        val isolatedMoney = Regex("""^(?:[¥￥]\s*$RAW_AMOUNT\s*(?:元)?|$RAW_AMOUNT\s*元)$""")
        if (lines.any(isolatedMoney::matches)) return true
        if (lines.zipWithNext().any { (first, second) ->
                first in setOf("¥", "￥") && Regex("""^$RAW_AMOUNT\s*(?:元)?$""").matches(second) ||
                    second == "元" && Regex("""^$RAW_AMOUNT$""").matches(first)
            }) return true
        return labelledAmount.findAll(receiptFacts(text)).any { match ->
            match.groupValues[1] in screenAmountLabels
        }
    }

    private fun direction(text: String): String? = when {
        refundCompleted.containsMatchIn(text) -> "INCOME"
        incomeCompleted.containsMatchIn(text) -> "INCOME"
        expenseCompleted.containsMatchIn(text) -> "EXPENSE"
        labelledAmount.findAll(text).any { role(it.groupValues[1]) == Role.INCOME } -> "INCOME"
        labelledAmount.findAll(text).any { role(it.groupValues[1]) in setOf(Role.ACTUAL, Role.PAYMENT) } -> "EXPENSE"
        neutralCompleted.containsMatchIn(text) -> null
        incomeAction.containsMatchIn(text) -> "INCOME"
        paymentAction.containsMatchIn(text) -> "EXPENSE"
        else -> null
    }

    private fun role(label: String?): Role = when (label) {
        in ignoredLabels -> Role.IGNORED
        in actualLabels -> Role.ACTUAL
        in actualRefundLabels -> Role.ACTUAL_REFUND
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
            refundCompleted.containsMatchIn(text) -> relevant.filter { it.role == Role.ACTUAL_REFUND }
                .ifEmpty { relevant.filter { it.role == Role.REFUND } }
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

    private fun refundIds(text: String): List<Pair<String, String>> = if (!refundCompleted.containsMatchIn(text)) emptyList() else
        refundToken.findAll(text).mapNotNull { match ->
            val id = match.groupValues[2]
            if (validOrderId.matches(id) && id.any(Char::isDigit))
                id to if (match.groupValues[1].startsWith("商")) "merchant_refund" else "refund"
            else null
        }.distinct().toList()

    private fun ambiguousOrderIds(text: String, packageName: String? = null): Boolean =
        refundIds(text).groupBy { it.second }.values.any { it.size > 1 } ||
            paymentIds(text, packageName).groupBy { it.second }.values.any { it.size > 1 }

    private fun refundIdentity(text: String): Pair<String, String>? = refundIds(text)
        .sortedBy { if (it.second == "refund") 0 else 1 }.firstOrNull()

    private fun extractOrderId(packageName: String, text: String): String? {
        // 同一原订单可分次退款。只将退款自身身份用于强关联，原支付号保留为展示依据。
        if (refundCompleted.containsMatchIn(text)) return refundIdentity(text)?.first
        return extractPaymentOrderId(packageName, text)
    }

    private fun extractPaymentOrderId(packageName: String, text: String): String? {
        return paymentIds(text, packageName).sortedBy { if (it.second == "merchant") 0 else 1 }.firstOrNull()?.first
    }

    private fun paymentIds(text: String, packageName: String?): List<Pair<String, String>> {
        // Refund tokens contain words such as 订单号 as suffixes; never reinterpret them as payment IDs.
        val paymentText = text.replace(refundToken, "")
        return orderToken.findAll(paymentText).mapNotNull { match ->
            val (label, id) = match.destructured
            if (label in wechatOrderLabels) {
                // A different app must not claim this wallet's identity. Source-neutral checks
                // and user-selected image drafts can still validate the printed order number.
                if (!packageName.isNullOrEmpty() && packageName != "com.tencent.mm") return@mapNotNull null
                val previous = paymentText.getOrNull(match.range.first - 1)
                if ((previous != null && !previous.isWhitespace() && previous !in "，,。；;") ||
                    !wechatWalletOrderId.matches(id)) return@mapNotNull null
            } else if (!validOrderId.matches(id) || id.none(Char::isDigit)) return@mapNotNull null
            id to if (label in setOf("商户订单号", "商家订单号", "商户单号")) "merchant" else "payment"
        }.distinct().toList()
    }

    private fun extractPaymentMethod(packageName: String, text: String): String? = paymentMethod.find(text)?.groupValues?.get(1)
        ?: when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.unionpay" -> "云闪付"
            else -> null
        }

    private fun extractMerchant(text: String, direction: String): String? {
        val raw = (if (direction == "INCOME") payerMerchant.find(text)?.groupValues?.get(1) else null)
            ?: merchantField.find(text)?.groupValues?.get(1)
            ?: merchantLines.find(text)?.groupValues?.get(1)
            ?: (if (direction == "EXPENSE") transferMerchant.find(text)?.groupValues?.get(1) else payerMerchant.find(text)?.groupValues?.get(1))
            ?: (if (direction == "INCOME") incomeMerchant.find(text)?.groupValues?.get(1) else null)
            ?: actionMerchant.find(text)?.groupValues?.get(1)
            ?: return null
        return raw.split(Regex("""\s+(?:订单号|交易号|支付方式|付款方式|实付|金额|支付金额|付款金额)"""), limit = 2)
            .first().trim().removeSuffix("的").takeIf { it.length in 1..50 && !it.all(Char::isDigit) }
    }
}

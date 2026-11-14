package com.pennypilot.app.data.parser

import com.pennypilot.app.util.MoneyUtil

/** Application-scoped translations for completed commerce receipts. */
internal object CommercePaymentPages {
    private const val DECIMAL = "[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?"
    private val amountValue = Regex("""^[¥￥]?\s*$DECIMAL\s*(?:元)?$""")
    private val currencyValue = Regex("""^(?:[¥￥]\s*$DECIMAL\s*(?:元)?|$DECIMAL\s*元)$""")
    private val actionLabels = setOf("完成", "关闭", "返回", "返回首页", "查看订单", "查看订单详情", "查看订单状态",
        "继续逛逛", "返回商家")
    private val orderStates = setOf("下单成功，等待商家接单", "下单成功，商家已接单", "下单成功，商家正在备餐",
        "下单成功，商家备餐中", "下单成功，商家备货完成")
    private val payTitles = setOf("支付成功", "付款成功", "交易成功", "已支付", "支付完成", "付款完成",
        "先用后付自动扣款成功", "极速支付成功", "自动扣款成功", "免密支付成功", "自动付款成功")
    private val refundTitles = setOf("退款成功", "退款到账", "退款已到账", "已退款",
        "退款处理完成，微信已入账", "退款处理完成，支付宝已入账")
    private val actualLabels = setOf("实付金额", "实付", "实付款", "实际付款", "实际支付", "实扣金额", "付款金额", "支付金额")
    private val deferredPayment = Regex(
        """^(?:(?:0元下单[，,]\s*)?确认收货后(?:抖音月付)?付款|0元下单[，,]\s*收货后付款|先用后付|先享后付|已下单[，,]\s*未付款|暂未扣款)[。!！]?$"""
    )
    private val promotionalMoney = Regex(
        """^(?:消费返现|支付返现|返现|返红包|返券|返利|得红包|送红包|领红包|预计返现|预估返现|最高返现)\s*[:：]?\s*([¥￥]?\s*$DECIMAL\s*元?|[¥￥]\s*$DECIMAL)[。!！]?$"""
    )
    private val marketingContext = Regex("""支付返现|消费返现|返现|返利|返红包|返券|得红包|送红包|奖励红包""")
    private val invalidAmountLabel = Regex("""^(?:券后价|优惠价|现价)""")
    private val statusField = Regex("""^(支付状态|付款状态|订单状态|交易状态|当前状态)\s*[:：]\s*(.+)$""")
    private val moneyAnywhere = Regex("""[¥￥]|[0-9０-９](?:(?:[.,．，][0-9０-９])|(?:\s*元))""")
    private val unverifiedResult = Regex(
        """待(?:付款|支付)|未(?:付款|支付|扣款)|处理中|失败|取消|关闭交易|退款|订单列表|我的订单|全部订单|历史订单|直播间|评论|教程|示例|截图示意|请输入|密码|验证码|立即(?:支付|付款)|确认(?:订单|支付|付款)|提交订单"""
    )
    private val packageLayouts: Map<String, Layout> = buildMap {
        put("com.jingdong.app.mall", Layout(
            inlineTitles = setOf("支付成功", "付款成功"), aliases = mapOf("退款说明" to "备注"),
            resultActivities = setOf("com.jd.lib.cashier.complete.view.CashierCompleteActivity",
                "com.jd.lib.cashier.sdk.complete.view.CashierCompleteActivity")
        ))
        put("com.xunmeng.pinduoduo", Layout(
            orderResults = setOf("拼单成功", "免拼成功"),
            aliases = mapOf("多多支付立减优惠" to "优惠金额", "关联商品" to "商品名称"),
        ))
        put("com.alibaba.wireless", Layout(inlineTitles = setOf("支付成功", "付款成功")))
        put("com.taobao.idlefish", Layout(inlineTitles = setOf("支付成功"), aliases = mapOf("现价" to "商品金额")))
        put("com.taobao.taobao", Layout(
            inlineTitles = setOf("支付成功", "免密支付成功"), orderResults = orderStates,
            standaloneResults = setOf("免密支付成功"), aliases = mapOf("实付价" to "实付金额"),
        ))
        val meituan = Layout(
            inlineTitles = setOf("支付成功", "交易成功"),
        )
        for (app in listOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1")) put(app, meituan)
        put("me.ele", Layout(orderResults = orderStates))
        put("com.wudaokou.hippo", Layout(inlineTitles = setOf("支付成功")))
        put("com.ss.android.ugc.lifeservices", Layout(inlineTitles = setOf("支付成功")))
        val kuaishou = Layout(
            standaloneResults = setOf("购买成功"), inlineTitles = setOf("购买成功", "支付成功"),
            aliases = mapOf("实付价" to "实付金额"),
        )
        put("com.smile.gifmaker", kuaishou)
        put("com.kuaishou.nebula", kuaishou)
        val douyin = Layout(
            standaloneResults = setOf("购买成功", "自动付款成功"), inlineTitles = setOf("支付成功", "付款成功", "购买成功"),
            aliases = mapOf("实付价" to "实付金额", "交易方式" to "付款方式", "账单说明" to "订单说明",
                "券后价" to "商品金额"),
        )
        for (app in listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.livelite", "com.ss.android.ugc.aweme.lite",
            "com.ss.android.yumme.video")) put(app, douyin)
    }

    private data class Layout(
        val standaloneResults: Set<String> = emptySet(),
        val inlineTitles: Set<String> = emptySet(),
        val orderResults: Set<String> = emptySet(),
        val aliases: Map<String, String> = emptyMap(),
        val resultActivities: Set<String> = emptySet(),
    )

    private fun exactFieldValue(lines: List<String>, index: Int, name: String): String? {
        val text = lines[index]
        if (text == name || text == "$name：" || text == "$name:") {
            val next = lines.getOrNull(index + 1)?.trim() ?: return null
            return if (next == "¥" || next == "￥") "$next${lines.getOrNull(index + 2).orEmpty()}" else next
        }
        val value = text.removePrefix(name)
        if (value == text || value.firstOrNull()?.let { it !in "：: ¥￥0123456789" } != false) return null
        return value.trimStart('：', ':', ' ').takeIf(String::isNotEmpty)
    }

    private fun labelledPaymentAmounts(lines: List<String>, extraActualLabels: Set<String>): Set<Long> {
        val labels = actualLabels + extraActualLabels
        return lines.indices.flatMap { index -> labels.mapNotNull { label ->
            exactFieldValue(lines, index, label)?.takeIf(amountValue::matches)?.let(::cents)
        } }.toSet()
    }

    /** A checkout amount may seed a review only after an independent completed result is visible. */
    fun isPaymentCompletionWithoutAmount(packageName: String, text: String, activityName: String?): Boolean {
        val layout = packageLayouts[packageName] ?: return false
        if (text.length > 12_000 || blocksReceipt(packageName, text)) return false
        val lines = facts(text, layout)
        if (lines.any { line -> unverifiedResult.containsMatchIn(line) || moneyAnywhere.containsMatchIn(line) ||
                amountValue.matches(line) || actualLabels.any { label ->
                    line == label || line.startsWith("$label：") || line.startsWith("$label:")
                } }) return false
        if (lines.any { it in PaymentSceneAdapters.detailTitles(packageName) }) return false
        val titles = setOf("支付成功", "付款成功", "支付完成", "付款完成") + layout.standaloneResults
        return lines.take(6).any { it.trimEnd('。', '!', '！') in titles } &&
            (lines.any(actionLabels::contains) || activityName in layout.resultActivities)
    }

    /** These sources can show a reward amount beside non-monetary success or delayed-payment text. */
    fun blocksReceipt(packageName: String, text: String): Boolean {
        val layout = packageLayouts[packageName] ?: return false
        if (text.length > 12_000) return true
        val lines = facts(text, layout)
        if (lines.any(deferredPayment::matches)) return true
        val actualAliases = layout.aliases.filterValues { it == "实付金额" }.keys
        val labelled = labelledPaymentAmounts(lines, actualAliases)
        if (labelled.size > 1) return true
        if (labelled.isNotEmpty() && hasConflictingHeadline(lines, layout, labelled.single())) return true
        if (!lines.any(marketingContext::containsMatchIn) && !lines.any(invalidAmountLabel::containsMatchIn)) return false
        val independentAmount = labelled.isNotEmpty() ||
            lines.any { inlineReceipt(it, layout) != null } ||
            lines.any(refundTitles::contains) &&
                lines.indices.any { exactFieldValue(lines, it, "退款金额")?.let(amountValue::matches) == true }
        return !independentAmount
    }

    fun normalize(packageName: String, page: PaymentPageNormalizer.Result, activityName: String? = null): PaymentPageNormalizer.Result {
        val layout = packageLayouts[packageName] ?: return page
        if (page.text.length > 12_000 || blocksReceipt(packageName, page.text)) return page
        val lines = facts(page.text, layout)
        val rawLines = page.text.lines().map(String::trim)
        // A text value occurring inside a free-form product description is not a status or a price.
        val factCount = lines.groupingBy { it }.eachCount()
        val rawCount = rawLines.groupingBy { it }.eachCount()
        val directPayment = lines.any { line -> line.trimEnd('。', '!', '！') in payTitles ||
            statusField.matchEntire(line)?.groupValues?.get(2) in payTitles || inlineReceipt(line, layout) != null }
        val explicitReward = lines.any(promotionalMoney::matches)
        val actualAliases = layout.aliases.filterValues { it == "实付金额" }.keys
        val explicitPaid = labelledPaymentAmounts(lines, actualAliases).isNotEmpty()
        val detail = lines.take(6).any { it in PaymentSceneAdapters.detailTitles(packageName) }
        val completionControl = lines.any(actionLabels::contains) || activityName in layout.resultActivities
        val amountHeadline = lines.count(currencyValue::matches) + lines.zipWithNext().count { (left, right) ->
            left in setOf("¥", "￥") && amountValue.matches(right) && !currencyValue.matches(right)
        } == 1
        val promoFree = !explicitReward && lines.none(marketingContext::containsMatchIn)
        val scopedStatus = lines.take(6).any { it.trimEnd('。', '！', '!') in layout.standaloneResults }
        val orderResult = lines.take(6).any { it.replace(',', '，') in layout.orderResults }
        val successWithPayment = (scopedStatus && (completionControl || detail) &&
            (explicitPaid || amountHeadline && promoFree && !detail)) || (orderResult && completionControl && explicitPaid)
        if (!directPayment && !successWithPayment && lines.none { it in refundTitles }) return page

        val changes = mutableListOf<String>()
        val mapped = rawLines.flatMap { line ->
            if (factCount[line] != rawCount[line]) return@flatMap listOf(line)
            val inline = inlineReceipt(line, layout)
            val status = line.trimEnd('。', '!', '！')
            val alias = layout.aliases.keys.firstOrNull { label ->
                line == label || line.startsWith("$label：") || line.startsWith("$label:") ||
                    line.startsWith("$label ") || line.startsWith("$label¥") || line.startsWith("$label￥")
            }
            val replacement = when {
                inline != null && (!detail || explicitPaid) ->
                    listOf(if (inline.first == "交易成功") "交易成功" else "支付成功", "实付金额：${inline.second}元")
                successWithPayment && (status in layout.standaloneResults || line.replace(',', '，') in layout.orderResults) ->
                    listOf("支付成功")
                alias != null && layout.aliases.getValue(alias) != alias ->
                    listOf(layout.aliases.getValue(alias) + line.removePrefix(alias).let { suffix ->
                        if (suffix.startsWith('¥') || suffix.startsWith('￥')) "：$suffix" else suffix
                    })
                promotionalMoney.matches(line) -> listOf("付款奖励")
                else -> listOf(line)
            }
            if (replacement != listOf(line)) changes += line
            replacement
        }
        return if (changes.isEmpty()) page else PaymentPageNormalizer.Result(mapped.joinToString("\n"), (page.evidence + changes).distinct())
    }

    private fun facts(text: String, layout: Layout): List<String> =
        PaymentReceiptSections.split(text, additionalBoundaries = layout.aliases.keys)
            .facts.lines().map(String::trim).filter(String::isNotEmpty)

    private fun hasConflictingHeadline(lines: List<String>, layout: Layout, labelledAmount: Long): Boolean {
        val resultTitles = payTitles + layout.standaloneResults
        for (index in 0 until minOf(lines.size, 6)) {
            val line = lines[index].trimEnd('。', '!', '！')
            val inline = inlineReceipt(line, layout)?.second?.let(::cents)
            if (inline != null && inline != labelledAmount) return true
            if (line !in resultTitles) continue
            val next = lines.getOrNull(index + 1) ?: continue
            val headline = when {
                next in setOf("¥", "￥") -> lines.getOrNull(index + 2)?.takeIf(amountValue::matches)
                currencyValue.matches(next) -> next
                else -> null
            }?.let(::cents)
            if (headline != null && headline != labelledAmount) return true
        }
        return false
    }

    private fun cents(value: String): Long? =
        MoneyUtil.parsePositiveYuanToCents(value.trim().trimStart('¥', '￥').trim().removeSuffix("元").trim())

    private val inlineStatusAmount = Regex(
        """^([\p{IsHan}]{2,12})[：:\s]+[¥￥]\s*($DECIMAL)(?:\s*元)?[。！!]?$"""
    )

    private fun inlineReceipt(line: String, layout: Layout): Pair<String, String>? =
        inlineStatusAmount.matchEntire(line)?.destructured?.let { (title, amount) ->
            if (title in layout.inlineTitles) title to amount else null
        }
}

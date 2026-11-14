package com.pennypilot.app.data.parser

import kotlinx.serialization.Serializable
import com.pennypilot.app.util.MoneyUtil

@Serializable
data class PaymentReceiptContext(val label: String, val value: String, val source: String = "")

/** Auxiliary receipt fields never replace the independently validated transaction amount or status. */
data class PaymentReceiptDetails(
    val merchantName: String? = null,
    val payeeName: String? = null,
    val productDescription: String? = null,
    val remark: String? = null,
    val paymentChannel: String? = null,
    val fundingAccount: String? = null,
    val fieldSources: Map<String, String> = emptyMap(),
    val context: List<PaymentReceiptContext> = emptyList(),
    val originalAmountCents: Long? = null,
    val discountAmountCents: Long? = null,
    val feeAmountCents: Long? = null,
    val receivedAmountCents: Long? = null,
    val transferFromAccount: String? = null,
    val transferToAccount: String? = null
) {
    fun mergeMissing(other: PaymentReceiptDetails): PaymentReceiptDetails {
        val local = textValues()
        val incoming = other.textValues()
        val selected = local.mapValues { (field, value) -> value?.takeIf(String::isNotBlank) ?: incoming[field]?.takeIf(String::isNotBlank) }
        val money = moneyValues().mapValues { (field, value) -> value ?: other.moneyValues()[field] }
        val sources = buildMap {
            selected.forEach { (field, value) ->
                if (value != null) {
                    val origin = if (!local[field].isNullOrBlank()) fieldSources else other.fieldSources
                    origin[field]?.takeIf(String::isNotBlank)?.let { put(field, it) }
                }
            }
            money.forEach { (field, value) ->
                if (value != null) {
                    val origin = if (moneyValues()[field] != null) fieldSources else other.fieldSources
                    origin[field]?.takeIf(String::isNotBlank)?.let { put(field, it) }
                }
            }
        }
        return PaymentReceiptDetails(selected["merchantName"], selected["payeeName"],
            selected["productDescription"], selected["remark"], selected["paymentChannel"],
            selected["fundingAccount"], sources, normalizeContext(context + other.context),
            money["originalAmountCents"], money["discountAmountCents"], money["feeAmountCents"], money["receivedAmountCents"],
            selected["transferFromAccount"], selected["transferToAccount"])
    }

    fun isEmpty(): Boolean = textValues().values.all { it.isNullOrBlank() } &&
        moneyValues().values.all { it == null } && normalizeContext(context).isEmpty()

    fun textValues(): Map<String, String?> = mapOf("merchantName" to merchantName, "payeeName" to payeeName,
        "productDescription" to productDescription, "remark" to remark, "paymentChannel" to paymentChannel,
        "fundingAccount" to fundingAccount, "transferFromAccount" to transferFromAccount, "transferToAccount" to transferToAccount)

    fun moneyValues(): Map<String, Long?> = mapOf("originalAmountCents" to originalAmountCents,
        "discountAmountCents" to discountAmountCents, "feeAmountCents" to feeAmountCents, "receivedAmountCents" to receivedAmountCents)

    companion object {
        val TEXT_FIELDS = setOf("merchantName", "payeeName", "productDescription", "remark", "paymentChannel", "fundingAccount",
            "transferFromAccount", "transferToAccount")
        val MONEY_FIELDS = setOf("originalAmountCents", "discountAmountCents", "feeAmountCents", "receivedAmountCents")
        fun normalizeContext(fields: List<PaymentReceiptContext>): List<PaymentReceiptContext> {
            val result = mutableListOf<PaymentReceiptContext>()
            val seen = mutableSetOf<Pair<String, String>>()
            var remaining = 2_400
            for (field in fields) {
                val label = field.label.trim().take(40)
                val value = field.value.replace("\r\n", "\n").replace('\r', '\n').trim().take(300)
                if (label.isEmpty() || value.isEmpty() || !seen.add(label to value)) continue
                if (result.size == 16 || remaining <= label.length) break
                val boundedValue = value.take(remaining - label.length).trimEnd()
                if (boundedValue.isEmpty()) break
                result += PaymentReceiptContext(label, boundedValue, field.source.trim().take(80))
                remaining -= label.length + boundedValue.length
            }
            return result
        }
    }
}

/** Only explicitly labelled transaction descriptions form a section, never an entire window. */
internal object PaymentReceiptSections {
    val productLabels = setOf("商品说明", "商品名称", "商品信息", "交易商品", "商品明细", "商品详情", "订单商品", "购买商品", "商品")
    val remarkLabels = setOf("备注", "转账备注", "转账说明", "付款说明", "收款说明", "留言", "红包祝福语", "付款备注", "付款方留言", "收款方备注", "赔付说明")
    val contextLabels = setOf("订单描述", "订单说明", "交易说明", "服务名称", "服务内容", "服务项目", "项目名称",
        "套餐", "套餐名称", "规格", "商品规格", "数量", "商品数量", "优惠说明", "优惠详情", "优惠内容",
        "配送方式", "取餐方式", "就餐方式")
    val labels = productLabels + remarkLabels + contextLabels
    private val names = labels.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val marker = Regex("(?:^|[\\s，,；;。])($names)\\s*[:：]")
    private val heading = Regex("^($names)\\s*[:：]?$")
    private val receiptBoundary = Regex("^(?:商户名称|商户全称|收款商户|商家名称|店铺名称|门店名称|品牌名称|商户|商家|店铺|门店|品牌|收款方|收款人|付款方|付款人|付款对象|支付方式|付款方式|支付渠道|付款渠道|扣款账户|付款账户|支付账户|付款银行卡|扣款银行卡|创建时间|交易时间|支付时间|付款时间|收款时间|到账时间|完成时间|退款到账时间|退款成功时间|退款完成时间|收支类型|资金方向|交易状态|支付状态|付款状态|收款状态|退款状态|订单状态|当前状态|状态|实际支付|实际付款|实付款|实付金额|实付|实扣金额|实扣|支付金额|付款金额|交易金额|消费金额|转账金额|收款金额|退款金额|退回金额|退还金额|订单金额|商品金额|原支付金额|原价|商品原价|订单原价|总价|优惠金额|已优惠|优惠合计|共优惠|总优惠|配送费|运费|手续费|服务费|提现手续费|实际到账金额|实际到账|到账金额|提现到账金额|转出账户|转出账号|转出银行卡|转入账户|转入账号|收款账户|到账账户|提现账户|转入银行卡|订单编号|商户订单号|商家订单号|支付订单号|交易订单号|${PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS}|订单号|交易单号|交易号|流水号|退款单号|退款编号|商户退款单号)(?:\\s*[:：]|\\s*$|\\s*[¥￥0-9])")
    private val otherField = Regex("^[\\p{L}\\p{N}（）() _-]{1,40}[:：]")
    private val privateHeading = Regex("^(?:收货地址|联系地址|收货人|联系人|联系电话|手机号码|手机号|身份证号|验证码|密码)(?:[:：\\s]|$)")
    private val inlineReceiptBoundary = Regex("[，,；;]\\s*(?:商户名称|商户全称|商家名称|店铺名称|门店名称|商户|商家|店铺|门店|收款方|收款人|付款方|付款人|支付方式|付款方式|支付渠道|付款渠道|扣款账户|付款账户|创建时间|交易时间|支付时间|付款时间|收款时间|到账时间|交易状态|支付状态|实付金额|实付|支付金额|付款金额|交易金额|订单编号|商户订单号|商家订单号|支付订单号|交易订单号|${PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS}|订单号|交易单号|交易号|流水号|退款单号|退款编号|联系电话|手机号|收货地址|联系地址|收货人)\\s*[:：]")
    private val identityBoundary = Regex("^(?:订单编号|商户订单号|商家订单号|支付订单号|交易订单号|${PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS}|订单号|交易单号|交易号|流水号|退款单号|退款编号|商户退款单号)\\s*[:：]?\\s*[A-Za-z0-9]{10,64}$")
    private val financialAssertion = Regex("(?:支付|付款|消费|收款|转账|退款|退回|到账|入账|交易)(?:已)?(?:成功|失败|受理|完成|取消|处理中|到账)|(?:未|待)(?:支付|付款|到账)|(?:${PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS}|订单号|交易单号|交易号|退款单号)")
    private val controls = Regex("^(?:完成|返回|关闭|查看(?:订单|账单|交易)?详情|联系商家|联系客服|推荐|更多推荐|点击领取优惠券|领取优惠券|立即领取|领券|限时福利|支付立减|消费立减|待领取).*$|^(?:账单分类|积分奖励|标签和备注)(?:[:：].*)?$")
    // A standalone negative state after a description still blocks a receipt. A product line
    // containing such words is semantic text and cannot establish or reverse a money movement.
    private val negativeState = Regex("^(?:(?:支付|付款|交易|收款|转账|退款)(?:已)?(?:失败|取消|关闭|处理中|受理中)|待支付|待付款|未支付|未付款|退款申请中|申请退款)[!！。]?$")
    private val financialValue = Regex("^(?:实际支付|实际付款|实付款|实付金额|实付|实扣金额|实扣|支付金额|付款金额|交易金额|消费金额|转账金额|收款金额|退款金额|退回金额|退还金额|订单金额|商品金额|原支付金额|原价|商品原价|订单原价|总价|优惠金额|已优惠|优惠合计|共优惠|总优惠|配送费|运费|手续费|服务费|提现手续费|实际到账金额|实际到账|到账金额|提现到账金额|转出账户|转出账号|转出银行卡|转入账户|转入账号|收款账户|到账账户|提现账户|转入银行卡|订单编号|商户订单号|商家订单号|支付订单号|交易订单号|${PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS}|订单号|交易单号|交易号|流水号|退款单号|退款编号|商户退款单号|创建时间|交易时间|支付时间|付款时间|收款时间|到账时间|完成时间|退款到账时间|退款成功时间|退款完成时间)")

    data class Result(val facts: String, val fields: List<PaymentReceiptContext>)

    private fun isReceiptBoundary(line: String): Boolean = receiptBoundary.containsMatchIn(line) || identityBoundary.matches(line)

    fun split(text: String, additionalBoundaries: Set<String> = emptySet()): Result {
        val facts = mutableListOf<String>()
        val fields = mutableListOf<PaymentReceiptContext>()
        var activeLabel: String? = null
        val activeLines = mutableListOf<String>()
        fun flush() {
            val label = activeLabel
            if (label != null && activeLines.any(String::isNotBlank)) {
                fields += PaymentReceiptContext(label, activeLines.joinToString("\n").trim())
            }
            activeLabel = null
            activeLines.clear()
        }
        text.lines().forEach { raw ->
            val line = raw.trim()
            val markers = marker.findAll(line).toList()
            val title = heading.matchEntire(line)
            if (markers.isNotEmpty() || title != null) {
                val prefix = markers.firstOrNull()?.let { line.substring(0, it.range.first).trim() }.orEmpty()
                if (prefix.isNotEmpty()) {
                    if (activeLabel != null && !isReceiptBoundary(prefix)) activeLines += prefix
                    else facts += prefix
                }
                flush()
                if (markers.isEmpty()) activeLabel = title!!.groupValues[1] else {
                    markers.forEachIndexed { index, match ->
                        flush()
                        activeLabel = match.groupValues[1]
                        val end = markers.getOrNull(index + 1)?.range?.first ?: line.length
                        val value = line.substring(match.range.last + 1, end).trim().trimEnd('，', ',', '；', ';').trim()
                        // A product field in a wallet notification can share a line with the
                        // following order field. User-authored remarks keep their entire value.
                        val next = if (activeLabel in remarkLabels) null else inlineReceiptBoundary.find(value)?.takeUnless {
                            financialAssertion.containsMatchIn(value.substring(0, it.range.first))
                        }
                        value.substring(0, next?.range?.first ?: value.length).trim()
                            .takeIf(String::isNotEmpty)?.let(activeLines::add)
                        if (next != null) {
                            flush()
                            facts += value.substring(next.range.first).trimStart('，', ',', '；', ';').trim()
                        }
                    }
                }
            } else if (activeLabel != null) {
                // A bare heading owns its first value even when the value resembles an amount.
                // Later explicit receipt rows delimit the section; unrelated labelled fields
                // are not pulled into the model's payment context.
                val protectedBoundary = privateHeading.containsMatchIn(line) || controls.matches(line) || negativeState.matches(line)
                val sourceBoundary = activeLines.isNotEmpty() && additionalBoundaries.any { label ->
                    line.startsWith(label) && line.getOrNull(label.length).let {
                        it == null || it == ':' || it == '：' || it.isWhitespace()
                    }
                }
                val boundary = protectedBoundary || sourceBoundary || ((isReceiptBoundary(line) || otherField.containsMatchIn(line)) &&
                    (activeLines.isNotEmpty() || !financialValue.containsMatchIn(line)))
                if (boundary) {
                    flush()
                    facts += raw
                } else if (line.isNotEmpty()) activeLines += line
            } else facts += raw
        }
        flush()
        return Result(facts.joinToString("\n"), fields)
    }
}

/** Invoked only after the caller has independently validated a payment receipt. */
internal object PaymentReceiptDetailsReader {
    private val merchantLabels = setOf("商户名称", "商户全称", "收款商户", "商家名称", "商户简称", "商户", "商家")
    private val storeLabels = setOf("店铺名称", "门店名称", "商家门店", "店铺", "门店", "分店名称")
    private val brandLabels = setOf("品牌", "品牌名称", "商户品牌")
    private val payeeLabels = setOf("收款方", "收款人", "收款人名称", "付款对象", "付款给")
    private val productLabels = PaymentReceiptSections.productLabels
    private val remarkLabels = PaymentReceiptSections.remarkLabels
    private val methodLabels = setOf("支付方式", "付款方式", "收款方式", "退款方式")
    private val accountLabels = setOf("扣款账户", "付款账户", "支付账户", "付款银行卡", "扣款银行卡")
    private val channelLabels = setOf("支付渠道", "付款渠道")
    private val fromAccountLabels = setOf("转出账户", "转出账号", "付款账户", "扣款账户", "转出银行卡")
    private val toAccountLabels = setOf("转入账户", "转入账号", "收款账户", "到账账户", "提现账户", "转入银行卡", "收款账号")
    private val originalAmountLabels = setOf("原价", "商品原价", "订单原价", "原支付金额", "原付款金额")
    private val discountAmountLabels = setOf("优惠金额", "已优惠", "优惠合计", "共优惠", "总优惠", "优惠", "支付优惠")
    private val feeAmountLabels = setOf("手续费", "服务费", "提现手续费")
    private val receivedAmountLabels = setOf("实际到账金额", "实际到账", "到账金额", "提现到账金额")
    private val auxiliaryMoneyLabels = originalAmountLabels + discountAmountLabels + feeAmountLabels + receivedAmountLabels
    private val allLabels = merchantLabels + storeLabels + brandLabels + payeeLabels + PaymentReceiptSections.labels +
        methodLabels + accountLabels + channelLabels + fromAccountLabels + toAccountLabels + auxiliaryMoneyLabels
    private val channels = listOf("微信支付", "支付宝", "云闪付", "抖音支付", "美团支付", "京东支付", "数字人民币", "微信")
    private val balanceAccounts = setOf("微信零钱", "微信钱包", "支付宝余额", "微信经营账户", "零钱", "零钱通", "余额", "余额宝", "花呗", "抖音月付", "美团月付", "京东白条", "银行卡", "信用卡", "储蓄卡")
    private val financialLabels = setOf("原价", "实付金额", "实付", "金额", "支付金额", "付款金额", "交易金额", "优惠金额",
        "订单号", "订单编号", "交易号", "交易单号", "支付订单号", "商户订单号", "商家订单号") +
        PaymentTextParser.WECHAT_PAYMENT_ORDER_LABELS.split('|')
    private val nextField = Regex("(?:^|[\\s，,；;])(?:(?:${allLabels.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }})\\s*[:：]|(?:${financialLabels.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }})\\s*[:：]?)")

    data class Result(val details: PaymentReceiptDetails, val ambiguousFields: Set<String>, val merchantFromStore: Boolean = false) {
        val ambiguousParty get() = "merchantName" in ambiguousFields || "payeeName" in ambiguousFields || "storeName" in ambiguousFields
    }

    fun read(packageName: String, text: String, structure: PaymentScreenStructure?, source: String): Result {
        val sections = PaymentReceiptSections.split(text)
        val facts = sections.facts
        val rows = structure?.labelledTextFields(allLabels).orEmpty()
        val sources = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        fun field(key: String, labels: Set<String>, semantic: Boolean = false): String? {
            val matchingRows = rows.filterKeys(labels::contains)
            val rowValues = matchingRows.values.flatten().distinct()
            val candidates = if (matchingRows.isNotEmpty()) rowValues else if (semantic)
                sections.fields.filter { it.label in labels }.map { it.value }.distinct()
                else labelledValues(facts, labels)
            // A row's hierarchy is stronger than flattened traversal order. Conflicting rows
            // do not become a guessed merchant/account.
            if (!semantic && (candidates.size > 1 || matchingRows.isNotEmpty() && candidates.isEmpty())) {
                ambiguous += key
                return null
            }
            val value = if (semantic) candidates.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: return null
                else candidates.singleOrNull() ?: return null
            val limit = if (semantic) 300 else 120
            if (!semantic && (value.length > limit || value in allLabels ||
                    value in setOf("完成", "返回", "关闭", "支付成功", "付款成功") || value.all(Char::isDigit))) return null
            sources[key] = "$source·${if (rowValues.isNotEmpty()) "同一字段行" else "明确标签"}"
            return value.take(limit)
        }
        var merchant = field("merchantName", merchantLabels)
        val store = field("storeName", storeLabels)
        val merchantFromStore = merchant == null && "merchantName" !in ambiguous && store != null
        if (merchantFromStore) {
            merchant = store
            sources["merchantName"] = sources.getValue("storeName")
        }
        sources.remove("storeName")
        var payee = field("payeeName", payeeLabels)
        if (merchant == null && "merchantName" !in ambiguous && "storeName" !in ambiguous) structure?.namedReceiptField(setOf("merchant_name", "shop_name", "store_name"))?.let {
            merchant = it
            sources["merchantName"] = "$source·商户节点标识"
        }
        if (payee == null && "payeeName" !in ambiguous) structure?.namedReceiptField(setOf("payee_name", "receiver_name"))?.let {
            payee = it
            sources["payeeName"] = "$source·收款方节点标识"
        }
        val product = field("productDescription", productLabels, semantic = true)
        val remark = field("remark", remarkLabels, semantic = true)
        val method = field("paymentMethod", methodLabels)
        val explicitChannel = field("paymentChannel", channelLabels)
        val channel = channel(explicitChannel) ?: channel(method) ?: when (packageName) {
            "com.tencent.mm" -> "微信支付"
            "com.eg.android.AlipayGphone" -> "支付宝"
            "com.unionpay" -> "云闪付"
            else -> null
        }
        if (channel != null) sources["paymentChannel"] = when {
            channel(explicitChannel) != null -> sources.getValue("paymentChannel")
            channel(method) != null -> sources.getValue("paymentMethod")
            else -> "应用渠道"
        }
        val account = field("fundingAccount", accountLabels)?.takeUnless { channel(it) != null }
            ?: method?.takeIf { "fundingAccount" !in ambiguous && isFundingAccount(it) }?.also {
            sources["fundingAccount"] = sources.getValue("paymentMethod")
        }
        sources.remove("paymentMethod")
        if (channel == null) sources.remove("paymentChannel")
        if (account == null) sources.remove("fundingAccount")
        val context = mutableListOf<PaymentReceiptContext>()
        for (label in brandLabels + storeLabels + PaymentReceiptSections.labels) {
            if (label in storeLabels && "storeName" in ambiguous) continue
            val rowValues = rows[label]
            val values = rowValues ?: if (label in PaymentReceiptSections.labels)
                sections.fields.filter { it.label == label }.map { it.value }
                else labelledValues(facts, setOf(label))
            val origin = "$source·${if (rowValues != null) "同一字段行" else "明确标签"}"
            values.forEach { context += PaymentReceiptContext(label, it, origin) }
        }
        fun moneyField(key: String, labels: Set<String>): Long? {
            val matching = rows.filterKeys(labels::contains)
            val raw = if (matching.isNotEmpty()) matching.values.flatten().distinct() else labelledValues(facts, labels)
            if (raw.isEmpty()) return null
            val amounts = raw.map { value ->
                val plain = value.trim().removePrefix("人民币").trim().removePrefix("¥").removePrefix("￥").trim()
                    .removeSuffix("元").trim()
                if (plain.matches(Regex("0(?:\\.0{1,2})?"))) 0L else MoneyUtil.parsePositiveYuanToCents(plain)
            }
            val amount = amounts.distinct().singleOrNull()
            if (amount == null || amounts.any { it == null }) {
                ambiguous += key
                return null
            }
            sources[key] = "$source·${if (matching.isNotEmpty()) "同一字段行" else "明确标签"}"
            return amount
        }
        val originalAmount = moneyField("originalAmountCents", originalAmountLabels)
        val discountAmount = moneyField("discountAmountCents", discountAmountLabels)
        val feeAmount = moneyField("feeAmountCents", feeAmountLabels)
        val receivedAmount = moneyField("receivedAmountCents", receivedAmountLabels)
        val transferFrom = field("transferFromAccount", fromAccountLabels)
        val transferTo = field("transferToAccount", toAccountLabels)
        return Result(PaymentReceiptDetails(merchant, payee, product, remark, channel, account, sources,
            PaymentReceiptDetails.normalizeContext(context), originalAmount, discountAmount, feeAmount, receivedAmount,
            transferFrom, transferTo), ambiguous, merchantFromStore)
    }

    private fun channel(value: String?): String? = channels.firstOrNull { it == value }
        ?.let { if (it == "微信") "微信支付" else it }

    private fun isFundingAccount(value: String): Boolean = value in balanceAccounts ||
        Regex("银行|信用卡|储蓄卡|借记卡").containsMatchIn(value)

    private fun labelledValues(text: String, labels: Set<String>): List<String> {
        val names = labels.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
        val inline = Regex("(?:^|[\\s，,；;])(?:$names)\\s*[:：]\\s*")
        val lines = text.lines().map(String::trim)
        return lines.flatMapIndexed { index, line ->
            val starts = inline.findAll(line).map { it.range.last + 1 }.toList()
            val values = if (starts.isEmpty() && line.trimEnd(':', '：').trim() in labels) listOf("")
                else starts.map(line::substring)
            values.mapNotNull { value ->
                value.takeIf(String::isNotBlank) ?: lines.getOrNull(index + 1)?.takeIf {
                    it.isNotBlank() && it !in allLabels && nextField.find(it)?.range?.first != 0
                }
            }.map {
                val boundary = nextField.find(it)
                it.substring(0, boundary?.range?.first ?: it.length).trim().trimEnd('，', ',', '；', ';').trim()
            }
        }.filter(String::isNotBlank).distinct()
    }
}

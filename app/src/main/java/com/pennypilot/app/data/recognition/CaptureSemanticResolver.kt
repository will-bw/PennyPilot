package com.pennypilot.app.data.recognition

import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentTextParser
import kotlinx.serialization.Serializable

@Serializable
data class CaptureSemanticCategory(val id: Long, val name: String, val direction: String)

@Serializable
data class CaptureSemanticHistory(
    val merchant: String,
    val direction: String,
    val businessKind: String,
    val categoryId: Long,
    val accountTag: String? = null,
    val note: String? = null
)

@Serializable
data class CaptureSemanticPreference(
    val merchant: String,
    val direction: String,
    val categoryId: Long? = null,
    val businessKind: String? = null,
    val accountTag: String? = null
)

@Serializable
data class CaptureSemanticReceipt(
    val details: Map<String, String> = emptyMap(),
    val context: List<PaymentReceiptContext> = emptyList(),
    val occurredAt: String? = null,
    val scene: String? = null
) {
    fun bounded(): CaptureSemanticReceipt {
        val fields = PaymentReceiptDetails.TEXT_FIELDS + PaymentReceiptDetails.MONEY_FIELDS
        return copy(
            details = details.filterKeys(fields::contains).mapNotNull { (key, value) ->
                val limit = if (key in setOf("productDescription", "remark")) 300 else 120
                val bounded = if (key in PaymentReceiptDetails.MONEY_FIELDS)
                    value.toLongOrNull()?.takeIf { it >= 0 }?.toString()
                else semanticText(value).trim().take(limit).takeIf(String::isNotBlank)
                bounded?.let { key to it }
            }.toMap(),
            context = PaymentReceiptDetails.normalizeContext(context.take(16).map { field ->
                // Include the label so a separately captured order identifier is redacted too.
                val value = RecognitionRedactor.redact("${field.label}：${field.value}", null)
                    .text.substringAfter('：', "")
                field.copy(label = semanticText(field.label), value = semanticText(value),
                    source = semanticText(field.source).take(80))
            }),
            occurredAt = occurredAt?.take(80), scene = scene?.take(80)
        )
    }

    private fun semanticText(value: String): String = RecognitionRedactor.redact(value, null).text
        .replace(Regex("(?<![0-9０-９])(?:[0-9０-９][ \\-]?){7,}[0-9０-９](?![0-9０-９])"), "[号码已隐去]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[邮箱已隐去]")
}

@Serializable
data class CaptureSemanticContext(
    val deviceTime: String = "",
    val allowedCategories: List<CaptureSemanticCategory> = emptyList(),
    val relatedHistory: List<CaptureSemanticHistory> = emptyList(),
    val preferences: List<CaptureSemanticPreference> = emptyList(),
    val receipt: CaptureSemanticReceipt? = null
) {
    /** Context is selected locally; never send a full ledger to classify one payment. */
    fun bounded() = copy(
        deviceTime = deviceTime.take(80),
        allowedCategories = allowedCategories.take(100).map { it.copy(name = it.name.take(80)) },
        relatedHistory = relatedHistory.take(12).map {
            it.copy(merchant = it.merchant.take(120), accountTag = it.accountTag?.take(100), note = it.note?.take(120))
        },
        preferences = preferences.take(12).map {
            it.copy(merchant = it.merchant.take(120), accountTag = it.accountTag?.take(100))
        },
        receipt = receipt?.bounded()
    )
}

data class CaptureSemanticResolution(
    val businessKind: String,
    val categoryId: Long?,
    val accountTag: String?,
    val note: String?,
    val assumptions: List<String>,
    val autoEligible: Boolean = true,
    val categoryIsFallback: Boolean = false
)

/** Classifies already verified money movements; never supplies amounts or payment state. */
object CaptureSemanticResolver {
    val businessKinds = setOf("expense", "income", "transfer", "receivable", "payable", "refund")

    internal fun isFallbackCategory(name: String) = name.trim() in
        setOf("其他", "其他收入", "其他支出", "其它", "其它收入", "其它支出")
    private val peerTransferScenes = setOf("transfer_sent", "transfer_received")
    private val completedTransfer = Regex("转账(?:已)?(?:成功|完成|到账)|已转账|(?:已收到|收到)[^\\n，。；]{0,40}转账")
    private val genericCounterparties = setOf("微信", "微信支付", "支付宝", "支付宝支付", "云闪付", "银行", "银行卡",
        "本人", "自己", "对方", "收款方", "付款方", "商户", "未知", "转账成功", "支付成功", "付款成功")

    fun compatible(kind: String?, direction: String): Boolean = kind in businessKinds && when (kind) {
        "expense", "receivable" -> direction == "EXPENSE"
        "income", "payable", "refund" -> direction == "INCOME"
        else -> true
    }

    fun resolve(
        parsed: NotificationParseResult,
        packageName: String,
        text: String,
        context: CaptureSemanticContext = CaptureSemanticContext(),
        proposal: PaymentProposal? = null
    ): CaptureSemanticResolution {
        val bounded = context.bounded()
        val merchant = parsed.merchant.orEmpty().trim()
        val history = bounded.relatedHistory.filter {
            merchant.isNotEmpty() && it.merchant.trim() == merchant && it.direction == parsed.direction
        }
        val preference = bounded.preferences.firstOrNull {
            merchant.isNotEmpty() && it.merchant.trim() == merchant && it.direction == parsed.direction
        }
        val assumptions = mutableListOf<String>()
        val description = (listOfNotNull(merchant, text, parsed.details.merchantName, parsed.details.payeeName,
            parsed.details.productDescription, parsed.details.remark) +
            parsed.details.context.map { "${it.label}：${it.value}" } +
            bounded.receipt?.details.orEmpty().values +
            bounded.receipt?.context.orEmpty().map { "${it.label}：${it.value}" }).joinToString("\n")
        val current = currentMeaning(parsed, description)
        val facts = PaymentTextParser.receiptFacts(text)
        val scenes = setOfNotNull(parsed.scene, bounded.receipt?.scene)
        val explicit = explicitMeaning(parsed, facts, scenes)
        val isPeerTransfer = scenes.any(peerTransferScenes::contains) || completedTransfer.containsMatchIn(facts)
        val payees = listOfNotNull(parsed.details.payeeName, bounded.receipt?.details?.get("payeeName")).map(String::trim)
        val parties = listOf(merchant) + bounded.receipt?.context.orEmpty()
            .filter { it.label == "交易对方" }.map { it.value.trim() }
        // On an incoming receipt the displayed payee can be the account holder, not the sender.
        val counterparties = if (parsed.direction == "EXPENSE") parties + payees else parties.filterNot(payees::contains)
        val hasCounterparty = counterparties.any { it.trim().let { name -> name.isNotEmpty() && name !in genericCounterparties } }
        val stableKind = history.map { it.businessKind }.distinct().singleOrNull()
            ?.takeIf { history.size >= 2 && it != "transfer" && compatible(it, parsed.direction) }
        // The former model prompt used transfer as its default. Neither a model suggestion nor
        // its copy in the parsed result establishes a transfer between the user's own accounts.
        val modelKind = proposal?.businessKind?.takeIf { it != "transfer" && compatible(it, parsed.direction) }
        val kind = when {
            explicit?.first == "refund" -> {
                assumptions += explicit.second
                explicit.first
            }
            compatible(preference?.businessKind, parsed.direction) -> {
                assumptions += "用途沿用你为“$merchant”设置的偏好"
                preference!!.businessKind!!
            }
            explicit != null -> {
                assumptions += explicit.second
                explicit.first
            }
            current?.first == "transfer" -> {
                assumptions += current.second
                current.first
            }
            modelKind != null -> {
                assumptions += "用途结合本次支付场景推断"
                modelKind
            }
            current != null -> {
                assumptions += current.second
                current.first
            }
            stableKind != null -> {
                assumptions += "用途沿用“$merchant”最近 ${history.size} 笔同方向已确认记录"
                stableKind
            }
            isPeerTransfer && hasCounterparty -> {
                assumptions += "已识别交易对方，按本人的资金方向记录转账"
                if (parsed.direction == "EXPENSE") "expense" else "income"
            }
            parsed.businessKind != "transfer" && compatible(parsed.businessKind, parsed.direction) -> parsed.businessKind
            else -> {
                assumptions += "已保留金额与资金方向，交易用途待确认"
                "unknown"
            }
        }
        val applicableProposal = proposal?.takeUnless { it.businessKind == "transfer" && kind != "transfer" }
        val categories = bounded.allowedCategories.filter { it.direction == parsed.direction }
        fun allowed(id: Long?) = id?.takeIf { candidate -> categories.any { it.id == candidate } }
        val preferredCategory = allowed(preference?.categoryId)
        val fallbackIds = categories.filter { isFallbackCategory(it.name) }.map { it.id }.toSet()
        val modelCategory = allowed(applicableProposal?.categoryId)?.takeUnless(fallbackIds::contains)
        val historyCategory = history.map { it.categoryId }.distinct().singleOrNull()
            ?.takeIf { history.size >= 2 && it !in fallbackIds }?.let(::allowed)
        val inferredCategory = categories.firstOrNull { categoryMatches(it.name, description) }?.id
        val matchedCategory = preferredCategory ?: modelCategory ?: inferredCategory ?: historyCategory
        val categoryId = matchedCategory ?: categories.firstOrNull { it.id in fallbackIds }?.id
            ?: categories.firstOrNull()?.id
        when {
            preferredCategory != null -> assumptions += "分类沿用你为“$merchant”设置的偏好"
            modelCategory != null -> assumptions += "分类结合支付内容推荐"
            inferredCategory != null -> assumptions += "分类按商户和支付内容推断"
            historyCategory != null -> assumptions += "分类沿用“$merchant”的稳定记账习惯"
            categoryId != null -> assumptions += "分类暂用“${categories.first { it.id == categoryId }.name}”"
        }
        val historyAccount = history.mapNotNull { it.accountTag?.takeIf(String::isNotBlank) }
            .distinct().singleOrNull()?.takeIf { history.size >= 2 }
        val receiptAccount = (if (parsed.direction == "EXPENSE") parsed.details.transferFromAccount
            else parsed.details.transferToAccount)?.takeIf(String::isNotBlank)
            ?: parsed.details.fundingAccount?.takeIf(String::isNotBlank)
        val account = receiptAccount ?: parsed.paymentMethod?.takeIf(String::isNotBlank)
            ?: preference?.accountTag?.takeIf(String::isNotBlank)?.take(100)
            ?: applicableProposal?.accountTag?.takeIf(String::isNotBlank)?.take(100)
            ?: historyAccount
            ?: when (packageName) {
                "com.tencent.mm" -> "微信"
                "com.eg.android.AlipayGphone" -> "支付宝"
                else -> null
            }
        if (receiptAccount == null && parsed.paymentMethod.isNullOrBlank() && account != null) {
            assumptions += "支付账户按本次渠道或已有习惯推断为“$account”"
        }
        assumptions += applicableProposal?.assumptions.orEmpty().take(8).map { it.take(200) }
        val note = receiptNote(
            parsed.details.productDescription ?: bounded.receipt?.details?.get("productDescription"),
            parsed.details.remark ?: bounded.receipt?.details?.get("remark"), applicableProposal?.note)
        return CaptureSemanticResolution(kind, categoryId, account, note, assumptions.distinct().take(12),
            autoEligible = kind != "unknown", categoryIsFallback = matchedCategory == null)
    }

    internal fun receiptNote(productDescription: String?, remark: String?, summary: String? = null): String? {
        val main = summary?.trim()?.takeIf(String::isNotBlank)
        fun comparable(value: String) = value.replace(Regex("[\\s\\p{P}]+"), "")
        val covered = main?.let(::comparable).orEmpty()
        val details = listOfNotNull(
            productDescription?.takeIf(String::isNotBlank)?.takeUnless { comparable(it) in covered }?.let { "商品：$it" },
            remark?.takeIf(String::isNotBlank)?.takeUnless { comparable(it) in covered }?.let { "备注：$it" }
        ).joinToString("；")
        if (main == null) return details.take(500).takeIf(String::isNotBlank)
        if (details.isEmpty()) return main.take(500)
        val detailBudget = minOf(details.length, 250)
        return main.take(499 - detailBudget) + "；" + details.take(detailBudget)
    }

    private fun explicitMeaning(parsed: NotificationParseResult, facts: String, scenes: Set<String>): Pair<String, String>? {
        return when {
            parsed.direction == "INCOME" && (parsed.businessKind == "refund" || scenes.any { it in setOf("refund_received", "red_packet_returned") } ||
                Regex("(?:退款|退回|退还)(?:已)?(?:成功|到账|完成)|款项已退回|已原路退回").containsMatchIn(facts)) ->
                "refund" to "退款到账用于冲减支出"
            "account_transfer" in scenes || PaymentTextParser.isAccountMovement(facts) ->
                "transfer" to "已识别账户调拨、储值或还款，按资金转移记录"
            parsed.direction == "EXPENSE" && "red_packet_sent" in scenes -> "expense" to "发出红包按人情支出记录"
            parsed.direction == "INCOME" && "red_packet_received" in scenes -> "income" to "收到红包按收入记录"
            else -> null
        }
    }

    private fun currentMeaning(parsed: NotificationParseResult, text: String): Pair<String, String>? {
        val incoming = parsed.direction == "INCOME"
        fun meaning(kind: String, explanation: String) = kind to explanation
        return when {
            incoming && (parsed.businessKind == "refund" || parsed.scene == "refund_received" || Regex("退款|退回|退还").containsMatchIn(text)) ->
                meaning("refund", "退款到账用于冲减支出")
            !incoming && Regex("借给|借出|代付|垫付").containsMatchIn(text) ->
                meaning("receivable", "借出或垫付款按应收记录，不计消费")
            incoming && Regex("借入|借到|借款到账|贷款到账|放款").containsMatchIn(text) ->
                meaning("payable", "借入资金按应付记录，不计收入")
            incoming && Regex("报销|垫付款返还|还我|还给我").containsMatchIn(text) ->
                meaning("transfer", "报销或垫付款回收按资金转移记录，不计新增收入")
            incoming && Regex("工资|薪资|薪酬|工资代发|奖金").containsMatchIn(text) ->
                meaning("income", "工资或奖金到账按收入记录")
            parsed.scene == "red_packet_sent" || !incoming && Regex("红包|份子|礼金").containsMatchIn(text) ->
                meaning("expense", "发出红包或礼金按人情支出记录")
            parsed.scene == "red_packet_received" || incoming && Regex("红包|礼金").containsMatchIn(text) ->
                meaning("income", "收到红包或礼金按收入记录")
            !incoming && categoryKeywords.filterKeys { it !in setOf("工资", "红包", "人情") }
                .values.any { it.containsMatchIn(text) } -> meaning("expense", "商户与支付内容表明是日常消费")
            parsed.scene == "merchant_payment" -> meaning("expense", "商户支付按消费记录")
            parsed.scene == "qr_payment_received" -> meaning("income", "收款码到账按收入记录")
            else -> null
        }
    }

    private val categoryKeywords = mapOf(
        "餐饮" to Regex("餐|饭|盖饭|小吃|外卖|奶茶|咖啡|面馆|火锅|烧烤|麦当劳|肯德基|星巴克|瑞幸|饿了么|夜宵|烘焙|茶饮"),
        "交通" to Regex("打车|滴滴|地铁|公交|加油|停车|高铁|火车|机票|航空|出租|骑行|哈啰|青桔|通行费"),
        "购物" to Regex("超市|商城|淘宝|京东|拼多多|商场|便利店|旗舰店|购物|百货"),
        "居住" to Regex("房租|物业|电费|水费|燃气|供暖|租金"),
        "娱乐" to Regex("电影|游戏|演出|门票|KTV|会员|视频|音乐|剧本杀"),
        "医疗" to Regex("医院|药房|药店|诊所|挂号|体检|医药"),
        "教育" to Regex("书店|课程|培训|学费|考试|文具"),
        "人情" to Regex("红包|份子|礼金"),
        "红包" to Regex("红包|礼金"),
        "工资" to Regex("工资|薪资|薪酬|代发|奖金")
    )

    private fun categoryMatches(name: String, text: String): Boolean {
        val key = when (val normalized = name.trim()) {
            "餐饮美食", "餐饮食品", "饮食" -> "餐饮"
            "交通出行", "出行交通" -> "交通"
            "日用百货", "购物消费", "日常购物" -> "购物"
            "住房缴费", "住房物业" -> "居住"
            "休闲娱乐", "文娱休闲" -> "娱乐"
            "医疗健康", "健康医疗" -> "医疗"
            "教育学习", "学习教育" -> "教育"
            "人情往来", "人情礼金" -> "人情"
            "红包收入" -> "红包"
            "工资收入", "工资薪酬" -> "工资"
            else -> normalized
        }
        return categoryKeywords[key]?.containsMatchIn(text) == true
    }
}

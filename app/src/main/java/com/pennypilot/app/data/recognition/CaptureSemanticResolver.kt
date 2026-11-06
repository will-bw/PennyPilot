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
        val fields = setOf("merchantName", "payeeName", "productDescription", "remark", "paymentChannel", "fundingAccount")
        return copy(
            details = details.filterKeys(fields::contains).mapNotNull { (key, value) ->
                val limit = if (key in setOf("productDescription", "remark")) 300 else 120
                semanticText(value).trim().take(limit).takeIf(String::isNotBlank)?.let { key to it }
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
    val autoEligible: Boolean = true
)

/** Classifies already verified money movements; never supplies amounts or payment state. */
object CaptureSemanticResolver {
    val businessKinds = setOf("expense", "income", "transfer", "receivable", "payable", "refund")

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
        val explicit = explicitMeaning(parsed, text, bounded.receipt?.scene)
        val stableKind = history.map { it.businessKind }.distinct().singleOrNull()
            ?.takeIf { history.size >= 2 && compatible(it, parsed.direction) }
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
            compatible(proposal?.businessKind, parsed.direction) -> {
                assumptions += "用途结合本次支付场景推断"
                proposal!!.businessKind
            }
            current != null -> {
                assumptions += current.second
                current.first
            }
            stableKind != null -> {
                assumptions += "用途沿用“$merchant”最近 ${history.size} 笔同方向已确认记录"
                stableKind
            }
            compatible(parsed.businessKind, parsed.direction) -> parsed.businessKind
            else -> {
                assumptions += "已记录资金变动；用途暂按资金转移，不计入消费或收入"
                "transfer"
            }
        }
        val categories = bounded.allowedCategories.filter { it.direction == parsed.direction }
        fun allowed(id: Long?) = id?.takeIf { candidate -> categories.any { it.id == candidate } }
        val preferredCategory = allowed(preference?.categoryId)
        val modelCategory = allowed(proposal?.categoryId)
        val historyCategory = history.map { it.categoryId }.distinct().singleOrNull()
            ?.takeIf { history.size >= 2 }?.let(::allowed)
        val inferredCategory = categories.firstOrNull { categoryMatches(it.name, description) }?.id
        val categoryId = preferredCategory ?: modelCategory ?: historyCategory ?: inferredCategory
            ?: categories.firstOrNull { it.name == "其他" || it.name == "其他收入" }?.id
            ?: categories.firstOrNull()?.id
        when {
            preferredCategory != null -> assumptions += "分类沿用你为“$merchant”设置的偏好"
            modelCategory != null -> assumptions += "分类结合支付内容推荐"
            historyCategory != null -> assumptions += "分类沿用“$merchant”的稳定记账习惯"
            inferredCategory != null -> assumptions += "分类按商户和支付内容推断"
            categoryId != null -> assumptions += "分类暂用“${categories.first { it.id == categoryId }.name}”"
        }
        val historyAccount = history.mapNotNull { it.accountTag?.takeIf(String::isNotBlank) }
            .distinct().singleOrNull()?.takeIf { history.size >= 2 }
        val receiptAccount = parsed.details.fundingAccount?.takeIf(String::isNotBlank)
        val account = receiptAccount ?: parsed.paymentMethod?.takeIf(String::isNotBlank)
            ?: preference?.accountTag?.takeIf(String::isNotBlank)?.take(100)
            ?: proposal?.accountTag?.takeIf(String::isNotBlank)?.take(100)
            ?: historyAccount
            ?: when (packageName) {
                "com.tencent.mm" -> "微信"
                "com.eg.android.AlipayGphone" -> "支付宝"
                else -> null
            }
        if (receiptAccount == null && parsed.paymentMethod.isNullOrBlank() && account != null) {
            assumptions += "支付账户按本次渠道或已有习惯推断为“$account”"
        }
        assumptions += proposal?.assumptions.orEmpty().take(8).map { it.take(200) }
        val note = receiptNote(
            parsed.details.productDescription ?: bounded.receipt?.details?.get("productDescription"),
            parsed.details.remark ?: bounded.receipt?.details?.get("remark"), proposal?.note)
            ?: if (kind == "transfer" && current == null && stableKind == null &&
                !compatible(preference?.businessKind, parsed.direction) &&
                !compatible(proposal?.businessKind, parsed.direction)) "用途暂按资金转移记录" else null
        return CaptureSemanticResolution(kind, categoryId, account, note, assumptions.distinct().take(12))
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

    private fun explicitMeaning(parsed: NotificationParseResult, text: String, capturedScene: String?): Pair<String, String>? {
        val facts = PaymentTextParser.receiptFacts(text)
        val scenes = setOfNotNull(parsed.scene, capturedScene)
        return when {
            parsed.direction == "INCOME" && (parsed.businessKind == "refund" || scenes.any { it in setOf("refund_received", "red_packet_returned") } ||
                Regex("(?:退款|退回|退还)(?:已)?(?:成功|到账|完成)|款项已退回|已原路退回").containsMatchIn(facts)) ->
                "refund" to "退款到账用于冲减支出"
            "account_transfer" in scenes -> "transfer" to "已识别本人账户调拨，按资金转移记录"
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
            Regex("本人(?:账户|银行卡|互转)|自己(?:的)?(?:账户|银行卡|微信|支付宝)|转给自己|账户互转|银行卡转入|零钱充值|余额充值|余额宝|零钱通|提现|信用卡还款|还信用卡|账单还款|还款").containsMatchIn(text) ->
                meaning("transfer", "本人账户调拨或还款按资金转移记录，不计消费")
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
            !incoming && Regex("充值").containsMatchIn(text) && !Regex("话费|流量|游戏").containsMatchIn(text) ->
                meaning("transfer", "储值充值先按资金转移记录，避免重复计算消费")
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

    private fun categoryMatches(name: String, text: String) = categoryKeywords[name]?.containsMatchIn(text) == true
}

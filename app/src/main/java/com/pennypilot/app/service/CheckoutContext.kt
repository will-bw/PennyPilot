package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentReceiptSections
import com.pennypilot.app.data.parser.PaymentScreenStructure
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.util.MoneyUtil

/** Short-lived, local-only description from a read-only order preview. It cannot create a receipt. */
internal class CheckoutContext {
    data class Preview(val packageName: String, val merchantOrderId: String, val amountCents: Long,
        val merchant: String?, val fields: List<PaymentReceiptContext>)
    data class LiveNotification(val wallAt: Long, val elapsedAt: Long)
    data class NotificationEnrichment(val receipt: NotificationParseResult, val authorization: Long)

    private data class WalletHandoff(val packageName: String, val at: Long, val wallAt: Long?)
    private data class Pending(val preview: Preview, val revision: Long, val seenAt: Long,
        val ambiguous: Boolean = false, val handoff: WalletHandoff? = null)
    private var pending: Pending? = null
    private var authorization = 0L

    /** The service owns synchronization and expiry; redraws cannot extend the same preview's lifetime. */
    fun remember(preview: Preview, revision: Long, now: Long): Boolean {
        expire(revision, now)
        val previous = pending
        if (previous != null && previous.preview.packageName == preview.packageName &&
            previous.preview.merchantOrderId == preview.merchantOrderId) {
            val conflicts = previous.preview.amountCents != preview.amountCents ||
                merchantConflict(previous.preview.merchant, preview.merchant)
            pending = if (conflicts || previous.ambiguous) previous.copy(ambiguous = true)
                else previous.copy(preview = preview.copy(merchant = preview.merchant ?: previous.preview.merchant))
            return false
        }
        pending = Pending(preview, revision, now)
        return true
    }

    fun reset() { pending = null }

    @Synchronized fun clearAuthorization() {
        reset()
        authorization++
    }

    @Synchronized fun remainsAuthorized(value: Long): Boolean = authorization == value

    fun onWindowChanged(packageName: String, revision: Long, now: Long, wallAt: Long? = null) {
        expire(revision, now)
        val cached = pending ?: return
        val handoff = cached.handoff
        if (handoff != null) {
            if (packageName != handoff.packageName) reset()
            return
        }
        if (packageName == cached.preview.packageName) return
        // Bind one directly observed shopping-to-wallet transition. Any other observed
        // package boundary invalidates it. No extra packages are monitored for checkout.
        if (!cached.ambiguous && cached.preview.merchant != null && packageName in wallets &&
            now - cached.seenAt in 0 until HANDOFF_MS) {
            pending = cached.copy(handoff = WalletHandoff(packageName, now, wallAt))
        } else reset()
    }

    fun expire(revision: Long, now: Long) {
        val cached = pending ?: return
        if (cached.revision != revision || now - cached.seenAt !in 0 until TTL_MS ||
            cached.handoff?.let { now - it.at !in 0 until HANDOFF_MS } == true) reset()
    }

    fun enrich(packageName: String, structure: PaymentScreenStructure?, receipt: NotificationParseResult,
        revision: Long, now: Long): NotificationParseResult {
        expire(revision, now)
        val cached = pending?.takeUnless { it.ambiguous } ?: return receipt
        val preview = cached.preview
        val expectedPackage = cached.handoff?.packageName ?: preview.packageName
        if (expectedPackage != packageName || !matchesPayment(receipt, preview) || structure == null) return receipt
        val receiptFields = fields(structure, identityLabels + merchantLabels)
        if (merchantOrderId(receiptFields) != preview.merchantOrderId) return receipt
        val explicitMerchants = merchants(receiptFields)
        if (explicitMerchants.size > 1) {
            reset()
            return receipt
        }
        // The wallet may render the merchant later. Never bind until it is explicitly visible.
        if (cached.handoff != null && explicitMerchants.isEmpty()) return receipt
        val merchant = explicitMerchants.singleOrNull() ?: merchantName(receipt.details.merchantName ?: receipt.merchant)
        if (merchantConflict(merchant, preview.merchant)) {
            reset()
            return receipt
        }
        return attach(preview, receipt)
    }

    /** A bounded OCR receipt supplies its independently validated merchant and typed order field. */
    @Synchronized fun enrichOpaqueMerchant(packageName: String, receipt: NotificationParseResult,
        merchantOrderId: String, merchant: String, revision: Long, now: Long): NotificationParseResult? {
        expire(revision, now)
        return matchWallet(packageName, receipt, merchantOrderId, merchant)
    }

    /** Historical notification recovery has no LiveNotification and never reaches this matcher. */
    @Synchronized fun enrichNotification(input: CapturedNotification, receipt: NotificationParseResult,
        arrival: LiveNotification, revision: Long, now: Long): NotificationEnrichment? {
        expire(revision, now)
        val handoff = pending?.handoff ?: return null
        val beganAt = handoff.wallAt ?: return null
        if (input.packageName != handoff.packageName || arrival.elapsedAt !in handoff.at..now ||
            input.postedAt !in beganAt..arrival.wallAt) return null
        // The wall timestamp must also project after handoff on the arrival's monotonic timeline.
        val delay = arrival.wallAt - input.postedAt
        if (delay !in 0 until HANDOFF_MS || arrival.elapsedAt - delay < handoff.at) return null
        val text = PaymentTextParser.receiptFacts(input.content)
        val fields = labelledFields(text.split(Regex("[\\r\\n，,；;]")), identityLabels + merchantLabels)
        val id = merchantOrderId(fields) ?: return null
        val merchant = merchants(fields).singleOrNull() ?: return null
        val enriched = matchWallet(input.packageName, receipt, id, merchant) ?: return null
        return NotificationEnrichment(enriched, authorization)
    }

    private fun matchesPayment(receipt: NotificationParseResult, preview: Preview): Boolean =
        receipt.direction == "EXPENSE" && receipt.scene == "merchant_payment" &&
            receipt.amountCents == preview.amountCents && receipt.orderId == preview.merchantOrderId

    private fun matchWallet(packageName: String, receipt: NotificationParseResult, merchantOrderId: String,
        merchant: String): NotificationParseResult? {
        val cached = pending?.takeUnless { it.ambiguous } ?: return null
        val preview = cached.preview
        if (cached.handoff?.packageName != packageName || !matchesPayment(receipt, preview) ||
            CaptureEventIdentity.reliableOrderId(merchantOrderId) != preview.merchantOrderId) return null
        val explicitMerchant = merchantName(merchant) ?: return null
        if (preview.merchant != explicitMerchant ||
            merchantConflict(explicitMerchant, merchantName(receipt.details.merchantName ?: receipt.merchant))) {
            reset()
            return null
        }
        return attach(preview, receipt)
    }

    private fun attach(preview: Preview, receipt: NotificationParseResult): NotificationParseResult {
        reset()
        val details = receipt.details
        val labels = details.context.mapTo(mutableSetOf()) { it.label }
        if (!details.productDescription.isNullOrBlank()) labels += PaymentReceiptSections.productLabels
        if (!details.remark.isNullOrBlank()) labels += remarkLabels
        val missing = preview.fields.filterNot { it.label in labels }
        return receipt.copy(details = details.copy(context = PaymentReceiptDetails.normalizeContext(details.context + missing)))
    }

    companion object {
        const val TTL_MS = 120_000L
        private const val HANDOFF_MS = 30_000L
        const val LOCAL_SOURCE = "前置订单（本机）"
        private val packages = setOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew",
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite")
        private val wallets = setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay")
        private val platformNames = PaymentAppRegistry.packages.map(PaymentAppRegistry::displayName).toSet() +
            setOf("微信支付", "支付宝支付", "银联云闪付", "美团支付", "抖音支付", "收单机构")
        private val titles = setOf("确认订单", "核对订单", "订单确认", "待支付订单", "订单待支付")
        private val actions = setOf("提交订单", "去支付", "确认支付", "立即支付")
        private val merchantOrderLabels = setOf("商户订单号", "商家订单号", "商户单号")
        private val identityLabels = merchantOrderLabels + setOf("订单号", "订单编号", "支付订单号", "交易订单号", "交易单号", "交易号", "流水号")
        private val amountLabels = setOf("应付金额", "待付金额", "待支付金额")
        private val merchantLabels = setOf("商户名称", "商家名称", "商户", "商家", "店铺名称", "门店名称")
        private val remarkLabels = setOf("订单备注", "下单备注", "商品备注", "商家备注", "备注")
        private val descriptionLabels = PaymentReceiptSections.productLabels + remarkLabels +
            setOf("规格", "商品规格", "数量", "商品数量", "套餐", "套餐名称", "订单说明")
        private val sensitive = Regex("收货人|收货地址|联系方式|手机号码|手机号|联系电话|身份证|验证码|支付密码|银行卡号|(?:路|街|大道)\\s*[0-9０-９]+\\s*号|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:[0-9０-９][ \\-]?){8,}")
        private val forbiddenSurface = Regex("^(?:聊天记录|按住说话|发送消息|选择收货地址|编辑收货地址|登录|请输入支付密码|请输入验证码|支付成功|付款成功|支付失败|订单已取消)[：:]?$")
        private val money = Regex("""^(?:[¥￥]\s*)?([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)(?:\s*元)?$""")

        fun isPreview(packageName: String, text: String): Boolean {
            if (packageName !in packages) return false
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            return lines.take(4).count(titles::contains) == 1 && lines.any(actions::contains) &&
                lines.none(forbiddenSurface::matches)
        }

        fun parse(packageName: String, text: String, structure: PaymentScreenStructure): Preview? {
            if (!isPreview(packageName, text)) return null
            val fields = fields(structure, identityLabels + amountLabels + merchantLabels + descriptionLabels)
            val id = merchantOrderId(fields) ?: return null
            // Additional or differently typed identities make an order preview ambiguous.
            if (fields.filterKeys { it in identityLabels }.values.any { values ->
                    values.isEmpty() || values.any { CaptureEventIdentity.reliableOrderId(it) != id }
                }) return null
            val amountValues = fields.filterKeys { it in amountLabels }.values.flatten()
            val amounts = amountValues.map { value -> money.matchEntire(value)?.groupValues?.get(1)
                ?.let(MoneyUtil::parsePositiveYuanToCents)?.takeIf(MoneyUtil::isValidAmountCents) ?: return null }
            val amount = amounts.distinct().singleOrNull() ?: return null
            val merchants = merchants(fields)
            if (merchants.size > 1) return null
            val descriptions = descriptionLabels.flatMap { label -> fields[label].orEmpty().mapNotNull { value ->
                value.takeIf { it.length in 1..160 && it !in actions && it !in identityLabels &&
                    !it.contains('\n') && !sensitive.containsMatchIn(it) && id !in it }
                    ?.let { PaymentReceiptContext(label, it, LOCAL_SOURCE) }
            } }.take(8)
            return Preview(packageName, id, amount, merchants.singleOrNull(), descriptions).takeIf { descriptions.isNotEmpty() }
        }

        private fun merchantConflict(first: String?, second: String?) = first != null && second != null && first != second

        private fun merchantName(value: String?): String? = value?.let(CaptureEventIdentity::normalize)
            ?.takeIf { it.length in 2..120 && it !in platformNames && it !in merchantLabels &&
                CaptureEventIdentity.merchantFingerprint(it) != null && !sensitive.containsMatchIn(it) }

        private fun merchants(fields: Map<String, List<String>>): List<String> = fields.filterKeys { it in merchantLabels }
            .values.flatten().mapNotNull(::merchantName).distinct()

        private fun merchantOrderId(fields: Map<String, List<String>>): String? {
            val values = fields.filterKeys { it in merchantOrderLabels }.values
            if (values.any(List<String>::isEmpty)) return null
            return values.flatten().distinct().singleOrNull()?.let(CaptureEventIdentity::reliableOrderId)
        }

        private fun fields(structure: PaymentScreenStructure, labels: Set<String>): Map<String, List<String>> {
            val result = structure.labelledTextFields(labels).mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
            val inline = structure.nodes.filter { it.visible }.flatMap { it.labels }
            labelledFields(inline, labels).forEach { (label, values) -> result.getOrPut(label) { mutableListOf() }.addAll(values) }
            return result.mapValues { (_, values) -> values.distinct() }
        }

        private fun labelledFields(lines: List<String>, labels: Set<String>): Map<String, List<String>> =
            lines.mapNotNull { text ->
                val separator = text.indexOfFirst { it == ':' || it == '：' }.takeIf { it > 0 } ?: return@mapNotNull null
                val label = text.substring(0, separator).trim().takeIf(labels::contains) ?: return@mapNotNull null
                label to text.substring(separator + 1).trim()
            }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.distinct() }
    }
}

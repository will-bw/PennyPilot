package com.pennypilot.app.service

import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.CommercePaymentPages
import com.pennypilot.app.data.parser.PaymentAppRegistry
import com.pennypilot.app.data.parser.PaymentReceiptContext
import com.pennypilot.app.data.parser.PaymentReceiptDetails
import com.pennypilot.app.data.parser.PaymentReceiptSections
import com.pennypilot.app.data.parser.PaymentScreenStructure
import com.pennypilot.app.data.parser.PaymentTextParser
import com.pennypilot.app.util.MoneyUtil

/** Short-lived, local-only description from a read-only order preview. It cannot create a receipt. */
internal class CheckoutContext {
    data class Preview(val packageName: String, val merchantOrderId: String?, val amountCents: Long?,
        val merchant: String?, val fields: List<PaymentReceiptContext>, val platformOrderId: String? = null) {
        val orderId: String? get() = merchantOrderId ?: platformOrderId
    }
    data class LiveNotification(val wallAt: Long, val elapsedAt: Long)
    data class NotificationEnrichment(val receipt: NotificationParseResult, val authorization: Long)

    private data class WalletHandoff(val packageName: String, val at: Long, val wallAt: Long?)
    private data class Pending(val preview: Preview, val revision: Long, val seenAt: Long, val windowId: Int?,
        val ambiguous: Boolean = false, val handoff: WalletHandoff? = null)
    private var pending: Pending? = null
    private var authorization = 0L

    /** The service owns synchronization and expiry; redraws cannot extend the same preview's lifetime. */
    fun remember(preview: Preview, revision: Long, now: Long, windowId: Int? = null): Boolean {
        expire(revision, now)
        val previous = pending
        if (previous != null && previous.preview.packageName == preview.packageName &&
            previous.preview.orderId == preview.orderId &&
            (preview.orderId != null || previous.windowId == windowId)) {
            val conflicts = previous.preview.amountCents != preview.amountCents ||
                merchantConflict(previous.preview.merchant, preview.merchant) ||
                preview.orderId == null && productConflict(products(previous.preview.fields), products(preview.fields))
            pending = if (conflicts || previous.ambiguous) previous.copy(ambiguous = true)
                else previous.copy(windowId = windowId ?: previous.windowId,
                    preview = preview.copy(merchant = preview.merchant ?: previous.preview.merchant))
            return false
        }
        pending = Pending(preview, revision, now, windowId = windowId)
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
        if (!cached.ambiguous && cached.preview.merchantOrderId != null && cached.preview.merchant != null &&
            packageName in wallets &&
            now - cached.seenAt in 0 until HANDOFF_MS) {
            pending = cached.copy(handoff = WalletHandoff(packageName, now, wallAt))
        } else reset()
    }

    fun expire(revision: Long, now: Long) {
        val cached = pending ?: return
        if (cached.revision != revision || now - cached.seenAt !in 0 until retentionMillis(cached.preview) ||
            cached.handoff?.let { now - it.at !in 0 until HANDOFF_MS } == true) reset()
    }

    fun observeNonResult(packageName: String, text: String, revision: Long, now: Long) {
        expire(revision, now)
        val cached = pending ?: return
        if (packageName != (cached.handoff?.packageName ?: cached.preview.packageName)) return
        if (text.lineSequence().map(String::trim).any(abandonedCheckout::matches)) reset()
    }

    /** Only an unpaid amount from the same visible checkout can seed a user-reviewed draft. */
    fun pendingResult(packageName: String, text: String, revision: Long, now: Long, windowId: Int,
        activityName: String? = null): NotificationParseResult? {
        expire(revision, now)
        val cached = pending?.takeUnless { it.ambiguous || it.handoff != null } ?: return null
        val preview = cached.preview
        if (packageName != preview.packageName || windowId < 0 || windowId != cached.windowId ||
            now - cached.seenAt !in 0 until HANDOFF_MS ||
            !CommercePaymentPages.isPaymentCompletionWithoutAmount(packageName, text, activityName)) return null
        val amount = preview.amountCents ?: return null
        val estimate = MoneyUtil.centsToYuanString(amount) + "元"
        return NotificationParseResult(amount, null, "EXPENSE", scene = "checkout_payment_review",
            businessKind = "expense", ruleVersion = "checkout-result-review-1",
            fieldEvidence = mapOf(
                "reviewRequired" to "付款结果未显示实付金额，请核对结账页金额",
                "checkoutAmountEstimate" to "$LOCAL_SOURCE·应付金额 $estimate"
            ),
            details = PaymentReceiptDetails(context = preview.fields + listOfNotNull(
                preview.merchant?.let { PaymentReceiptContext("结账页商户", it, LOCAL_SOURCE) })))
    }

    fun consumePendingResult(packageName: String, revision: Long, windowId: Int) {
        val cached = pending ?: return
        if (cached.revision == revision && cached.preview.packageName == packageName && cached.windowId == windowId &&
            cached.handoff == null) reset()
    }

    fun enrich(packageName: String, structure: PaymentScreenStructure?, receipt: NotificationParseResult,
        revision: Long, now: Long, windowId: Int? = null, liveResult: Boolean = false): NotificationParseResult {
        expire(revision, now)
        val cached = pending?.takeUnless { it.ambiguous } ?: return receipt
        val preview = cached.preview
        val expectedPackage = cached.handoff?.packageName ?: preview.packageName
        if (expectedPackage != packageName || !matchesPayment(receipt, preview) || structure == null) return receipt
        val receiptFields = fields(structure, identityLabels + merchantLabels + PaymentReceiptSections.productLabels)
        val crossApp = cached.handoff != null
        val orderId = preview.orderId
        if (orderId != null) {
            val matchingLabels = if (preview.merchantOrderId != null) merchantOrderLabels else platformOrderLabels
            if (receipt.orderId != orderId || orderId(receiptFields, matchingLabels) != orderId) return receipt
        } else if (crossApp || !liveResult) return receipt
        val explicitMerchants = merchants(receiptFields)
        if (explicitMerchants.size > 1) {
            reset()
            return receipt
        }
        // The wallet may render the merchant later. Never bind until it is explicitly visible.
        if (crossApp && explicitMerchants.isEmpty()) return receipt
        val merchant = explicitMerchants.singleOrNull() ?: merchantName(receipt.details.merchantName ?: receipt.merchant)
        if (merchantConflict(merchant, preview.merchant)) {
            reset()
            return receipt
        }
        if (orderId == null) {
            val resultProducts = products(receipt.details.context) + listOfNotNull(
                receipt.details.productDescription?.let(CaptureEventIdentity::normalize)) +
                receiptFields.filterKeys { it in PaymentReceiptSections.productLabels }.values.flatten()
                    .map(CaptureEventIdentity::normalize)
            val offeredProducts = products(preview.fields)
            if (productConflict(offeredProducts, resultProducts.toSet())) {
                reset()
                return receipt
            }
            val sameWindow = windowId != null && windowId >= 0 && cached.windowId == windowId
            val corroborated = merchant != null && merchant == preview.merchant &&
                offeredProducts.any(resultProducts::contains)
            if (!sameWindow && !corroborated) return receipt
        }
        // Keep a checkout-only party local and distinct from a party read on the payment receipt.
        val checkoutMerchant = preview.merchant?.takeIf { !crossApp && liveResult && merchant == null }
        return attach(preview, receipt, checkoutMerchant)
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
        val id = orderId(fields, merchantOrderLabels) ?: return null
        val merchant = merchants(fields).singleOrNull() ?: return null
        val enriched = matchWallet(input.packageName, receipt, id, merchant) ?: return null
        return NotificationEnrichment(enriched, authorization)
    }

    private fun matchesPayment(receipt: NotificationParseResult, preview: Preview): Boolean =
        receipt.direction == "EXPENSE" && receipt.scene == "merchant_payment" &&
            (receipt.amountCents == preview.amountCents ||
                preview.packageName == BOX_HORSE && preview.amountCents == null)

    private fun matchWallet(packageName: String, receipt: NotificationParseResult, merchantOrderId: String,
        merchant: String): NotificationParseResult? {
        val cached = pending?.takeUnless { it.ambiguous } ?: return null
        val preview = cached.preview
        val id = preview.merchantOrderId ?: return null
        if (cached.handoff?.packageName != packageName || !matchesPayment(receipt, preview) ||
            receipt.orderId != id || CaptureEventIdentity.reliableOrderId(merchantOrderId) != id) return null
        val explicitMerchant = merchantName(merchant) ?: return null
        if (preview.merchant != explicitMerchant ||
            merchantConflict(explicitMerchant, merchantName(receipt.details.merchantName ?: receipt.merchant))) {
            reset()
            return null
        }
        return attach(preview, receipt)
    }

    private fun attach(preview: Preview, receipt: NotificationParseResult, checkoutMerchant: String? = null): NotificationParseResult {
        reset()
        val details = receipt.details
        val labels = details.context.mapTo(mutableSetOf()) { it.label }
        if (!details.productDescription.isNullOrBlank()) labels += PaymentReceiptSections.productLabels
        if (!details.remark.isNullOrBlank()) labels += remarkLabels
        if (!details.fundingAccount.isNullOrBlank() || !details.paymentChannel.isNullOrBlank()) labels += preferredMethodLabel
        val missing = preview.fields.filterNot { it.label in labels } +
            listOfNotNull(checkoutMerchant?.let { PaymentReceiptContext("结账页商户", it, LOCAL_SOURCE) })
        return receipt.copy(details = details.copy(context = PaymentReceiptDetails.normalizeContext(details.context + missing)))
    }

    companion object {
        const val TTL_MS = 120_000L
        private const val HANDOFF_MS = 30_000L
        const val LOCAL_SOURCE = "前置订单（本机）"
        private const val BOX_HORSE = "com.wudaokou.hippo"
        private val meituan = setOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1")
        private val douyin = setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.livelite", "com.ss.android.yumme.video")
        private val kuaishou = setOf("com.smile.gifmaker", "com.kuaishou.nebula")
        private val checkoutProfiles = mapOf(
            "com.jingdong.app.mall" to CheckoutProfile(setOf("填写订单", "确认订单"), setOf("提交订单", "去支付", "立即支付")),
            "com.xunmeng.pinduoduo" to CheckoutProfile(setOf("确认订单", "订单确认", "确认支付"), setOf("立即支付", "确认支付", "提交订单")),
            "com.alibaba.wireless" to CheckoutProfile(setOf("确认订单", "确认采购订单"), setOf("提交订单", "立即支付")),
            "com.taobao.idlefish" to CheckoutProfile(setOf("确认订单", "确认购买", "立即购买"), setOf("提交订单", "确认付款", "立即支付")),
            "com.taobao.taobao" to CheckoutProfile(setOf("确认订单", "订单确认", "确认付款"), setOf("提交订单", "确认付款", "立即支付")),
            "me.ele" to CheckoutProfile(setOf("确认订单", "订单确认", "提交订单"), setOf("提交订单", "确认支付")),
            "com.ss.android.ugc.lifeservices" to CheckoutProfile(setOf("确认订单", "立即购买", "确认购买"), setOf("提交订单", "确认支付", "立即支付")),
        ) + meituan.associateWith { CheckoutProfile(
            setOf("确认订单", "核对订单", "订单确认", "待支付订单", "订单待支付"),
            setOf("提交订单", "去支付", "确认支付", "立即支付", "极速支付", "提交月付订单"))
        } + douyin.associateWith { CheckoutProfile(
            setOf("确认订单", "核对订单", "订单确认", "待支付订单", "订单待支付"),
            setOf("提交订单", "去支付", "确认支付", "立即支付"))
        } + kuaishou.associateWith { CheckoutProfile(
            setOf("确认订单", "确认购买", "立即购买"), setOf("提交订单", "去支付", "确认支付", "立即支付"))
        }
        private data class CheckoutProfile(val titles: Set<String>, val actions: Set<String>)
        private val wallets = setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay")
        private val platformNames = PaymentAppRegistry.packages.map(PaymentAppRegistry::displayName).toSet() +
            setOf("微信支付", "支付宝支付", "银联云闪付", "美团支付", "抖音支付", "收单机构", "阿里巴巴", "京东商城")
        private val checkoutControls = checkoutProfiles.values.flatMap { it.titles + it.actions }.toSet()
        private val actionFormat = Regex("[,，]\\s*按钮$")
        private val merchantOrderLabels = setOf("商户订单号", "商家订单号", "商户单号")
        private val platformOrderLabels = setOf("订单号", "订单编号")
        private val identityLabels = merchantOrderLabels + platformOrderLabels +
            setOf("支付订单号", "交易订单号", "交易单号", "交易号", "流水号")
        private val amountLabels = setOf("应付金额", "待付金额", "待支付金额", "合计金额", "订单应付", "应付合计", "合计")
        private val previewAmountLabels = amountLabels + setOf("实付价", "实付款", "付款金额", "现价", "券后价")
        private val merchantLabels = setOf("商户名称", "商家名称", "商户", "商家", "店铺名称", "门店名称")
        private val remarkLabels = setOf("订单备注", "下单备注", "商品备注", "商家备注", "备注")
        private val descriptionLabels = PaymentReceiptSections.productLabels + remarkLabels +
            setOf("规格", "商品规格", "数量", "商品数量", "套餐", "套餐名称", "订单说明", "已选", "已选择")
        private const val preferredMethodLabel = "结账页付款方式"
        private val preferredMethodLabels = setOf("优先使用此付款方式", "优先付款方式")
        private val preferredMethods = Regex("^(?:支付宝|微信(?:支付)?|花呗|余额宝|盒马(?:钱包|礼品卡)|(?:[\\p{L}]{2,18}银行)?(?:储蓄卡|信用卡)(?:\\s*[（(](?:尾号\\s*)?[0-9]{4}[）)])?)$")
        private val sensitive = Regex("收货人|收货地址|联系方式|手机号码|手机号|联系电话|身份证|验证码|支付密码|银行卡号|(?:路|街|大道)\\s*[0-9０-９]+\\s*号|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:[0-9０-９][ \\-]?){8,}")
        private val forbiddenSurface = Regex("^(?:聊天记录|按住说话|发送消息|选择收货地址|编辑收货地址|登录|请输入支付密码|请输入验证码|支付成功|付款成功|交易成功|购买成功|免密支付成功|支付失败|订单已取消)[：:!！。]?$")
        private val abandonedCheckout = Regex("^(?:支付|付款|订单|交易)(?:已)?(?:失败|关闭|取消)[!！。]?$|^(?:购物车|我的订单|订单列表|所有订单|订单已取消|返回购物车|已取消支付)$")
        private val money = Regex("""^(?:[¥￥]\s*)?([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)(?:\s*元)?$""")
        private val payableAction = Regex("^(立即支付|确认付款|确认支付)\\s*([¥￥].+)$")
        private val specialCheckoutActivities = mapOf(
            "com.alibaba.wireless" to setOf("AliWindvaneActivity", "PopPageWindow"),
            "com.jingdong.app.mall" to setOf("NewFillOrderActivity", "PopupNewFillOrderActivity"),
            "me.ele" to setOf("WMCheckoutActivity", "RetailSubmitActivity"),
            "com.taobao.idlefish" to setOf("FishFlutterBoostActivity"),
            "com.xunmeng.pinduoduo" to setOf("NewPageActivity"),
        ) + kuaishou.associateWith { setOf("MerchantPurchasePanelContainerV2Activity", "KwaiRnActivity") } +
            douyin.associateWith { setOf("BuyNowFullscreenActivity", "BuyNowFullscreenDialog", "DyPayStandardActivity") }

        fun isPreview(packageName: String, text: String, activityName: String? = null): Boolean {
            val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
            if (lines.any { forbiddenSurface.matches(it) || abandonedCheckout.matches(it) }) return false
            if (packageName == BOX_HORSE) return activityName == "com.wudaokou.hippo.pay.PayCodeActivity" &&
                lines.any { line -> preferredMethodLabels.any(line::startsWith) }
            val profile = checkoutProfiles[packageName] ?: return false
            val controls = lines.map { raw ->
                val line = raw.replace(actionFormat, "")
                payableAction.matchEntire(line)?.groupValues?.get(1) ?: line
            }
            val title = controls.take(6).firstOrNull(profile.titles::contains)
            if (title != null && controls.any { it in profile.actions && it != title }) return true
            val activity = activityName?.substringAfterLast('.') ?: return false
            if (activity !in specialCheckoutActivities[packageName].orEmpty()) return false
            val hasPreviewField = lines.any { line -> (previewAmountLabels + descriptionLabels).any(line::startsWith) }
            return hasPreviewField && controls.any(profile.actions::contains)
        }

        fun retentionMillis(preview: Preview): Long = if (preview.orderId == null) HANDOFF_MS else TTL_MS

        fun parse(packageName: String, text: String, structure: PaymentScreenStructure,
            activityName: String? = null): Preview? {
            if (!isPreview(packageName, text, activityName)) return null
            if (packageName == BOX_HORSE) {
                val preferredMethod = fields(structure, preferredMethodLabels).values.flatten().distinct().singleOrNull()
                    ?.takeIf { preferredMethods.matches(it) && !sensitive.containsMatchIn(it) } ?: return null
                return Preview(packageName, null, null, null, listOf(
                    PaymentReceiptContext(preferredMethodLabel, preferredMethod, LOCAL_SOURCE)))
            }
            val fields = fields(structure, identityLabels + previewAmountLabels + merchantLabels + descriptionLabels)
            val merchantId = orderId(fields, merchantOrderLabels)
            val platformId = orderId(fields, platformOrderLabels)
            val id = merchantId ?: platformId
            // Do not weaken a visible but ambiguous identity into an unlabelled checkout.
            val ids = fields.filterKeys { it in identityLabels }
            if (merchantId != null && platformId != null && merchantId != platformId ||
                ids.keys.any { it !in merchantOrderLabels && it !in platformOrderLabels } ||
                ids.any { (label, values) ->
                    val expected = if (label in merchantOrderLabels) merchantId else platformId
                    expected == null || values.isEmpty() || values.any { CaptureEventIdentity.reliableOrderId(it) != expected }
                }) return null
            val amountValues = fields.filterKeys { it in amountLabels }.values.flatten().ifEmpty {
                fields.filterKeys { it in previewAmountLabels }.values.flatten().ifEmpty {
                    val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
                    lines.mapNotNull { payableAction.matchEntire(it.replace(actionFormat, ""))?.groupValues?.get(2) }
                        .ifEmpty {
                            // A known checkout screen may place the total immediately before
                            // 提交订单. Require a currency marker, never infer from a bare number.
                            if (packageName == "me.ele" && activityName?.substringAfterLast('.') in
                                specialCheckoutActivities.getValue("me.ele") &&
                                lines.count { it == "提交订单" } == 1) {
                                val total = lines.getOrNull(lines.indexOf("提交订单") - 1)
                                listOfNotNull(total?.takeIf { it.startsWith("¥") || it.startsWith("￥") })
                            } else emptyList()
                        }
                }
            }
            val amount = amountValues.map { value -> money.matchEntire(value)?.groupValues?.get(1)
                ?.let(MoneyUtil::parsePositiveYuanToCents)?.takeIf(MoneyUtil::isValidAmountCents) ?: return null }
                .distinct().singleOrNull() ?: return null
            val merchants = merchants(fields)
            if (merchants.size > 1) return null
            val descriptions = descriptionLabels.flatMap { label -> fields[label].orEmpty().mapNotNull { value ->
                value.takeIf { it.length in 1..160 && it !in checkoutControls && it !in identityLabels &&
                    !it.contains('\n') && !sensitive.containsMatchIn(it) && (id == null || id !in it) &&
                    !forbiddenSurface.matches(it) && !abandonedCheckout.matches(it) }
                    ?.let { PaymentReceiptContext(label, it, LOCAL_SOURCE) }
            } }.take(8)
            return Preview(packageName, merchantId, amount, merchants.singleOrNull(), descriptions, platformId)
                .takeIf { descriptions.isNotEmpty() || (id != null && it.merchant != null) }
        }

        private fun products(fields: List<PaymentReceiptContext>): Set<String> = fields.asSequence()
            .filter { it.label in PaymentReceiptSections.productLabels }.map { CaptureEventIdentity.normalize(it.value) }.toSet()

        private fun productConflict(first: Set<String>, second: Set<String>): Boolean =
            first.isNotEmpty() && second.isNotEmpty() && first.none(second::contains)

        private fun merchantConflict(first: String?, second: String?) = first != null && second != null && first != second

        private fun merchantName(value: String?): String? = value?.let(CaptureEventIdentity::normalize)
            ?.takeIf { it.length in 2..120 && it !in platformNames && it !in merchantLabels &&
                CaptureEventIdentity.merchantFingerprint(it) != null && !sensitive.containsMatchIn(it) }

        private fun merchants(fields: Map<String, List<String>>): List<String> = fields.filterKeys { it in merchantLabels }
            .values.flatten().mapNotNull(::merchantName).distinct()

        private fun orderId(fields: Map<String, List<String>>, labels: Set<String>): String? {
            val values = fields.filterKeys { it in labels }.values
            if (values.any(List<String>::isEmpty)) return null
            return values.flatten().distinct().singleOrNull()?.let(CaptureEventIdentity::reliableOrderId)
        }

        private fun fields(structure: PaymentScreenStructure, labels: Set<String>): Map<String, List<String>> {
            val result = structure.labelledTextFields(labels).mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
            val lines = structure.nodes.filter { it.visible }.flatMap { node -> node.labels.flatMap(String::lines) }
                .map(String::trim).filter(String::isNotEmpty)
            val inline = labelledFields(lines, labels)
            inline.forEach { (label, values) -> result.getOrPut(label) { mutableListOf() }.addAll(values) }
            val explicitLabels = labels + checkoutControls
            fun isLabel(line: String) = line.substringBefore('：').substringBefore(':').trim() in explicitLabels
            val adjacent = lines.mapIndexedNotNull { index, line ->
                val label = line.trimEnd(':', '：').trimEnd().takeIf(labels::contains) ?: return@mapIndexedNotNull null
                val value = if (lines.getOrNull(index + 1) in setOf("¥", "￥") && label in previewAmountLabels)
                    lines.getOrNull(index + 2) else lines.getOrNull(index + 1)
                label to value?.takeUnless { isLabel(it) || forbiddenSurface.matches(it) || abandonedCheckout.matches(it) }
            }.groupBy({ it.first }, { it.second })
            adjacent.forEach { (label, values) ->
                val bucket = result.getOrPut(label) { mutableListOf() }
                // A structured row outranks traversal for a single field. Repeated labels
                // still contribute their plausible values so a second checkout cannot be hidden.
                if (bucket.isEmpty() || label in inline || values.size > 1) bucket.addAll(values.filterNotNull())
            }
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

package com.pennypilot.app.data.parser

/** Exact labels and title arrangements from single receipts in the four supported wallet apps. */
internal object WalletPaymentPageAdapter {
    private const val UNION_PAY = "com.unionpay"
    private const val HUAWEI_WALLET = "com.huawei.wallet"
    private const val CCB_LIFE = "com.ccb.longjiLife"
    private const val JD_FINANCE = "com.jd.jrapp"

    private val unionTitles = setOf("云闪付交易详情", "银联交易详情")
    private val detailActivities = mapOf(
        HUAWEI_WALLET to setOf(
            "com.huawei.wallet.nfc.bankcard.transactionhistory.ui.activity.UnCardTransRecordDetailActivity",
            "com.huawei.petalpay.userapp.function.billdetails.activity.BillDetailInfoActivity"),
        CCB_LIFE to setOf("com.ccb.cloudmerchant.wallet.transactions.TransactionsDetailActivity")
    )
    private val resultActivities = mapOf(
        HUAWEI_WALLET to setOf("com.huawei.nfc.carrera.ui.swipe.SwipeResultActivity",
            "com.huawei.wallet.bankcard.scanpay.ui.activity.ScanQrCodePayResultActivity"),
        CCB_LIFE to setOf("com.ccb.cloudmerchant.wallet.newOne.view.CodeResultActivity")
    )
    private val resultTitles = setOf("支付成功", "付款成功", "消费成功", "收款成功", "转账成功")
    private val resultActions = setOf("完成", "查看账单", "查看订单")
    private val unionSelfTransfer = setOf("转账成功", "转出成功")
    private val selfTransferReceiver = Regex("""^转给本人(?:[：:]?\s*(?:本人)?(?:银行卡|账户))?$""")
    private val detailTitles = setOf("账单详情", "交易详情", "退款详情", "收款详情", "转账详情", "支付详情", "收支详情")
    private val detailCompletion = setOf("交易成功", "支付成功", "付款成功", "消费成功", "转账成功", "转出成功", "收款成功",
        "收款到账", "到账成功", "入账成功", "退款成功", "退款到账", "退款已到账", "还款成功", "白条还款成功", "京东白条还款成功")
    private val statusFields = PaymentTextParser.completedStatusByLabel.keys + setOf("订单状态", "当前状态", "状态")
    private val paymentTimeFields = setOf("交易时间", "支付时间", "付款时间", "收款时间", "到账时间", "完成时间", "转账时间")
    private val explicitPartyFields = setOf("商户名称", "商户全称", "商户简称", "商家名称", "商户名", "收款商户",
        "商户", "商家", "门店", "门店名称", "店铺", "店铺名称", "收款方", "收款人", "付款方", "付款人")
    private val walletCardFields = setOf("付款卡", "收款卡", "付款银行")
    private val sensitiveOrControl = Regex(
        "账单|订单|收款|支付|退款|充值|提现|还款|转账|日期|时间|方式|状态|金额|账户|卡号|返回|关闭|完成|查看|联系|推荐|个人信息|验证码|密码")
    private val signedHeadline = Regex("""^(?:[¥￥]\s*)?([+−-])\s*([0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*(?:元)?$""")
    private val signBeforeCurrency = Regex("""^([+−-])\s*([¥￥])\s*([0-9][0-9,.]*)(\s*元)?$""")
    private val splitSign = Regex("""^(?:([+−-])\s*[¥￥]|[¥￥]\s*([+−-]))$""")
    private val bareAmount = Regex("""^[0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2})?\s*(?:元)?$""")
    private val dateWithoutTime = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""")
    private val clockTime = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
    private val merchantName = Regex("""^[\p{L}\p{N} ().（）·&_-]{2,60}$""")
    private val numericCredential = Regex("""(?<!\d)\d(?:[ -]?\d){11,}(?!\d)""")
    private val unionTimestamp = Regex("""^(?:延迟扣费\|\s*)?(\d{4})\.(\d{1,2})\.(\d{1,2})(\s+\d{1,2}:\d{2}(?::\d{2})?)$""")
    private val labelsByPackage = mapOf(
        UNION_PAY to mapOf("支出金额" to "实付金额", "实付金额（元）" to "实付金额", "实付金额(元)" to "实付金额",
            "实收金额（元）" to "收款金额", "实收金额(元)" to "收款金额", "扣款时间" to "支付时间",
            "付款卡" to "付款账户", "收款卡" to "收款账户"),
        HUAWEI_WALLET to mapOf("商家" to "商户名称", "日期" to "交易时间", "付款银行" to "付款账户"),
        CCB_LIFE to mapOf("商户名" to "商户名称"),
        JD_FINANCE to mapOf("退款至" to "退款方式", "支付立减" to "优惠金额")
    )

    fun isDedicatedDetailActivity(packageName: String, activityName: String?): Boolean =
        activityName in detailActivities[packageName].orEmpty()

    fun normalize(packageName: String, text: String, activityName: String? = null): PaymentPageNormalizer.Result {
        val unchanged = PaymentPageNormalizer.Result(text, emptyList())
        val aliases = labelsByPackage[packageName] ?: return unchanged
        if (text.length !in 1..12_000) return unchanged
        val facts = factLines(text, aliases.keys)
        val jdHeadline = if (packageName == JD_FINANCE) jdHeadline(facts) else null
        val titles = if (packageName == UNION_PAY) detailTitles + unionTitles else detailTitles
        val detail = facts.take(6).firstOrNull(titles::contains)
        val completed = hasCompletedStatus(facts)
        val activityDetail = isDedicatedDetailActivity(packageName, activityName) && completed &&
            facts.any { line -> (paymentTimeFields + aliases.filterValues(paymentTimeFields::contains).keys)
                .any { hasLabel(line, it) } } &&
            facts.any { line -> explicitPartyFields.any { hasLabel(line, it) } }
        val detailCompleted = completed && (detail != null || activityDetail)
        // Headerless JD Finance details carry a signed amount, account row and creation/payment time.
        val implicitJdDetail = detail == null && jdHeadline != null && hasJdDetailRows(facts)
        val result = facts.take(6).any(resultTitles::contains) &&
            (facts.any(resultActions::contains) || activityName in resultActivities[packageName].orEmpty())
        if (!detailCompleted && !implicitJdDetail && !result) return unchanged

        val changes = mutableListOf<String>()
        val financialLines = facts.toSet()
        val unionTransfer = packageName == UNION_PAY && facts.any(unionSelfTransfer::contains) &&
            facts.any { hasLabel(it, "转账金额") }
        val unionSelf = unionTransfer && facts.any(selfTransferReceiver::matches)
        val scopedAliases = if (unionTransfer)
            aliases + mapOf("付款卡" to "转出账户", "收款卡" to "转入账户") else aliases
        val sourceLines = text.lines().map(String::trim)
        val lines = sourceLines.map { line ->
            if (line !in financialLines) return@map line
            val replacement = when {
                packageName == UNION_PAY && line == detail && line in unionTitles -> "交易详情"
                unionSelf && line in unionSelfTransfer -> "本人账户转账成功"
                unionTransfer && line == "转出成功" -> "转账成功"
                detailCompleted && signBeforeCurrency.matches(line) -> normalizeSignedMoney(line)
                packageName == HUAWEI_WALLET && !detailCompleted && hasLabel(line, "日期") -> line
                else -> scopedAliases.entries.firstOrNull { (label, _) -> hasLabel(line, label) }
                    ?.let { (label, normalized) ->
                        val tail = line.removePrefix(label).trimStart(' ', '：', ':')
                        when {
                            tail.isEmpty() -> normalized
                            label in walletCardFields -> "$normalized：${maskAccount(tail)}"
                            packageName == UNION_PAY && label == "扣款时间" -> "$normalized：${unionTime(tail)}"
                            else -> "$normalized：$tail"
                        }
                    } ?: line
            }
            if (replacement != line) changes += if (walletCardFields.any { hasLabel(line, it) }) maskAccount(line) else line
            replacement
        }.toMutableList()
        sourceLines.zipWithNext().forEachIndexed { index, (label, value) ->
            if (label !in financialLines || value !in financialLines || label !in aliases) return@forEachIndexed
            if (label in walletCardFields) lines[index + 1] = maskAccount(value)
            if (packageName == UNION_PAY && label == "扣款时间") lines[index + 1] = unionTime(value)
        }
        if (detailCompleted) {
            sourceLines.zipWithNext().forEachIndexed { index, (currency, amount) ->
                if (currency !in financialLines || amount !in financialLines || !bareAmount.matches(amount)) return@forEachIndexed
                val split = splitSign.matchEntire(currency) ?: return@forEachIndexed
                val sign = split.groupValues[1].ifEmpty { split.groupValues[2] }
                lines[index] = "¥$sign$amount"
                lines[index + 1] = ""
                changes += "$currency $amount"
            }
        }
        if (activityDetail && detail == null) {
            lines.add(0, "交易详情")
            changes += "钱包单笔交易详情"
        }
        if (implicitJdDetail) {
            lines.add(0, "账单详情")
            changes += "京东金融单笔账单"
        }
        if (jdHeadline != null && (detailCompleted || implicitJdDetail)) {
            if (jdHeadline.status == "还款成功") {
                val statusIndex = lines.indexOf("还款成功")
                val rawAmount = lines.getOrNull(statusIndex - 1)
                val amount = rawAmount?.let(signedHeadline::matchEntire)
                if (amount != null && amount.groupValues[1] in setOf("-", "−") &&
                    lines.getOrNull(statusIndex - 2) == "京东白条") {
                    lines[statusIndex] = "白条还款成功"
                    lines[statusIndex - 1] = "还款金额：${amount.groupValues[2]}元"
                    if (lines.none { hasLabel(it, "转入账户") }) lines += "转入账户：京东白条"
                    changes += listOf("还款成功", rawAmount)
                }
            } else if (facts.none { line -> explicitPartyFields.any { hasLabel(line, it) } }) {
                val corroboration = lines.indexOf(jdHeadline.status)
                if (lines.getOrNull(corroboration - 2) == jdHeadline.name) {
                    lines[corroboration - 2] = "商户名称：${jdHeadline.name}"
                    changes += jdHeadline.name
                }
            }
        }
        if (jdHeadline != null && lines.any { hasLabel(it, "创建时间") }) {
            val index = lines.indexOfFirst { it.trimEnd('：', ':') == "创建时间" }
            val day = lines.getOrNull(index + 1).orEmpty()
            val clock = lines.getOrNull(index + 2).orEmpty()
            if (index >= 0 && day.matches(dateWithoutTime) && clock.matches(clockTime)) {
                lines[index + 1] = "$day $clock"
                lines.removeAt(index + 2)
                changes += "创建时间"
            }
        }
        return if (changes.isEmpty()) unchanged else PaymentPageNormalizer.Result(lines.joinToString("\n"), changes.distinct())
    }

    /** A historical page without order identity can become a draft only with independent receipt evidence. */
    fun supportsIdentitylessReview(packageName: String, normalizedText: String): Boolean {
        if (packageName !in labelsByPackage) return false
        val lines = factLines(normalizedText)
        if (!isSingleCompletedDetail(lines)) return false
        val fields = PaymentReceiptDetailsReader.read(packageName, normalizedText, null, "页面")
        val details = fields.details
        val hasParty = !fields.ambiguousParty && (details.merchantName != null || details.payeeName != null)
        val hasAccount = listOf(details.fundingAccount, details.transferFromAccount, details.transferToAccount).any { it != null }
        return when (packageName) {
            CCB_LIFE -> hasParty && hasAccount && lines.any(signedHeadline::matches)
            HUAWEI_WALLET -> hasParty && hasAccount
            UNION_PAY -> (hasParty && hasAccount) || (hasAccount && lines.any { hasLabel(it, "转账金额") })
            JD_FINANCE -> hasAccount && (hasParty || lines.any { hasLabel(it, "还款金额") })
            else -> false
        }
    }

    fun allowsCreationTimeFallback(packageName: String, normalizedText: String): Boolean {
        if (packageName != JD_FINANCE) return false
        val lines = factLines(normalizedText)
        return isSingleCompletedDetail(lines) && hasJdDetailRows(lines) &&
            lines.any { hasLabel(it, "创建时间") } && supportsIdentitylessReview(packageName, normalizedText)
    }

    private data class JdHeadline(val name: String, val status: String)

    private fun jdHeadline(lines: List<String>): JdHeadline? =
        lines.take(8).windowed(3).mapNotNull { (name, amount, status) ->
            if (!signedHeadline.matches(amount) || status !in setOf("交易成功", "退款成功", "还款成功")) return@mapNotNull null
            if (status == "还款成功" && name == "京东白条") return@mapNotNull JdHeadline(name, status)
            name.takeIf { merchantName.matches(it) && it.any(Char::isLetter) &&
                !sensitiveOrControl.containsMatchIn(it) && !numericCredential.containsMatchIn(it) }?.let { JdHeadline(it, status) }
        }.singleOrNull()

    private fun hasJdDetailRows(lines: List<String>): Boolean =
        lines.any { hasLabel(it, "支付方式") || hasLabel(it, "付款方式") || hasLabel(it, "退款至") || hasLabel(it, "退款方式") } &&
            lines.any { line -> hasLabel(line, "创建时间") || paymentTimeFields.any { hasLabel(line, it) } || hasLabel(line, "退款时间") }

    private fun hasCompletedStatus(lines: List<String>): Boolean = lines.any { it.trimEnd('!', '！', '。') in detailCompletion } ||
        lines.withIndex().any { (index, line) ->
            val field = statusFields.firstOrNull { hasLabel(line, it) } ?: return@any false
            val suffix = line.removePrefix(field).trimStart(' ', ':', '：')
            PaymentTextParser.completedStatus(field, suffix.ifBlank { lines.getOrNull(index + 1).orEmpty() }) != null
        }

    private fun isSingleCompletedDetail(lines: List<String>): Boolean =
        lines.take(6).any(detailTitles::contains) && lines.count(detailTitles::contains) == 1 && hasCompletedStatus(lines)

    private fun hasLabel(line: String, label: String): Boolean =
        line.startsWith(label) && line.getOrNull(label.length).let { it == null || it == '：' || it == ':' || it.isWhitespace() }

    private fun unionTime(value: String): String = unionTimestamp.replace(value, "$1-$2-$3$4").removePrefix("延迟扣费|").trim()

    private fun normalizeSignedMoney(value: String): String = signBeforeCurrency.replace(value, "$2$1$3$4")

    private fun maskAccount(value: String): String = numericCredential.replace(value) {
        "尾号${it.value.filter(Char::isDigit).takeLast(4)}"
    }

    private fun factLines(text: String, additionalBoundaries: Set<String> = emptySet()): List<String> =
        PaymentReceiptSections.split(text, additionalBoundaries).facts.lineSequence()
            .map(String::trim).filter(String::isNotBlank).toList()
}

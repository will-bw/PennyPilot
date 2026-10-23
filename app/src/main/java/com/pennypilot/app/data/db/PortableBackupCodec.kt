package com.pennypilot.app.data.db

import com.pennypilot.app.data.db.dao.BackupSnapshot
import com.pennypilot.app.data.db.entity.*
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.serialization.json.*
import java.time.YearMonth

/** Versioned, platform-neutral backup. Credentials and device permissions never enter it. */
object PortableBackupCodec {
    private const val MAX_SAFE_INTEGER = 9007199254740991L

    fun encode(data: BackupSnapshot): Map<String, Any?> = mapOf(
        "format" to "pennypilot", "version" to 1,
        "categories" to data.categories.map { mapOf("id" to it.id, "name" to it.name, "icon" to it.icon,
            "colorIndex" to it.colorIndex, "type" to it.type.name, "sortOrder" to it.sortOrder,
            "enabled" to it.enabled, "isPreset" to it.isPreset) },
        "transactions" to data.transactions.map { mapOf("id" to it.id, "amount" to it.amount,
            "type" to it.type.name, "categoryId" to it.categoryId, "note" to it.note,
            "merchant" to it.merchant, "accountTag" to it.accountTag, "occurredAt" to it.occurredAt,
            "createdAt" to it.createdAt, "source" to it.source.name, "status" to it.status.name,
            "rawPayload" to it.rawPayload, "captureEventKey" to it.captureEventKey, "duplicateOf" to it.duplicateOf) },
        "rules" to data.rules.map { mapOf("id" to it.id, "appPackage" to it.appPackage,
            "regexAmount" to it.regexAmount, "regexMerchant" to it.regexMerchant,
            "directionHint" to it.directionHint, "enabled" to it.enabled, "priority" to it.priority) },
        "reports" to data.reports.map { mapOf("id" to it.id, "yearMonth" to it.yearMonth,
            "totalExpense" to it.totalExpense, "totalIncome" to it.totalIncome,
            "byCategoryJson" to it.byCategoryJson, "llmSummary" to it.llmSummary, "generatedAt" to it.generatedAt) },
        "captureEvents" to data.captureEvents.map { mapOf("eventKey" to it.eventKey,
            "transactionId" to it.transactionId, "packageName" to it.packageName,
            "source" to it.source.name, "observedAt" to it.observedAt, "firstObservedAt" to it.firstObservedAt,
            "notificationKey" to it.notificationKey, "textFingerprint" to it.textFingerprint,
            "orderId" to it.orderId, "orderDomain" to it.orderDomain, "amount" to it.amount,
            "type" to it.type.name, "merchantFingerprint" to it.merchantFingerprint) }
    )

    fun decode(data: Map<String, Any?>): BackupSnapshot {
        require(data["format"] == "pennypilot" && data.number("version") == 1L) { "不支持此备份格式或版本。" }
        require(data.keys.all { it in setOf("format", "version", "categories", "transactions", "rules", "reports", "captureEvents") }) {
            "备份包含未知字段，请升级应用后重试。"
        }
        require(portableJson(data).toString().toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) { "备份文件不能超过 20 MB。" }
        val categories = data.rows("categories", max = 10000).map { row -> CategoryEntity(
            id = row.positive("id"), name = row.text("name", 20).trim(), icon = row.text("icon", 32).trim(),
            colorIndex = row.int("colorIndex").also { require(it >= 0) }, type = TxType.valueOf(row.text("type")),
            sortOrder = row.int("sortOrder").also { require(it >= 0) }, enabled = row.flag("enabled"), isPreset = row.flag("isPreset")) }
        val byCategory = categories.associateBy { it.id }
        require(categories.isNotEmpty() && byCategory.size == categories.size) { "备份分类缺失或编号重复。" }
        require(categories.map { it.type to it.name }.distinct().size == categories.size) { "备份分类名称重复。" }
        val transactions = data.rows("transactions").map { row ->
            val type = TxType.valueOf(row.text("type"))
            val amount = row.number("amount")
            require(if (type == TxType.INCOME) amount in -999999999L..-1L else amount in 1L..999999999L) { "备份金额无效。" }
            val category = row.positive("categoryId")
            require(byCategory[category]?.type == type) { "备份账目分类无效。" }
            val id = row.positive("id")
            val duplicateOf = row["duplicateOf"]?.let { row.positive("duplicateOf") }
            require(duplicateOf != id) { "账目不能关联自身为重复项。" }
            TransactionEntity(id = id, amount = amount, type = type, categoryId = category,
                note = row.optionalText("note", 2000), merchant = row.optionalText("merchant", 200),
                accountTag = row.optionalText("accountTag", 100), occurredAt = row.time("occurredAt"),
                createdAt = row.time("createdAt"), source = TxSource.valueOf(row.text("source")),
                status = TxStatus.valueOf(row.text("status")), rawPayload = row.optionalText("rawPayload", 100000),
                captureEventKey = row.optionalText("captureEventKey", 2000),
                duplicateOf = duplicateOf)
        }
        unique(transactions.map { it.id })
        val captureKeys = transactions.mapNotNull { it.captureEventKey }
        require(captureKeys.distinct().size == captureKeys.size) { "备份采集标识重复。" }
        // A duplicate candidate can point at an already-deleted transaction; preserve it as evidence.
        val rules = data.rows("rules", max = 10000).map { row ->
            val amount = row.text("regexAmount", 4000)
            val merchant = row.optionalText("regexMerchant", 4000)
            require(Regex(amount).toPattern().matcher("").groupCount() >= 1) { "金额规则必须有捕获组。" }
            merchant?.let { require(Regex(it).toPattern().matcher("").groupCount() >= 1) { "商户规则必须有捕获组。" } }
            val direction = row.text("directionHint")
            require(direction in listOf("INCOME", "EXPENSE"))
            val appPackage = row.text("appPackage", 300).trim()
            require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(appPackage)) { "备份规则应用包名无效。" }
            ParseRuleEntity(row.positive("id"), appPackage, amount, merchant,
                direction, row.flag("enabled"), row.int("priority"))
        }
        unique(rules.map { it.id })
        val reports = data.rows("reports", max = 10000).map(::decodeReport)
        require(reports.all { it.id > 0 }) { "备份月报编号无效。" }
        unique(reports.map { it.id })
        require(reports.map { it.yearMonth }.distinct().size == reports.size) { "备份月份重复。" }
        val txIds = transactions.map { it.id }.toSet()
        val evidence = data.rows("captureEvents", optional = true).map { row ->
            val transaction = row.positive("transactionId")
            require(transaction in txIds) { "备份采集证据引用无效。" }
            val source = TxSource.valueOf(row.text("source"))
            require(source in listOf(TxSource.NOTIFICATION, TxSource.ACCESSIBILITY))
            val type = TxType.valueOf(row.text("type"))
            val amount = row.number("amount")
            require(if (type == TxType.INCOME) amount in -999999999L..-1L else amount in 1L..999999999L)
            CaptureEvidenceEntity(row.text("eventKey", 2000), transaction, row.text("packageName", 300),
                source, row.time("observedAt"), row.time("firstObservedAt"),
                row.optionalText("notificationKey", 2000), row.text("textFingerprint", 2000),
                row.optionalText("orderId", 2000), row.text("orderDomain", 300), amount, type,
                row.optionalText("merchantFingerprint", 2000))
        }
        require(evidence.map { it.eventKey }.distinct().size == evidence.size) { "备份采集证据重复。" }
        return BackupSnapshot(categories, transactions, rules, reports, evidence)
    }

    fun decodeReport(row: Map<String, Any?>): MonthlyReportEntity {
        val month = row.text("yearMonth", 7)
        require(Regex("[0-9]{4}-[0-9]{2}").matches(month))
        require(YearMonth.parse(month).year in 1..9999)
        val categories = row.text("byCategoryJson", 100000)
        val breakdown = Json.parseToJsonElement(categories) as? JsonArray
            ?: throw IllegalArgumentException("报告分类汇总必须是数组。")
        require(breakdown.size <= 10000)
        breakdown.forEach { entry ->
            val item = entry as? JsonObject ?: throw IllegalArgumentException("报告分类汇总格式无效。")
            val name = item["name"] as? JsonPrimitive
            require(name != null && name.isString && name.content.isNotBlank() && name.content.length <= 200)
            for (field in listOf("total", "count")) {
                val value = item[field] as? JsonPrimitive
                require(value != null && !value.isString && value.longOrNull?.let { it in 0..MAX_SAFE_INTEGER } == true)
            }
        }
        return MonthlyReportEntity(id = row["id"]?.let { row.number("id").also { id -> require(id >= 0) } } ?: 0,
            yearMonth = month, totalExpense = row.number("totalExpense").also { require(it >= 0) },
            totalIncome = row.number("totalIncome").also { require(it >= 0) }, byCategoryJson = categories,
            llmSummary = row.text("llmSummary", 100000, allowEmpty = true), generatedAt = row.time("generatedAt"))
    }

    private fun unique(ids: List<Long>) { require(ids.distinct().size == ids.size) { "备份编号重复。" } }
    private fun Map<String, Any?>.number(key: String): Long = (when (val value = this[key]) {
        is Int -> value.toLong(); is Long -> value
        else -> throw IllegalArgumentException("备份整数格式无效：$key")
    }).also { require(it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "备份整数超出跨平台支持的范围：$key" } }
    private fun Map<String, Any?>.positive(key: String) = number(key).also { require(it > 0) }
    private fun Map<String, Any?>.time(key: String) = number(key).also { require(it in 0..253402214400000L) }
    private fun Map<String, Any?>.int(key: String) = number(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun Map<String, Any?>.flag(key: String) = this[key] as? Boolean ?: throw IllegalArgumentException("备份开关格式无效。")
    private fun Map<String, Any?>.text(key: String, max: Int = 1000, allowEmpty: Boolean = false): String =
        (this[key] as? String)?.also { require(it.length <= max && (allowEmpty || it.isNotBlank())) }
            ?: throw IllegalArgumentException("备份文本格式无效：$key")
    private fun Map<String, Any?>.optionalText(key: String, max: Int) = this[key]?.let { text(key, max, true) }
    private fun Map<String, Any?>.rows(key: String, optional: Boolean = false, max: Int = 100000): List<Map<String, Any?>> {
        if (optional && !containsKey(key)) return emptyList()
        val rows = this[key] as? List<*> ?: throw IllegalArgumentException("备份列表格式无效：$key")
        require(rows.size <= max) { "备份记录过多。" }
        return rows.map { value ->
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("备份行格式无效。")
            require(map.keys.all { it is String })
            map.entries.associate { it.key as String to it.value }
        }
    }

    private fun portableJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::portableJson))
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "备份字段名格式无效。" }
            JsonObject(value.entries.associate { it.key as String to portableJson(it.value) })
        }
        else -> throw IllegalArgumentException("备份包含无法识别的数据。")
    }
}

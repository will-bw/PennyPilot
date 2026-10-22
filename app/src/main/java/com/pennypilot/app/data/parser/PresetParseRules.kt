package com.pennypilot.app.data.parser

import androidx.sqlite.db.SupportSQLiteDatabase
import com.pennypilot.app.data.db.entity.ParseRuleEntity

/** 支付动作后紧邻完整金额，避免把应用名、商户编号或金额前缀识别为交易。 */
object PresetParseRules {
    private const val AMOUNT = """((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)(?![\d.,])"""
    private const val SEPARATOR = """\s*[:：]?\s*[¥￥]?\s*"""
    private const val EXPENSE = """(?:付款|支付|消费)(?:成功)?(?:金额)?"""
    private const val INCOME = """(?:收款(?:到账)?|收入|到账|收到(?:转账|红包))(?:成功)?(?:金额)?"""

    val rules = listOf(
        ParseRuleEntity(
            appPackage = "com.tencent.mm",
            regexAmount = EXPENSE + SEPARATOR + AMOUNT,
            regexMerchant = """(?:向|给)(.+?)(?:付款|支付)""",
            directionHint = "EXPENSE", priority = 10
        ),
        ParseRuleEntity(
            appPackage = "com.tencent.mm",
            regexAmount = """微信支付\s*[:：]?\s*[¥￥]\s*""" + AMOUNT,
            directionHint = "EXPENSE", priority = 20
        ),
        ParseRuleEntity(
            appPackage = "com.tencent.mm",
            regexAmount = INCOME + SEPARATOR + AMOUNT,
            directionHint = "INCOME", priority = 0
        ),
        ParseRuleEntity(
            appPackage = "com.eg.android.AlipayGphone",
            regexAmount = EXPENSE + SEPARATOR + AMOUNT,
            regexMerchant = """(?:向|在)(.+?)(?:付款|支付|消费)""",
            directionHint = "EXPENSE", priority = 10
        ),
        ParseRuleEntity(
            appPackage = "com.eg.android.AlipayGphone",
            regexAmount = INCOME + SEPARATOR + AMOUNT,
            directionHint = "INCOME", priority = 0
        )
    )

    /** 一一对应旧版五条预置值；迁移只更新全部字段仍与旧预置相同的行。 */
    internal val legacyRules = listOf(
        ParseRuleEntity(appPackage = "com.tencent.mm",
            regexAmount = """(?:付款|支付)[^\d]*(\d+(?:\.\d{1,2})?)""",
            regexMerchant = """(?:向|给)(.+?)(?:付款|支付)""", priority = 1),
        ParseRuleEntity(appPackage = "com.tencent.mm",
            regexAmount = """微信支付[^\d]*(\d+(?:\.\d{1,2})?)""", priority = 2),
        ParseRuleEntity(appPackage = "com.tencent.mm",
            regexAmount = """收款[^\d]*(\d+(?:\.\d{1,2})?)""",
            directionHint = "INCOME", priority = 3),
        ParseRuleEntity(appPackage = "com.eg.android.AlipayGphone",
            regexAmount = """(?:付款|支付|消费)[^\d]*(\d+(?:\.\d{1,2})?)""",
            regexMerchant = """(?:向|在)(.+?)(?:付款|支付|消费)""", priority = 1),
        ParseRuleEntity(appPackage = "com.eg.android.AlipayGphone",
            regexAmount = """(?:收入|到账)[^\d]*(\d+(?:\.\d{1,2})?)""",
            directionHint = "INCOME", priority = 2)
    )

    fun upgradeLegacyRules(db: SupportSQLiteDatabase) {
        legacyRules.zip(rules).forEach { (old, replacement) ->
            db.execSQL(
                """UPDATE parse_rules SET regex_amount = ?, regex_merchant = ?,
                    direction_hint = ?, priority = ?
                    WHERE app_package = ? AND regex_amount = ? AND regex_merchant IS ?
                    AND direction_hint = ? AND enabled = ? AND priority = ?""".trimIndent(),
                arrayOf(replacement.regexAmount, replacement.regexMerchant,
                    replacement.directionHint, replacement.priority, old.appPackage,
                    old.regexAmount, old.regexMerchant, old.directionHint,
                    if (old.enabled) 1 else 0, old.priority)
            )
        }
    }
}

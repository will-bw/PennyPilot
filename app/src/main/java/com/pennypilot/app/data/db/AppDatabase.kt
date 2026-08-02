package com.pennypilot.app.data.db

import android.content.Context
import androidx.room.*
import com.pennypilot.app.data.db.converter.Converters
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.MonthlyReportDao
import com.pennypilot.app.data.db.dao.ParseRuleDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxType

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, ParseRuleEntity::class, MonthlyReportEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun parseRuleDao(): ParseRuleDao
    abstract fun insightDao(): InsightDao
    abstract fun monthlyReportDao(): MonthlyReportDao

    companion object {
        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "pennypilot.db")
                .fallbackToDestructiveMigration()
                .build()
        }

        val PRESET_CATEGORIES = listOf(
            CategoryEntity(name = "餐饮", icon = "🍜", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0, isPreset = true),
            CategoryEntity(name = "交通", icon = "🚕", colorIndex = 1, type = TxType.EXPENSE, sortOrder = 1, isPreset = true),
            CategoryEntity(name = "购物", icon = "🛍️", colorIndex = 2, type = TxType.EXPENSE, sortOrder = 2, isPreset = true),
            CategoryEntity(name = "居住", icon = "🏠", colorIndex = 3, type = TxType.EXPENSE, sortOrder = 3, isPreset = true),
            CategoryEntity(name = "娱乐", icon = "🎬", colorIndex = 4, type = TxType.EXPENSE, sortOrder = 4, isPreset = true),
            CategoryEntity(name = "医疗", icon = "💊", colorIndex = 5, type = TxType.EXPENSE, sortOrder = 5, isPreset = true),
            CategoryEntity(name = "教育", icon = "📚", colorIndex = 6, type = TxType.EXPENSE, sortOrder = 6, isPreset = true),
            CategoryEntity(name = "人情", icon = "🧧", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 7, isPreset = true),
            CategoryEntity(name = "其他", icon = "📦", colorIndex = 1, type = TxType.EXPENSE, sortOrder = 8, isPreset = true),
            CategoryEntity(name = "工资", icon = "💼", colorIndex = 6, type = TxType.INCOME, sortOrder = 9, isPreset = true),
            CategoryEntity(name = "红包", icon = "🧧", colorIndex = 6, type = TxType.INCOME, sortOrder = 10, isPreset = true),
            CategoryEntity(name = "其他", icon = "💰", colorIndex = 6, type = TxType.INCOME, sortOrder = 11, isPreset = true),
        )

        val PRESET_RULES = listOf(
            // WeChat pay
            ParseRuleEntity(
                appPackage = "com.tencent.mm",
                regexAmount = """(?:付款|支付)[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = """(?:向|给)(.+?)(?:付款|支付)""",
                directionHint = "EXPENSE", priority = 1
            ),
            ParseRuleEntity(
                appPackage = "com.tencent.mm",
                regexAmount = """微信支付[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = null,
                directionHint = "EXPENSE", priority = 2
            ),
            ParseRuleEntity(
                appPackage = "com.tencent.mm",
                regexAmount = """收款[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = null,
                directionHint = "INCOME", priority = 3
            ),
            // Alipay
            ParseRuleEntity(
                appPackage = "com.eg.android.AlipayGphone",
                regexAmount = """(?:付款|支付|消费)[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = """(?:向|在)(.+?)(?:付款|支付|消费)""",
                directionHint = "EXPENSE", priority = 1
            ),
            ParseRuleEntity(
                appPackage = "com.eg.android.AlipayGphone",
                regexAmount = """(?:收入|到账)[^\d]*(\d+(?:\.\d{1,2})?)""",
                regexMerchant = null,
                directionHint = "INCOME", priority = 2
            ),
        )
    }
}

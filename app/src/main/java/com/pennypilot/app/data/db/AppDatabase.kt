package com.pennypilot.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pennypilot.app.data.db.dao.BackupDao
import com.pennypilot.app.data.parser.PresetParseRules
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
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun parseRuleDao(): ParseRuleDao
    abstract fun insightDao(): InsightDao
    abstract fun monthlyReportDao(): MonthlyReportDao

    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 4
        const val NAME = "pennypilot.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS parse_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, app_package TEXT NOT NULL, regex_amount TEXT NOT NULL, regex_merchant TEXT, direction_hint TEXT NOT NULL, enabled INTEGER NOT NULL, priority INTEGER NOT NULL)")
                seedRules(db)
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS monthly_reports (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, year_month TEXT NOT NULL, total_expense INTEGER NOT NULL, total_income INTEGER NOT NULL, by_category_json TEXT NOT NULL, llm_summary TEXT NOT NULL, generated_at INTEGER NOT NULL)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN capture_event_key TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_capture_event_key ON transactions(capture_event_key)")
                // 保留同月生成时间最新的报告；时间相同时保留较大的 id。
                db.execSQL("""DELETE FROM monthly_reports WHERE EXISTS (
                    SELECT 1 FROM monthly_reports newer
                    WHERE newer.year_month = monthly_reports.year_month
                    AND (newer.generated_at > monthly_reports.generated_at OR
                        (newer.generated_at = monthly_reports.generated_at AND newer.id > monthly_reports.id)))""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_monthly_reports_year_month ON monthly_reports(year_month)")
                PresetParseRules.upgradeLegacyRules(db)
            }
        }
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun build(context: Context): AppDatabase = builder(context, NAME)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 在创建事务内初始化，避免首条通知早于异步分类 seed。
                    PRESET_CATEGORIES.forEach { c ->
                        db.execSQL("INSERT INTO categories (name, icon, color_index, type, sort_order, enabled, is_preset) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(c.name, c.icon, c.colorIndex, c.type.name, c.sortOrder, 1, 1))
                    }
                    seedRules(db)
                }
            }).build()

        /** 备份临时数据库不插入预置数据；也复用全部非破坏性迁移。 */
        fun builder(context: Context, name: String): Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)

        private fun seedRules(db: SupportSQLiteDatabase) {
            PRESET_RULES.forEach { r ->
                db.execSQL("INSERT INTO parse_rules (app_package, regex_amount, regex_merchant, direction_hint, enabled, priority) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(r.appPackage, r.regexAmount, r.regexMerchant, r.directionHint, if (r.enabled) 1 else 0, r.priority))
            }
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

        val PRESET_RULES get() = PresetParseRules.rules
    }
}

package com.pennypilot.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.pennypilot.app.data.parser.PresetParseRules
import java.io.File

/** 历史版本 1/2/3 的真实表结构，不使用当前实体模拟旧库。 */
internal fun legacyDatabase(context: Context, name: String, version: Int): File {
    val file = context.getDatabasePath(name)
    file.parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amount INTEGER NOT NULL, type TEXT NOT NULL, category_id INTEGER NOT NULL, note TEXT, merchant TEXT, account_tag TEXT, occurred_at INTEGER NOT NULL, created_at INTEGER NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, raw_payload TEXT)")
        db.execSQL("CREATE INDEX index_transactions_occurred_at ON transactions(occurred_at)")
        db.execSQL("CREATE INDEX index_transactions_category_id_occurred_at ON transactions(category_id, occurred_at)")
        db.execSQL("CREATE INDEX index_transactions_status ON transactions(status)")
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, icon TEXT NOT NULL, color_index INTEGER NOT NULL, type TEXT NOT NULL, sort_order INTEGER NOT NULL, enabled INTEGER NOT NULL, is_preset INTEGER NOT NULL)")
        db.execSQL("INSERT INTO categories VALUES (42, '自定义餐饮', '🍜', 0, 'EXPENSE', 0, 1, 0)")
        db.execSQL("INSERT INTO transactions VALUES (77, 1850, 'EXPENSE', 42, '保留备注', '保留商户', '现金', 1770000000000, 1770000000000, 'MANUAL', 'CONFIRMED', NULL)")
        if (version >= 2) {
            db.execSQL("CREATE TABLE parse_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, app_package TEXT NOT NULL, regex_amount TEXT NOT NULL, regex_merchant TEXT, direction_hint TEXT NOT NULL, enabled INTEGER NOT NULL, priority INTEGER NOT NULL)")
            val old = PresetParseRules.legacyRules.first()
            db.execSQL("INSERT INTO parse_rules VALUES (1, ?, ?, ?, ?, 1, ?)", arrayOf(old.appPackage, old.regexAmount, old.regexMerchant, old.directionHint, old.priority))
            db.execSQL("INSERT INTO parse_rules VALUES (2, 'custom', '(custom)', NULL, 'EXPENSE', 0, 7)")
        }
        if (version >= 3) {
            db.execSQL("CREATE TABLE monthly_reports (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, year_month TEXT NOT NULL, total_expense INTEGER NOT NULL, total_income INTEGER NOT NULL, by_category_json TEXT NOT NULL, llm_summary TEXT NOT NULL, generated_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO monthly_reports VALUES (1, '2026-01', 10, 0, '[]', '旧报告', 100)")
            db.execSQL("INSERT INTO monthly_reports VALUES (2, '2026-01', 20, 0, '[]', '最新报告', 200)")
        }
        db.version = version
    }
    return file
}

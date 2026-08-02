package com.pennypilot.app.data.db.entity

import androidx.room.*

@Entity(tableName = "parse_rules")
data class ParseRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "app_package") val appPackage: String,
    @ColumnInfo(name = "regex_amount") val regexAmount: String,
    @ColumnInfo(name = "regex_merchant") val regexMerchant: String? = null,
    @ColumnInfo(name = "direction_hint") val directionHint: String = "EXPENSE", // EXPENSE or INCOME
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "priority") val priority: Int = 0
)

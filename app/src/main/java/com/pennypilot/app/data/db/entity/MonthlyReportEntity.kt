package com.pennypilot.app.data.db.entity

import androidx.room.*

@Entity(tableName = "monthly_reports")
data class MonthlyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "year_month") val yearMonth: String, // "2026-07"
    @ColumnInfo(name = "total_expense") val totalExpense: Long,
    @ColumnInfo(name = "total_income") val totalIncome: Long,
    @ColumnInfo(name = "by_category_json") val byCategoryJson: String,
    @ColumnInfo(name = "llm_summary") val llmSummary: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long = System.currentTimeMillis()
)

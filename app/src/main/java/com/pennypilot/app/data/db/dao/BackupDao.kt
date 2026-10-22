package com.pennypilot.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.data.db.entity.TransactionEntity

data class BackupSnapshot(
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val rules: List<ParseRuleEntity>,
    val reports: List<MonthlyReportEntity>
)

@Dao
interface BackupDao {
    @Query("SELECT * FROM categories")
    suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM transactions")
    suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM parse_rules")
    suspend fun rules(): List<ParseRuleEntity>
    @Query("SELECT * FROM monthly_reports")
    suspend fun reports(): List<MonthlyReportEntity>

    @Transaction
    suspend fun snapshot(): BackupSnapshot =
        BackupSnapshot(categories(), transactions(), rules(), reports())

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()
    @Query("DELETE FROM categories")
    suspend fun clearCategories()
    @Query("DELETE FROM parse_rules")
    suspend fun clearRules()
    @Query("DELETE FROM monthly_reports")
    suspend fun clearReports()
    @Insert
    suspend fun insertCategories(rows: List<CategoryEntity>)
    @Insert
    suspend fun insertTransactions(rows: List<TransactionEntity>)
    @Insert
    suspend fun insertRules(rows: List<ParseRuleEntity>)
    @Insert
    suspend fun insertReports(rows: List<MonthlyReportEntity>)

    /** 通过事务恢复行，保留活动 Room 连接和观察者；任何失败都会回滚原账本。 */
    @Transaction
    suspend fun replace(snapshot: BackupSnapshot) {
        clearTransactions()
        clearReports()
        clearRules()
        clearCategories()
        insertCategories(snapshot.categories)
        insertTransactions(snapshot.transactions)
        insertRules(snapshot.rules)
        insertReports(snapshot.reports)
    }
}

package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlyReportDao {
    /** year_month 唯一索引使同月替换成为单条 SQL 的原子操作。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: MonthlyReportEntity)

    @Query("SELECT * FROM monthly_reports ORDER BY year_month DESC")
    fun observeAll(): Flow<List<MonthlyReportEntity>>

    @Query("SELECT * FROM monthly_reports WHERE year_month = :ym")
    suspend fun getByMonth(ym: String): MonthlyReportEntity?

    @Query("SELECT year_month FROM monthly_reports")
    suspend fun getMonths(): List<String>

    @Query("DELETE FROM monthly_reports WHERE year_month = :ym")
    suspend fun deleteByMonth(ym: String)
}

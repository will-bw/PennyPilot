package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlyReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: MonthlyReportEntity)

    @Query("SELECT * FROM monthly_reports ORDER BY year_month DESC")
    fun observeAll(): Flow<List<MonthlyReportEntity>>

    @Query("SELECT * FROM monthly_reports WHERE year_month = :ym")
    suspend fun getByMonth(ym: String): MonthlyReportEntity?

    @Query("DELETE FROM monthly_reports WHERE year_month = :ym")
    suspend fun deleteByMonth(ym: String)
}

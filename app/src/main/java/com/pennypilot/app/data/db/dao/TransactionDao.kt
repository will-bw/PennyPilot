package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TxStatus)

    @Query("SELECT * FROM transactions WHERE status != 'IGNORED' AND occurred_at BETWEEN :start AND :end ORDER BY occurred_at DESC")
    fun observeByMonth(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'EXPENSE' AND status = 'CONFIRMED' AND occurred_at BETWEEN :start AND :end")
    fun observeMonthExpense(start: Long, end: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'INCOME' AND status = 'CONFIRMED' AND occurred_at BETWEEN :start AND :end")
    fun observeMonthIncome(start: Long, end: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** 统计指定来源在 since 之后入库的笔数（用于捕获服务今日计数回填） */
    @Query("SELECT COUNT(*) FROM transactions WHERE source = :source AND created_at >= :since")
    suspend fun countBySourceSince(source: TxSource, since: Long): Int

    /** 分类被删除时把名下交易改挂到目标分类 */
    @Query("UPDATE transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun reassignCategory(oldId: Long, newId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

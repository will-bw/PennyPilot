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

    /** 唯一 capture_event_key 冲突返回 -1；不覆盖已确认或忽略的原账目。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCaptured(tx: TransactionEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE capture_event_key = :eventKey)")
    suspend fun hasCaptureEvent(eventKey: String): Boolean

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("UPDATE transactions SET status = :status WHERE id = :id AND status = 'PENDING' AND :status IN ('CONFIRMED', 'IGNORED')")
    suspend fun updateStatus(id: Long, status: TxStatus): Int

    /** 通知动作同时绑定事件身份；恢复后复用同一自增 ID 不会改到别的账目。 */
    @Query("UPDATE transactions SET status = :status WHERE id = :id AND capture_event_key = :eventKey AND status = 'PENDING' AND :status IN ('CONFIRMED', 'IGNORED')")
    suspend fun updateCapturedStatus(id: Long, eventKey: String, status: TxStatus): Int

    @Query("SELECT * FROM transactions WHERE status != 'IGNORED' AND occurred_at >= :start AND occurred_at < :end ORDER BY occurred_at DESC")
    fun observeByMonth(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'EXPENSE' AND status = 'CONFIRMED' AND occurred_at >= :start AND occurred_at < :end")
    fun observeMonthExpense(start: Long, end: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'INCOME' AND status = 'CONFIRMED' AND occurred_at >= :start AND occurred_at < :end")
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

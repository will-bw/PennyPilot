package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.db.entity.CaptureEvidenceEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
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

    @Query("SELECT * FROM transactions WHERE capture_event_key = :eventKey LIMIT 1")
    suspend fun getByCaptureEvent(eventKey: String): TransactionEntity?

    @Insert
    suspend fun insertCaptureEvidence(evidence: CaptureEvidenceEntity)

    @Query("SELECT * FROM capture_events WHERE event_key = :eventKey LIMIT 1")
    suspend fun captureEvidenceByKey(eventKey: String): CaptureEvidenceEntity?

    @Query("SELECT * FROM capture_events WHERE order_domain = :domain AND order_id = :orderId ORDER BY observed_at DESC")
    suspend fun captureEvidenceByOrder(domain: String, orderId: String): List<CaptureEvidenceEntity>

    @Query("SELECT * FROM capture_events WHERE package_name = :packageName AND notification_key = :notificationKey AND observed_at BETWEEN :start AND :end ORDER BY observed_at DESC")
    suspend fun captureEvidenceByNotification(packageName: String, notificationKey: String, start: Long, end: Long): List<CaptureEvidenceEntity>

    @Query("SELECT * FROM capture_events WHERE observed_at BETWEEN :start AND :end AND amount = :amount ORDER BY observed_at DESC")
    suspend fun captureEvidenceNear(start: Long, end: Long, amount: Long): List<CaptureEvidenceEntity>

    @Query("SELECT * FROM transactions WHERE source IN ('MANUAL', 'AI_TEXT', 'AI_VOICE') AND status != 'IGNORED' AND occurred_at BETWEEN :start AND :end AND amount = :amount AND type = :type ORDER BY occurred_at DESC, id DESC")
    suspend fun nonCapturedTransactionsNear(start: Long, end: Long, amount: Long, type: TxType): List<TransactionEntity>

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

    @Query("SELECT COUNT(*) FROM transactions WHERE source IN ('NOTIFICATION', 'ACCESSIBILITY') AND created_at >= :since")
    suspend fun countCapturedSince(since: Long): Int

    /** 分类被删除时把名下交易改挂到目标分类 */
    @Query("UPDATE transactions SET category_id = :newId WHERE category_id = :oldId")
    suspend fun reassignCategory(oldId: Long, newId: Long)

    @Query("UPDATE transactions SET duplicate_of = NULL WHERE duplicate_of = :id")
    suspend fun clearDuplicateReferences(id: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    /** 删除候选关联及账目原子提交；capture_events 外键同步清理所有事件别名。 */
    @Transaction
    suspend fun deleteById(id: Long) {
        clearDuplicateReferences(id)
        deleteTransactionById(id)
    }
}

package com.pennypilot.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.pennypilot.app.data.db.entity.TransactionEntity
import kotlinx.serialization.Serializable

/** 按分类聚合结果（金额单位：分） */
@Serializable
data class CategoryAggregate(
    val categoryName: String,
    val total: Long,
    val count: Int
)

/** 按天聚合结果（金额单位：分） */
@Serializable
data class DailyAggregate(
    val day: String,
    val total: Long
)

/**
 * 洞察查询 DAO：为 InsightUseCase 提供聚合 / 明细查询能力。
 */
@Dao
interface InsightDao {

    @Query(
        """
        SELECT c.name as categoryName, COALESCE(SUM(t.amount), 0) as total, COUNT(*) as count
        FROM transactions t JOIN categories c ON t.category_id = c.id
        WHERE t.status = 'CONFIRMED' AND t.occurred_at BETWEEN :start AND :end
        AND (:category IS NULL OR c.name = :category)
        AND (:keyword IS NULL OR t.merchant LIKE '%' || :keyword || '%' OR t.note LIKE '%' || :keyword || '%')
        GROUP BY c.name ORDER BY total DESC
        """
    )
    suspend fun aggregateByCategory(
        start: Long,
        end: Long,
        category: String?,
        keyword: String?
    ): List<CategoryAggregate>

    @Query(
        """
        SELECT DATE(occurred_at / 1000, 'unixepoch', 'localtime') as day, COALESCE(SUM(amount), 0) as total
        FROM transactions
        WHERE status = 'CONFIRMED' AND occurred_at BETWEEN :start AND :end AND type = 'EXPENSE'
        GROUP BY day ORDER BY day
        """
    )
    suspend fun aggregateByDay(start: Long, end: Long): List<DailyAggregate>

    @Query(
        """
        SELECT * FROM transactions
        WHERE status = 'CONFIRMED' AND occurred_at BETWEEN :start AND :end
        AND (:category IS NULL OR category_id IN (SELECT id FROM categories WHERE name = :category))
        AND (:keyword IS NULL OR merchant LIKE '%' || :keyword || '%' OR note LIKE '%' || :keyword || '%')
        ORDER BY occurred_at DESC LIMIT :limit
        """
    )
    suspend fun queryTransactions(
        start: Long,
        end: Long,
        category: String?,
        keyword: String?,
        limit: Int
    ): List<TransactionEntity>

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE status = 'CONFIRMED' AND type = 'EXPENSE' AND occurred_at BETWEEN :start AND :end
        """
    )
    suspend fun totalExpense(start: Long, end: Long): Long
}

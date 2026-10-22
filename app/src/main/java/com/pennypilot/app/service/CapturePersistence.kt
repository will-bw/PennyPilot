package com.pennypilot.app.service

import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity

object CapturePersistence {
    /** 恢复与捕获写入使用同一数据库事务队列，避免旧解析跨恢复写入新账本。 */
    suspend fun insertIfCurrent(
        database: AppDatabase,
        expectedRevision: Long,
        transaction: TransactionEntity
    ): Long = database.withTransaction {
        if (LedgerRevision.current != expectedRevision) return@withTransaction -1L
        database.transactionDao().insertCaptured(transaction)
    }
}

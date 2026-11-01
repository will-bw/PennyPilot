package com.pennypilot.app.data.db

import androidx.room.withTransaction
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus

data class RecognitionUndoItem(val id: Long, val editVersion: Long, val operationId: String)

/** Undo only the unchanged records created by one active-input operation. */
object RecognitionSaveUndo {
    suspend fun undoCaptured(database: AppDatabase, revision: Long, id: Long, eventKey: String): Boolean = database.withTransaction {
        if (revision != LedgerRevision.current) return@withTransaction false
        val dao = database.transactionDao()
        val row = dao.getById(id) ?: return@withTransaction false
        if (row.captureEventKey != eventKey || row.editVersion != 0L || row.status != TxStatus.CONFIRMED ||
            row.source !in setOf(TxSource.NOTIFICATION, TxSource.ACCESSIBILITY)) return@withTransaction false
        dao.deleteById(id)
        true
    }

    suspend fun undo(database: AppDatabase, revision: Long, items: List<RecognitionUndoItem>): Int = database.withTransaction {
        require(revision == LedgerRevision.current) { "账本已恢复，这次自动记账不能再撤销。" }
        require(items.size in 1..100 && items.map { it.id }.distinct().size == items.size) { "没有有效的自动记账记录。" }
        val dao = database.transactionDao()
        val rows = items.map { expected ->
            val row = requireNotNull(dao.getById(expected.id)) { "账单已不存在，请刷新流水。" }
            require(row.source in setOf(TxSource.AI_TEXT, TxSource.AI_VOICE, TxSource.AI_IMAGE) &&
                expected.operationId.isNotBlank() && row.operationKey == expected.operationId && row.editVersion == expected.editVersion) {
                "账单已被修改，无法整批撤销，请在流水中处理。"
            }
            row
        }
        rows.forEach { dao.deleteById(it.id) }
        rows.size
    }
}

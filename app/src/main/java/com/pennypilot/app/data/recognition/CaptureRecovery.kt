package com.pennypilot.app.data.recognition

import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.CaptureTombstoneEntity

/** Resolves a captured event atomically with the ledger, before its encrypted material is removed. */
class CaptureRecovery(private val database: AppDatabase, private val store: RecognitionTaskStore) {
    suspend fun complete(task: RecognitionTask, expectedRevision: Long,
        save: suspend (operationKey: String) -> Long): Long = database.withTransaction {
        checkCurrent(task, expectedRevision)
        require(task.state == RecognitionTaskState.NEEDS_ATTENTION) { "凭证正在识别，请等待结果后再补录。" }
        val dao = database.transactionDao()
        require(dao.captureEvidenceByKey(task.eventKey) == null && dao.getByCaptureEvent(task.eventKey) == null &&
            dao.tombstone(task.eventKey) == null) { "凭证已处理，请回到流水核对。" }
        val id = save("capture-recovery:${task.eventKey}")
        dao.insertTombstones(listOf(CaptureTombstoneEntity(task.eventKey, id, System.currentTimeMillis())))
        id
    }

    suspend fun discard(task: RecognitionTask, expectedRevision: Long) = database.withTransaction {
        checkCurrent(task, expectedRevision)
        val dao = database.transactionDao()
        // If a concurrent local read has just saved it, removing the material must not delete that bill.
        val savedId = dao.captureEvidenceByKey(task.eventKey)?.transactionId ?: dao.getByCaptureEvent(task.eventKey)?.id ?: 0
        dao.insertTombstones(listOf(CaptureTombstoneEntity(task.eventKey, savedId, System.currentTimeMillis())))
    }

    private fun checkCurrent(task: RecognitionTask, expectedRevision: Long) {
        require(LedgerRevision.current == expectedRevision && task.ledgerRevision == expectedRevision) {
            "账本已恢复，请重新打开此页面。"
        }
        require(task.isUnrecognized && store.get(task.id)?.let {
            it.evidenceVersion == task.evidenceVersion && it.state == task.state
        } == true) { "凭证已更新，请刷新列表。" }
    }
}

package com.pennypilot.app.data.recognition

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.service.CaptureEvidence
import com.pennypilot.app.service.CapturePersistence
import com.pennypilot.app.service.CaptureResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureRecoveryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    private val file = File.createTempFile("capture-recovery", ".enc").apply { delete(); deleteOnExit() }
    private val store = RecognitionTaskStore(file, object : TaskCipher {
        override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 47).toByte() }.toByteArray()
        override fun decrypt(bytes: ByteArray) = encrypt(bytes)
    })
    private val recovery = CaptureRecovery(database, store)
    private val observedAt = System.currentTimeMillis()
    private val task = RecognitionTask("receipt", "payment-event", "com.tencent.mm", "支付成功\n¥18.00",
        observedAt, LedgerRevision.current, "model-A", "ACCESSIBILITY")
    private fun transaction(source: TxSource = TxSource.MANUAL, operationKey: String? = null) = TransactionEntity(
        amount = 1800, type = TxType.EXPENSE, categoryId = 1, occurredAt = observedAt,
        source = source, businessKind = "expense", operationKey = operationKey)
    private fun review(): RecognitionTask {
        store.enqueue(task)
        store.needsAttention(task.id, task.evidenceVersion, RecognitionFailure.NETWORK)
        return store.get(task.id)!!
    }
    private suspend fun replay() = CapturePersistence.capture(database, LedgerRevision.current,
        transaction(TxSource.ACCESSIBILITY),
        CaptureEvidence(task.packageName, task.eventKey, observedAt, task.text))

    @After fun close() {
        database.close()
        store.clear()
    }

    @Test fun `manual recovery and event resolution commit together and block delayed capture`() = runTest {
        val failed = review()
        val id = recovery.complete(failed, LedgerRevision.current) { operationKey ->
            database.transactionDao().insert(transaction(operationKey = operationKey))
        }
        assertEquals("capture-recovery:${task.eventKey}", database.transactionDao().getById(id)!!.operationKey)
        assertEquals(id, database.transactionDao().tombstone(task.eventKey)!!.transactionId)
        // Leave encrypted material in place, as if the process died before its cleanup.
        assertNotNull(store.get(task.id))
        assertEquals(CaptureResult.Duplicate(id), replay())
        assertEquals(1, database.transactionDao().observeByMonth(observedAt - 1, observedAt + 1).first().size)
    }

    @Test fun `a failure after inserting manual bill rolls back both bill and event resolution`() = runTest {
        val failed = review()
        try {
            recovery.complete(failed, LedgerRevision.current) { operationKey ->
                database.transactionDao().insert(transaction(operationKey = operationKey))
                error("save interrupted")
            }
            fail("transaction must fail")
        } catch (_: IllegalStateException) { }
        assertTrue(database.transactionDao().observeByMonth(observedAt - 1, observedAt + 1).first().isEmpty())
        assertNull(database.transactionDao().tombstone(task.eventKey))
        assertNotNull(store.get(task.id))
    }

    @Test fun `a local result saved before manual commit prevents a second transaction`() = runTest {
        val failed = review()
        val captured = replay() as CaptureResult.Inserted
        var called = false
        try {
            recovery.complete(failed, LedgerRevision.current) { called = true; database.transactionDao().insert(transaction()) }
            fail("already saved event")
        } catch (_: IllegalArgumentException) { }
        assertFalse(called)
        assertNotNull(database.transactionDao().getById(captured.transactionId))
        assertEquals(1, database.transactionDao().observeByMonth(observedAt - 1, observedAt + 1).first().size)
    }

    @Test fun `new evidence version and restored ledger reject an old manual form`() = runTest {
        val failed = review()
        store.retry(task.id, task.evidenceVersion)
        try {
            recovery.complete(failed, LedgerRevision.current) { fail("stale form cannot save"); 0 }
            fail("stale evidence")
        } catch (_: IllegalArgumentException) { }
        try {
            recovery.discard(store.get(task.id)!!, LedgerRevision.current + 1)
            fail("stale ledger")
        } catch (_: IllegalArgumentException) { }
        assertNotNull(store.get(task.id))
        assertNull(database.transactionDao().tombstone(task.eventKey))
    }

    @Test fun `discarding queued material blocks a later result without creating a bill`() = runTest {
        store.enqueue(task)
        recovery.discard(task, LedgerRevision.current)
        assertEquals(CaptureResult.Duplicate(0), replay())
        assertTrue(database.transactionDao().observeByMonth(observedAt - 1, observedAt + 1).first().isEmpty())
    }

    @Test fun `discard never deletes a concurrent saved transaction`() = runTest {
        store.enqueue(task)
        val captured = replay() as CaptureResult.Inserted
        recovery.discard(task, LedgerRevision.current)
        assertNotNull(database.transactionDao().getById(captured.transactionId))
        assertEquals(CaptureResult.Duplicate(captured.transactionId), replay())
    }
}

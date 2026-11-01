package com.pennypilot.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecognitionSaveUndoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun row(key: String, source: TxSource = TxSource.AI_TEXT) = TransactionEntity(
        amount = 1700, type = TxType.EXPENSE, categoryId = 1, occurredAt = System.currentTimeMillis(),
        source = source, businessKind = "expense", operationKey = key)

    @Test fun `undo removes only the last unchanged operation and prevents replay`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.transactionDao()
            val first = dao.insert(row("first"))
            val other = dao.insert(row("other", TxSource.MANUAL))
            assertEquals(1, RecognitionSaveUndo.undo(database, LedgerRevision.current, listOf(RecognitionUndoItem(first, 0, "first"))))
            assertNull(dao.getById(first))
            assertNotNull(dao.getById(other))
            assertNotNull(dao.tombstone("operation:first"))
        } finally { database.close() }
    }

    @Test fun `one edited record prevents the whole undo without deleting another record`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.transactionDao()
            val first = dao.insert(row("first"))
            val second = dao.insert(row("second").copy(editVersion = 1))
            try {
                RecognitionSaveUndo.undo(database, LedgerRevision.current,
                    listOf(RecognitionUndoItem(first, 0, "first"), RecognitionUndoItem(second, 0, "second")))
                fail("edited operation must not be undone")
            } catch (_: IllegalArgumentException) { }
            assertNotNull(dao.getById(first))
            assertNotNull(dao.getById(second))
            assertNull(dao.tombstone("operation:first"))
        } finally { database.close() }
    }

    @Test fun `a stale ledger revision cannot undo a current record`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = database.transactionDao().insert(row("first"))
            try {
                RecognitionSaveUndo.undo(database, LedgerRevision.current + 1, listOf(RecognitionUndoItem(id, 0, "first")))
                fail("stale receipt must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertNotNull(database.transactionDao().getById(id))
        } finally { database.close() }
    }

    @Test fun `captured undo is bound to its event revision and unedited record`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.transactionDao()
            val first = dao.insert(row("capture", TxSource.ACCESSIBILITY).copy(captureEventKey = "capture-event"))
            assertFalse(RecognitionSaveUndo.undoCaptured(database, LedgerRevision.current + 1, first, "capture-event"))
            assertFalse(RecognitionSaveUndo.undoCaptured(database, LedgerRevision.current, first, "other-event"))
            assertTrue(RecognitionSaveUndo.undoCaptured(database, LedgerRevision.current, first, "capture-event"))
            assertNotNull(dao.tombstone("capture-event"))
            val edited = dao.insert(row("edited", TxSource.NOTIFICATION).copy(captureEventKey = "edited-event", editVersion = 1))
            assertFalse(RecognitionSaveUndo.undoCaptured(database, LedgerRevision.current, edited, "edited-event"))
            assertNotNull(dao.getById(edited))
        } finally { database.close() }
    }
}

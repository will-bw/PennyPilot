package com.pennypilot.app.domain.usecase

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.legacyDatabase
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.service.CaptureEvidence
import com.pennypilot.app.service.CapturePersistence
import com.pennypilot.app.service.CaptureResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupUseCaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var useCase: BackupUseCase
    private lateinit var directory: File

    @Before fun setUp() {
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        db = AppDatabase.builder(context, File(directory, "live.db").absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        useCase = BackupUseCase(db)
    }
    @After fun tearDown() { db.close(); directory.deleteRecursively() }

    private suspend fun insert(amount: Long = 1850): Long {
        if (db.categoryDao().getById(1) == null) {
            db.categoryDao().insert(CategoryEntity(id = 1, name = "餐饮", icon = "🍜", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
        }
        return db.transactionDao().insert(TransactionEntity(amount = amount, type = TxType.EXPENSE, categoryId = 1, occurredAt = 1770000000000))
    }

    @Test fun `export includes committed WAL rows in a standalone file`() = runTest {
        insert()
        assertTrue(File(directory, "live.db-wal").length() > 0)
        val target = File(directory, "export.db")
        useCase.exportToFile(context, target)
        assertFalse(File(target.path + "-wal").exists())
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            copy.rawQuery("SELECT SUM(amount) FROM transactions", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1850L, it.getLong(0))
            }
        }
    }

    @Test fun `restore replaces ledger atomically and existing DAO remains usable`() = runTest {
        val id = insert()
        val target = File(directory, "export.db")
        useCase.exportToFile(context, target)
        insert(3000)
        val existingDao = db.transactionDao()
        useCase.restoreFromFile(context, target)
        assertEquals(listOf(id), db.backupDao().transactions().map { it.id })
        assertEquals(1850L, existingDao.getById(id)!!.amount)
        insert(500)
        assertEquals(2, db.backupDao().transactions().size)
    }

    @Test fun `invalid backup cannot change live ledger`() = runTest {
        val id = insert()
        val invalid = File(directory, "invalid.db").apply { writeText("not a SQLite database") }
        assertTrue(runCatching { useCase.restoreFromFile(context, invalid) }.isFailure)
        assertEquals(1850L, db.transactionDao().getById(id)!!.amount)
    }

    @Test fun `restore migrates a legacy database before touching live ledger`() = runTest {
        insert(3000)
        val name = "legacy-${UUID.randomUUID()}.db"
        val source = legacyDatabase(context, name, 1)
        try {
            useCase.restoreFromFile(context, source)
            assertEquals(1850L, db.transactionDao().getById(77)!!.amount)
            assertEquals("自定义餐饮", db.categoryDao().getById(42)!!.name)
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `failed replacement rolls back deletes and partial inserts`() = runTest {
        val id = insert()
        val snapshot = db.backupDao().snapshot()
        val conflicting = snapshot.copy(transactions = listOf(snapshot.transactions.single(), snapshot.transactions.single()))
        assertTrue(runCatching { db.backupDao().replace(conflicting) }.isFailure)
        assertEquals(1850L, db.transactionDao().getById(id)!!.amount)
        assertEquals(1, db.backupDao().transactions().size)
    }

    @Test fun `legacy paired WAL is replayed before restore`() = runTest {
        insert(1850)
        val incoming = File(directory, "incoming.db")
        File(directory, "live.db").copyTo(incoming)
        File(directory, "live.db-wal").copyTo(File(incoming.path + "-wal"))
        insert(3000)
        useCase.restoreFromFile(context, incoming)
        assertEquals(1850L, db.backupDao().transactions().single().amount)
    }

    @Test fun `backup preserves capture evidence ignored status and duplicate links`() = runTest {
        val timestamp = 1770000000000L
        val captured = TransactionEntity(amount = 1800, type = TxType.EXPENSE, categoryId = 1,
            merchant = "小王面馆", occurredAt = timestamp, source = TxSource.NOTIFICATION)
        val evidence = CaptureEvidence("com.sankuai.meituan", "order-notification", timestamp,
            "小王面馆支付成功18元", notificationKey = "notice-1")
        val first = CapturePersistence.capture(db, LedgerRevision.current, captured, evidence) as CaptureResult.Inserted
        db.transactionDao().updateStatus(first.transactionId, TxStatus.IGNORED)
        val second = CapturePersistence.capture(db, LedgerRevision.current,
            captured.copy(source = TxSource.ACCESSIBILITY), evidence.copy(eventKey = "page", notificationKey = null)) as CaptureResult.Inserted
        val target = File(directory, "capture-export.db")
        useCase.exportToFile(context, target)
        val oldRevision = LedgerRevision.current
        db.transactionDao().deleteById(first.transactionId)
        assertEquals(1, db.backupDao().captureEvents().size)
        useCase.restoreFromFile(context, target)
        assertTrue(LedgerRevision.current > oldRevision)
        assertEquals(2, db.backupDao().captureEvents().size)
        assertEquals(TxStatus.IGNORED, db.transactionDao().getById(first.transactionId)!!.status)
        assertEquals(first.transactionId, db.transactionDao().getById(second.transactionId)!!.duplicateOf)
        assertEquals(CaptureResult.StaleRevision,
            CapturePersistence.capture(db, oldRevision, captured, evidence.copy(eventKey = "stale")))
        assertEquals(CaptureResult.Duplicate(first.transactionId),
            CapturePersistence.capture(db, LedgerRevision.current, captured, evidence))
    }
}

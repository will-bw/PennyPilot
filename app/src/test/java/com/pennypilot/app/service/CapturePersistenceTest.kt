package com.pennypilot.app.service

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapturePersistenceTest {
    @Test
    fun `notification parsing started before restore cannot write into restored ledger`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val parsedUnderRevision = LedgerRevision.current
            db.withTransaction { LedgerRevision.advance() }
            val tx = TransactionEntity(amount = 1800, type = TxType.EXPENSE, categoryId = 1,
                occurredAt = 1000, source = TxSource.NOTIFICATION, status = TxStatus.PENDING,
                captureEventKey = "event-before-restore")
            assertEquals(-1L, CapturePersistence.insertIfCurrent(db, parsedUnderRevision, tx))
            assertFalse(db.transactionDao().hasCaptureEvent(tx.captureEventKey!!))
            // 新账本上的新尝试可以落库，不会被旧解析留下的标记吞掉。
            assertTrue(CapturePersistence.insertIfCurrent(db, LedgerRevision.current, tx) > 0)
            assertEquals(-1L, CapturePersistence.insertIfCurrent(db, LedgerRevision.current, tx))
        } finally {
            db.close()
        }
    }
}

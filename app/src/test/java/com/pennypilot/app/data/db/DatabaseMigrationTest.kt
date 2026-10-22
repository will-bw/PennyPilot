package com.pennypilot.app.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.parser.PresetParseRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `version one upgrades without losing ledger`() = verifyMigration(1)
    @Test fun `version two upgrades and preserves edited rules`() = verifyMigration(2)
    @Test fun `version three upgrades and keeps newest duplicate report`() = verifyMigration(3)

    private fun verifyMigration(version: Int) = runTest {
        val name = "migration-${UUID.randomUUID()}.db"
        legacyDatabase(context, name, version)
        val db = AppDatabase.builder(context, name).build()
        try {
            val tx = db.transactionDao().getById(77)!!
            assertEquals(1850L, tx.amount)
            assertEquals(42L, tx.categoryId)
            assertEquals("保留备注", tx.note)
            assertNull(tx.captureEventKey)
            assertEquals("自定义餐饮", db.categoryDao().getById(42)!!.name)
            assertEquals(AppDatabase.VERSION, db.openHelper.writableDatabase.version)
            if (version >= 2) {
                val rules = db.backupDao().rules()
                assertEquals(PresetParseRules.rules.first().regexAmount, rules.first { it.id == 1L }.regexAmount)
                assertEquals("(custom)", rules.first { it.id == 2L }.regexAmount)
                assertFalse(rules.first { it.id == 2L }.enabled)
            } else assertEquals(5, db.backupDao().rules().size)
            if (version == 3) {
                val reports = db.backupDao().reports()
                assertEquals(1, reports.size)
                assertEquals("最新报告", reports.single().llmSummary)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}

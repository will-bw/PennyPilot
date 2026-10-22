package com.pennypilot.app.data.parser

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresetParseRulesUpgradeTest {
    @Test
    fun `upgrade changes only untouched presets and preserves rule IDs and edits`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java).build()
        try {
            val dao = db.parseRuleDao()
            PresetParseRules.legacyRules.forEach { dao.insert(it) }
            val edited = PresetParseRules.legacyRules[0].copy(regexAmount = """自定义(\d+)""")
            val editedId = dao.insert(edited)
            val disabled = PresetParseRules.legacyRules[0].copy(enabled = false)
            val disabledId = dao.insert(disabled)
            val reordered = PresetParseRules.legacyRules[0].copy(priority = 99)
            val reorderedId = dao.insert(reordered)

            PresetParseRules.upgradeLegacyRules(db.openHelper.writableDatabase)
            val result = dao.observeAll().first().associateBy { it.id }
            PresetParseRules.rules.forEachIndexed { index, rule ->
                assertEquals(rule.copy(id = index + 1L), result[index + 1L])
            }
            assertEquals(edited.copy(id = editedId), result[editedId])
            assertEquals(disabled.copy(id = disabledId), result[disabledId])
            assertEquals(reordered.copy(id = reorderedId), result[reorderedId])
            // 重复迁移不会追加规则或修改已经升级的行。
            PresetParseRules.upgradeLegacyRules(db.openHelper.writableDatabase)
            assertEquals(result, dao.observeAll().first().associateBy { it.id })
        } finally {
            db.close()
        }
    }
}

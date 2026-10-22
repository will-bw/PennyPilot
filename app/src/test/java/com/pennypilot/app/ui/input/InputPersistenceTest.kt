package com.pennypilot.app.ui.input

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.*
import com.pennypilot.app.domain.model.*
import com.pennypilot.app.domain.usecase.ParseTextUseCase
import com.pennypilot.app.ui.edit.EditTransactionViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InputPersistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val categories = TestCategories()
    private val transactions = TestTransactions()
    private val response = """{"transactions":[{"amount":2500,"type":"EXPENSE","category":"餐饮"}]}"""

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun <T : ViewModel> keep(vm: T): T = vm.also { store.put("vm", it) }
    private fun input(client: LLMClient) = keep(AiInputViewModel(
        ApplicationProvider.getApplicationContext(), ParseTextUseCase(client, categories), transactions, categories
    ))
    private fun client(block: suspend () -> String) = object : LLMClient {
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean) = block()
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("unused")
    }

    @Test fun `new draft survives delayed parsing and a second send cannot race`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        var calls = 0
        val vm = input(client { calls++; pending.await() })
        vm.updateInput("午饭25")
        vm.sendForParsing()
        runCurrent()
        vm.updateInput("打车30")
        vm.sendForParsing()
        runCurrent()
        assertEquals(1, calls)
        pending.complete(response)
        runCurrent()
        assertEquals("打车30", vm.state.value.inputText)
        assertEquals(2500L, vm.state.value.previewItems.single().amount)
        assertFalse(vm.state.value.isParsing)
    }

    @Test fun `parse failure restores original only when user has not entered another draft`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        val vm = input(client { pending.await() })
        vm.updateInput("午饭25")
        vm.sendForParsing()
        runCurrent()
        pending.completeExceptionally(IllegalStateException("offline"))
        runCurrent()
        assertEquals("午饭25", vm.state.value.inputText)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `manual double save inserts once and failure remains retryable`() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        transactions.beforeInsert = { pending.await() }
        val vm = input(client { response })
        var closed = 0
        vm.saveManual(TxType.EXPENSE, 1, 1000, "保留备注") { closed++ }
        vm.saveManual(TxType.EXPENSE, 1, 1000, "保留备注") { closed++ }
        runCurrent()
        assertTrue(vm.state.value.isSaving)
        assertEquals(1, transactions.attempts)
        pending.completeExceptionally(IllegalStateException("disk full"))
        runCurrent()
        assertFalse(vm.state.value.isSaving)
        assertNotNull(vm.state.value.error)
        assertEquals(0, closed)
        assertTrue(transactions.saved.isEmpty())
        transactions.beforeInsert = {}
        vm.saveManual(TxType.EXPENSE, 1, 1000, "保留备注") { closed++ }
        runCurrent()
        assertEquals("保留备注", transactions.saved.single().note)
        assertEquals(1, closed)
    }

    @Test fun `parse failure never restores stale text over a newer draft`() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        val vm = input(client { pending.await() })
        vm.updateInput("午饭25")
        vm.sendForParsing()
        runCurrent()
        vm.updateInput("打车30")
        pending.completeExceptionally(IllegalStateException("offline"))
        runCurrent()
        assertEquals("打车30", vm.state.value.inputText)
        assertFalse(vm.state.value.isParsing)
    }

    @Test fun `batch failure keeps unsaved previews and retry does not duplicate completed items`() = runTest(dispatcher) {
        val twoItems = """{"transactions":[{"amount":2500,"type":"EXPENSE","category":"餐饮"},{"amount":3000,"type":"EXPENSE","category":"餐饮"}]}"""
        val vm = input(client { twoItems })
        vm.updateInput("午饭25晚饭30")
        vm.sendForParsing()
        runCurrent()
        transactions.beforeInsert = { if (transactions.attempts == 2) error("disk full") }
        vm.saveAll { fail("Batch has an unsaved item") }
        runCurrent()
        assertEquals(1, transactions.saved.size)
        assertEquals(3000L, vm.state.value.previewItems.single().amount)
        assertFalse(vm.state.value.isSaving)
        transactions.beforeInsert = {}
        vm.saveAll {}
        runCurrent()
        assertEquals(listOf(2500L, 3000L), transactions.saved.map { it.amount })
        assertTrue(vm.state.value.previewItems.isEmpty())
    }

    @Test fun `edit validates amount and prevents duplicate inserts`() = runTest(dispatcher) {
        val vm = keep(EditTransactionViewModel(transactions, categories, SavedStateHandle()))
        runCurrent()
        listOf("-10", "NaN", "Infinity", "0", "1.001").forEach {
            vm.updateAmount(it)
            vm.save { fail("Invalid amount must not save") }
        }
        runCurrent()
        assertEquals(0, transactions.attempts)
        val pending = CompletableDeferred<Unit>()
        transactions.beforeInsert = { pending.await() }
        vm.updateAmount("4.35")
        var done = 0
        vm.save { done++ }
        vm.save { done++ }
        runCurrent()
        assertEquals(1, transactions.attempts)
        pending.complete(Unit)
        runCurrent()
        assertEquals(435L, transactions.saved.single().amount)
        assertEquals(1, done)
    }

    @Test fun `successive parses append previews and edits target stable draft identity`() = runTest(dispatcher) {
        val vm = input(client { response })
        vm.updateInput("午饭25")
        vm.sendForParsing()
        runCurrent()
        val first = vm.state.value.previewItems.single()
        vm.updateInput("晚饭25")
        vm.sendForParsing()
        runCurrent()
        assertEquals(2, vm.state.value.previewItems.size)
        assertNotEquals(first.draftId, vm.state.value.previewItems.last().draftId)
        assertTrue(vm.updatePreviewItem(first.copy(amount = 4200, merchant = "修改后的商家")))
        assertEquals(listOf(4200L, 2500L), vm.state.value.previewItems.map { it.amount })
        vm.saveAll {}
        runCurrent()
        assertEquals(listOf(4200L, 2500L), transactions.saved.map { it.amount })
        assertEquals("修改后的商家", transactions.saved.first().merchant)
    }

    @Test fun `manual selected date is persisted instead of save time`() = runTest(dispatcher) {
        val vm = input(client { response })
        vm.saveManual(TxType.EXPENSE, 1, 1000, "补记", occurredAt = 1234567890000) {}
        runCurrent()
        assertEquals(1234567890000, transactions.saved.single().occurredAt)
    }

    @Test fun `editing an archived category retains its identity`() = runTest(dispatcher) {
        categories.cats = categories.cats + CategoryEntity(id = 2, name = "旧分类", icon = "旧", colorIndex = 1,
            type = TxType.EXPENSE, sortOrder = 1, enabled = false)
        transactions.saved += TransactionEntity(id = 5, amount = 2500, type = TxType.EXPENSE,
            categoryId = 2, occurredAt = 1234567890000)
        val vm = keep(EditTransactionViewModel(transactions, categories, SavedStateHandle(mapOf("id" to 5L))))
        runCurrent()
        assertEquals(2L, vm.state.value.selectedCategoryId)
        assertFalse(vm.state.value.hasChanges)
        vm.updateNote("只改备注")
        assertTrue(vm.state.value.hasChanges)
        vm.save {}
        runCurrent()
        assertEquals(2L, transactions.updated?.categoryId)
        assertEquals("只改备注", transactions.updated?.note)
    }

    private class TestCategories : CategoryDao {
        var cats = listOf(CategoryEntity(id = 1, name = "餐饮", icon = "餐", colorIndex = 0, type = TxType.EXPENSE, sortOrder = 0))
        override fun observeAll() = flowOf(cats)
        override fun observeEnabled() = flowOf(cats)
        override fun observeByType(type: TxType) = flowOf(cats.filter { it.type == type })
        override suspend fun getById(id: Long) = cats.find { it.id == id }
        override suspend fun insert(cat: CategoryEntity) = 0L
        override suspend fun insertAll(cats: List<CategoryEntity>) {}
        override suspend fun update(cat: CategoryEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun maxColorIndex() = 0
    }

    private class TestTransactions : TransactionDao {
        var beforeInsert: suspend () -> Unit = {}
        var attempts = 0
        var updated: TransactionEntity? = null
        val saved = mutableListOf<TransactionEntity>()
        override suspend fun insert(tx: TransactionEntity): Long {
            attempts++
            beforeInsert()
            saved += tx
            return saved.size.toLong()
        }
        override suspend fun insertCaptured(tx: TransactionEntity) = insert(tx)
        override suspend fun hasCaptureEvent(eventKey: String) = false
        override suspend fun update(tx: TransactionEntity) { updated = tx }
        override suspend fun updateStatus(id: Long, status: TxStatus) = 0
        override suspend fun updateCapturedStatus(id: Long, eventKey: String, status: TxStatus) = 0
        override suspend fun getById(id: Long) = saved.firstOrNull { it.id == id }
        override suspend fun countBySourceSince(source: TxSource, since: Long) = 0
        override suspend fun reassignCategory(oldId: Long, newId: Long) {}
        override suspend fun deleteById(id: Long) {}
        override fun observeByMonth(start: Long, end: Long) = flowOf(saved.toList())
        override fun observeMonthExpense(start: Long, end: Long) = flowOf(0L)
        override fun observeMonthIncome(start: Long, end: Long) = flowOf(0L)
        override fun observePendingCount() = flowOf(0)
    }
}

package com.pennypilot.app.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.llm.*
import com.pennypilot.app.domain.usecase.InsightUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var db: AppDatabase
    private class Client : LLMClient {
        var fail = false
        var pending: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<List<Message>>()
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean) = "答案"
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools {
            requests += messages
            pending?.await()
            if (fail) error("network unavailable")
            return ChatResponseWithTools(listOf(ChoiceWithTools(AssistantMessage(content = "根据账目回答"))))
        }
    }
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }
    @After fun tearDown() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun vm(client: Client) = ChatViewModel(InsightUseCase(client, db.insightDao())).also { store.put("chat", it) }

    @Test fun `retry does not repeat question or submit error text and preserves next draft`() = runTest(dispatcher) {
        val client = Client().apply { fail = true }
        val vm = vm(client)
        vm.updateInput("本月支出")
        vm.send()
        runCurrent()
        assertEquals("本月支出", vm.state.value.failedQuestion)
        assertTrue(vm.state.value.messages.last().isError)
        vm.updateInput("下一个问题")
        client.fail = false
        vm.retry()
        runCurrent()
        assertEquals("下一个问题", vm.state.value.inputText)
        assertEquals(2, vm.state.value.messages.size)
        assertFalse(vm.state.value.messages.last().isError)
        assertNull(vm.state.value.failedQuestion)
        assertEquals(listOf("本月支出"), client.requests.last().filter { it.role == "user" }.map { it.content })
        assertFalse(client.requests.last().any { it.content.contains("查询出错") })
    }

    @Test fun `suggestion and delayed response keep typed draft and block duplicate sends`() = runTest(dispatcher) {
        val client = Client().apply { pending = CompletableDeferred() }
        val vm = vm(client)
        vm.updateInput("我的草稿")
        vm.send("餐饮占比多少")
        runCurrent()
        vm.send("重复问题")
        vm.clearConversation()
        assertEquals(1, client.requests.size)
        assertEquals("我的草稿", vm.state.value.inputText)
        client.pending!!.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertEquals(2, vm.state.value.messages.size)
        vm.clearConversation()
        assertEquals("我的草稿", vm.state.value.inputText)
        assertTrue(vm.state.value.messages.isEmpty())
    }
}

package com.pennypilot.app.data.recognition

import android.app.Application
import com.pennypilot.app.data.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecognitionTaskRunnerTest {
    private val file = File.createTempFile("task-runner", ".enc").apply { delete(); deleteOnExit() }
    private val cipher = object : TaskCipher {
        override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 63).toByte() }.toByteArray()
        override fun decrypt(bytes: ByteArray) = encrypt(bytes)
    }
    private val store = RecognitionTaskStore(file, cipher)
    private var config = "A"
    private var allowed = true
    private var calls = 0
    private var commits = 0
    private var response: suspend () -> String = { """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"¥18.00","direction_evidence":"转账成功","status_evidence":"转账成功"}""" }
    private val client = object : LLMClient {
        override suspend fun operationIdentity(role: ModelRole) = config
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String { calls++; return response() }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("Models must never receive writing tools")
    }
    private val reports = mutableListOf<String>()
    private fun enqueue() { store.enqueue(RecognitionTask("task", "event", "com.tencent.mm", "转账成功\n¥18.00\n待对方收款", System.currentTimeMillis(), 0, "A", "ACCESSIBILITY")) }
    private fun runner(restored: RecognitionTaskStore = store) = RecognitionTaskRunner(restored, client, { allowed }, { _, _, _, isCurrent -> if (isCurrent()) { commits++; true } else false }, reports::add)
    @Test fun `recreated worker retries and commits same event once`() = runTest {
        enqueue(); response = { error("offline") }
        assertFalse(runner().run("task")); assertEquals(1, store.get("task")!!.attempts); assertEquals(0, commits)
        response = { """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"¥18.00","direction_evidence":"转账成功","status_evidence":"转账成功"}""" }
        assertTrue(runner(RecognitionTaskStore(file, cipher)).run("task")); assertEquals(1, commits)
        assertTrue(runner().run("task")); assertEquals(1, commits); assertEquals(2, calls)
    }
    @Test fun `configuration switch before execution never sends old data`() = runTest {
        enqueue(); config = "B"; assertTrue(runner().run("task")); assertEquals(0, calls); assertEquals(0, commits); assertNull(store.get("task"))
    }
    @Test fun `configuration switch during inference never commits`() = runTest {
        enqueue(); val old = response; response = { config = "B"; old() }
        assertTrue(runner().run("task")); assertEquals(1, calls); assertEquals(0, commits); assertNull(store.get("task"))
    }
    @Test fun `ledger restore or consent revocation drops delayed result`() = runTest {
        enqueue(); val old = response; response = { allowed = false; old() }
        assertTrue(runner().run("task")); assertEquals(0, commits); assertNull(store.get("task"))
    }
    @Test fun `permanent network failure is bounded and reported`() = runTest {
        enqueue(); response = { error("offline") }
        repeat(3) { assertFalse(runner().run("task")) }
        assertTrue(runner().run("task")); assertEquals(3, calls); assertEquals(0, commits); assertNull(store.get("task")); assertTrue(reports.last().contains("重试未完成"))
    }
    @Test fun `uncertain and injected models cannot write`() = runTest {
        enqueue(); response = { """{"valid":true,"amount_cents":9900,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"99元","direction_evidence":"转账成功","status_evidence":"转账成功"}""" }
        assertTrue(runner().run("task")); assertEquals(0, commits); assertNull(store.get("task"))
    }
    @Test fun `cancelled task retains encrypted record for OS restart`() = runTest {
        enqueue(); response = { throw CancellationException("stopped") }
        try { runner().run("task"); fail() } catch (_: CancellationException) { }
        assertNotNull(RecognitionTaskStore(file, cipher).get("task")); assertEquals(0, commits)
    }
    @Test fun `late evidence supersedes in flight proposal without deleting new job`() = runTest {
        enqueue(); val old = response
        response = {
            val original = store.get("task")!!
            store.enqueue(original.copy(text = original.text + "\n收款方：小林"))
            old()
        }
        assertFalse(runner().run("task")); assertEquals(0, commits)
        assertEquals(2, store.get("task")!!.evidenceVersion)
        response = old
        assertTrue(runner().run("task")); assertEquals(1, commits); assertNull(store.get("task"))
    }

    @Test fun `evidence changing while writer is suspended prevents old amount commit`() = runTest {
        enqueue()
        val enteredWriter = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resumeWriter = kotlinx.coroutines.CompletableDeferred<Unit>()
        val guardedRunner = RecognitionTaskRunner(store, client, { allowed }, { _, _, _, isCurrent ->
            enteredWriter.complete(Unit)
            resumeWriter.await() // Mirrors category/preferences/Room suspension before the final guarded insert.
            if (isCurrent()) { commits++; true } else false
        }, reports::add)
        val result = async { guardedRunner.run("task") }
        enteredWriter.await()
        val original = store.get("task")!!
        store.enqueue(original.copy(text = "转账成功\n¥28.00\n待对方收款"))
        resumeWriter.complete(Unit)
        assertFalse(result.await()); assertEquals(0, commits)
        assertEquals(2, store.get("task")!!.evidenceVersion)
        assertTrue(store.get("task")!!.text.contains("28.00"))
    }

}

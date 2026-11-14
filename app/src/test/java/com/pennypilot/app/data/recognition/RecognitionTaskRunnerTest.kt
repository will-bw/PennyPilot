package com.pennypilot.app.data.recognition

import android.app.Application
import com.pennypilot.app.data.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    private var identityUnavailable = false
    private var response: suspend () -> String = { """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"¥18.00","direction_evidence":"转账成功","status_evidence":"转账成功"}""" }
    private val client = object : LLMClient {
        override suspend fun operationIdentity(role: ModelRole): String {
            check(!identityUnavailable) { "account unavailable" }
            return config
        }
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean): String { calls++; return response() }
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("Models must never receive writing tools")
    }
    private val reports = mutableListOf<String>()
    private val states = mutableListOf<String>()
    private fun enqueue() { store.enqueue(RecognitionTask("task", "event", "com.tencent.mm", "转账成功\n¥18.00\n待对方收款", System.currentTimeMillis(), 0, "A", "ACCESSIBILITY")) }
    private fun runner(restored: RecognitionTaskStore = store) = RecognitionTaskRunner(restored, client, { allowed }, { _, _, _, isCurrent -> if (isCurrent()) { commits++; true } else false }, reports::add,
        onState = { _, state -> states += state })
    private fun addEvidence() {
        val original = store.get("task")!!
        assertEquals(RecognitionTaskStore.EnqueueResult.UPDATED,
            store.enqueue(original.copy(text = original.text + "\n收款方：小林")))
    }
    private fun assertSuccessorPreserved() {
        assertEquals(2, store.get("task")!!.evidenceVersion)
        assertEquals(0, store.get("task")!!.attempts)
        assertEquals(0, commits)
        assertTrue(reports.isEmpty())
        assertEquals("model_superseded", states.last())
        assertFalse(states.contains("model_failed"))
    }
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
    @Test fun `consent revoked while reading semantic context stops before sending`() = runTest {
        enqueue()
        val guarded = RecognitionTaskRunner(store, client, { allowed },
            { _, _, _, _ -> commits++; true }, reports::add,
            semanticContext = {
                allowed = false
                CaptureSemanticContext(relatedHistory = listOf(CaptureSemanticHistory("小林", "EXPENSE", "expense", 1)))
            })
        assertTrue(guarded.run("task"))
        assertEquals(0, calls)
        assertEquals(0, commits)
        assertNull(store.get("task"))
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
        repeat(2) { assertFalse(runner().run("task")) }
        assertTrue(runner().run("task")); assertEquals(3, calls); assertEquals(0, commits)
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, store.get("task")!!.state)
        assertEquals(RecognitionFailure.NETWORK, store.get("task")!!.failure)
        assertTrue(reports.last().contains("未识别凭证"))
        assertEquals("model_exhausted", states.last())
        repeat(3) { assertTrue(runner().run("task")) }
        assertEquals(3, calls)
    }
    @Test fun `uncertain and injected models cannot write`() = runTest {
        enqueue(); response = { """{"valid":true,"amount_cents":9900,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"99元","direction_evidence":"转账成功","status_evidence":"转账成功"}""" }
        assertTrue(runner().run("task")); assertEquals(0, commits)
        assertEquals(RecognitionFailure.INCOMPLETE, store.get("task")!!.failure)
    }
    @Test fun `explicit non receipt rejection is not archived`() = runTest {
        enqueue(); response = { """{"valid":false,"missing_fields":["payment_status"]}""" }
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
            addEvidence()
            old()
        }
        assertTrue(runner().run("task"))
        assertSuccessorPreserved()
        response = old
        assertTrue(runner().run("task")); assertEquals(1, commits); assertNull(store.get("task"))
        assertEquals("model_completed", states.last())
    }

    @Test fun `evidence changing while writer is suspended prevents old amount commit`() = runTest {
        enqueue()
        val enteredWriter = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resumeWriter = kotlinx.coroutines.CompletableDeferred<Unit>()
        val guardedRunner = RecognitionTaskRunner(store, client, { allowed }, { _, _, _, isCurrent ->
            enteredWriter.complete(Unit)
            resumeWriter.await() // Mirrors category/preferences/Room suspension before the final guarded insert.
            if (isCurrent()) { commits++; true } else false
        }, reports::add, onState = { _, state -> states += state })
        val result = async { guardedRunner.run("task") }
        enteredWriter.await()
        val original = store.get("task")!!
        store.enqueue(original.copy(text = "转账成功\n¥28.00\n待对方收款"))
        resumeWriter.complete(Unit)
        assertTrue(result.await())
        assertSuccessorPreserved()
        assertTrue(store.get("task")!!.text.contains("28.00"))
    }

    @Test fun `evidence received before sending finishes predecessor without a model call`() = runTest {
        enqueue()
        val guarded = RecognitionTaskRunner(store, client, { allowed },
            { _, _, _, _ -> commits++; true }, reports::add,
            onState = { _, state -> states += state },
            semanticContext = { addEvidence(); CaptureSemanticContext() })
        assertTrue(guarded.run("task"))
        assertEquals(0, calls)
        assertSuccessorPreserved()
        assertTrue(runner().run("task"))
        assertEquals(1, commits)
    }

    @Test fun `timeout of superseded response does not back off successor`() = runTest {
        enqueue(); val old = response
        response = { addEvidence(); delay(60_001); old() }
        assertTrue(runner().run("task"))
        assertSuccessorPreserved()
        response = old
        assertTrue(runner().run("task"))
        assertEquals(1, commits)
    }

    @Test fun `network failure of superseded response does not back off successor`() = runTest {
        enqueue(); val old = response
        response = { addEvidence(); error("old request disconnected") }
        assertTrue(runner().run("task"))
        assertSuccessorPreserved()
        response = old
        assertTrue(runner().run("task"))
        assertEquals(1, commits)
    }

    @Test fun `commit exception after evidence update preserves immediately runnable successor`() = runTest {
        enqueue()
        val guarded = RecognitionTaskRunner(store, client, { allowed },
            { _, _, _, _ -> addEvidence(); error("stale writer interrupted") }, reports::add,
            onState = { _, state -> states += state })
        assertTrue(guarded.run("task"))
        assertSuccessorPreserved()
        assertTrue(runner().run("task"))
        assertEquals(1, commits)
    }

    @Test fun `timeout of current evidence still retries and is bounded`() = runTest {
        enqueue()
        response = { delay(60_001); error("timeout must cancel request") }
        repeat(2) {
            assertFalse(runner().run("task"))
            assertEquals(it + 1, store.get("task")!!.attempts)
            assertTrue(reports.last().contains("超时"))
            assertEquals("model_failed", states.last())
        }
        assertTrue(runner().run("task"))
        assertEquals(RecognitionFailure.TIMEOUT, store.get("task")!!.failure)
        assertEquals(3, calls)
        assertEquals("model_exhausted", states.last())
    }

    @Test fun `explicit retry reuses event and finishes once after automatic budget is exhausted`() = runTest {
        enqueue(); val valid = response; response = { error("offline") }
        repeat(3) { runner().run("task") }
        val failed = store.get("task")!!
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, failed.state)
        val retried = store.retry(failed.id, failed.evidenceVersion)!!
        assertEquals(failed.eventKey, retried.eventKey)
        assertEquals(failed.createdAt, retried.createdAt)
        assertEquals(failed.configurationId, retried.configurationId)
        assertEquals(0, retried.attempts)
        assertEquals(failed.evidenceVersion + 1, retried.evidenceVersion)
        response = valid
        assertTrue(runner().run("task"))
        assertTrue(runner().run("task"))
        assertEquals(1, commits)
        assertNull(store.get("task"))
    }

    @Test fun `failed receipt cannot be resent after changing configuration`() = runTest {
        enqueue(); response = { error("offline") }
        repeat(3) { runner().run("task") }
        val failed = store.get("task")!!
        store.retry(failed.id, failed.evidenceVersion)
        config = "new account"
        assertTrue(runner().run("task"))
        assertEquals(3, calls)
        assertNull(store.get("task"))
    }

    @Test fun `revocation during final failed request discards instead of retaining receipt`() = runTest {
        enqueue(); response = { error("offline") }
        repeat(2) { runner().run("task") }
        response = { allowed = false; error("cancelled remote request") }
        assertTrue(runner().run("task"))
        assertNull(store.get("task"))
        assertEquals("model_discarded", states.last())
    }

    @Test fun `unavailable model identity consumes bounded retry budget without sending`() = runTest {
        enqueue(); identityUnavailable = true
        repeat(2) { assertFalse(runner().run("task")) }
        assertTrue(runner().run("task"))
        assertEquals(0, calls)
        assertEquals(RecognitionTaskState.NEEDS_ATTENTION, store.get("task")!!.state)
        assertEquals(3, store.get("task")!!.attempts)
    }

}

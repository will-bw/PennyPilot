package com.pennypilot.app.data.recognition

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.Processor
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.WorkerWrapper
import androidx.work.impl.constraints.trackers.Trackers
import androidx.work.impl.foreground.ForegroundProcessor
import androidx.work.impl.utils.SerialExecutorImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.pennypilot.app.data.llm.*
import com.pennypilot.app.service.NotificationHelper
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Real WorkManager persistence and dependency transitions, without Android's job scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecognitionWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val direct = Executor { it.run() }
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val serial = SerialExecutorImpl(direct)
    private val taskExecutor = object : TaskExecutor {
        override fun getMainThreadExecutor() = direct
        override fun getSerialTaskExecutor() = serial
        override fun getCoroutineScope() = taskScope
        override fun getTaskCoroutineDispatcher() = Dispatchers.Unconfined
    }
    private val now = System.currentTimeMillis()
    private val clock = Clock { now }
    private val file = File.createTempFile("worker-recognition", ".enc").apply { delete(); deleteOnExit() }
    private val store = RecognitionTaskStore(file, object : TaskCipher {
        override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 47).toByte() }.toByteArray()
        override fun decrypt(bytes: ByteArray) = encrypt(bytes)
    })
    private val validResponse = """{"valid":true,"amount_cents":1800,"direction":"EXPENSE","currency":"CNY","payment_status":"COMPLETED","amount_evidence":"¥18.00","direction_evidence":"转账成功","status_evidence":"转账成功"}"""
    private var response: suspend () -> String = { validResponse }
    private var commits = 0
    private val client = object : LLMClient {
        override suspend fun operationIdentity(role: ModelRole) = "A"
        override suspend fun chat(messages: List<Message>, jsonMode: Boolean) = response()
        override suspend fun chatWithTools(messages: List<Message>, tools: List<ToolDefinition>): ChatResponseWithTools = error("Unexpected tools")
    }
    private val runner = RecognitionTaskRunner(store, client, { true }, { _, _, _, isCurrent ->
        if (isCurrent()) { commits++; true } else false
    }, {})
    private val workers = mutableListOf<RecognitionWorker>()
    private val configuration = Configuration.Builder()
        .setExecutor(direct).setTaskExecutor(direct).setWorkerCoroutineContext(Dispatchers.Unconfined).setClock(clock)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                check(workerClassName == RecognitionWorker::class.java.name)
                workers += RecognitionWorker(appContext, workerParameters)
                // Keep Hilt/Room business wiring out of this scheduler test; run the real durable runner.
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork() = if (runner.run(inputData.getString("task_id")!!)) Result.success() else Result.retry()
                }
            }
        }).build()
    private val database = WorkDatabase.create(context, direct, clock, true)
    private val manager = WorkManagerImpl(context, configuration, taskExecutor, database, emptyList(),
        Processor(context, configuration, taskExecutor, database), Trackers(context, taskExecutor))

    @After fun close() {
        WorkManagerImpl.setDelegate(null)
        taskScope.cancel()
        database.close()
        store.clear()
    }

    private fun enqueueReceipt() {
        WorkManagerImpl.setDelegate(manager)
        store.enqueue(RecognitionTask("task", "event", "com.tencent.mm", "转账成功\n¥18.00\n待对方收款", now, 0, "A", "ACCESSIBILITY"))
        RecognitionWorker.schedule(context, "task")
    }

    private fun ids() = database.workSpecDao().getWorkSpecIdAndStatesForName("recognition_task").map { it.id }
    private fun spec(id: String) = database.workSpecDao().getWorkSpec(id)!!
    private fun launch(id: String) = WorkerWrapper.Builder(context, configuration, taskExecutor,
        ForegroundProcessor { _, _ -> error("API 34 expedited jobs do not require a foreground service") },
        database, spec(id), emptyList()).build().launch()

    @Test fun `scheduler keeps pending work expedited and supplies compatibility foreground notification`() = runBlocking {
        enqueueReceipt()
        RecognitionWorker.schedule(context, "task")
        val id = ids().single()
        assertTrue(spec(id).expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec(id).outOfQuotaPolicy)
        assertEquals(NetworkType.CONNECTED, spec(id).constraints.requiredNetworkType)
        assertFalse(launch(id).get(2, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, spec(id).state)

        // WorkManager calls this contract on Android 26–30 when implementing expedited work.
        val foreground = workers.single().getForegroundInfo()
        assertNotEquals(0, foreground.notificationId)
        assertEquals(NotificationHelper.CHANNEL_SYSTEM, foreground.notification.channelId)
        assertNotNull(foreground.notification.smallIcon)
        assertTrue(foreground.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(foreground.notification.extras.toString().contains("18.00"))
    }

    @Test fun `superseded predecessor succeeds and appended evidence runs without backoff`() {
        enqueueReceipt()
        val first = ids().single()
        val releaseResponse = CompletableDeferred<String>()
        response = { releaseResponse.await() }
        val firstRun = launch(first)
        assertEquals(WorkInfo.State.RUNNING, spec(first).state)

        val previous = store.get("task")!!
        store.enqueue(previous.copy(text = previous.text + "\n收款方：小林"))
        RecognitionWorker.schedule(context, "task", enqueueSuccessor = true)
        val second = ids().single { it != first }
        assertEquals(WorkInfo.State.BLOCKED, spec(second).state)
        assertEquals(listOf(first), database.dependencyDao().getPrerequisites(second))

        releaseResponse.complete(validResponse)
        assertFalse(firstRun.get(2, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, spec(first).state)
        assertEquals(WorkInfo.State.ENQUEUED, spec(second).state)
        assertEquals(0, spec(second).runAttemptCount)
        assertTrue(spec(second).calculateNextRunTime() <= now)
        assertEquals(2, store.get("task")!!.evidenceVersion)
        assertEquals(0, commits)

        response = { validResponse }
        assertFalse(launch(second).get(2, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.SUCCEEDED, spec(second).state)
        assertEquals(1, commits)
        assertNull(store.get("task"))
    }

    @Test fun `current network failure retains retry backoff and does not release dependencies`() {
        enqueueReceipt()
        val first = ids().single()
        RecognitionWorker.schedule(context, "task", enqueueSuccessor = true)
        val second = ids().single { it != first }
        response = { error("offline") }
        assertTrue(launch(first).get(2, TimeUnit.SECONDS))
        assertEquals(WorkInfo.State.ENQUEUED, spec(first).state)
        assertEquals(1, spec(first).runAttemptCount)
        assertEquals(now + 30_000, spec(first).calculateNextRunTime())
        assertEquals(WorkInfo.State.BLOCKED, spec(second).state)
        assertEquals(1, store.get("task")!!.attempts)
        assertEquals(0, commits)
    }
}

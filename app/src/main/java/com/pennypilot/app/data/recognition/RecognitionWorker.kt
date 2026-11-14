package com.pennypilot.app.data.recognition

import android.content.Context
import androidx.work.*
import com.pennypilot.app.service.CaptureProcessor
import com.pennypilot.app.service.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecognitionEntryPoint {
    fun captureProcessor(): CaptureProcessor
    fun captureServiceMonitor(): com.pennypilot.app.service.CaptureServiceMonitor
}

class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Android 26–30 implements expedited work with a foreground service for this task only.
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        FOREGROUND_NOTIFICATION_ID, NotificationHelper.recognitionForegroundNotification(applicationContext)
    )

    override suspend fun doWork(): Result {
        val id = inputData.getString("task_id") ?: return Result.failure()
        return try {
            val processor = EntryPointAccessors.fromApplication(applicationContext, RecognitionEntryPoint::class.java).captureProcessor()
            if (processor.processRecognitionTask(id)) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = -90004

        fun schedule(context: Context, id: String, enqueueSuccessor: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("recognition_expiry", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecognitionCleanupWorker>(12, TimeUnit.HOURS).build())
            WorkManager.getInstance(context).enqueueUniqueWork("recognition_$id",
                if (enqueueSuccessor) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecognitionWorker>()
                    .setInputData(workDataOf("task_id" to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        /** Repairs the narrow process-death gap between durable enqueue and WorkManager scheduling. */
        fun recover(context: Context) {
            try { RecognitionTaskStore(context).entries().forEach {
                if (it.state == RecognitionTaskState.QUEUED) schedule(context, it.id)
                else NotificationHelper.cancelRecognitionPending(context, it.eventKey)
            } }
            catch (_: Exception) {
                RecognitionTaskStore(context).clear()
                runCatching { EntryPointAccessors.fromApplication(context.applicationContext, RecognitionEntryPoint::class.java)
                    .captureServiceMonitor().onError("加密识别缓存不可用，请重新识别本次支付") }
            }
        }
    }
}

/** Offline cleanup does not wait for the network constraint of inference tasks. */
class RecognitionCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            RecognitionTaskStore(applicationContext).entries().filter { it.state == RecognitionTaskState.NEEDS_ATTENTION }
                .forEach { NotificationHelper.cancelRecognitionPending(applicationContext, it.eventKey) }
            Result.success()
        }
        catch (_: Exception) { RecognitionTaskStore(applicationContext).clear(); Result.failure() }
    }
}

package com.pennypilot.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.worker.MonthlyReportWorker
import dagger.hilt.android.HiltAndroidApp
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class PennyPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.pennypilot.app.data.db.LedgerRevision.initialize(this)
        NotificationHelper.createChannels(this)
        com.pennypilot.app.data.recognition.RecognitionWorker.recover(this)

        // 每天核对已结束月份并补齐遗漏，避免固定 30 天周期跳过自然月。
        val initialDelay = millisUntilNextReportTime()
        val request = PeriodicWorkRequestBuilder<MonthlyReportWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "monthly_report", ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    /** 首次检查安排在下个当地 09:00；WorkManager 可根据系统后台策略延后。 */
    private fun millisUntilNextReportTime(): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate()
            .atTime(LocalTime.of(9, 0)).atZone(zone)
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusDays(1)
                .atTime(LocalTime.of(9, 0)).atZone(zone)
        }
        return Duration.between(now, next).toMillis()
    }
}

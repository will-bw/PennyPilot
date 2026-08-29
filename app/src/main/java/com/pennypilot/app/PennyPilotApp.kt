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
        NotificationHelper.createChannels(this)

        // Schedule monthly report worker：设计约定每月 1 号 09:00 触发，
        // 用初始延迟对齐到下个 1 号 09:00，周期保持 30 天（Worker 内有幂等守卫防漂移）
        val initialDelay = millisUntilNextReportTime()
        val request = PeriodicWorkRequestBuilder<MonthlyReportWorker>(30, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "monthly_report", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** 计算到下个每月 1 号 09:00 的毫秒差；若今天正是 1 号且未到 9 点则今天触发 */
    private fun millisUntilNextReportTime(): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().withDayOfMonth(1)
            .atTime(LocalTime.of(9, 0)).atZone(zone)
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusMonths(1)
                .withDayOfMonth(1).atTime(LocalTime.of(9, 0)).atZone(zone)
        }
        return Duration.between(now, next).toMillis()
    }
}

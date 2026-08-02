package com.pennypilot.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.worker.MonthlyReportWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class PennyPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)

        // Schedule monthly report worker
        val request = PeriodicWorkRequestBuilder<MonthlyReportWorker>(30, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "monthly_report", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}

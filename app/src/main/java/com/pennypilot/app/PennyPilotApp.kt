package com.pennypilot.app

import android.app.Application
import com.pennypilot.app.service.NotificationHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PennyPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}

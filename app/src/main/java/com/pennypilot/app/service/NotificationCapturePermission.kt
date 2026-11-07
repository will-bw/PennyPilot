package com.pennypilot.app.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Listener authorization can outlive the service connection. */
object NotificationCapturePermission {
    fun isGranted(context: Context): Boolean {
        val listener = ComponentName(context, NotificationCaptorService::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(listener) == true
            } else {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.split(':')?.any { ComponentName.unflattenFromString(it) == listener } == true
            }
        }.getOrDefault(false)
    }
}

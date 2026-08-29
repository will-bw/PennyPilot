package com.pennypilot.app.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 开机自检（M3 Task 5）：ColorOS 重启后通知使用权可能被重置，
 * 未授权时发提醒通知引导重新开启；已授权则静默。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val cn = ComponentName(context, NotificationCaptorService::class.java)
        val listenerEnabled = Settings.Secure
            .getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(cn.flattenToString()) == true

        if (!listenerEnabled) {
            NotificationHelper.postSystemReminder(
                context,
                title = "通知捕获未开启",
                text = "开机后检测到通知使用权未授权，点击完成 ColorOS 保活设置",
                openRoute = "coloros_guide"
            )
        }
    }
}

package com.pennypilot.app.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** 开机或应用升级后恢复已有系统授权的监听连接。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val component = ComponentName(context, NotificationCaptorService::class.java)
        val listenerEnabled = Settings.Secure
            .getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        if (listenerEnabled) {
            runCatching { NotificationListenerService.requestRebind(component) }
        } else if (intent.action == Intent.ACTION_BOOT_COMPLETED && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            // 输出提醒权限可能在检查后被系统撤销，不能使开机接收器崩溃。
            runCatching {
                NotificationHelper.postSystemReminder(context, "通知捕获未开启",
                    "开机后检测到通知使用权未授权，点击检查自动捕获设置", "coloros_guide")
            }
        }
    }
}

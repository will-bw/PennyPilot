package com.pennypilot.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pennypilot.app.MainActivity

object NotificationHelper {
    const val CHANNEL_CAPTURE = "capture_confirm"
    const val CHANNEL_REPORT = "monthly_report"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CAPTURE, "记账确认", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "通知捕获后的确认提醒" })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_REPORT, "月度报告", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "每月账单报告" })
    }

    fun postCaptureNotification(context: Context, txId: Long, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Confirm action
        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM
            putExtra(NotificationActionReceiver.EXTRA_TX_ID, txId)
        }
        val confirmPi = PendingIntent.getBroadcast(context, txId.toInt() * 3, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Ignore action
        val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_IGNORE
            putExtra(NotificationActionReceiver.EXTRA_TX_ID, txId)
        }
        val ignorePi = PendingIntent.getBroadcast(context, txId.toInt() * 3 + 1, ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Edit action (opens app)
        val editIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("edit_tx_id", txId)
        }
        val editPi = PendingIntent.getActivity(context, txId.toInt() * 3 + 2, editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "✓ 记入", confirmPi)
            .addAction(0, "✗ 忽略", ignorePi)
            .addAction(0, "✎ 编辑", editPi)
            .setAutoCancel(true)
            .build()

        nm.notify(txId.toInt(), notification)
    }
}

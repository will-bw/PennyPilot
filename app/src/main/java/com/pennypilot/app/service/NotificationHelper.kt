package com.pennypilot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pennypilot.app.MainActivity
import java.security.MessageDigest

object NotificationHelper {
    const val CHANNEL_CAPTURE = "capture_results_v2"
    internal const val CHANNEL_CAPTURE_LEGACY = "capture_confirm"
    const val CHANNEL_REPORT = "monthly_report"
    const val CHANNEL_SYSTEM = "system_reminder"

    private const val NOTIFY_ID_REPORT = 90001
    private const val NOTIFY_ID_PERMISSION = 90002
    private const val NOTIFY_ID_RECOGNITION_PENDING = -90003
    private const val PENDING_HISTORY_PREFS = "capture_notification_history"
    private const val PENDING_HISTORY_TTL_MS = 24 * 60 * 60 * 1000L
    private const val PENDING_HISTORY_LIMIT = 512
    private const val PENDING_TIMEOUT_MS = 10 * 60 * 1000L

    fun captureNotificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(captureChannelId(manager)) ?: return true
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return Build.VERSION.SDK_INT < 28 || channel.group == null ||
            manager.getNotificationChannelGroup(channel.group)?.isBlocked != true
    }

    fun captureChannelId(context: Context): String =
        captureChannelId(context.getSystemService(NotificationManager::class.java))

    /** 渠道允许高优先级提醒；免打扰及厂商横幅开关仍由系统决定。 */
    fun captureHeadsUpEnabled(context: Context): Boolean {
        if (!captureNotificationsEnabled(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return (manager.getNotificationChannel(captureChannelId(manager))?.importance
            ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH
    }

    fun cancelCaptureNotification(context: Context, txId: Long, captureEventKey: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (captureEventKey != null) {
            nm.cancel(captureEventKey, txId.toInt())
        } else {
            // 兼容旧版本无 tag 的提醒，以及尚未提供事件身份的页面调用。
            nm.cancel(txId.toInt())
            nm.activeNotifications.filter {
                it.id == txId.toInt() && it.notification.channelId in
                    setOf(CHANNEL_CAPTURE, CHANNEL_CAPTURE_LEGACY)
            }.forEach { nm.cancel(it.tag, it.id) }
        }
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val legacy = nm.getNotificationChannel(CHANNEL_CAPTURE_LEGACY)
        if (nm.getNotificationChannel(CHANNEL_CAPTURE) == null &&
            (legacy == null || (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                canMigrateDefaultCaptureChannel(legacy)))) {
            // Android 不允许提升既有渠道优先级；仅迁移应用原先的默认低优先级设置。
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_CAPTURE, "自动记账", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "支付识别进度、自动记账结果与待处理账单" })
        }
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_REPORT, "月度报告", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "每月账单报告" })
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SYSTEM, "系统提醒", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "权限与保活提醒" })
    }

    fun postCaptureNotification(context: Context, txId: Long, captureEventKey: String, title: String, text: String,
        allowQuickConfirm: Boolean = true, recorded: Boolean = false, updateOnly: Boolean = false,
        pendingEventKey: String? = null): Boolean {
        createChannels(context)
        if (!captureNotificationsEnabled(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = nm.activeNotifications.firstOrNull { it.tag == captureEventKey && it.id == txId.toInt() }
        // 补充详情只更新用户仍保留的提醒，不把已经划走的账目重新弹出来。
        if (updateOnly && active == null) return false
        val pending = pendingEventKey?.let { key ->
            nm.activeNotifications.firstOrNull { it.tag == pendingTag(key) && it.id == NOTIFY_ID_RECOGNITION_PENDING }
        }
        val pendingHistory = pendingEventKey?.let { pendingHistoryState(context, it) } ?: 0L
        if (active == null && pendingHistory < 0L) return false
        val hadPendingNotice = pending != null || pendingHistory != 0L

        // Confirm action
        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM
            data = captureActionUri(captureEventKey, "confirm")
            putExtra(NotificationActionReceiver.EXTRA_TX_ID, txId)
            putExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY, captureEventKey)
        }
        val confirmPi = PendingIntent.getBroadcast(context, txId.toInt() * 3, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Ignore action
        val ignoreIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = if (recorded) NotificationActionReceiver.ACTION_UNDO else NotificationActionReceiver.ACTION_IGNORE
            data = captureActionUri(captureEventKey, if (recorded) "undo" else "ignore")
            putExtra(NotificationActionReceiver.EXTRA_TX_ID, txId)
            putExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY, captureEventKey)
            putExtra(NotificationActionReceiver.EXTRA_LEDGER_REVISION, com.pennypilot.app.data.db.LedgerRevision.current)
        }
        val ignorePi = PendingIntent.getBroadcast(context, txId.toInt() * 3 + 1, ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Edit action (opens app)
        val editIntent = Intent(context, MainActivity::class.java).apply {
            data = captureActionUri(captureEventKey, "edit")
            putExtra("edit_tx_id", txId)
            putExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY, captureEventKey)
        }
        val editPi = PendingIntent.getActivity(context, txId.toInt() * 3 + 2, editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, active?.notification?.channelId ?: captureChannelId(nm))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(updateOnly || active != null || hadPendingNotice)
            .setContentIntent(editPi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        if (hadPendingNotice) builder.setSilent(true)
        if (allowQuickConfirm && !recorded) builder.addAction(0, "记入", confirmPi)
        builder.addAction(0, if (recorded) "撤销" else "忽略", ignorePi)
            .addAction(0, if (recorded || allowQuickConfirm) "修改" else "核对记录", editPi)
        val notification = builder.build()

        nm.notify(captureEventKey, txId.toInt(), notification)
        if (pendingEventKey != null) cancelRecognitionPending(context, pendingEventKey)
        return true
    }

    /** 只反馈识别已开始，尚未形成可入账的资金事实。重试和进程恢复不重复提醒。 */
    @Synchronized
    fun postRecognitionPending(context: Context, eventKey: String): Boolean {
        createChannels(context)
        if (!captureNotificationsEnabled(context) || pendingHistoryState(context, eventKey) != 0L) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        val postedAt = System.currentTimeMillis()
        val historyKey = pendingHistoryKey(eventKey)
        val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISMISS_RECOGNITION_PENDING
            data = captureActionUri(historyKey, "dismiss-pending")
            putExtra(NotificationActionReceiver.EXTRA_PENDING_HISTORY_KEY, historyKey)
            putExtra(NotificationActionReceiver.EXTRA_PENDING_POSTED_AT, postedAt)
            putExtra(NotificationActionReceiver.EXTRA_PENDING_POSTED_ELAPSED, SystemClock.elapsedRealtime())
        }
        val deletePi = PendingIntent.getBroadcast(context, NOTIFY_ID_RECOGNITION_PENDING, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, captureChannelId(nm))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("正在识别支付信息")
            .setContentText("尚未记账，识别完成后会更新结果")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(PENDING_TIMEOUT_MS)
            .setContentIntent(openRecognitionIntent(context))
            .setDeleteIntent(deletePi)
            .build()
        nm.notify(pendingTag(eventKey), NOTIFY_ID_RECOGNITION_PENDING, notification)
        rememberPendingPosted(context, historyKey, postedAt)
        return true
    }

    fun cancelRecognitionPending(context: Context, eventKey: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(pendingTag(eventKey), NOTIFY_ID_RECOGNITION_PENDING)
    }

    @Synchronized
    internal fun recordPendingDismissal(context: Context, historyKey: String, postedAt: Long,
        postedElapsed: Long) {
        if (!historyKey.matches(Regex("[a-f0-9]{64}")) || postedAt <= 0L || postedElapsed < 0L) return
        // Android 的超时取消也会发送 deleteIntent，只有有效显示期内的清除才代表用户不再需要提醒。
        val elapsed = SystemClock.elapsedRealtime() - postedElapsed
        if (elapsed < 0L || elapsed >= PENDING_TIMEOUT_MS) return
        val preferences = context.getSharedPreferences(PENDING_HISTORY_PREFS, Context.MODE_PRIVATE)
        if (preferences.getLong(historyKey, 0L) != postedAt) return
        preferences.edit().putLong(historyKey, -postedAt).apply()
    }

    /** Android 8–11 的加急 Worker 前台执行提示；由 WorkManager 管理其独立通知 ID。 */
    fun recognitionForegroundNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("正在处理支付识别")
            .setContentText("后台识别进行中，可在流水中查看结果")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openRecognitionIntent(context))
            .build()
    }

    private fun openRecognitionIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, NOTIFY_ID_RECOGNITION_PENDING, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun captureChannelId(manager: NotificationManager): String = when {
        manager.getNotificationChannel(CHANNEL_CAPTURE) != null -> CHANNEL_CAPTURE
        manager.getNotificationChannel(CHANNEL_CAPTURE_LEGACY) != null -> CHANNEL_CAPTURE_LEGACY
        else -> CHANNEL_CAPTURE
    }

    private fun canMigrateDefaultCaptureChannel(channel: NotificationChannel): Boolean {
        // Android 8–9 无法判断用户是否明确选择过原优先级，保留该渠道。
        if (Build.VERSION.SDK_INT < 29 || channel.hasUserSetImportance() ||
            channel.importance != NotificationManager.IMPORTANCE_LOW) return false
        if (Build.VERSION.SDK_INT >= 30 && channel.hasUserSetSound()) return false
        val defaults = NotificationChannel(CHANNEL_CAPTURE_LEGACY, "自动记账", NotificationManager.IMPORTANCE_LOW)
        // 部分系统给默认音频属性增加私有 flags，不能把它误认成用户设置。
        return channel.sound == defaults.sound &&
            channel.audioAttributes?.usage == defaults.audioAttributes?.usage &&
            channel.audioAttributes?.contentType == defaults.audioAttributes?.contentType &&
            channel.shouldVibrate() == defaults.shouldVibrate() &&
            channel.vibrationPattern.contentEquals(defaults.vibrationPattern) &&
            channel.shouldShowLights() == defaults.shouldShowLights() &&
            channel.lightColor == defaults.lightColor &&
            channel.lockscreenVisibility == defaults.lockscreenVisibility &&
            channel.canShowBadge() == defaults.canShowBadge() &&
            channel.canBypassDnd() == defaults.canBypassDnd() && channel.group == null
    }

    private fun pendingTag(eventKey: String): String = "recognition:" + pendingHistoryKey(eventKey)

    private fun pendingHistoryKey(eventKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(eventKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 同一哈希只占一项：正数为首次发布时间，负数保留该时间并标记用户主动清除。 */
    private fun pendingHistoryState(context: Context, eventKey: String): Long {
        val preferences = context.getSharedPreferences(PENDING_HISTORY_PREFS, Context.MODE_PRIVATE)
        val state = preferences.getLong(pendingHistoryKey(eventKey), 0L)
        val postedAt = kotlin.math.abs(state)
        return state.takeIf { postedAt > 0L && System.currentTimeMillis() - postedAt < PENDING_HISTORY_TTL_MS } ?: 0L
    }

    private fun rememberPendingPosted(context: Context, historyKey: String, postedAt: Long) {
        val preferences = context.getSharedPreferences(PENDING_HISTORY_PREFS, Context.MODE_PRIVATE)
        val retained = preferences.all.mapNotNull { (key, value) ->
            (value as? Long)?.takeIf { postedAt - kotlin.math.abs(it) < PENDING_HISTORY_TTL_MS }?.let { key to it }
        }.sortedByDescending { kotlin.math.abs(it.second) }.take(PENDING_HISTORY_LIMIT - 1).toMap()
        preferences.edit().apply {
            preferences.all.keys.filter { it !in retained }.forEach(::remove)
            putLong(historyKey, postedAt)
        }.apply()
    }

    private fun captureActionUri(eventKey: String, action: String): Uri = Uri.Builder()
        .scheme("pennypilot").authority("capture").appendPath(eventKey).appendPath(action).build()

    /** 月度报告生成完成提醒，点击打开 App */
    fun postReportNotification(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, NOTIFY_ID_REPORT, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_REPORT)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID_REPORT, notification)
    }

    /** 系统提醒（如开机后通知使用权未授权），点击跳转 App 内指定路由 */
    fun postSystemReminder(context: Context, title: String, text: String, openRoute: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, openRoute)
        }
        val pi = PendingIntent.getActivity(context, NOTIFY_ID_PERMISSION, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID_PERMISSION, notification)
    }
}

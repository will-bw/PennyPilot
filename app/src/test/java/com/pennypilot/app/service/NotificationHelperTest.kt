package com.pennypilot.app.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationHelperTest {
    @Test fun `new installs create a high priority result channel and first result can alert`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE).importance)
        assertTrue(NotificationHelper.captureHeadsUpEnabled(context))
        assertEquals(NotificationHelper.CHANNEL_CAPTURE, NotificationHelper.captureChannelId(context))
        assertTrue(NotificationHelper.postCaptureNotification(context, 101, "first-result", "交易已记录", "可查看流水"))
        val notification = manager.activeNotifications.single().notification
        assertEquals(NotificationCompat.PRIORITY_HIGH, notification.priority)
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun `untouched legacy low channel migrates without deleting old channel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())

        NotificationHelper.createChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE_LEGACY).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE).importance)
        NotificationHelper.postCaptureNotification(context, 102, "migrated-result", "已记录", "支付结果")
        assertEquals(NotificationHelper.CHANNEL_CAPTURE, manager.activeNotifications.single().notification.channelId)
    }

    @Test fun `disabled legacy channel is not bypassed by migration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel(NotificationManager.IMPORTANCE_NONE))

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
        assertFalse(NotificationHelper.captureNotificationsEnabled(context))
        assertFalse(NotificationHelper.captureHeadsUpEnabled(context))
        assertFalse(NotificationHelper.postCaptureNotification(context, 103, "disabled-result", "已记录", "支付结果"))
        assertFalse(NotificationHelper.postRecognitionPending(context, "disabled-pending"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `user confirmed low importance is preserved`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = legacyChannel()
        ReflectionHelpers.setField(legacy, "mUserLockedFields", 4)
        assertTrue(legacy.hasUserSetImportance())
        manager.createNotificationChannel(legacy)

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
        assertFalse(NotificationHelper.captureHeadsUpEnabled(context))
        assertEquals(NotificationHelper.CHANNEL_CAPTURE_LEGACY, NotificationHelper.captureChannelId(context))
        assertTrue(NotificationHelper.postCaptureNotification(context, 104, "custom-result", "已记录", "支付结果"))
        assertEquals(NotificationHelper.CHANNEL_CAPTURE_LEGACY, manager.activeNotifications.single().notification.channelId)
    }

    @Test fun `custom legacy sound remains on the original channel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = legacyChannel().apply { setSound(Uri.parse("content://example/tone"), audioAttributes) }
        manager.createNotificationChannel(legacy)

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
        assertEquals(legacy.sound, manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE_LEGACY).sound)
    }

    @Test fun `OEM default audio flags do not prevent default low channel migration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = legacyChannel().apply {
            setSound(sound, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(0x800).build())
        }
        manager.createNotificationChannel(legacy)

        NotificationHelper.createChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE).importance)
    }

    @Test fun `user confirmed default sound is preserved even when value matches defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = legacyChannel()
        ReflectionHelpers.setField(legacy, "mUserLockedFields", 32)
        assertTrue(legacy.hasUserSetSound())
        manager.createNotificationChannel(legacy)

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
    }

    @Test fun `existing new channel preferences remain authoritative`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())
        manager.createNotificationChannel(NotificationChannel(NotificationHelper.CHANNEL_CAPTURE,
            "自动记账", NotificationManager.IMPORTANCE_NONE))

        NotificationHelper.createChannels(context)

        assertEquals(NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE).importance)
        assertFalse(NotificationHelper.captureNotificationsEnabled(context))
        assertFalse(NotificationHelper.postRecognitionPending(context, "disabled-new-channel"))
    }

    @Test fun `app notification permission prevents both posts and legacy migration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())
        shadowOf(manager).setNotificationsEnabled(false)

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
        assertFalse(NotificationHelper.captureNotificationsEnabled(context))
        assertFalse(NotificationHelper.postCaptureNotification(context, 105, "denied-result", "已记录", "支付结果"))
        assertFalse(NotificationHelper.postRecognitionPending(context, "denied-pending"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test @Config(sdk = [28])
    fun `older Android preserves legacy settings when user choice cannot be inspected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())

        NotificationHelper.createChannels(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.CHANNEL_CAPTURE))
        NotificationHelper.postCaptureNotification(context, 106, "older-result", "已记录", "支付结果")
        assertEquals(NotificationHelper.CHANNEL_CAPTURE_LEGACY, manager.activeNotifications.single().notification.channelId)
    }

    @Test fun `enrichment replaces active result silently and does not resurrect dismissed result`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postCaptureNotification(context, 107, "enriched-result", "交易已记录", "识别完成")

        assertTrue(NotificationHelper.postCaptureNotification(context, 107, "enriched-result", "咖啡店", "详情已补充",
            updateOnly = true))
        val updated = manager.activeNotifications.single().notification
        assertEquals("咖啡店", updated.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotEquals(0, updated.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        manager.cancel("enriched-result", 107)

        assertFalse(NotificationHelper.postCaptureNotification(context, 107, "enriched-result", "咖啡店", "分类已补充",
            updateOnly = true))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `legacy and current tagged and untagged reminders can be cancelled by transaction`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())
        NotificationHelper.createChannels(context)
        val legacy = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_CAPTURE_LEGACY)
            .setSmallIcon(android.R.drawable.ic_menu_edit).build()
        manager.notify(108, legacy)
        manager.notify("legacy-result", 108, legacy)
        NotificationHelper.postCaptureNotification(context, 108, "current-result", "交易", "已记录")
        NotificationHelper.postCaptureNotification(context, 109, "unrelated-result", "其他交易", "已记录")

        NotificationHelper.cancelCaptureNotification(context, 108)

        assertEquals(listOf("unrelated-result"), manager.activeNotifications.map { it.tag })
    }

    @Test fun `pending payment says not yet recorded and retries do not repost after dismissal`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val event = "synthetic-private-source-event"
        assertTrue(NotificationHelper.postRecognitionPending(context, event))
        val pending = manager.activeNotifications.single()
        assertEquals("正在识别支付信息", pending.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(pending.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("尚未记账"))
        assertEquals(NotificationCompat.PRIORITY_HIGH, pending.notification.priority)
        assertNull(pending.notification.actions)
        assertFalse(pending.tag.contains(event))
        assertEquals(10 * 60 * 1000L, pending.notification.timeoutAfter)
        val dismissIntent = shadowOf(pending.notification.deleteIntent).savedIntent
        assertNull(dismissIntent.getStringExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY))
        assertFalse(dismissIntent.data.toString().contains(event))
        assertFalse(NotificationHelper.postRecognitionPending(context, event))
        NotificationActionReceiver().onReceive(context, dismissIntent)
        manager.cancel(pending.tag, pending.id)

        assertFalse(NotificationHelper.postRecognitionPending(context, event))
        assertTrue(manager.activeNotifications.isEmpty())
        val history = context.getSharedPreferences("capture_notification_history", Context.MODE_PRIVATE).all
        assertEquals(1, history.size)
        assertTrue(history.keys.all { it.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test fun `successful recognition replaces its pending notice without another alert`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "source-event")

        assertTrue(NotificationHelper.postCaptureNotification(context, 110, "canonical-event", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "source-event"))

        val result = manager.activeNotifications.single()
        assertEquals("canonical-event", result.tag)
        assertNotEquals(0, result.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, result.notification.groupAlertBehavior)
        assertNull(result.notification.sound)
        assertFalse(NotificationHelper.postRecognitionPending(context, "source-event"))
    }

    @Test fun `dismissed pending notice is not replaced by a fresh result banner`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "dismissed-source")
        val dismissIntent = shadowOf(manager.activeNotifications.single().notification.deleteIntent).savedIntent
        NotificationActionReceiver().onReceive(context, dismissIntent)
        manager.cancelAll()

        assertFalse(NotificationHelper.postCaptureNotification(context, 111, "canonical-event", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "dismissed-source"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun `timed out pending notice allows late result quietly despite Android delete intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "timeout-source")
        val dismissIntent = shadowOf(manager.activeNotifications.single().notification.deleteIntent).savedIntent
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        // AOSP 的 REASON_TIMEOUT 也发送 deleteIntent，不能据此认定用户划走。
        NotificationActionReceiver().onReceive(context, dismissIntent)
        manager.cancelAll()

        assertTrue(NotificationHelper.postCaptureNotification(context, 112, "late-result", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "timeout-source"))
        val result = manager.activeNotifications.single().notification
        assertEquals(Notification.GROUP_ALERT_SUMMARY, result.groupAlertBehavior)
        assertNotEquals(0, result.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertFalse(NotificationHelper.postRecognitionPending(context, "timeout-source"))
    }

    @Test fun `notification removed without user dismissal allows recovered result quietly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "recovered-source")
        // 模拟设备重启清空通知，持久去重记录仍在；没有用户清除广播。
        manager.cancelAll()

        assertTrue(NotificationHelper.postCaptureNotification(context, 113, "recovered-result", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "recovered-source"))
        val result = manager.activeNotifications.single().notification
        assertEquals(Notification.GROUP_ALERT_SUMMARY, result.groupAlertBehavior)
        assertNotEquals(0, result.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun `explicit early dismissal remains respected after notification timeout has elapsed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "early-dismissed-source")
        val dismissIntent = shadowOf(manager.activeNotifications.single().notification.deleteIntent).savedIntent
        NotificationActionReceiver().onReceive(context, dismissIntent)
        manager.cancelAll()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))

        assertFalse(NotificationHelper.postCaptureNotification(context, 114, "dismissed-late-result", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "early-dismissed-source"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(NotificationHelper.postRecognitionPending(context, "early-dismissed-source"))
    }

    @Test fun `stale dismiss broadcast cannot affect a new pending generation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "reused-source")
        val staleDismiss = android.content.Intent(shadowOf(manager.activeNotifications.single().notification.deleteIntent).savedIntent)
        manager.cancelAll()
        val history = context.getSharedPreferences("capture_notification_history", Context.MODE_PRIVATE)
        history.edit().clear().commit()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        NotificationHelper.postRecognitionPending(context, "reused-source")
        NotificationActionReceiver().onReceive(context, staleDismiss)

        assertTrue(NotificationHelper.postCaptureNotification(context, 115, "reused-result", "交易已记录", "识别完成",
            recorded = true, pendingEventKey = "reused-source"))
    }

    @Test fun `pending cancellation is event scoped and retains deduplication history`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "cancelled-source")
        NotificationHelper.postRecognitionPending(context, "different-source")

        NotificationHelper.cancelRecognitionPending(context, "cancelled-source")

        assertEquals(1, manager.activeNotifications.size)
        assertFalse(NotificationHelper.postRecognitionPending(context, "cancelled-source"))
    }

    @Test fun `expired pending history is pruned and bounds persisted event markers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.postRecognitionPending(context, "expiring-source")
        NotificationHelper.cancelRecognitionPending(context, "expiring-source")
        val history = context.getSharedPreferences("capture_notification_history", Context.MODE_PRIVATE)
        val originalKey = history.all.keys.single()
        history.edit().putLong(originalKey, System.currentTimeMillis() - 24 * 60 * 60 * 1000L - 1).commit()
        assertTrue(NotificationHelper.postRecognitionPending(context, "expiring-source"))

        history.edit().apply {
            for (index in 1..520) putLong("synthetic-$index", System.currentTimeMillis())
            putLong("expired-marker", System.currentTimeMillis() - 24 * 60 * 60 * 1000L - 1)
        }.commit()
        assertTrue(NotificationHelper.postRecognitionPending(context, "new-source"))
        assertEquals(512, history.all.size)
        assertFalse(history.contains("expired-marker"))
        assertEquals(2, manager.activeNotifications.size)
    }

    @Test fun `worker foreground notice is low priority and does not post a second capture notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationHelper.recognitionForegroundNotification(context)

        assertEquals(NotificationHelper.CHANNEL_SYSTEM, notification.channelId)
        assertEquals(NotificationCompat.PRIORITY_LOW, notification.priority)
        assertNotEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun legacyChannel(importance: Int = NotificationManager.IMPORTANCE_LOW) = NotificationChannel(
        NotificationHelper.CHANNEL_CAPTURE_LEGACY, "自动记账", importance
    )

    @Test fun `recorded payment offers undo and edit without another confirmation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannels(context)
        NotificationHelper.postCaptureNotification(context, 44, "automatically-recorded", "咖啡17元", "已自动记账", recorded = true)
        val notification = manager.activeNotifications.single { it.tag == "automatically-recorded" }.notification
        assertEquals(listOf("撤销", "修改"), notification.actions.map { it.title.toString() })
        val undo = shadowOf(notification.actions.first().actionIntent).savedIntent
        assertEquals(NotificationActionReceiver.ACTION_UNDO, undo.action)
        assertEquals("automatically-recorded", undo.getStringExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY))
        assertEquals(com.pennypilot.app.data.db.LedgerRevision.current,
            undo.getLongExtra(NotificationActionReceiver.EXTRA_LEDGER_REVISION, -1))
    }
    @Test
    fun `suspected duplicate requires opening record instead of one tap confirmation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannels(context)
        NotificationHelper.postCaptureNotification(context, 43, "possible-duplicate", "交易", "疑似重复",
            allowQuickConfirm = false)
        val notification = manager.activeNotifications.single { it.tag == "possible-duplicate" }.notification
        assertNotNull(notification.contentIntent)
        assertEquals(listOf("忽略", "核对记录"), notification.actions.map { it.title.toString() })
        assertEquals(43L, shadowOf(notification.contentIntent).savedIntent.getLongExtra("edit_tx_id", -1))
    }

    @Test
    fun `reused transaction ID cannot replace an older event pending intent or cancel new reminder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.createChannels(context)
        NotificationHelper.postCaptureNotification(context, 42, "old-event", "旧账目", "待确认")
        val old = manager.activeNotifications.single { it.tag == "old-event" }.notification.actions[0].actionIntent
        NotificationHelper.postCaptureNotification(context, 42, "new-event", "新账目", "待确认")
        val new = manager.activeNotifications.single { it.tag == "new-event" }.notification.actions[0].actionIntent

        assertNotEquals(old, new)
        assertEquals("old-event", shadowOf(old).savedIntent
            .getStringExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY))
        assertEquals("new-event", shadowOf(new).savedIntent
            .getStringExtra(NotificationActionReceiver.EXTRA_CAPTURE_EVENT_KEY))
        NotificationHelper.cancelCaptureNotification(context, 42, "old-event")
        assertTrue(manager.activeNotifications.any { it.tag == "new-event" })
        assertFalse(manager.activeNotifications.any { it.tag == "old-event" })
    }
}

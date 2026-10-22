package com.pennypilot.app.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationHelperTest {
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

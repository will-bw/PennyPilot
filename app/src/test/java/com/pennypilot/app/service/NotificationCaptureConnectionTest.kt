package com.pennypilot.app.service

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.data.settings.AppPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [NotificationConnectionShadow::class])
class NotificationCaptureConnectionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val component get() = ComponentName(context, NotificationCaptorService::class.java)
    private lateinit var preferences: AppPreferences
    private lateinit var connection: NotificationCaptureConnection
    private val monitor = CaptureServiceMonitor()
    private val requests = mutableListOf<String>()

    @Before fun setUp() {
        preferences = AppPreferences(context)
        runBlocking { preferences.setNotificationCaptureEnabled(true) }
        setPermission(granted = true)
        connection = NotificationCaptureConnection(context, preferences, monitor)
        NotificationConnectionShadow.onRequest = { action -> requests.add(action) }
    }

    @After fun tearDown() {
        NotificationConnectionShadow.onRequest = null
    }

    @Test fun `recovery waits for a real connection and only resets a still disconnected listener`() = runTest {
        NotificationConnectionShadow.onRequest = { action ->
            requests.add(action)
            assertTrue(monitor.health.value.isNotificationRecoveryInProgress)
            if (requests.size == 3) monitor.onServiceConnected()
        }
        val foreground = async { connection.restoreIfNeeded() }
        val manual = async { connection.restoreIfNeeded() }
        assertTrue(foreground.await())
        assertTrue(manual.await())
        assertEquals(listOf("bind", "unbind", "bind"), requests)
        assertFalse(monitor.health.value.isNotificationRecoveryInProgress)
    }

    @Test fun `ordinary reconnect and an already bound listener do not reset the service`() = runTest {
        NotificationConnectionShadow.onRequest = { action ->
            requests.add(action)
            monitor.onServiceConnected()
        }
        assertTrue(connection.restoreIfNeeded())
        assertTrue(connection.restoreIfNeeded())
        assertEquals(listOf("bind"), requests)
    }

    @Test fun `recovery does not run when capture is disabled or system permission is absent`() = runTest {
        preferences.setNotificationCaptureEnabled(false)
        assertFalse(connection.restoreIfNeeded())
        preferences.setNotificationCaptureEnabled(true)
        setPermission(granted = false)
        assertFalse(connection.restoreIfNeeded())
        assertTrue(requests.isEmpty())
    }

    @Test fun `losing permission during recovery prevents resetting the listener`() = runTest {
        NotificationConnectionShadow.onRequest = { action ->
            requests.add(action)
            setPermission(granted = false)
        }
        assertFalse(connection.restoreIfNeeded())
        assertEquals(listOf("bind"), requests)
        assertFalse(monitor.health.value.isNotificationRecoveryInProgress)
    }

    @Test
    @Config(sdk = [28])
    fun `older Android returns disconnected when a regular rebind produces no callback`() = runTest {
        assertFalse(connection.restoreIfNeeded())
        assertEquals(listOf("bind"), requests)
        assertFalse(monitor.health.value.isNotificationRecoveryInProgress)
    }

    private fun setPermission(granted: Boolean) {
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, granted)
    }
}

@Implements(NotificationListenerService::class)
class NotificationConnectionShadow {
    companion object {
        var onRequest: ((String) -> Unit)? = null

        @JvmStatic @Implementation
        fun requestRebind(component: ComponentName) {
            check(component.className == NotificationCaptorService::class.java.name)
            onRequest?.invoke("bind")
        }

        @JvmStatic @Implementation(minSdk = 34)
        fun requestUnbind(component: ComponentName) {
            check(component.className == NotificationCaptorService::class.java.name)
            onRequest?.invoke("unbind")
        }
    }
}

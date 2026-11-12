package com.pennypilot.app.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import androidx.annotation.MainThread
import com.pennypilot.app.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationCaptureConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val monitor: CaptureServiceMonitor
) {
    private val component = ComponentName(context, NotificationCaptorService::class.java)

    @MainThread
    suspend fun restoreIfNeeded(): Boolean {
        if (monitor.health.value.isBound) return true
        if (!canReconnect()) return false
        val health = monitor.health.value
        if (health.isBound) return true
        if (health.isNotificationRecoveryInProgress) {
            return monitor.health.first { it.isBound || !it.isNotificationRecoveryInProgress }.isBound
        }

        monitor.onNotificationRecovery(inProgress = true)
        try {
            NotificationListenerService.requestRebind(component)
            if (awaitConnection(1_500)) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                canReconnect() && !monitor.health.value.isBound) {
                // Android can keep the listener enabled in settings after its binding is lost.
                // requestRebind alone may be a no-op unless the component was first unbound.
                NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            }
            return awaitConnection(2_500)
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            return monitor.health.value.isBound
        } finally {
            monitor.onNotificationRecovery(inProgress = false)
        }
    }

    private suspend fun canReconnect(): Boolean =
        preferences.notificationCaptureEnabled.first() && NotificationCapturePermission.isGranted(context)

    private suspend fun awaitConnection(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { monitor.health.first { it.isBound } } != null
}

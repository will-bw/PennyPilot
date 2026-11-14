package com.pennypilot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the app process eligible to run while a user-requested model exchange is active. */
class AgentForegroundService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (error: IllegalStateException) {
            promotionFailed(error)
            return START_NOT_STICKY
        } catch (error: SecurityException) {
            promotionFailed(error)
            return START_NOT_STICKY
        }

        instance = this
        // A short exchange may finish before Android delivers onStartCommand. Promote
        // before stopping to fulfill the original foreground-service start request.
        if (requestedRun == null) {
            finish()
        } else if (cpuLock == null) {
            // Foreground importance alone does not keep the CPU awake during lock screen.
            cpuLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_MS)
                }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (instance === this) requestedRun = null
        finish()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            requestedRun = null
        }
        releaseCpuLock()
        super.onDestroy()
    }

    private fun promotionFailed(error: RuntimeException) {
        Log.w(TAG, "Agent foreground promotion unavailable (${error.javaClass.simpleName})")
        requestedRun = null
        finish()
    }

    private fun finish() {
        if (instance === this) instance = null
        releaseCpuLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCpuLock() {
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = null
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.agent_foreground_channel), NotificationManager.IMPORTANCE_LOW
            )
        )
        val openApp = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, "assistant"),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.agent_foreground_title))
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PennyPilotAgent"
        private const val CHANNEL_ID = "agent_foreground"
        private const val NOTIFICATION_ID = 90004
        private const val MAX_WAKE_MS = 8 * 60_000L
        private var requestedRun: String? = null
        private var instance: AgentForegroundService? = null

        @MainThread
        fun start(context: Context, runId: String): Boolean {
            if (requestedRun != null) return requestedRun == runId
            try {
                val service = context.startForegroundService(Intent(context, AgentForegroundService::class.java))
                if (service == null) return false
                requestedRun = runId
                return true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Agent foreground start unavailable (${error.javaClass.simpleName})")
            } catch (error: SecurityException) {
                Log.w(TAG, "Agent foreground start unavailable (${error.javaClass.simpleName})")
            }
            return false
        }

        @MainThread
        fun stop(runId: String) {
            if (requestedRun != runId) return
            requestedRun = null
            instance?.finish()
        }
    }
}

package com.pennypilot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pennypilot.app.di.AppDatabaseHolder
import com.pennypilot.app.domain.model.TxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM = "com.pennypilot.app.CONFIRM_TX"
        const val ACTION_IGNORE = "com.pennypilot.app.IGNORE_TX"
        const val EXTRA_TX_ID = "tx_id"
        const val EXTRA_CAPTURE_EVENT_KEY = "capture_event_key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val txId = intent.getLongExtra(EXTRA_TX_ID, -1L)
        if (txId < 0) return
        // 老版本通知没有事件身份，无法判断 ID 是否已被恢复后的账目复用。
        val eventKey = intent.getStringExtra(EXTRA_CAPTURE_EVENT_KEY)?.takeIf { it.isNotBlank() } ?: return
        val status = when (intent.action) {
            ACTION_CONFIRM -> TxStatus.CONFIRMED
            ACTION_IGNORE -> TxStatus.IGNORED
            else -> return
        }

        val db = AppDatabaseHolder.get(context)
        val dao = db.transactionDao()

        // BroadcastReceiver 必须 goAsync()，否则进程可能在协程完成前被系统杀死
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updated = dao.updateCapturedStatus(txId, eventKey, status)
                if (updated > 0) {
                    NotificationHelper.cancelCaptureNotification(context, txId, eventKey)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

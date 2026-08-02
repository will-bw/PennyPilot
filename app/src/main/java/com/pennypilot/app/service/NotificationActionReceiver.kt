package com.pennypilot.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.domain.model.TxStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM = "com.pennypilot.app.CONFIRM_TX"
        const val ACTION_IGNORE = "com.pennypilot.app.IGNORE_TX"
        const val EXTRA_TX_ID = "tx_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val txId = intent.getLongExtra(EXTRA_TX_ID, -1L)
        if (txId < 0) return

        val db = AppDatabase.build(context)
        val dao = db.transactionDao()

        CoroutineScope(Dispatchers.IO).launch {
            when (intent.action) {
                ACTION_CONFIRM -> dao.updateStatus(txId, TxStatus.CONFIRMED)
                ACTION_IGNORE -> dao.updateStatus(txId, TxStatus.IGNORED)
            }
            // Cancel the notification
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(txId.toInt())
        }
    }
}

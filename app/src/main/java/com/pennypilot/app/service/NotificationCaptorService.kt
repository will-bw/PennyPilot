package com.pennypilot.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.parser.ParseRuleEngine
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.util.MoneyUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotificationCaptorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentHashes = mutableMapOf<Int, Long>() // hash -> timestamp for dedup

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone"
        )
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in TARGET_PACKAGES) return

        val text = sbn.notification.extras?.let { extras ->
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            "$title $content".trim()
        } ?: return

        if (text.isBlank()) return

        // Dedup
        val hash = text.hashCode()
        val now = System.currentTimeMillis()
        synchronized(recentHashes) {
            recentHashes.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
            if (recentHashes.containsKey(hash)) return
            recentHashes[hash] = now
        }

        scope.launch {
            try {
                val db = AppDatabase.build(applicationContext)
                val rules = db.parseRuleDao().getRulesForPackage(sbn.packageName)
                val result = ParseRuleEngine.parse(text, rules) ?: return@launch

                val type = if (result.direction == "INCOME") TxType.INCOME else TxType.EXPENSE
                val amount = if (type == TxType.EXPENSE) result.amountCents else -result.amountCents

                // Find "其他" category as default
                val defaultCat = db.categoryDao().observeAll().first()
                    .find { it.name == "其他" && it.type == type }

                val txId = db.transactionDao().insert(TransactionEntity(
                    amount = amount,
                    type = type,
                    categoryId = defaultCat?.id ?: 1L,
                    merchant = result.merchant,
                    note = null,
                    occurredAt = System.currentTimeMillis(),
                    source = TxSource.NOTIFICATION,
                    status = TxStatus.PENDING,
                    rawPayload = text
                ))

                // Post confirmation notification
                val displayAmount = MoneyUtil.formatForDisplay(amount, type == TxType.EXPENSE)
                val title = "${result.merchant ?: "交易"} $displayAmount"
                val content = "${if (type == TxType.EXPENSE) "支出" else "收入"} · 待确认"
                NotificationHelper.postCaptureNotification(applicationContext, txId, title, content)
            } catch (_: Exception) {
                // Silent fail for non-parseable notifications
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, NotificationCaptorService::class.java))
    }
}

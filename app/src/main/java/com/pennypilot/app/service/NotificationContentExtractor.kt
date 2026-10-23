package com.pennypilot.app.service

import android.app.Notification
import android.service.notification.StatusBarNotification

data class CapturedNotification(
    val packageName: String,
    val notificationKey: String,
    val postedAt: Long,
    val title: String,
    val content: String,
    val issuedAt: Long = 0L
) {
    /** postTime 变化不足以证明更新；还需上游保留原通知的 when 时间。 */
    val updateIdentity: String? get() = issuedAt.takeIf { it > 0 }?.let { "$notificationKey:issued:$it" }
}

/** 选择同一通知的完整展现，避免同时拼入折叠/展开正文导致一笔金额出现多次。 */
object NotificationContentExtractor {
    fun extract(sbn: StatusBarNotification): CapturedNotification? {
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }?.filter { it.isNotBlank() }?.distinct()
            ?.joinToString("\n")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val content = bigText?.takeIf { it.isNotBlank() }
            ?: lines?.takeIf { it.isNotBlank() } ?: text
        // 不截断后再尝试匹配，避免金额或失败说明被裁掉后误判。
        if (title.length + content.length > 8192 || content.isBlank()) return null
        return CapturedNotification(sbn.packageName, sbn.key, sbn.postTime, title, content, notification.`when`)
    }

    fun shouldRecover(postedAt: Long, now: Long): Boolean =
        postedAt <= now && now - postedAt <= 10 * 60_000L
}

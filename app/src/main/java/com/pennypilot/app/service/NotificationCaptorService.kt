package com.pennypilot.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.parser.CategoryInferrer
import com.pennypilot.app.data.parser.NotificationParseResult
import com.pennypilot.app.data.parser.ParseRuleEngine
import com.pennypilot.app.di.AppDatabaseHolder
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.util.MoneyUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

/**
 * NotificationListenerService 无法使用 @AndroidEntryPoint 注入，
 * 通过 EntryPoint 按需获取 Hilt 单例（健康监视器 / LLM 客户端）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptorServiceEntryPoint {
    fun monitor(): CaptureServiceMonitor
    fun llmClient(): LLMClient
}

class NotificationCaptorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentHashes = mutableMapOf<Int, Long>() // hash -> timestamp for dedup
    private val json = Json { ignoreUnknownKeys = true }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CaptorServiceEntryPoint::class.java)
    }

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone"
        )
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        entryPoint.monitor().onServiceConnected()
        // 进程重启后按数据库回填今日捕获计数，健康卡数据不丢失
        scope.launch {
            try {
                val db = AppDatabaseHolder.get(applicationContext)
                val todayStart = LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val count = db.transactionDao().countBySourceSince(TxSource.NOTIFICATION, todayStart)
                entryPoint.monitor().setTodayCount(count)
            } catch (_: Exception) {
            }
        }
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
                // 通过持有器获取单例数据库，避免每条通知都 build 新实例
                val db = AppDatabaseHolder.get(applicationContext)
                val rules = db.parseRuleDao().getRulesForPackage(sbn.packageName)
                // 正则优先；未命中走 LLM 兜底（未配置供应商时静默丢弃，见 parseWithLlm）
                val result = ParseRuleEngine.parse(text, rules) ?: parseWithLlm(text) ?: return@launch

                val type = if (result.direction == "INCOME") TxType.INCOME else TxType.EXPENSE
                val amount = if (type == TxType.EXPENSE) result.amountCents else -result.amountCents

                // 分类推断：商户关键词命中则用命中分类，否则兜底同类型"其他"
                val categories = db.categoryDao().observeAll().first()
                val category = CategoryInferrer.infer(
                    "${result.merchant ?: ""} $text", categories, type
                ) ?: categories.find { it.name == "其他" && it.type == type }

                val txId = db.transactionDao().insert(TransactionEntity(
                    amount = amount,
                    type = type,
                    categoryId = category?.id ?: 1L,
                    merchant = result.merchant,
                    note = null,
                    occurredAt = System.currentTimeMillis(),
                    source = TxSource.NOTIFICATION,
                    status = TxStatus.PENDING,
                    rawPayload = text
                ))

                entryPoint.monitor().onNotificationCaptured()

                // Post confirmation notification
                val displayAmount = MoneyUtil.formatForDisplay(amount, type == TxType.EXPENSE)
                val title = "${result.merchant ?: "交易"} $displayAmount"
                val catName = category?.name ?: "其他"
                val content = "$catName · ${if (type == TxType.EXPENSE) "支出" else "收入"} · 待确认"
                NotificationHelper.postCaptureNotification(applicationContext, txId, title, content)
            } catch (_: Exception) {
                // Silent fail for non-parseable notifications
            }
        }
    }

    /**
     * 正则全部未命中时的 LLM 兜底解析（设计文档 §4.1）。
     * 未配置供应商/Key、超时或解析失败一律返回 null（静默丢弃）。
     */
    private suspend fun parseWithLlm(text: String): NotificationParseResult? {
        return try {
            val messages = listOf(
                Message(
                    role = "system",
                    content = """你是一个支付通知解析器。判断通知文本是否包含一笔交易。
规则：
- 输出 JSON：{"valid": true/false, "amount_cents": 整数分, "merchant": 商户名或null, "direction": "EXPENSE"或"INCOME"}
- 非交易通知（聊天消息、广告、群邀请等）返回 {"valid": false}
- 金额单位是分（1元=100分）
- 只输出JSON，不要解释"""
                ),
                Message(role = "user", content = text)
            )
            val raw = entryPoint.llmClient().chat(messages, jsonMode = true)
                .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed = json.decodeFromString(LlmNotificationParse.serializer(), raw)
            if (!parsed.valid || parsed.amountCents <= 0) null
            else NotificationParseResult(
                amountCents = parsed.amountCents,
                merchant = parsed.merchant,
                direction = if (parsed.direction == "INCOME") "INCOME" else "EXPENSE"
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        entryPoint.monitor().onServiceDisconnected()
        requestRebind(ComponentName(this, NotificationCaptorService::class.java))
    }
}

@Serializable
private data class LlmNotificationParse(
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("amount_cents") val amountCents: Long = 0,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("direction") val direction: String = "EXPENSE"
)

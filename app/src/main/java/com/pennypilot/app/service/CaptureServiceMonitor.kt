package com.pennypilot.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ScreenReadTrace(
    val status: String,
    val timestamp: Long,
    val packageName: String?,
    val diagnostics: Map<String, Int>
)

data class ServiceHealth(
    val isBound: Boolean = false,
    val isAccessibilityBound: Boolean = false,
    val lastNotificationTime: Long = 0L,
    val todayCaptureCount: Int = 0,
    val duplicateCount: Int = 0,
    val lastError: String? = null,
    val countDate: LocalDate = LocalDate.now(),
    // 仅记录诊断状态，不保存屏幕文本、聊天或输入内容。
    val lastScreenEventTime: Long = 0L,
    val lastScreenReadTime: Long = 0L,
    val lastScreenPackage: String? = null,
    val screenCaptureStatus: String = "waiting",
    val screenReadDiagnostics: Map<String, Int> = emptyMap(),
    val screenReadHistory: List<ScreenReadTrace> = emptyList()
)

@Singleton
class CaptureServiceMonitor @Inject constructor() {
    private val _health = MutableStateFlow(ServiceHealth())
    val health: StateFlow<ServiceHealth> = _health.asStateFlow()

    fun onServiceConnected() {
        _health.update { it.copy(isBound = true) }
    }

    fun onServiceDisconnected() {
        _health.update { it.copy(isBound = false) }
    }

    fun onNotificationCaptured() {
        _health.update { it.copy(lastNotificationTime = System.currentTimeMillis(), lastError = null) }
    }

    fun onAccessibilityConnected() { _health.update { it.copy(isAccessibilityBound = true, screenCaptureStatus = "waiting") } }
    fun onAccessibilityDisconnected() { _health.update { it.copy(isAccessibilityBound = false, screenCaptureStatus = "disconnected") } }
    fun onScreenEvent(packageName: String) {
        _health.update { it.copy(lastScreenEventTime = System.currentTimeMillis(), lastScreenPackage = packageName,
            screenCaptureStatus = "event_received") }
    }
    fun onScreenRead(status: String, diagnostics: Map<String, Int> = emptyMap(), packageName: String? = null) {
        val now = System.currentTimeMillis()
        _health.update { it.copy(lastScreenReadTime = now, screenCaptureStatus = status,
            screenReadDiagnostics = diagnostics.toMap()).recordScreenTrace(status, now, packageName) }
    }
    fun onScreenStatus(status: String, packageName: String? = null) {
        val now = System.currentTimeMillis()
        _health.update { it.copy(screenCaptureStatus = status).recordScreenTrace(status, now, packageName) }
    }

    private fun ServiceHealth.recordScreenTrace(status: String, now: Long, packageName: String?): ServiceHealth {
        // parsed 是中间态，不进入历史，避免每次重绘的 parsed → duplicate 挤走已保存结果。
        if (status !in setOf("unreadable", "input_page", "password_page", "incomplete_tree", "traversal_limit",
                "text_limit", "empty_page", "not_receipt", "saved", "duplicate", "error")) return this
        val trace = ScreenReadTrace(status, now, packageName ?: lastScreenPackage, screenReadDiagnostics.toMap())
        val previous = screenReadHistory.lastOrNull()
        // 同页密集重绘合并成一次；返回聊天不能抹掉刚才完成页的读取结果。
        val history = if (previous?.status == status && previous.packageName == trace.packageName)
            screenReadHistory.dropLast(1) + trace else screenReadHistory + trace
        return copy(screenReadHistory = history.takeLast(8))
    }
    fun onDuplicate() { _health.update { it.copy(duplicateCount = it.duplicateCount + 1) } }
    fun onError(message: String) { _health.update { it.copy(lastError = message) } }

    /** 服务重连/进程重启后按数据库回填今日捕获计数，避免计数丢失 */
    fun setTodayCount(count: Int) {
        _health.update { it.copy(todayCaptureCount = count, countDate = LocalDate.now()) }
    }

    fun resetDailyCount() {
        setTodayCount(0)
    }
}

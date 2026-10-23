package com.pennypilot.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceHealth(
    val isBound: Boolean = false,
    val isAccessibilityBound: Boolean = false,
    val lastNotificationTime: Long = 0L,
    val todayCaptureCount: Int = 0,
    val duplicateCount: Int = 0,
    val lastError: String? = null,
    val countDate: LocalDate = LocalDate.now()
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

    fun onAccessibilityConnected() { _health.update { it.copy(isAccessibilityBound = true) } }
    fun onAccessibilityDisconnected() { _health.update { it.copy(isAccessibilityBound = false) } }
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

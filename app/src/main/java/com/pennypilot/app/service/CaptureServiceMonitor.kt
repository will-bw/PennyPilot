package com.pennypilot.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceHealth(
    val isBound: Boolean = false,
    val lastNotificationTime: Long = 0L,
    val todayCaptureCount: Int = 0
)

@Singleton
class CaptureServiceMonitor @Inject constructor() {
    private val _health = MutableStateFlow(ServiceHealth())
    val health: StateFlow<ServiceHealth> = _health.asStateFlow()

    fun onServiceConnected() {
        _health.value = _health.value.copy(isBound = true)
    }

    fun onServiceDisconnected() {
        _health.value = _health.value.copy(isBound = false)
    }

    fun onNotificationCaptured() {
        _health.value = _health.value.copy(
            lastNotificationTime = System.currentTimeMillis(),
            todayCaptureCount = _health.value.todayCaptureCount + 1
        )
    }

    fun resetDailyCount() {
        _health.value = _health.value.copy(todayCaptureCount = 0)
    }
}

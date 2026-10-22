package com.pennypilot.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.usecase.BackupUseCase
import com.pennypilot.app.service.CaptureServiceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val serviceMonitor: CaptureServiceMonitor,
    val appPreferences: AppPreferences,
    private val backupUseCase: BackupUseCase
) : ViewModel() {
    val lastBackupAt = appPreferences.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val cloudParsingEnabled = appPreferences.notificationCloudParsingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()
    private val _savingCloudPreference = MutableStateFlow(false)
    val savingCloudPreference = _savingCloudPreference.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun setCloudParsingEnabled(enabled: Boolean) {
        if (_savingCloudPreference.value) return
        _savingCloudPreference.value = true
        viewModelScope.launch {
            try {
                appPreferences.setNotificationCloudParsingEnabled(enabled)
                _message.value = if (enabled) "通知云端解析已开启" else "通知云端解析已关闭"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _message.value = "设置未保存，请重试"
            } finally { _savingCloudPreference.value = false }
        }
    }

    fun clearMessage() { _message.value = null }

    fun exportDb(context: Context, destination: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                backupUseCase.exportDb(context.applicationContext, destination).getOrThrow()
                _message.value = try {
                    appPreferences.setLastBackupAt(System.currentTimeMillis())
                    "备份已保存到所选位置"
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { "备份已保存，但上次备份时间未能更新" }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _message.value = "备份失败，请检查保存位置与可用空间后重试"
            } finally { _isBusy.value = false }
        }
    }

    fun restoreDb(context: Context, documents: List<Uri>) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                backupUseCase.restoreDb(context.applicationContext, documents).getOrThrow()
                _message.value = "账本已恢复，立即生效"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { _message.value = "恢复失败，原账本未改动。请检查备份文件是否完整。"
            } finally { _isBusy.value = false }
        }
    }
}

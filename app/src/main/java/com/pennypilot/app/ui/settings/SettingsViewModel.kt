package com.pennypilot.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.usecase.BackupUseCase
import com.pennypilot.app.service.CaptureServiceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun setCloudParsingEnabled(enabled: Boolean) = viewModelScope.launch {
        appPreferences.setNotificationCloudParsingEnabled(enabled)
    }

    fun clearMessage() { _message.value = null }

    fun exportDb(context: Context, destination: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = backupUseCase.exportDb(context.applicationContext, destination)
                result.onSuccess { appPreferences.setLastBackupAt(System.currentTimeMillis()) }
                _message.value = result.fold({ "备份已保存到所选位置" }, { "备份失败：${it.message}" })
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun restoreDb(context: Context, documents: List<Uri>) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val result = backupUseCase.restoreDb(context.applicationContext, documents)
                _message.value = result.fold({ "账本已恢复，立即生效" }, { "恢复失败，原账本未改动：${it.message}" })
            } finally {
                _isBusy.value = false
            }
        }
    }
}

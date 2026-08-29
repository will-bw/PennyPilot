package com.pennypilot.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.domain.usecase.BackupUseCase
import com.pennypilot.app.service.CaptureServiceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val serviceMonitor: CaptureServiceMonitor,
    val appPreferences: AppPreferences,
    private val backupUseCase: BackupUseCase
) : ViewModel() {

    /** 最近备份时间戳（0 = 未备份），设置页显示"X月X日" */
    val lastBackupAt = appPreferences.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun exportDb(context: Context): Result<String> {
        val result = backupUseCase.exportDb(context)
        result.onSuccess {
            viewModelScope.launch { appPreferences.setLastBackupAt(System.currentTimeMillis()) }
        }
        return result
    }

    fun restoreDb(context: Context, backupPath: String): Result<Unit> =
        backupUseCase.restoreDb(context, backupPath)

    fun listBackups(): List<File> = backupUseCase.listBackups()
}

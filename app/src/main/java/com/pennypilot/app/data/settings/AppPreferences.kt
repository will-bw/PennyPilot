package com.pennypilot.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "pennypilot_prefs")

/**
 * 轻量偏好存储（设计文档 §3.8）：
 * - last_backup_at：最近备份时间，设置页显示"X月X日"
 * - onboarding_done：首次启动 ColorOS 引导完成状态
 *
 * API Key 等敏感数据仍由 [SecureKeyStore] 加密存储，不经过此处。
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastBackupAtKey = longPreferencesKey("last_backup_at")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")

    val lastBackupAt: Flow<Long> = context.dataStore.data
        .map { it[lastBackupAtKey] ?: 0L }

    val onboardingDone: Flow<Boolean> = context.dataStore.data
        .map { it[onboardingDoneKey] ?: false }

    suspend fun setLastBackupAt(timestamp: Long) {
        context.dataStore.edit { it[lastBackupAtKey] = timestamp }
    }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[onboardingDoneKey] = true }
    }
}

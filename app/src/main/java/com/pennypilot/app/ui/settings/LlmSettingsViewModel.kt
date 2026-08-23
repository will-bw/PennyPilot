package com.pennypilot.app.ui.settings

import androidx.lifecycle.ViewModel
import com.pennypilot.app.data.settings.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * AI 设置页 ViewModel：复用 Hilt 单例 [SecureKeyStore]，
 * 避免在 Composable 中重复创建 EncryptedSharedPreferences / MasterKey。
 */
@HiltViewModel
class LlmSettingsViewModel @Inject constructor(
    val keyStore: SecureKeyStore
) : ViewModel()

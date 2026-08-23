package com.pennypilot.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "pennypilot_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var deepSeekKey: String
        get() = prefs.getString("deepseek_key", "") ?: ""
        set(value) = prefs.edit().putString("deepseek_key", value).apply()

    var qwenKey: String
        get() = prefs.getString("qwen_key", "") ?: ""
        set(value) = prefs.edit().putString("qwen_key", value).apply()

    /** 当前激活供应商："deepseek" / "qwen"；空串表示未选择（无默认值，须用户手动选择） */
    var activeProvider: String
        get() = prefs.getString("active_provider", "") ?: ""
        set(value) = prefs.edit().putString("active_provider", value).apply()

    var qwenBaseUrl: String
        get() = prefs.getString("qwen_base_url", "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1") ?: "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
        set(value) = prefs.edit().putString("qwen_base_url", value).apply()

    var qwenModel: String
        get() = prefs.getString("qwen_model", "qwen-plus") ?: "qwen-plus"
        set(value) = prefs.edit().putString("qwen_model", value).apply()

    var deepSeekModel: String
        get() = prefs.getString("deepseek_model", "deepseek-chat") ?: "deepseek-chat"
        set(value) = prefs.edit().putString("deepseek_model", value).apply()
}

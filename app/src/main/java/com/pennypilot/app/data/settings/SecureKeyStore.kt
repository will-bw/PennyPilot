package com.pennypilot.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyStore(context: Context) {

    private val appContext = context.applicationContext
    var needsReconfiguration: Boolean = false
        private set
    private var prefs: SharedPreferences? = try {
        createPreferences()
    } catch (_: Exception) {
        // 系统恢复可能带回密文却没有原 Keystore 密钥。读取保持可用，等待用户重新保存配置。
        needsReconfiguration = true
        null
    }

    private fun createPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext, "pennypilot_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun read(key: String, default: String): String = try {
        prefs?.getString(key, default) ?: default
    } catch (_: Exception) {
        needsReconfiguration = true
        prefs = null
        default
    }

    private fun write(key: String, value: String) {
        val target = prefs ?: run {
            // 只在用户保存新配置时重建不可解密的密钥存储，不回退到明文。
            appContext.deleteSharedPreferences("pennypilot_secrets")
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            createPreferences().also { prefs = it }
        }
        check(target.edit().putString(key, value).commit()) { "AI 配置保存失败，请重试" }
    }

    fun clearRecoveryNotice() { needsReconfiguration = false }

    var deepSeekKey: String
        get() = read("deepseek_key", "")
        set(value) = write("deepseek_key", value)

    var qwenKey: String
        get() = read("qwen_key", "")
        set(value) = write("qwen_key", value)

    /** 当前激活供应商：chatgpt / deepseek / qwen；空串表示未选择。 */
    var activeProvider: String
        get() = read("active_provider", "")
        set(value) = write("active_provider", value)

    var qwenBaseUrl: String
        get() = read("qwen_base_url", "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        set(value) = write("qwen_base_url", value)

    var qwenModel: String
        get() = read("qwen_model", "qwen-plus")
        set(value) = write("qwen_model", value)

    var deepSeekModel: String
        get() = read("deepseek_model", "deepseek-chat")
        set(value) = write("deepseek_model", value)

    var recognitionTextProvider: String
        get() = read("recognition_text_provider", "")
        set(value) = write("recognition_text_provider", value.trim())
    var recognitionVisionProvider: String
        get() = read("recognition_vision_provider", "")
        set(value) = write("recognition_vision_provider", value.trim())
    var recognitionReviewProvider: String
        get() = read("recognition_review_provider", "")
        set(value) = write("recognition_review_provider", value.trim())

    /** 空值复用当前服务的通用模型；每种能力独立固定版本。 */
    var recognitionTextModel: String
        get() = read("recognition_text_model", "")
        set(value) = write("recognition_text_model", value.trim())
    var recognitionVisionModel: String
        get() = read("recognition_vision_model", "")
        set(value) = write("recognition_vision_model", value.trim())
    var recognitionReviewModel: String
        get() = read("recognition_review_model", "")
        set(value) = write("recognition_review_model", value.trim())

    /** 不同 ChatGPT 工作空间的模型权限可能不同，因此按注册账号分别保存。 */
    fun chatGptModel(accountId: String): String = read("chatgpt_model_$accountId", "")

    fun setChatGptModel(accountId: String, model: String) {
        require(accountId.isNotBlank() && model.isNotBlank())
        write("chatgpt_model_$accountId", model)
    }
}

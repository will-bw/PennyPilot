package com.pennypilot.app.data.openai.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString

internal interface ChatGptCredentialStore {
    fun read(): AuthSnapshot?
    fun write(snapshot: AuthSnapshot)
}

/** The complete token rotation is one encrypted, synchronous SharedPreferences transaction. */
internal class EncryptedChatGptCredentialStore(context: Context) : ChatGptCredentialStore {
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext, "pennypilot_chatgpt_auth_master_key")
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, "pennypilot_chatgpt_auth", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    override fun read(): AuthSnapshot? = preferences.getString("snapshot", null)?.let {
        ChatGptAuthProtocol.json.decodeFromString<AuthSnapshot>(it)
    }
    override fun write(snapshot: AuthSnapshot) {
        check(preferences.edit().putString("snapshot", ChatGptAuthProtocol.json.encodeToString(snapshot)).commit()) {
            "ChatGPT 安全凭据保存失败"
        }
    }
    // Keystore recovery errors deliberately propagate. Never delete another provider's key or fall back to plaintext.
}

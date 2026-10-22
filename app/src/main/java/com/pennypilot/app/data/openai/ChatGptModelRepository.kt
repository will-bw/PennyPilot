package com.pennypilot.app.data.openai

import com.pennypilot.app.data.openai.auth.ChatGptAuthManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class ChatGptModel(val slug: String, val displayName: String)

@Singleton
class ChatGptModelRepository internal constructor(private val access: ChatGptAccess, private val http: OpenAiHttp) {
    @Inject constructor(auth: ChatGptAuthManager) : this(AuthManagerAccess(auth), OpenAiHttp())

    /** Never cache another account's catalog or infer entitlement from a visible entry. */
    suspend fun listModels(): List<ChatGptModel> {
        access.awaitReady()
        val accountId = access.requireAccount()
        val token = requireBearerToken(access.token())
        access.requireSameAccount(accountId)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        val models = http.request(request) { response ->
            val source = response.body?.source() ?: throw OpenAiErrors.protocol("模型目录为空", response.header("x-request-id"))
            parseModels(source.readBoundedUtf8(2 * 1024 * 1024L))
        }
        access.requireSameAccount(accountId)
        return models
    }

    internal companion object {
        fun parseModels(body: String): List<ChatGptModel> {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val entries = root?.get("models") as? JsonArray ?: throw OpenAiErrors.protocol("模型目录格式无效")
            return entries.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                if (item.string("visibility") != "list") return@mapNotNull null
                val slug = item.string("slug")?.takeIf { it.isNotBlank() && it.length <= 256 && !it.any(Char::isISOControl) }
                    ?: return@mapNotNull null
                val label = item.string("display_name")?.takeIf { it.isNotBlank() && it.length <= 256 && !it.any(Char::isISOControl) }
                    ?: return@mapNotNull null
                ChatGptModel(slug, label)
            }.distinctBy { it.slug }
        }
    }
}

package com.pennypilot.app.data.openai.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

internal class OAuthEndpointException(val errorCode: String?, val status: Int) : Exception("ChatGPT 认证服务请求失败")
internal interface ChatGptAuthTransport {
    suspend fun exchange(request: AuthorizationRequest, code: String, issuedClientId: String): JsonObject
    suspend fun refresh(clientId: String, refreshToken: String): JsonObject
    suspend fun verifyIdentity(token: String, clientId: String, nonce: String?, nowSeconds: Long): VerifiedIdentity
    suspend fun revoke(clientId: String, refreshToken: String): Boolean
}

internal class OfficialChatGptAuthTransport : ChatGptAuthTransport {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override suspend fun exchange(request: AuthorizationRequest, code: String, issuedClientId: String): JsonObject =
        tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", issuedClientId)
            .add("code", code).add("code_verifier", request.verifier).add("redirect_uri", request.redirectUri)
            .add("resource", OPENAI_RESOURCE).build())

    override suspend fun refresh(clientId: String, refreshToken: String): JsonObject =
        tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId)
            .add("refresh_token", refreshToken).add("resource", OPENAI_RESOURCE).build())

    private suspend fun tokenRequest(body: FormBody): JsonObject = requestJson(
        Request.Builder().url("$OPENAI_ISSUER/api/accounts/oauth/token").post(singleUseFormBody(body)).build()
    )

    private suspend fun metadata(): JsonObject = requestJson(
        Request.Builder().url("$OPENAI_ISSUER/.well-known/openid-configuration").build()
    ).also { if (it["issuer"]?.jsonPrimitive?.content != OPENAI_ISSUER) throw AuthProtocolException("ChatGPT 认证服务配置无效") }

    override suspend fun verifyIdentity(token: String, clientId: String, nonce: String?, nowSeconds: Long): VerifiedIdentity {
        val jwksUrl = metadata()["jwks_uri"]?.jsonPrimitive?.contentOrNull
            ?: throw AuthProtocolException("ChatGPT 认证服务配置无效")
        val jwks = requestJson(Request.Builder().url(ChatGptAuthProtocol.trustedAuthUrl(jwksUrl)).build())
        return ChatGptAuthProtocol.verifyIdToken(token, jwks, clientId, nonce, nowSeconds)
    }

    override suspend fun revoke(clientId: String, refreshToken: String): Boolean {
        try {
            val endpoint = metadata()["revocation_endpoint"]?.jsonPrimitive?.contentOrNull ?: return false
            val request = Request.Builder().url(ChatGptAuthProtocol.trustedAuthUrl(endpoint)).post(
                FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token")
                    .add("client_id", clientId).build()
            ).build()
            repeat(2) { attempt ->
                val status = try {
                    withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { 503 }
                if (status == 200) return true
                if (status < 500) return false
                if (attempt == 0) delay(600)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        return false
    }

    private suspend fun requestJson(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val source = response.body?.source()
                source?.request(1_048_577)
                val bytes = source?.buffer?.readByteArray() ?: byteArrayOf()
                if (bytes.size > 1_048_576) throw AuthProtocolException("ChatGPT 认证服务返回异常，请稍后重试")
                val body = runCatching { ChatGptAuthProtocol.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    val code = body?.get("error")?.let { error ->
                        if (error is JsonObject) error["code"]?.jsonPrimitive?.contentOrNull else error.jsonPrimitive.contentOrNull
                    }?.takeIf { it.length <= 100 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
                    throw OAuthEndpointException(code, response.code)
                }
                body ?: throw AuthProtocolException("ChatGPT 认证服务返回异常，请稍后重试")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: OAuthEndpointException) { throw known }
        catch (known: AuthProtocolException) { throw known }
        catch (_: Exception) { throw AuthProtocolException("无法连接 ChatGPT 认证服务，请检查网络后重试") }
    }
}

internal fun parseTokenResponse(body: JsonObject, nowSeconds: Long, previous: StoredTokens? = null): StoredTokens {
    try {
        val access = body["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("no token")
        require(body["token_type"]?.jsonPrimitive?.contentOrNull.equals("Bearer", ignoreCase = true))
        val expires = body["expires_in"]?.jsonPrimitive?.longOrNull ?: error("no expiry")
        require(expires in 1..2_678_400)
        val scopes = body["scope"]?.jsonPrimitive?.contentOrNull?.split(' ')?.filter { it.isNotBlank() }?.toSet()
            ?: previous?.scopes ?: emptySet()
        return StoredTokens(
            accessToken = access,
            refreshToken = body["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: previous?.refreshToken,
            idToken = body["id_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: previous?.idToken,
            scopes = scopes, expiresAt = nowSeconds + expires,
            earliestRefreshAt = body["earliest_refresh_at"]?.jsonPrimitive?.longOrNull ?: 0
        )
    } catch (_: Exception) { throw AuthProtocolException("ChatGPT 登录凭据不完整，请重新登录") }
}

/** A consumed authorization code or rotating refresh token must never be automatically replayed. */
internal fun singleUseFormBody(body: FormBody): RequestBody = object : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}

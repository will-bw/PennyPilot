package com.pennypilot.app.data.openai.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal class AuthProtocolException(val safeMessage: String) : Exception(safeMessage)
internal data class VerifiedIdentity(val subject: String, val email: String)
internal data class AuthorizationRequest(
    val clientId: String,
    val hostId: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val verifier: String,
    val loginHint: String? = null,
    // ID tokens deliberately stay out of public URLs/state. Email is sufficient for returning accounts.
    val requestPlanPermission: Boolean = false
) {
    override fun toString() = "AuthorizationRequest([REDACTED])"
}

internal object ChatGptAuthProtocol {
    val json = Json { ignoreUnknownKeys = true }
    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun sameSecret(actual: String?, expected: String): Boolean = actual != null &&
        MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    fun authorizationUrl(request: AuthorizationRequest): String =
        "$OPENAI_ISSUER/api/accounts/authorize".toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", request.clientId)
            if (request.clientId == DYNAMIC_CLIENT_ID) addQueryParameter("agent_name_hint", "PennyPilot")
            addQueryParameter("ext_agent_host_id", request.hostId)
            request.loginHint?.takeIf { it.isNotBlank() }?.let { addQueryParameter("login_hint", it) }
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", request.redirectUri)
            addQueryParameter("scope", "openid profile email offline_access resource.invoke $PLAN_SCOPE")
            addQueryParameter("resource", OPENAI_RESOURCE)
            addQueryParameter("state", request.state)
            addQueryParameter("nonce", request.nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge(request.verifier))
            if (request.requestPlanPermission) addQueryParameter("prompt", "consent")
        }.build().toString()

    fun issuedClientId(pending: String, returned: String?): String {
        if (pending == DYNAMIC_CLIENT_ID) {
            return returned?.takeIf { it.isNotBlank() && it != DYNAMIC_CLIENT_ID && it.length <= 512 }
                ?: throw AuthProtocolException("ChatGPT 未返回有效的应用注册信息，请重新登录")
        }
        if (returned != null && returned != pending) throw AuthProtocolException("ChatGPT 返回的账号注册信息不匹配，请重新登录")
        return pending
    }

    /** Only same-origin, HTTPS discovery endpoints are trusted, never a location supplied by a JWT. */
    fun trustedAuthUrl(value: String): String {
        val url = runCatching { value.toHttpUrl() }.getOrNull()
            ?: throw AuthProtocolException("ChatGPT 认证服务配置无效")
        if (url.scheme != "https" || url.host != "auth.openai.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            throw AuthProtocolException("ChatGPT 认证服务配置无效")
        }
        return url.toString()
    }

    fun verifyIdToken(token: String, jwks: JsonObject, clientId: String, nonce: String?, nowSeconds: Long): VerifiedIdentity {
        try {
            val segments = token.split('.')
            require(segments.size == 3 && segments.all { it.length in 1..32768 })
            fun decode(part: String) = Base64.getUrlDecoder().decode(part)
            val header = json.parseToJsonElement(decode(segments[0]).toString(Charsets.UTF_8)).jsonObject
            require(header["alg"]?.jsonPrimitive?.content == "RS256")
            require(header["crit"] == null)
            val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: error("missing kid")
            val keys = jwks["keys"] as? JsonArray ?: error("missing keys")
            val matching = keys.map { it.jsonObject }.filter {
                it["kid"]?.jsonPrimitive?.contentOrNull == kid && it["kty"]?.jsonPrimitive?.content == "RSA" &&
                    (it["use"] == null || it["use"]?.jsonPrimitive?.content == "sig") &&
                    (it["alg"] == null || it["alg"]?.jsonPrimitive?.content == "RS256")
            }
            require(matching.size == 1)
            val key = matching.single()
            val modulus = BigInteger(1, decode(key.getValue("n").jsonPrimitive.content))
            val exponent = BigInteger(1, decode(key.getValue("e").jsonPrimitive.content))
            require(modulus.bitLength() >= 2048 && exponent.signum() > 0)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update("${segments[0]}.${segments[1]}".toByteArray(Charsets.US_ASCII))
            require(signature.verify(decode(segments[2])))
            val claims = json.parseToJsonElement(decode(segments[1]).toString(Charsets.UTF_8)).jsonObject
            require(claims["iss"]?.jsonPrimitive?.content == OPENAI_ISSUER)
            val audiences = when (val audience = claims["aud"]) {
                is JsonArray -> audience.map { it.jsonPrimitive.content }
                else -> listOfNotNull(audience?.jsonPrimitive?.contentOrNull)
            }
            require(clientId in audiences)
            if (audiences.size > 1 || claims["azp"] != null) require(claims["azp"]?.jsonPrimitive?.content == clientId)
            require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) > nowSeconds)
            claims["nbf"]?.jsonPrimitive?.longOrNull?.let { require(it <= nowSeconds + 60) }
            claims["iat"]?.jsonPrimitive?.longOrNull?.let { require(it <= nowSeconds + 60) }
            if (nonce != null) require(sameSecret(claims["nonce"]?.jsonPrimitive?.contentOrNull, nonce))
            val subject = claims["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("missing sub")
            val email = claims["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "未提供邮箱"
            return VerifiedIdentity(subject, email)
        } catch (_: Exception) {
            // Never include JWT, claims, key material or a parser exception in an exposed failure.
            throw AuthProtocolException("ChatGPT 身份验证未通过，请重新登录")
        }
    }
}

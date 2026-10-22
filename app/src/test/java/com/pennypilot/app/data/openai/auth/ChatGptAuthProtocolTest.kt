package com.pennypilot.app.data.openai.auth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptAuthProtocolTest {
    private val now = 1_800_000_000L
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun claims(vararg changes: Pair<String, JsonPrimitive>) = buildJsonObject {
        put("iss", OPENAI_ISSUER); put("aud", "oaiapp_test"); put("exp", now + 3600)
        put("iat", now); put("nonce", "test-nonce"); put("sub", "subject-1"); put("email", "user@example.test")
        changes.forEach { (key, value) -> put(key, value) }
    }
    private fun token(claims: JsonObject = claims(), alg: String = "RS256"): String {
        val header = encoded("""{"alg":"$alg","kid":"test-key"}""".toByteArray())
        val payload = encoded(claims.toString().toByteArray())
        val signing = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(signing.toByteArray()) }.sign()
        return "$signing.${encoded(signature)}"
    }
    private fun jwks(): JsonObject {
        val key = pair.public as RSAPublicKey
        return buildJsonObject { put("keys", buildJsonArray { add(buildJsonObject {
            put("kid", "test-key"); put("kty", "RSA"); put("alg", "RS256"); put("use", "sig")
            put("n", encoded(key.modulus.toByteArray())); put("e", encoded(key.publicExponent.toByteArray()))
        }) }) }
    }
    private fun verify(value: String, nonce: String? = "test-nonce") = ChatGptAuthProtocol.verifyIdToken(value, jwks(), "oaiapp_test", nonce, now)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected verification failure") } catch (error: AuthProtocolException) {
            assertEquals("ChatGPT 身份验证未通过，请重新登录", error.message)
        }
    }

    @Test fun `authorization and refresh forms are one shot and preserve encoded body`() {
        val body = singleUseFormBody(FormBody.Builder().add("refresh_token", "test+token").build())
        assertTrue(body.isOneShot())
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals("refresh_token=test%2Btoken", buffer.readUtf8())
    }
    @Test fun `PKCE matches RFC7636 known vector and secrets are unique`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val secrets = (1..100).map { ChatGptAuthProtocol.randomSecret() }
        assertEquals(100, secrets.toSet().size)
        assertTrue(secrets.all { it.matches(Regex("[A-Za-z0-9_-]{43}")) })
        assertFalse(ChatGptAuthProtocol.sameSecret(null, "state"))
        assertFalse(ChatGptAuthProtocol.sameSecret("wrong", "state"))
    }
    @Test fun `dynamic and returning authorization use official host scopes and stable registration`() {
        val request = AuthorizationRequest(DYNAMIC_CLIENT_ID, "urn:uuid:test", "http://127.0.0.1:4567/auth/callback", "state", "nonce", "verifier")
        val url = ChatGptAuthProtocol.authorizationUrl(request).toHttpUrl()
        assertEquals("auth.openai.com", url.host)
        assertEquals("PennyPilot", url.queryParameter("agent_name_hint"))
        assertEquals(OPENAI_RESOURCE, url.queryParameter("resource"))
        assertTrue(url.queryParameter("scope")!!.contains(PLAN_SCOPE))
        assertNull(url.queryParameter("prompt"))
        val returning = ChatGptAuthProtocol.authorizationUrl(request.copy(clientId = "oaiapp_saved", requestPlanPermission = true)).toHttpUrl()
        assertNull(returning.queryParameter("agent_name_hint"))
        assertEquals("consent", returning.queryParameter("prompt"))
        assertEquals("urn:uuid:test", returning.queryParameter("ext_agent_host_id"))
    }
    @Test fun `returning client cannot be replaced and dynamic placeholder cannot be issued`() {
        assertEquals("oaiapp_saved", ChatGptAuthProtocol.issuedClientId("oaiapp_saved", null))
        for ((pending, returned) in listOf("oaiapp_saved" to "oaiapp_other", DYNAMIC_CLIENT_ID to DYNAMIC_CLIENT_ID, DYNAMIC_CLIENT_ID to null)) {
            try { ChatGptAuthProtocol.issuedClientId(pending, returned); fail() } catch (_: AuthProtocolException) { }
        }
    }
    @Test fun `valid RSA signature claims and nonce verify`() {
        assertEquals(VerifiedIdentity("subject-1", "user@example.test"), verify(token()))
    }
    @Test fun `tampered signature is rejected without leaking token`() {
        val jwt = token()
        val signatureStart = jwt.lastIndexOf('.') + 1
        val tampered = jwt.take(signatureStart) + (if (jwt[signatureStart] == 'A') 'B' else 'A') + jwt.drop(signatureStart + 1)
        rejects { verify(tampered) }
    }
    @Test fun `wrong issuer audience expiry nonce subject and algorithm are rejected`() {
        for (claim in listOf("iss" to JsonPrimitive("https://evil.test"), "aud" to JsonPrimitive("other-client"),
            "exp" to JsonPrimitive(now), "nonce" to JsonPrimitive("wrong"), "sub" to JsonPrimitive(""), "nbf" to JsonPrimitive(now + 120))) {
            rejects { verify(token(claims(claim))) }
        }
        rejects { verify(token(alg = "none")) }
        rejects { verify(token(), "another-nonce") }
    }
    @Test fun `only pinned HTTPS auth metadata and JWKS endpoints are accepted`() {
        assertEquals("https://auth.openai.com/.well-known/jwks.json", ChatGptAuthProtocol.trustedAuthUrl("https://auth.openai.com/.well-known/jwks.json"))
        for (url in listOf("http://auth.openai.com/jwks", "https://evil.test/jwks", "https://auth.openai.com:8443/jwks", "https://user@auth.openai.com/jwks")) {
            try { ChatGptAuthProtocol.trustedAuthUrl(url); fail() } catch (_: AuthProtocolException) { }
        }
    }
    @Test fun `refresh replaces complete token set without inferring permission from identity`() {
        val old = StoredTokens("old-access", "old-refresh", "old-id", setOf(PLAN_SCOPE), now + 10)
        val response = buildJsonObject { put("access_token", "new-access"); put("refresh_token", "new-refresh"); put("token_type", "Bearer"); put("expires_in", 3600); put("scope", "openid email") }
        val tokens = parseTokenResponse(response, now, old)
        assertEquals("new-refresh", tokens.refreshToken)
        assertEquals(now + 3600, tokens.expiresAt)
        assertFalse(PLAN_SCOPE in tokens.scopes)
        assertFalse(tokens.toString().contains("new-access"))
        val noScopes = JsonObject(response.filterKeys { it != "scope" })
        assertTrue(PLAN_SCOPE in parseTokenResponse(noScopes, now, old).scopes)
    }
}

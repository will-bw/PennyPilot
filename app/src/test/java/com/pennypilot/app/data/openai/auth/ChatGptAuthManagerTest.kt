package com.pennypilot.app.data.openai.auth

import com.pennypilot.app.data.llm.UserFacingLlmException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class ChatGptAuthManagerTest {
    private val now = 1_800_000_000L
    private val scopes = mutableListOf<CoroutineScope>()
    private class MemoryStore(var saved: AuthSnapshot? = null) : ChatGptCredentialStore {
        var failRead = false
        var writes = 0
        override fun read(): AuthSnapshot? { if (failRead) error("secret diagnostic"); return saved }
        override fun write(snapshot: AuthSnapshot) { saved = snapshot; writes++ }
    }
    private inner class Transport : ChatGptAuthTransport {
        val refreshCount = AtomicInteger()
        var refreshGate: CompletableDeferred<Unit>? = null
        var refreshEntered = CompletableDeferred<Unit>()
        var refreshFailure: Exception? = null
        var exchangedClient: String? = null
        var exchangeRequest: AuthorizationRequest? = null
        var identity = VerifiedIdentity("subject-1", "user@example.test")
        var planPermission = true
        var revokedToken: String? = null
        var revocationResult = true
        var revocationThrows = false
        override suspend fun exchange(request: AuthorizationRequest, code: String, issuedClientId: String): JsonObject {
            exchangeRequest = request; exchangedClient = issuedClientId
            return response()
        }
        override suspend fun refresh(clientId: String, refreshToken: String): JsonObject {
            refreshCount.incrementAndGet(); refreshEntered.complete(Unit); refreshGate?.await()
            refreshFailure?.let { throw it }
            return response()
        }
        override suspend fun verifyIdentity(token: String, clientId: String, nonce: String?, nowSeconds: Long) = identity
        override suspend fun revoke(clientId: String, refreshToken: String): Boolean {
            revokedToken = refreshToken
            if (revocationThrows) error("secret internal detail")
            return revocationResult
        }
        private fun response() = buildJsonObject {
            put("access_token", "new-access-secret"); put("refresh_token", "new-refresh-secret")
            put("id_token", "new-id-secret"); put("token_type", "Bearer"); put("expires_in", 3600)
            put("scope", if (planPermission) "openid email $PLAN_SCOPE" else "openid email")
        }
    }
    private fun saved(expiry: Long = now + 10) = AuthSnapshot("urn:uuid:host-1", "oaiapp_saved", listOf(
        StoredAccount("oaiapp_saved", "user@example.test", "subject-1", StoredTokens(
            "old-access-secret", "old-refresh-secret", "old-id-secret", setOf(PLAN_SCOPE), expiry
        ))
    ))
    private fun manager(store: MemoryStore, transport: Transport): ChatGptAuthManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return ChatGptAuthManager(store, transport, scope) { now }
    }
    @After fun cleanup() { scopes.forEach { it.cancel() } }
    private fun callback(url: String, clientId: String? = null) {
        val authorize = url.toHttpUrl()
        val redirect = authorize.queryParameter("redirect_uri")!!.toHttpUrl()
        val target = redirect.newBuilder().addQueryParameter("state", authorize.queryParameter("state"))
            .addQueryParameter("code", "test-code").apply { clientId?.let { addQueryParameter("client_id", it) } }.build()
        Socket("127.0.0.1", redirect.port).use {
            it.soTimeout = 3000
            it.getOutputStream().write("GET ${target.encodedPath}?${target.encodedQuery} HTTP/1.1\r\nHost: 127.0.0.1:${redirect.port}\r\n\r\n".toByteArray())
            assertTrue(it.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
        }
    }
    private suspend fun completed(manager: ChatGptAuthManager) = withTimeout(5000) { manager.state.first { !it.isAuthorizing } }

    @Test fun `concurrent callers refresh only once and receive complete rotated credentials`() = runBlocking {
        val store = MemoryStore(saved())
        val transport = Transport().apply { refreshGate = CompletableDeferred() }
        val manager = manager(store, transport)
        val first = async { manager.getAccessToken() }
        transport.refreshEntered.await()
        val second = async { manager.getAccessToken() }
        transport.refreshGate!!.complete(Unit)
        assertEquals("new-access-secret", first.await())
        assertEquals("new-access-secret", second.await())
        assertEquals(1, transport.refreshCount.get())
        assertEquals("new-refresh-secret", store.saved!!.accounts.single().tokens!!.refreshToken)
        assertFalse(manager.state.value.toString().contains("secret"))
    }
    @Test fun `logout after in flight refresh revokes newest token and cannot resurrect account`() = runBlocking {
        val store = MemoryStore(saved())
        val transport = Transport().apply { refreshGate = CompletableDeferred() }
        val manager = manager(store, transport)
        val refresh = async { manager.getAccessToken() }
        transport.refreshEntered.await()
        val logout = async { manager.signOut("oaiapp_saved") }
        transport.refreshGate!!.complete(Unit)
        refresh.await(); assertNull(logout.await())
        assertEquals("new-refresh-secret", transport.revokedToken)
        assertNull(store.saved!!.accounts.single().tokens)
        assertEquals("subject-1", store.saved!!.accounts.single().subject)
        assertEquals("urn:uuid:host-1", store.saved!!.hostId)
        assertNull(manager.state.value.activeAccount)
    }
    @Test fun `cancelling caller during refresh still saves rotation before next request`() = runBlocking {
        val store = MemoryStore(saved())
        val transport = Transport().apply { refreshGate = CompletableDeferred() }
        val manager = manager(store, transport)
        val request = async { manager.getAccessToken() }
        transport.refreshEntered.await()
        request.cancel()
        transport.refreshGate!!.complete(Unit)
        request.join()
        assertEquals("new-refresh-secret", store.saved!!.accounts.single().tokens!!.refreshToken)
        assertEquals("new-access-secret", manager.getAccessToken())
        assertEquals(1, transport.refreshCount.get())
    }
    @Test fun `transient refresh preserves tokens but terminal refresh clears them without secret error`() = runBlocking {
        val store = MemoryStore(saved())
        val transport = Transport().apply { refreshFailure = OAuthEndpointException(null, 503) }
        val manager = manager(store, transport)
        try { manager.getAccessToken(); fail() } catch (error: UserFacingLlmException) { assertFalse(error.message!!.contains("secret")) }
        assertEquals("old-refresh-secret", store.saved!!.accounts.single().tokens!!.refreshToken)
        transport.refreshFailure = OAuthEndpointException("invalid_grant", 400)
        try { manager.getAccessToken(); fail() } catch (_: UserFacingLlmException) { }
        assertNull(store.saved!!.accounts.single().tokens)
        assertEquals("oaiapp_saved", manager.state.value.accounts.single().id)
    }
    @Test fun `unconfirmed or failed revocation still clears local tokens and returns truthful notice`() = runBlocking {
        for (throws in listOf(false, true)) {
            val store = MemoryStore(saved())
            val manager = manager(store, Transport().apply { revocationResult = false; revocationThrows = throws })
            assertTrue(manager.signOut("oaiapp_saved")!!.contains("未能确认"))
            assertNull(store.saved!!.accounts.single().tokens)
        }
    }
    @Test fun `new sign in validates issued client and shows welcome only for first plan grant`() = runBlocking {
        val store = MemoryStore()
        val transport = Transport().apply { planPermission = false }
        val manager = manager(store, transport)
        val firstUrl = manager.beginSignIn()
        assertEquals(DYNAMIC_CLIENT_ID, firstUrl.toHttpUrl().queryParameter("client_id"))
        callback(firstUrl, "oaiapp_new")
        val identityOnly = completed(manager)
        assertTrue(identityOnly.activeAccount!!.isSignedIn)
        assertFalse(identityOnly.activeAccount!!.canUsePlan)
        assertFalse(identityOnly.showWelcome)
        assertEquals("oaiapp_new", transport.exchangedClient)
        try { manager.getAccessToken(); fail() } catch (_: UserFacingLlmException) { }
        transport.planPermission = true
        val grantUrl = manager.beginSignIn("oaiapp_new", requestPlanPermission = true)
        callback(grantUrl)
        assertTrue(completed(manager).showWelcome)
        assertTrue(store.saved!!.accounts.single().welcomeShown)
        val restarted = manager(store, transport)
        restarted.awaitReady()
        assertFalse(restarted.state.value.showWelcome)
        manager.dismissWelcome()
        val returning = manager.beginSignIn("oaiapp_new")
        callback(returning)
        assertFalse(completed(manager).showWelcome)
        assertEquals(firstUrl.toHttpUrl().queryParameter("ext_agent_host_id"), returning.toHttpUrl().queryParameter("ext_agent_host_id"))
        assertNull(returning.toHttpUrl().queryParameter("agent_name_hint"))
    }
    @Test fun `returning subject mismatch leaves current account and credentials intact`() = runBlocking {
        val store = MemoryStore(saved(now + 1000))
        val transport = Transport().apply { identity = VerifiedIdentity("different-subject", "other@example.test") }
        val manager = manager(store, transport)
        callback(manager.beginSignIn("oaiapp_saved"))
        assertNotNull(completed(manager).error)
        assertEquals("subject-1", manager.state.value.activeAccount!!.subject)
        assertEquals("old-access-secret", manager.getAccessToken())
        assertEquals(0, store.writes)
    }
    @Test fun `same email registrations stay separate and inactive logout preserves selected account`() = runBlocking {
        val initial = saved(now + 1000)
        val second = initial.accounts.single().copy(clientId = "oaiapp_second")
        val store = MemoryStore(initial.copy(accounts = initial.accounts + second))
        val manager = manager(store, Transport())
        manager.selectAccount("oaiapp_second")
        assertEquals(2, manager.state.value.accounts.size)
        manager.signOut("oaiapp_saved")
        assertEquals("oaiapp_second", manager.state.value.activeAccount!!.id)
        assertNull(store.saved!!.accounts.first { it.clientId == "oaiapp_saved" }.tokens)
        assertNotNull(store.saved!!.accounts.first { it.clientId == "oaiapp_second" }.tokens)
    }
    @Test fun `unreadable encrypted store is not replaced or downgraded`() = runBlocking {
        val store = MemoryStore().apply { failRead = true }
        val manager = manager(store, Transport())
        try { manager.beginSignIn(); fail() } catch (_: UserFacingLlmException) { }
        assertEquals(0, store.writes)
        assertTrue(manager.state.value.error!!.contains("安全存储"))
    }
}

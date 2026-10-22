package com.pennypilot.app.data.openai.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI

class LoopbackCallbackServerTest {
    private fun request(server: LoopbackCallbackServer, target: String, method: String = "GET", host: String? = null): String {
        val port = URI(server.redirectUri).port
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:$port"}\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }
    @Test fun `bad state path method and duplicate state cannot consume real callback`() = runBlocking {
        LoopbackCallbackServer("correct-state").use { server ->
            val pending = async(Dispatchers.IO) { server.awaitCallback() }
            for (target in listOf("/auth/callback?state=wrong&code=bad", "/callback?state=correct-state&code=bad", "/auth/callback?state=correct-state&state=other&code=bad")) {
                assertTrue(request(server, target).startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server, "/auth/callback?state=correct-state&code=bad", "POST").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, "/auth/callback?state=correct-state&code=bad", host = "evil.test").startsWith("HTTP/1.1 400"))
            assertFalse(pending.isCompleted)
            assertTrue(request(server, "/auth/callback?state=correct-state&code=real&client_id=oaiapp_test").startsWith("HTTP/1.1 200"))
            val callback = withTimeout(3000) { pending.await() }
            assertEquals("real", callback.code)
            assertEquals("oaiapp_test", callback.clientId)
        }
    }
    @Test fun `denial with valid state completes attempt and closing releases port`() = runBlocking {
        val server = LoopbackCallbackServer("valid")
        val port = URI(server.redirectUri).port
        val pending = async(Dispatchers.IO) { server.awaitCallback() }
        request(server, "/auth/callback?state=valid&error=access_denied")
        assertEquals("access_denied", withTimeout(3000) { pending.await() }.error)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }
    @Test fun `attempt times out and closes listener`() = runBlocking {
        val server = LoopbackCallbackServer("valid", timeoutMillis = 10)
        val port = URI(server.redirectUri).port
        try { withTimeout(3000) { server.awaitCallback() }; fail() } catch (_: AuthProtocolException) { }
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }
}

package com.pennypilot.app.data.openai.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

internal class OAuthCallback(val code: String?, val clientId: String?, val error: String?) {
    override fun toString() = "OAuthCallback([REDACTED])"
}

/** One authorization attempt, bound only to IPv4 loopback, with bounded headers and lifetime. */
internal class LoopbackCallbackServer(
    private val expectedState: String,
    private val timeoutMillis: Long = 10 * 60 * 1000L
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun awaitCallback(): OAuthCallback = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!server.isClosed && System.nanoTime() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 2000
                val request = runCatching {
                    val input = socket.getInputStream()
                    val bytes = ArrayList<Byte>()
                    while (bytes.size < 16_384) {
                        val next = input.read()
                        if (next < 0) error("incomplete request")
                        bytes.add(next.toByte())
                        if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                    }
                    require(bytes.size < 16_384)
                    bytes.toByteArray().toString(Charsets.US_ASCII)
                }.getOrNull()
                val callback = request?.let { parseRequest(it) }
                val accepted = callback != null
                val body = if (accepted) "PennyPilot 已收到授权结果。请返回应用查看登录状态。" else "请求无效，请返回 PennyPilot 重新发起登录。"
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.getOutputStream().write(
                        ("HTTP/1.1 ${if (accepted) "200 OK" else "400 Bad Request"}\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\n" +
                            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\n" +
                            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    )
                    socket.getOutputStream().write(bodyBytes)
                    socket.getOutputStream().flush()
                }
                // A bad state, malformed URL or unrelated browser request must not consume this attempt.
                if (callback != null) { close(); return@withContext callback }
            }
        }
        close()
        throw AuthProtocolException("ChatGPT 登录已超时，请重试")
    }

    private fun parseRequest(request: String): OAuthCallback? = runCatching {
        val lines = request.split("\r\n")
        val first = lines.first().split(' ')
        require(first.size == 3 && first[0] == "GET" && first[2] in setOf("HTTP/1.0", "HTTP/1.1"))
        require(first[1].substringBefore('?') == "/auth/callback")
        val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
        require(hosts.size == 1 && hosts.single().substringAfter(':').trim() == "127.0.0.1:${server.localPort}")
        val url = ("http://127.0.0.1:${server.localPort}" + first[1]).toHttpUrl()
        require(url.fragment == null)
        listOf("state", "code", "client_id", "error").forEach { require(url.queryParameterValues(it).size <= 1) }
        require(ChatGptAuthProtocol.sameSecret(url.queryParameter("state"), expectedState))
        val error = url.queryParameter("error")
        val code = url.queryParameter("code")
        require(!error.isNullOrBlank() || !code.isNullOrBlank())
        OAuthCallback(code, url.queryParameter("client_id"), error)
    }.getOrNull()

    override fun close() { runCatching { server.close() } }
}

package com.pennypilot.app.data.openai

import com.pennypilot.app.data.llm.UserFacingLlmException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request is issued once. Inference must never be automatically replayed after disconnect. */
internal class OpenAiHttp(private val calls: Call.Factory = client) {
    suspend fun <T> request(request: Request, decode: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(OpenAiErrors.network())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) {
                                // Admission may send {detail: ...}, not an API error object.
                                val body = response.body?.source()?.let { source ->
                                    // Oversized errors keep HTTP status without exposing their body.
                                    if (source.request(64 * 1024L + 1)) "" else source.readUtf8()
                                }.orEmpty()
                                throw OpenAiErrors.http(response.code, body, response.header("x-request-id"))
                            }
                            decode(response)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        val safeError = when (e) {
                            is UserFacingLlmException -> e
                            is IOException -> OpenAiErrors.network()
                            else -> OpenAiErrors.protocol("响应格式无效", response.header("x-request-id"))
                        }
                        if (continuation.isActive) continuation.resumeWithException(safeError)
                    }
                }
            })
        }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

internal fun BufferedSource.readBoundedUtf8(limit: Long): String {
    if (request(limit + 1)) throw OpenAiErrors.protocol("响应过大")
    return readUtf8()
}

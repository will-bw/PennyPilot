package com.pennypilot.app.service

import android.os.SystemClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Explicit, one-shot local diagnostics. Never written to disk, logs, backups or the model. */
object CaptureDiagnosticStore {
    private const val TTL_MS = 10 * 60 * 1_000L
    private const val MAX_BYTES = 96 * 1_024
    private var armedAt: Long? = null
    private var sample: String? = null
    private var completed = false
    internal var elapsedMillis: () -> Long = SystemClock::elapsedRealtime

    @Synchronized fun arm(): Map<String, Any?> {
        armedAt = elapsedMillis()
        sample = null
        completed = false
        return read()
    }

    @Synchronized fun clear(): Map<String, Any?> {
        armedAt = null
        sample = null
        completed = false
        return read()
    }

    @Synchronized fun read(): Map<String, Any?> {
        if (armedAt?.let { elapsedMillis() - it >= TTL_MS } == true) {
            armedAt = null
            sample = null
            completed = false
        }
        return mapOf("state" to when { completed -> "ready"; armedAt != null -> "waiting"; else -> "idle" },
            "sample" to sample)
    }

    @Synchronized fun record(packageName: String, snapshot: PaymentScreenReader.Snapshot,
        windowId: Int, eventType: Int, className: String?, paymentCandidate: Boolean) {
        if (read()["state"] != "waiting") return
        val payload = buildJsonObject {
            put("format", "pennypilot-payment-sample-v1")
            put("packageName", packageName)
            put("windowId", windowId)
            put("eventType", eventType)
            className?.let { put("className", it.take(200)) }
            put("status", snapshot.status)
            put("paymentCandidate", paymentCandidate)
            putJsonObject("counts") { snapshot.diagnostics.forEach { (key, value) -> put(key, value) } }
            // Even with diagnostic consent, never preserve raw chat, inputs or partial trees.
            if (paymentCandidate && snapshot.status == "ready" && snapshot.structure != null) {
                put("nodes", JsonArray(snapshot.structure.nodes.map { node ->
                    buildJsonObject {
                        put("id", node.id)
                        node.parentId?.let { put("parentId", it) }
                        node.className?.let { put("className", it) }
                        node.resourceId?.let { put("resourceId", it) }
                        put("visible", node.visible)
                        put("bounds", JsonArray(node.bounds.map(::JsonPrimitive)))
                        node.text?.let { put("text", redact(it)) }
                        node.description?.let { put("description", redact(it)) }
                    }
                }))
            }
        }
        val encoded = payload.toString()
        // Keep valid JSON with the reason/counters when a complex tree exceeds the export budget.
        sample = if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) encoded else
            JsonObject(payload.filterKeys { it != "nodes" } + ("nodesOmitted" to JsonPrimitive("size_limit"))).toString()
        completed = paymentCandidate && snapshot.status == "ready"
    }

    private fun redact(text: String): String = text
        .replace(Regex("""(?<![0-9])[0-9]{7,}(?![0-9])"""), "[长号码已隐藏]")
        .replace(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "[邮箱已隐藏]")
}

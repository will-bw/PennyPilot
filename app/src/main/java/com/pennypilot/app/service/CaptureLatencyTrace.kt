package com.pennypilot.app.service

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.security.MessageDigest

/** Local timing only: no payment content or identifiers survive the event-key hash. */
object CaptureLatencyTrace {
    @Serializable
    enum class Source { NOTIFICATION, ACCESSIBILITY, MODEL, SERVICE }
    @Serializable
    enum class Stage {
        SOURCE_POSTED, RECEIVED, SAVED, QUEUED, STARTED, COMPLETED,
        NOTIFICATION_SUBMITTED, NOTIFICATION_BLOCKED, NOTIFICATION_SKIPPED, CONNECTED, DISCONNECTED, FAILED
    }

    @Serializable
    data class Times(val firstAt: Long, val lastAt: Long)

    @Serializable
    data class Row(val eventId: String, val sources: Set<Source>, val stages: Map<Stage, Times>) {
        val latestAt: Long get() = stages.values.maxOf { it.lastAt }
    }

    internal const val FILE_NAME = "capture-latency-v1.json"
    internal const val RETENTION_MS = 24 * 60 * 60 * 1_000L
    internal const val MAX_EVENTS = 32
    private const val MAX_BYTES = 128 * 1_024
    private val json = Json
    private val serializer = ListSerializer(Row.serializer())
    private val eventIdPattern = Regex("[0-9a-f]{64}")
    internal var nowMillis: () -> Long = System::currentTimeMillis

    /** Diagnostics must never prevent saving or notifying a payment when storage is unavailable. */
    @Synchronized
    fun record(context: Context, eventKey: String, source: Source, stage: Stage,
        at: Long = nowMillis()): Boolean {
        val now = nowMillis()
        if (at < 0 || at > now || now - at >= RETENTION_MS) return false
        return try {
            val file = storage(context)
            // Corrupt diagnostic metadata is replaceable; no ledger or raw capture is touched.
            val previous = try { read(file) } catch (_: SerializationException) { emptyList() }
                catch (_: IllegalArgumentException) { emptyList() }
            val rows = retained(previous, now)
            val eventId = MessageDigest.getInstance("SHA-256").digest(eventKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val existing = rows.firstOrNull { it.eventId == eventId }
            val times = existing?.stages?.get(stage)
            val next = Row(eventId, existing?.sources.orEmpty() + source,
                existing?.stages.orEmpty() + (stage to Times(minOf(times?.firstAt ?: at, at), maxOf(times?.lastAt ?: at, at))))
            val updated = (rows.filterNot { it.eventId == eventId } + next).sortedBy(Row::latestAt).takeLast(MAX_EVENTS)
            if (updated != previous) write(file, updated)
            true
        } catch (_: Exception) { false }
    }

    /** Reading also expires old timings, including after the application process restarts. */
    @Synchronized
    fun snapshot(context: Context, now: Long = nowMillis()): List<Row> {
        val file = storage(context)
        val previous = read(file)
        return retained(previous, now).also { if (it != previous) write(file, it) }
    }

    fun dumpTo(context: Context, writer: PrintWriter) {
        val rows = try { snapshot(context) } catch (_: Exception) {
            writer.println("captureLatency=unavailable")
            return
        }
        writer.println("captureLatencyEvents=${rows.size}")
        rows.forEach { row ->
            val sources = row.sources.sortedBy(Source::ordinal).joinToString(",") { it.name }
            row.stages.entries.sortedBy { it.value.firstAt }.forEach { (stage, times) ->
                writer.println("captureLatency event=${row.eventId.take(16)} sources=$sources stage=${stage.name} firstAt=${times.firstAt} lastAt=${times.lastAt}")
            }
        }
    }

    private fun retained(rows: List<Row>, now: Long): List<Row> = rows.mapNotNull { row ->
        val stages = row.stages.mapNotNull { (stage, times) ->
            if (times.lastAt > now || now - times.lastAt >= RETENTION_MS) null
            else stage to if (now - times.firstAt >= RETENTION_MS) Times(times.lastAt, times.lastAt) else times
        }.toMap()
        if (stages.isEmpty()) null else row.copy(stages = stages)
    }.sortedBy(Row::latestAt).takeLast(MAX_EVENTS)

    private fun storage(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    private fun read(file: AtomicFile): List<Row> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        if (maxOf(file.baseFile.length(), File(file.baseFile.path + ".bak").length()) > MAX_BYTES)
            throw IOException("Capture latency metadata exceeds its size limit")
        return json.decodeFromString(serializer, file.readFully().toString(Charsets.UTF_8)).also { rows ->
            require(rows.size <= MAX_EVENTS && rows.map(Row::eventId).distinct().size == rows.size)
            require(rows.all { row -> eventIdPattern.matches(row.eventId) && row.sources.isNotEmpty() && row.stages.isNotEmpty() &&
                row.stages.values.all { it.firstAt >= 0 && it.lastAt >= it.firstAt } })
        }
    }

    private fun write(file: AtomicFile, rows: List<Row>) {
        if (rows.isEmpty()) { file.delete(); return }
        val bytes = json.encodeToString(serializer, rows).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}

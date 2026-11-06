package com.pennypilot.app.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pennypilot.app.service.CaptureLatencyTrace.Source
import com.pennypilot.app.service.CaptureLatencyTrace.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureLatencyTraceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file get() = File(context.noBackupFilesDir, CaptureLatencyTrace.FILE_NAME)
    private var now = 2 * CaptureLatencyTrace.RETENTION_MS

    @Before fun setUp() {
        CaptureLatencyTrace.nowMillis = { now }
        file.delete()
        File(file.path + ".bak").delete()
    }

    @After fun tearDown() {
        CaptureLatencyTrace.nowMillis = System::currentTimeMillis
        file.delete()
        File(file.path + ".bak").delete()
    }

    private fun dump(): String = StringWriter().also { CaptureLatencyTrace.dumpTo(context, PrintWriter(it)) }.toString()

    @Test fun `stored and dumped timings contain only a hash and fixed diagnostic fields`() {
        val key = "v1:com.tencent.mm:private-notification:私密店铺18元:WX202611070001"
        assertTrue(CaptureLatencyTrace.record(context, key, Source.NOTIFICATION, Stage.RECEIVED))
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val row = CaptureLatencyTrace.snapshot(context).single()
        assertEquals(expectedHash, row.eventId)
        assertEquals(setOf(Source.NOTIFICATION), row.sources)
        assertEquals(now, row.stages.getValue(Stage.RECEIVED).firstAt)
        val content = file.readText() + dump()
        listOf(key, "com.tencent.mm", "private-notification", "私密店铺", "18元", "WX202611070001").forEach {
            assertFalse("raw input escaped into diagnostics", content.contains(it))
        }
        assertEquals(context.noBackupFilesDir, file.parentFile)
        assertTrue(dump().contains("event=${expectedHash.take(16)}"))
        assertFalse(dump().contains(expectedHash))
    }

    @Test fun `source post time can precede callback and later model stages retain both sources`() {
        val postedAt = now - 15_000
        assertTrue(CaptureLatencyTrace.record(context, "one-event", Source.NOTIFICATION, Stage.RECEIVED))
        assertTrue(CaptureLatencyTrace.record(context, "one-event", Source.NOTIFICATION, Stage.SOURCE_POSTED, postedAt))
        now += 80
        assertTrue(CaptureLatencyTrace.record(context, "one-event", Source.NOTIFICATION, Stage.SAVED))
        now += 20
        assertTrue(CaptureLatencyTrace.record(context, "one-event", Source.MODEL, Stage.QUEUED))
        val row = CaptureLatencyTrace.snapshot(context).single()
        assertEquals(setOf(Source.NOTIFICATION, Source.MODEL), row.sources)
        assertEquals(15_000, row.stages.getValue(Stage.RECEIVED).firstAt - row.stages.getValue(Stage.SOURCE_POSTED).firstAt)
        assertEquals(80, row.stages.getValue(Stage.SAVED).firstAt - row.stages.getValue(Stage.RECEIVED).firstAt)
    }

    @Test fun `repeat stages preserve first latency and latest update without accumulating rows`() {
        val first = now
        CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.NOTIFICATION_SUBMITTED)
        repeat(100) {
            now += 10
            CaptureLatencyTrace.record(context, "event", Source.MODEL, Stage.NOTIFICATION_SUBMITTED)
        }
        val rows = CaptureLatencyTrace.snapshot(context)
        assertEquals(1, rows.size)
        assertEquals(CaptureLatencyTrace.Times(first, now), rows.single().stages.getValue(Stage.NOTIFICATION_SUBMITTED))
        assertEquals(1, rows.single().stages.size)
    }

    @Test fun `only the 32 most recently updated events remain in persistent storage`() {
        repeat(40) { index ->
            now++
            CaptureLatencyTrace.record(context, "event-$index", Source.NOTIFICATION, Stage.RECEIVED)
        }
        val rows = CaptureLatencyTrace.snapshot(context)
        assertEquals(32, rows.size)
        assertEquals(now - 31, rows.first().latestAt)
        assertEquals(now, rows.last().latestAt)
        assertTrue(file.length() < 128 * 1_024)
    }

    @Test fun `snapshot expires stages and deletes empty trace after 24 hours`() {
        CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.RECEIVED)
        now += CaptureLatencyTrace.RETENTION_MS - 1
        assertEquals(1, CaptureLatencyTrace.snapshot(context).size)
        now++
        assertTrue(CaptureLatencyTrace.snapshot(context).isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `recording after restart reads the persisted source stages rather than an in memory cache`() {
        CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.RECEIVED)
        val persisted = file.readText()
        file.delete()
        assertTrue(CaptureLatencyTrace.snapshot(context).isEmpty())
        file.writeText(persisted)
        now += 200
        CaptureLatencyTrace.record(context, "event", Source.MODEL, Stage.COMPLETED)
        assertEquals(setOf(Stage.RECEIVED, Stage.COMPLETED), CaptureLatencyTrace.snapshot(context).single().stages.keys)
    }

    @Test fun `unknown disk fields never reach dump and the next record repairs malformed diagnostic metadata`() {
        file.writeText("""[{"eventId":"private order","unexpected":"private merchant"}]""")
        assertEquals("captureLatency=unavailable\n", dump())
        assertTrue(CaptureLatencyTrace.record(context, "new-event", Source.SERVICE, Stage.CONNECTED))
        assertFalse(file.readText().contains("private"))
        assertEquals(1, CaptureLatencyTrace.snapshot(context).size)
    }

    @Test fun `storage failure is reported without throwing into payment capture`() {
        assertTrue(file.mkdir())
        assertFalse(CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.RECEIVED))
    }

    @Test fun `expired or future supplied times cannot replace current timings`() {
        CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.RECEIVED)
        assertFalse(CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.SOURCE_POSTED, now + 1))
        assertFalse(CaptureLatencyTrace.record(context, "event", Source.NOTIFICATION, Stage.SOURCE_POSTED, now - CaptureLatencyTrace.RETENTION_MS))
        assertEquals(setOf(Stage.RECEIVED), CaptureLatencyTrace.snapshot(context).single().stages.keys)
    }
}

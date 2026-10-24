package com.pennypilot.app.service

import android.app.Application
import com.pennypilot.app.data.parser.PaymentScreenNode
import com.pennypilot.app.data.parser.PaymentScreenStructure
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureDiagnosticStoreTest {
    private var now = 0L
    private val safe = PaymentScreenReader.Snapshot("转账成功\n￥18.00", false, "ready", mapOf("textNodes" to 2),
        PaymentScreenStructure(listOf(PaymentScreenNode(1, null, text = "转账成功"),
            PaymentScreenNode(2, 1, text = "￥18.00\n订单号123456789012\n邮箱a@example.com"))))
    @Before fun setUp() { CaptureDiagnosticStore.elapsedMillis = { now }; CaptureDiagnosticStore.clear() }
    @After fun tearDown() { CaptureDiagnosticStore.clear(); CaptureDiagnosticStore.elapsedMillis = android.os.SystemClock::elapsedRealtime }
    private fun record(snapshot: PaymentScreenReader.Snapshot = safe, candidate: Boolean = true) =
        CaptureDiagnosticStore.record("com.tencent.mm", snapshot, 7, 32, "PaymentActivity", candidate)

    @Test fun `normal capture does not preserve diagnostic samples`() {
        record()
        assertEquals("idle", CaptureDiagnosticStore.read()["state"])
        assertNull(CaptureDiagnosticStore.read()["sample"])
    }

    @Test fun `opt in records one local sample and redacts long identifiers`() {
        assertEquals("waiting", CaptureDiagnosticStore.arm()["state"])
        record()
        val sample = CaptureDiagnosticStore.read()["sample"] as String
        assertEquals("ready", CaptureDiagnosticStore.read()["state"])
        assertTrue(sample.contains("转账成功"))
        assertFalse(sample.contains("123456789012"))
        assertFalse(sample.contains("a@example.com"))
        Json.parseToJsonElement(sample)
        record(PaymentScreenReader.Snapshot(null, true, "password_page", emptyMap()), false)
        assertEquals(sample, CaptureDiagnosticStore.read()["sample"])
    }

    @Test fun `input chat incomplete and unknown pages preserve counts only`() {
        listOf(
            PaymentScreenReader.Snapshot(null, true, "input_page", mapOf("editableNodes" to 1)),
            PaymentScreenReader.Snapshot(null, false, "incomplete_tree", mapOf("missingChildren" to 1)),
            safe.copy(text = "聊天记录 private", structure = PaymentScreenStructure(listOf(PaymentScreenNode(1, null, text = "private"))))
        ).forEach { page ->
            CaptureDiagnosticStore.arm()
            record(page, candidate = false)
            val sample = CaptureDiagnosticStore.read()["sample"] as String
            assertFalse(sample.contains("private"))
            assertFalse(sample.contains("\"nodes\""))
            assertTrue(sample.contains("counts"))
        }
    }

    @Test fun `armed request and captured sample both expire after ten minutes`() {
        CaptureDiagnosticStore.arm()
        now = 600_000
        assertEquals("idle", CaptureDiagnosticStore.read()["state"])
        CaptureDiagnosticStore.arm()
        record()
        now += 600_000
        assertEquals("idle", CaptureDiagnosticStore.read()["state"])
        assertNull(CaptureDiagnosticStore.read()["sample"])
    }

    @Test fun `passing through an input page does not consume the requested result sample`() {
        CaptureDiagnosticStore.arm()
        record(PaymentScreenReader.Snapshot(null, true, "input_page", mapOf("editableNodes" to 1)), false)
        assertEquals("waiting", CaptureDiagnosticStore.read()["state"])
        assertTrue((CaptureDiagnosticStore.read()["sample"] as String).contains("input_page"))
        record()
        assertEquals("ready", CaptureDiagnosticStore.read()["state"])
        assertTrue((CaptureDiagnosticStore.read()["sample"] as String).contains("转账成功"))
    }

    @Test fun `oversize export remains valid json and omits node contents`() {
        CaptureDiagnosticStore.arm()
        record(safe.copy(structure = PaymentScreenStructure(listOf(PaymentScreenNode(1, null, text = "界".repeat(100_000))))))
        val sample = CaptureDiagnosticStore.read()["sample"] as String
        assertTrue(sample.toByteArray().size < 96 * 1024)
        assertTrue(sample.contains("size_limit"))
        Json.parseToJsonElement(sample)
    }
}

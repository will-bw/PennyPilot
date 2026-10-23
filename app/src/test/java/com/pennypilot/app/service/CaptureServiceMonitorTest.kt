package com.pennypilot.app.service

import org.junit.Assert.*
import org.junit.Test

class CaptureServiceMonitorTest {
    @Test fun `redraw parsing does not evict saved result before the user returns to chat`() {
        val monitor = CaptureServiceMonitor()
        monitor.onScreenEvent("com.tencent.mm")
        monitor.onScreenRead("parsed", mapOf("textNodes" to 4), "com.tencent.mm")
        monitor.onScreenStatus("saved", "com.tencent.mm")
        repeat(20) {
            monitor.onScreenRead("parsed", mapOf("textNodes" to 4), "com.tencent.mm")
            monitor.onScreenStatus("duplicate", "com.tencent.mm")
        }
        monitor.onScreenRead("input_page", mapOf("editableNodes" to 1), "com.tencent.mm")
        assertEquals(listOf("saved", "duplicate", "input_page"), monitor.health.value.screenReadHistory.map { it.status })
    }

    @Test fun `async save belongs to the read request even after another app sends an event`() {
        val monitor = CaptureServiceMonitor()
        monitor.onScreenEvent("com.tencent.mm")
        monitor.onScreenRead("parsed", mapOf("textNodes" to 4), "com.tencent.mm")
        monitor.onScreenEvent("com.eg.android.AlipayGphone")
        monitor.onScreenStatus("saved", "com.tencent.mm")
        assertEquals("com.tencent.mm", monitor.health.value.screenReadHistory.single().packageName)
        assertEquals("com.eg.android.AlipayGphone", monitor.health.value.lastScreenPackage)
    }

    @Test fun `returning to chat preserves the preceding receipt result`() {
        val monitor = CaptureServiceMonitor()
        monitor.onScreenEvent("com.tencent.mm")
        monitor.onScreenRead("incomplete_tree", mapOf("missingChildren" to 1))
        monitor.onScreenRead("parsed", mapOf("textNodes" to 4))
        monitor.onScreenStatus("saved")
        monitor.onScreenEvent("com.tencent.mm")
        repeat(20) { monitor.onScreenRead("input_page", mapOf("editableNodes" to 1)) }
        val history = monitor.health.value.screenReadHistory
        assertEquals(listOf("incomplete_tree", "saved", "input_page"), history.map { it.status })
        assertTrue(history.all { it.packageName == "com.tencent.mm" && it.timestamp > 0 })
        assertEquals(mapOf("textNodes" to 4), history[1].diagnostics)
        assertEquals("input_page", monitor.health.value.screenCaptureStatus)
    }

    @Test fun `history is bounded and ordinary connection events do not displace read results`() {
        val monitor = CaptureServiceMonitor()
        monitor.onScreenEvent("com.tencent.mm")
        repeat(20) { monitor.onScreenRead(if (it % 2 == 0) "empty_page" else "not_receipt") }
        val history = monitor.health.value.screenReadHistory
        assertEquals(8, history.size)
        monitor.onScreenStatus("waiting")
        monitor.onAccessibilityDisconnected()
        assertEquals(history, monitor.health.value.screenReadHistory)
    }

    @Test fun `numeric diagnostics are snapshotted and unavailable roots clear the current counters`() {
        val monitor = CaptureServiceMonitor()
        val counts = mutableMapOf("textNodes" to 4)
        monitor.onScreenRead("parsed", counts)
        counts["textNodes"] = 999
        monitor.onScreenStatus("saved")
        assertEquals(4, monitor.health.value.screenReadHistory.last().diagnostics["textNodes"])
        monitor.onScreenRead("unreadable")
        assertTrue(monitor.health.value.screenReadDiagnostics.isEmpty())
        assertTrue(monitor.health.value.screenReadHistory.last().diagnostics.isEmpty())
    }
}

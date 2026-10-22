package com.pennypilot.app.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class ReportMonthsTest {
    @Test
    fun `March run recovers January missed by old thirty day schedule`() {
        val months = missingReportMonths(
            listOf("2026-10", "2027-01", "2027-02"),
            listOf("2026-10", "2026-11", "2026-12", "2027-02"),
            YearMonth.of(2027, 2)
        )
        assertEquals(listOf(YearMonth.of(2027, 1)), months)
    }

    @Test
    fun `empty ledger does not invent historical reports`() {
        assertTrue(missingReportMonths(emptyList(), emptyList(), YearMonth.of(2027, 2)).isEmpty())
        assertTrue(missingReportMonths(listOf("2027-03"), emptyList(), YearMonth.of(2027, 2)).isEmpty())
    }

    @Test
    fun `existing report anchors empty intervening months without repeating reports`() {
        assertEquals(
            listOf(YearMonth.of(2026, 12), YearMonth.of(2027, 1)),
            missingReportMonths(emptyList(), listOf("2026-11"), YearMonth.of(2027, 1))
        )
    }
}

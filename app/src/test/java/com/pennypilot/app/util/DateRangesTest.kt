package com.pennypilot.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class DateRangesTest {
    @Test
    fun `one calendar date includes full day and excludes next midnight`() {
        val range = DateRanges.inclusiveDates("2026-10-22", "2026-10-22", ZoneId.of("Asia/Hong_Kong"))
        assertEquals(86_400_000L, range.endExclusive - range.start)
        assertEquals(1792598400000L, range.start)
    }

    @Test
    fun `inclusive range follows daylight saving rather than fixed duration`() {
        val range = DateRanges.inclusiveDates("2026-03-08", "2026-03-08", ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, range.endExclusive - range.start)
    }

    @Test
    fun `missing or reversed dates are rejected instead of using now`() {
        assertThrows(IllegalArgumentException::class.java) { DateRanges.inclusiveDates(null, "2026-10-22") }
        assertThrows(IllegalArgumentException::class.java) { DateRanges.inclusiveDates("2026-10-23", "2026-10-22") }
    }

    @Test
    fun `adjacent months share a boundary without overlapping`() {
        val zone = ZoneId.of("Asia/Hong_Kong")
        val jan = DateRanges.month(YearMonth.of(2027, 1), zone)
        val feb = DateRanges.month(YearMonth.of(2027, 2), zone)
        assertEquals(jan.endExclusive, feb.start)
        assertEquals(28 * 86_400_000L, feb.endExclusive - feb.start)
    }
}

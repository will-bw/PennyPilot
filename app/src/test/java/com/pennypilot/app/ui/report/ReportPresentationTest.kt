package com.pennypilot.app.ui.report

import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import org.junit.Assert.*
import org.junit.Test

class ReportPresentationTest {
    private fun report(month: String) = MonthlyReportEntity(yearMonth = month, totalExpense = 100,
        totalIncome = 0, byCategoryJson = "[]", llmSummary = "")

    @Test fun `month comparison does not silently compare across missing months`() {
        val latest = report("2026-10")
        assertNull(adjacentPreviousReport(latest, listOf(latest, report("2026-08"))))
        assertEquals("2026-09", adjacentPreviousReport(latest, listOf(report("2026-08"), report("2026-09")))?.yearMonth)
    }

    @Test fun `month comparison crosses year boundary correctly`() {
        val previous = report("2025-12")
        assertEquals(previous, adjacentPreviousReport(report("2026-01"), listOf(previous)))
    }

    @Test fun `top categories are sorted limited and exclude invalid totals`() {
        val result = parseTopCats("""[{"name":"A","total":10},{"name":"B","total":50},{"name":"C","total":30},{"name":"D","total":20},{"name":"negative","total":-50}]""")
        assertEquals(listOf("B", "C", "D"), result.map { it.name })
        assertTrue(parseTopCats("invalid").isEmpty())
    }

    @Test fun `malformed report month is not replaced by a fabricated date`() {
        assertEquals("unknown", reportMonthLabel("unknown"))
        assertNull(adjacentPreviousReport(report("unknown"), listOf(report("2026-09"))))
    }
}

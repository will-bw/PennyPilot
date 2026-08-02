package com.pennypilot.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyUtilTest {

    @Test
    fun `expense 1800 fen displays as -18 dot 00`() {
        assertEquals("−18.00", MoneyUtil.formatForDisplay(1800L, isExpense = true))
    }

    @Test
    fun `income -800000 fen displays as +8000 dot 00 with comma`() {
        assertEquals("+8,000.00", MoneyUtil.formatForDisplay(-800000L, isExpense = false))
    }

    @Test
    fun `zero displays as 0 dot 00`() {
        assertEquals("0.00", MoneyUtil.formatForDisplay(0L, isExpense = true))
    }

    @Test
    fun `parseYuanToCents converts correctly`() {
        assertEquals(1800L, MoneyUtil.parseYuanToCents("18"))
        assertEquals(1850L, MoneyUtil.parseYuanToCents("18.5"))
        assertEquals(1850L, MoneyUtil.parseYuanToCents("18.50"))
        assertEquals(0L, MoneyUtil.parseYuanToCents(""))
        assertEquals(0L, MoneyUtil.parseYuanToCents("abc"))
    }

    @Test
    fun `centsToYuanString for input field`() {
        assertEquals("18.00", MoneyUtil.centsToYuanString(1800L))
        assertEquals("0.50", MoneyUtil.centsToYuanString(50L))
    }

    @Test
    fun `formatHeroAmount no sign for hero display`() {
        assertEquals("3,286.50", MoneyUtil.formatHeroAmount(328650L))
    }
}

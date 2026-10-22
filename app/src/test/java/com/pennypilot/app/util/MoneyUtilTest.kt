package com.pennypilot.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `positive transaction amounts are exact and bounded`() {
        assertEquals(435L, MoneyUtil.parsePositiveYuanToCents("4.35"))
        assertEquals(123456L, MoneyUtil.parsePositiveYuanToCents("1,234.56"))
        assertEquals(MoneyUtil.MAX_TRANSACTION_CENTS, MoneyUtil.parsePositiveYuanToCents("9999999.99"))
        listOf("0", "-10", "+10", "NaN", "Infinity", "1e3", "1.001", "1,2", "10000000", "9223372036854775807").forEach {
            assertNull("Should reject $it", MoneyUtil.parsePositiveYuanToCents(it))
        }
    }

    @Test
    fun `legacy parser does not throw or saturate for non finite and overflowing input`() {
        listOf("NaN", "Infinity", "-Infinity", "9223372036854775807").forEach {
            assertEquals(0L, MoneyUtil.parseYuanToCents(it))
        }
        assertEquals(-1000L, MoneyUtil.parseYuanToCents("-10"))
    }
}

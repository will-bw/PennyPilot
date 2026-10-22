package com.pennypilot.app.ui.input

import org.junit.Assert.assertEquals
import org.junit.Test

class KeypadAmountTest {

    private fun type(seq: String): KeypadAmount {
        var a = KeypadAmount()
        for (c in seq) a = if (c == '.') a.appendDot() else if (c == '⌫') a.backspace() else a.appendDigit(c)
        return a
    }

    @Test
    fun `leading zero replaced by first digit`() {
        assertEquals("5", type("5").display)
        assertEquals("0", KeypadAmount().display)
    }

    @Test
    fun `decimal limited to two places`() {
        assertEquals("12.34", type("12.345").display)
        assertEquals(1234, type("12.345").cents)
    }

    @Test
    fun `single dot allowed and padded on toCents`() {
        assertEquals("7.", type("7.").display)
        assertEquals(700, type("7.").cents)
        assertEquals("7.5", type("7.5").display)
        assertEquals(750, type("7.5").cents)
    }

    @Test
    fun `second dot ignored`() {
        assertEquals("1.59", type("1.5.9").display)
        assertEquals(159, type("1.5.9").cents)
    }

    @Test
    fun `integer capped at seven digits`() {
        assertEquals("1234567", type("12345678").display)
        assertEquals(123456700, type("12345678").cents)
    }

    @Test
    fun `backspace resets to zero`() {
        assertEquals("0", type("5⌫").display)
        assertEquals("1.2", type("1.23⌫").display)
        assertEquals("0", type("").display)
    }

    @Test
    fun `cents conversion`() {
        assertEquals(2550, type("25.5").cents)
        assertEquals(0, KeypadAmount().cents)
        assertEquals(800000, type("8000").cents)
    }
}

package com.pennypilot.app.ui.input

/**
 * 数字键盘金额状态机：纯字符串维护，避免浮点误差。
 * 规则：整数部分最多 7 位、小数最多 2 位、前导零归一、空态回 "0"。
 */
data class KeypadAmount(val display: String = "0") {

    fun appendDigit(d: Char): KeypadAmount {
        val (int, frac) = split()
        return when {
            display == "0" -> KeypadAmount(d.toString())
            frac != null -> if (frac.length < 2) KeypadAmount(display + d) else this
            else -> if (int.length < 7) KeypadAmount(display + d) else this
        }
    }

    fun appendDot(): KeypadAmount =
        if (display.contains('.')) this else KeypadAmount("$display.")

    fun backspace(): KeypadAmount {
        val next = display.dropLast(1)
        return KeypadAmount(next.ifEmpty { "0" })
    }

    val cents: Long
        get() {
            val (int, frac) = split()
            val fracPadded = (frac ?: "").padEnd(2, '0')
            return (int + fracPadded).toLongOrNull() ?: 0L
        }

    private fun split(): Pair<String, String?> {
        val idx = display.indexOf('.')
        return if (idx < 0) display to null
        else display.substring(0, idx) to display.substring(idx + 1)
    }
}

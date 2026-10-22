package com.pennypilot.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object MoneyUtil {
    /** 与手动键盘保持一致：最多 7 位整数、2 位小数。 */
    const val MAX_TRANSACTION_CENTS: Long = 999_999_999L
    private val positiveAmountPattern = Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")
    private val symbols = DecimalFormatSymbols(Locale.US)
    private val yuanFormat = DecimalFormat("#,##0.00", symbols)
    private val plainFormat = DecimalFormat("0.00", symbols)

    /** 流水列表显示：支出 "−18.00"（墨色），收入 "+8,000.00"（暖红） */
    fun formatForDisplay(amountCents: Long, isExpense: Boolean): String {
        if (amountCents == 0L) return "0.00"
        val absYuan = kotlin.math.abs(amountCents) / 100.0
        val formatted = yuanFormat.format(absYuan)
        return if (isExpense) "\u2212$formatted" else "+$formatted"
    }

    /** Hero 大数字：无符号，"3,286.50" */
    fun formatHeroAmount(amountCents: Long): String {
        return yuanFormat.format(amountCents / 100.0)
    }

    /** 编辑框：分 → 纯数字字符串 "18.00"（无千分位，便于解析） */
    fun centsToYuanString(amountCents: Long): String {
        return plainFormat.format(kotlin.math.abs(amountCents) / 100.0)
    }

    /** 用户输入元 → 分。无效输入返回 0。兼容千分位逗号 */
    fun parseYuanToCents(input: String): Long {
        return try {
            BigDecimal(input.trim().replace(",", ""))
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (_: IllegalArgumentException) {
            0L
        } catch (_: ArithmeticException) {
            0L
        }
    }

    /** 入账边界专用：仅接受精确到分的普通正十进制数，不接受指数/符号/非有限值。 */
    fun parsePositiveYuanToCents(input: String): Long? {
        val text = input.trim()
        if (!positiveAmountPattern.matches(text)) return null
        val cents = try {
            BigDecimal(text.replace(",", "")).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return cents.takeIf(::isValidAmountCents)
    }

    fun isValidAmountCents(cents: Long): Boolean = cents in 1..MAX_TRANSACTION_CENTS
}

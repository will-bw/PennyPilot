package com.pennypilot.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

object MoneyUtil {
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
            val yuan = input.trim().replace(",", "").toDouble()
            // roundToLong 避免浮点截断（如 4.35 * 100 = 434.999…）
            (yuan * 100).roundToLong()
        } catch (_: NumberFormatException) {
            0L
        }
    }
}

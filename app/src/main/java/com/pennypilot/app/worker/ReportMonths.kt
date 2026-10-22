package com.pennypilot.app.worker

import java.time.YearMonth

/** 只从实际使用账本的月份开始补齐，空数据库不会从时间戳 0 回溯。 */
internal fun missingReportMonths(
    transactionMonths: List<String>,
    existingReportMonths: List<String>,
    lastCompletedMonth: YearMonth
): List<YearMonth> {
    fun parse(value: String): YearMonth? = runCatching { YearMonth.parse(value) }.getOrNull()
    val existing = existingReportMonths.mapNotNull(::parse).toSet()
    val first = (transactionMonths.mapNotNull(::parse) + existing)
        .filter { it <= lastCompletedMonth }.minOrNull() ?: return emptyList()
    return generateSequence(first) { it.plusMonths(1) }
        .takeWhile { it <= lastCompletedMonth }
        .filterNot { it in existing }
        .toList()
}

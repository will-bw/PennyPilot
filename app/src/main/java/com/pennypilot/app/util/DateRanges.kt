package com.pennypilot.app.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 所有数据库时间范围均使用 [start, endExclusive)。 */
data class DateRange(val start: Long, val endExclusive: Long)

object DateRanges {
    /** 对话工具的结束日期包含整日；按当地日历加一天，兼容夏令时。 */
    fun inclusiveDates(start: String?, end: String?, zone: ZoneId = ZoneId.systemDefault()): DateRange {
        require(!start.isNullOrBlank() && !end.isNullOrBlank()) { "请提供开始日期和结束日期（yyyy-MM-dd）" }
        val first = LocalDate.parse(start)
        val last = LocalDate.parse(end)
        require(!last.isBefore(first)) { "结束日期不能早于开始日期" }
        return DateRange(
            first.atStartOfDay(zone).toInstant().toEpochMilli(),
            last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    fun month(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): DateRange = DateRange(
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )
}

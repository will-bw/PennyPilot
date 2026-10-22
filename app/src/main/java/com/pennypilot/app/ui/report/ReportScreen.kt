package com.pennypilot.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.data.db.dao.DailyAggregate
import com.pennypilot.app.data.db.dao.MonthlyTrend
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.theme.*
import com.pennypilot.app.util.MoneyUtil
import com.pennypilot.app.worker.MonthlyReportWorker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val CN_MONTHS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")

private data class TopCat(val name: String, val total: Long)

@Composable
fun ReportScreen(vm: ReportViewModel = hiltViewModel()) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val heat by vm.heat.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 按月份倒序：最新一份为当月 Hero，其余进历史列表
    val sorted = remember(reports) { reports.sortedByDescending { it.yearMonth } }
    val latest = sorted.firstOrNull()
    val history = sorted.drop(1)
    val top3 = remember(latest) { latest?.let { parseTopCats(it.byCategoryJson) } ?: emptyList() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // page-head：标题 + 副标题 + 「↻ 重新生成」
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("月度报告", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "月初自动生成 · 自动补齐遗漏月份",
                        style = PageSubStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    TextButton(
                        onClick = {
                            MonthlyReportWorker.regenerate(context, YearMonth.now().minusMonths(1))
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(16.dp)
                    ) {
                        Text(
                            "↻ 重新生成",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (latest == null) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有报告，下月 1 日会自动生成", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 当月 Hero 卡
            item { ReportHero(latest, sorted.getOrNull(1)) }

            // AI 点评引言卡
            item { LlmQuote(latest.llmSummary) }

            // 支出 Top3 分类
            if (top3.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text("支出 Top 3 分类", fontSize = 13.sp, fontWeight = FontWeight.W700)
                        val pctSum = top3.sumOf { it.total } * 100 / (latest.totalExpense.coerceAtLeast(1))
                        Text(
                            "占比 $pctSum%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.faintColor
                        )
                    }
                }
                item { Top3Card(top3, latest.totalExpense, categories) }
            }

            // 近 6 个月支出趋势
            if (trend.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text("支出趋势", fontSize = 13.sp, fontWeight = FontWeight.W700)
                        Text(
                            "近 6 个月",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.faintColor
                        )
                    }
                }
                item { TrendCard(trend) }
            }

            // 当月支出热力
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("支出热力", fontSize = 13.sp, fontWeight = FontWeight.W700)
                    Text(
                        "${LocalDate.now().monthValue} 月 · 按日",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.faintColor
                    )
                }
            }
            item { HeatmapCard(heat) }

            // 历史报告列表
            if (history.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text("历史报告", fontSize = 13.sp, fontWeight = FontWeight.W700)
                        Text(
                            "全部",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.faintColor
                        )
                    }
                }
                items(history) { report -> HistoryRow(report) }
            }
        }
    }
}

/** 当月 Hero：左缘 4dp 靛蓝渐变条 + 衬线「X 月账本」+ 37sp 总支出 + pill 行 */
@Composable
private fun ReportHero(report: MonthlyReportEntity, prev: MonthlyReportEntity?) {
    val (year, month) = parseYearMonth(report.yearMonth)

    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${monthEn(month)} $year",
                        style = EyebrowStyle,
                        color = MaterialTheme.faintColor
                    )
                    // 支出环比 badge：降绿升红
                    if (prev != null && prev.totalExpense > 0) {
                        val pct = (report.totalExpense - prev.totalExpense) * 100.0 / prev.totalExpense
                        val down = pct <= 0
                        val color = if (down) MaterialTheme.successColor else MaterialTheme.colorScheme.error
                        Box(
                            modifier = Modifier
                                .background(
                                    if (down) MaterialTheme.successSoftColor else MaterialTheme.incomeSoftColor,
                                    RoundedCornerShape(99.dp)
                                )
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "支出环比 ${if (down) "↓" else "↑"} ${"%.1f".format(kotlin.math.abs(pct))}%",
                                style = PillTextStyle,
                                color = color
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${CN_MONTHS[month - 1]}月账本",
                    fontFamily = FontFamily.Serif,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "¥",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        MoneyUtil.formatHeroAmount(report.totalExpense),
                        fontFamily = FontFamily.Serif,
                        fontSize = 37.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 收入 pill：红点 + 暖红浅底
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.incomeSoftColor, RoundedCornerShape(99.dp))
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape)
                        )
                        Text(
                            "收入 ¥${MoneyUtil.formatHeroAmount(report.totalIncome)}",
                            style = PillTextStyle,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // 结余 pill：卡片底 + 发丝描边
                    val balance = report.totalIncome - report.totalExpense
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "结余 ¥${MoneyUtil.formatHeroAmount(balance)}",
                            style = PillTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 左缘 4dp 靛蓝渐变条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .height(72.dp)
                    .background(Brush.verticalGradient(MaterialTheme.priGradient))
            )
        }
    }
}

/** AI 点评引言卡：靛蓝浅底 + 衬线大引号 + ✦ AI 点评标签 */
@Composable
private fun LlmQuote(summary: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        // 右上角 ✦ AI 点评标签
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("✦", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "AI 点评",
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Column(Modifier.padding(top = 6.dp)) {
            Text(
                "\u201C",
                fontFamily = FontFamily.Serif,
                fontSize = 40.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.height(26.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                summary,
                fontFamily = FontFamily.Serif,
                fontSize = 14.5.sp,
                lineHeight = 27.sp,
                letterSpacing = 0.3.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 支出 Top3：图标 + 金额 + 占比渐变进度条 */
@Composable
private fun Top3Card(top3: List<TopCat>, totalExpense: Long, categories: List<CategoryEntity>) {
    val catByName = categories.associateBy { it.name }

    Card(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
            top3.forEachIndexed { idx, cat ->
                val entity = catByName[cat.name]
                val pct = if (totalExpense > 0) cat.total * 100 / totalExpense else 0L
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CatIcon(
                        colorIndex = entity?.colorIndex ?: idx,
                        emoji = entity?.icon ?: "📦",
                        size = 36
                    )
                    Column(Modifier.weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                cat.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W600,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "¥${MoneyUtil.formatHeroAmount(cat.total)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        // 渐变进度条
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(99.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(pct.toFloat() / 100f)
                                    .height(6.dp)
                                    .background(
                                        Brush.horizontalGradient(MaterialTheme.priGradient),
                                        RoundedCornerShape(99.dp)
                                    )
                            )
                        }
                    }
                    Text(
                        "$pct%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.faintColor,
                        modifier = Modifier.width(34.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

/** 历史报告行：月份汉字方块图标 + 收支摘要 + 右箭头 */
@Composable
private fun HistoryRow(report: MonthlyReportEntity) {
    val (year, month) = parseYearMonth(report.yearMonth)

    Card(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    CN_MONTHS[month - 1],
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "$year 年 $month 月",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "支出 ¥${MoneyUtil.formatHeroAmount(report.totalExpense)} · 收入 ¥${MoneyUtil.formatHeroAmount(report.totalIncome)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.faintColor
                )
            }
            Text(
                "¥${MoneyUtil.formatHeroAmount(report.totalExpense)}",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text("›", color = MaterialTheme.faintColor)
        }
    }
}

private fun parseYearMonth(ym: String): Pair<Int, Int> {
    val parts = ym.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
    val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12)
    return year to month
}

private fun monthEn(month: Int): String =
    Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH)

private fun parseTopCats(json: String): List<TopCat> {
    return try {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val total = obj["total"]?.jsonPrimitive?.longOrNull ?: 0L
            TopCat(name, total)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 近 6 个月支出柱状卡：普通柱 primaryContainer，当月靛蓝渐变 */
@Composable
private fun TrendCard(trend: List<MonthlyTrend>) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val max = trend.maxOf { it.total }.coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                trend.forEachIndexed { i, t ->
                    val current = i == trend.lastIndex
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (t.total > 0) "¥${t.total / 100}" else "",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.faintColor
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(((t.total * 72 / max).toInt().coerceAtLeast(2)).dp)
                                .background(
                                    if (current) Brush.verticalGradient(MaterialTheme.priGradient)
                                    else SolidColor(MaterialTheme.colorScheme.primaryContainer),
                                    RoundedCornerShape(6.dp)
                                )
                        )
                        Text(
                            "${t.ym.substring(5).toInt()}月",
                            fontSize = 10.sp,
                            color = if (current) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.faintColor,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.W600
                        )
                    }
                }
            }
        }
    }
}

/** 当月日历热力卡：周一首列，日支出 4 档 alpha(primary)，无支出浅底 */
@Composable
private fun HeatmapCard(days: List<DailyAggregate>) {
    val now = LocalDate.now()
    val byDay = remember(days) { days.associate { it.day to it.total } }
    val max = days.maxOfOrNull { it.total } ?: 0L
    val leading = now.withDayOfMonth(1).dayOfWeek.value - 1
    val daysInMonth = now.lengthOfMonth()
    val ym = "%04d-%02d".format(now.year, now.monthValue)

    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                    Text(
                        d,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.faintColor
                    )
                }
            }
            val cells = (0 until (leading + daysInMonth + 6) / 7 * 7).map { idx ->
                val d = idx - leading + 1
                if (d in 1..daysInMonth) d else null
            }
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { d ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(16.dp)
                                .background(
                                    heatColor(d?.let { byDay["$ym-${"%02d".format(it)}"] } ?: 0L, max),
                                    RoundedCornerShape(5.dp)
                                )
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text("少", fontSize = 10.sp, color = MaterialTheme.faintColor)
                listOf(0L, 1L, 1L, 1L, 1L).forEachIndexed { i, _ ->
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                when (i) {
                                    0 -> heatColor(0L, 100L)
                                    1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("多", fontSize = 10.sp, color = MaterialTheme.faintColor)
            }
        }
    }
}

@Composable
private fun heatColor(total: Long, max: Long): androidx.compose.ui.graphics.Color {
    val primary = MaterialTheme.colorScheme.primary
    return when {
        total <= 0 || max <= 0 -> MaterialTheme.colorScheme.outlineVariant
        else -> primary.copy(
            alpha = when {
                total * 4 <= max -> 0.25f
                total * 2 <= max -> 0.5f
                total * 4 <= max * 3 -> 0.75f
                else -> 1f
            }
        )
    }
}

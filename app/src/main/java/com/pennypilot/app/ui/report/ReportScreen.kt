package com.pennypilot.app.ui.report

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pennypilot.app.data.db.dao.DailyAggregate
import com.pennypilot.app.data.db.dao.MonthlyTrend
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.theme.*
import com.pennypilot.app.util.MoneyUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.YearMonth

internal data class TopCat(val name: String, val total: Long)

internal fun parseTopCats(json: String): List<TopCat> = runCatching {
    Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val total = obj["total"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        TopCat(name, total)
    }.sortedByDescending { it.total }.take(3)
}.getOrDefault(emptyList())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(vm: ReportViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    var expandedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val latest = state.reports.firstOrNull()
    val lastMonth = state.currentMonth.minusMonths(1)
    val hasLastMonth = state.reports.any { it.yearMonth == lastMonth.toString() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "heading") {
            Column(reportMotion(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("月度报告", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text("月初自动生成 · 自动补齐遗漏月份", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.regenerate(lastMonth) }, enabled = !generation.busy,
                    modifier = Modifier.animateContentSize(PennyMotion.spatial()).heightIn(min = 48.dp)) {
                    if (generation.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (generation.busy) "正在生成报告…" else "${if (hasLastMonth) "重新生成" else "生成"} ${lastMonth.year} 年 ${lastMonth.monthValue} 月报告")
                }
            }
        }
        if (generation.month != null) item(key = "generation") {
            Box(reportMotion().animateContentSize(PennyMotion.spatial())) { GenerationStatus(generation, vm::regenerate) }
        }
        when {
            state.isLoading -> item(key = "loading") { LedgerState("正在读取报告", "正在整理你的月度收支。", loading = true, modifier = reportMotion()) }
            state.error != null -> item(key = "error") { LedgerState("报告加载失败", state.error.orEmpty(), actionLabel = "重试", onAction = vm::retry, modifier = reportMotion()) }
            else -> {
                if (latest == null) {
                    item(key = "empty") { LedgerState("还没有月度报告", "报告总结已经结束的月份；本月的实时收支可以在下方查看。", modifier = reportMotion()) }
                } else {
                    item(key = "latest_summary") {
                        Box(reportMotion()) { ReportHero(latest, adjacentPreviousReport(latest, state.reports)) }
                    }
                    item(key = "latest_quote") { Box(reportMotion()) { LlmQuote(latest.llmSummary) } }
                    val top3 = parseTopCats(latest.byCategoryJson)
                    if (top3.isNotEmpty()) item(key = "latest_categories") {
                        Column(reportMotion(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionHeading("支出 Top 3 分类", "${reportMonthLabel(latest.yearMonth)} · 已确认")
                            Top3Card(top3, latest.totalExpense, state.categories)
                        }
                    }
                }
                // 实时图表独立于 AI 报告存在，首次使用也能看到已有账目。
                item(key = "trend") {
                    Column(reportMotion(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("支出趋势", "近 6 个月 · 含本月")
                        TrendCard(state.trend)
                    }
                }
                item(key = "heat") {
                    Column(reportMotion(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("支出热力", "${state.currentMonth.year} 年 ${state.currentMonth.monthValue} 月")
                        HeatmapCard(state.heat, state.currentMonth)
                    }
                }
                if (state.reports.size > 1) {
                    item(key = "history_heading") {
                        Box(reportMotion()) { SectionHeading("历史报告", "点击月份查看详情") }
                    }
                    state.reports.drop(1).forEach { report ->
                        item(key = "history_${report.yearMonth}") {
                            val expanded = expandedMonth == report.yearMonth
                            Column(reportMotion()) {
                                HistoryRow(report, expanded) {
                                    expandedMonth = if (expanded) null else report.yearMonth
                                }
                                AnimatedVisibility(
                                    visible = expanded,
                                    enter = expandVertically(PennyMotion.spatial(), expandFrom = Alignment.Top) + fadeIn(PennyMotion.effects()),
                                    exit = shrinkVertically(PennyMotion.spatial(), shrinkTowards = Alignment.Top) + fadeOut(tween(PennyMotion.Exit))
                                ) {
                                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        ReportHero(report, adjacentPreviousReport(report, state.reports))
                                        LlmQuote(report.llmSummary)
                                        val top = remember(report.byCategoryJson) { parseTopCats(report.byCategoryJson) }
                                        if (top.isNotEmpty()) Top3Card(top, report.totalExpense, state.categories)
                                        val month = runCatching { YearMonth.parse(report.yearMonth) }.getOrNull()
                                        if (month != null && month < state.currentMonth) {
                                            TextButton(onClick = { vm.regenerate(month) }, enabled = expanded && !generation.busy,
                                                modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text("重新生成${reportMonthLabel(report.yearMonth)}报告")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// animateItem is scoped to LazyItemScope, so this factory must retain that receiver.
@SuppressLint("ModifierFactoryExtensionFunction")
private fun LazyItemScope.reportMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = PennyMotion.effects(),
    placementSpec = PennyMotion.spatial(),
    fadeOutSpec = tween(PennyMotion.Exit)
)

@Composable
private fun GenerationStatus(generation: ReportGeneration, onRetry: (YearMonth) -> Unit) {
    val month = generation.month ?: return
    val label = "${month.year} 年 ${month.monthValue} 月报告"
    val failure = generation.error != null || generation.state == WorkInfo.State.FAILED || generation.state == WorkInfo.State.CANCELLED
    val message = when {
        generation.error != null -> generation.error
        generation.isSubmitting -> "正在加入生成队列…"
        generation.state == WorkInfo.State.RUNNING -> "正在整理收支和生成点评，可以离开此页，完成后会自动更新。"
        generation.state == WorkInfo.State.ENQUEUED || generation.state == WorkInfo.State.BLOCKED -> "已加入队列，等待后台任务执行。"
        generation.state == WorkInfo.State.SUCCEEDED -> "报告已更新，可在本页查看。"
        generation.state == WorkInfo.State.CANCELLED -> "生成已取消，可以重新尝试。"
        else -> "生成未完成，请稍后重试。"
    }
    LedgerState(
        title = if (generation.busy) "正在生成 $label" else if (failure) "$label 未完成" else "$label 已生成",
        message = message.orEmpty(), loading = generation.busy,
        actionLabel = if (failure) "重试生成" else null,
        onAction = if (failure) ({ onRetry(month) }) else null,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionHeading(title: String, subtitle: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportHero(report: MonthlyReportEntity, prev: MonthlyReportEntity?) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial())) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${reportMonthLabel(report.yearMonth)}账本", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                modifier = Modifier.semantics { heading() })
            Text("已确认支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("¥${MoneyUtil.formatHeroAmount(report.totalExpense)}", style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
                maxLines = 1, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportPill("收入 ¥${MoneyUtil.formatHeroAmount(report.totalIncome)}", income = true)
                ReportPill("结余 ¥${MoneyUtil.formatHeroAmount(report.totalIncome - report.totalExpense)}")
                if (prev != null && prev.totalExpense > 0) {
                    val pct = (report.totalExpense.toDouble() - prev.totalExpense) * 100.0 / prev.totalExpense
                    val change = when { pct < 0 -> "减少"; pct > 0 -> "增加"; else -> "持平" }
                    val text = if (pct == 0.0) "较上月持平" else "较上月$change ${"%.1f".format(kotlin.math.abs(pct))}%"
                    Text(text, style = MaterialTheme.typography.labelMedium,
                        color = if (pct <= 0) MaterialTheme.successColor else MaterialTheme.colorScheme.error,
                        modifier = Modifier.background(if (pct <= 0) MaterialTheme.successSoftColor else MaterialTheme.colorScheme.errorContainer, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ReportPill(text: String, income: Boolean = false) {
    Text(text, style = MaterialTheme.typography.labelMedium,
        color = if (income) MaterialTheme.incomeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(if (income) MaterialTheme.incomeSoftColor else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun LlmQuote(summary: String) {
    Column(Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial()).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI 点评", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(summary.ifBlank { "暂无点评，可重新生成此月份报告。" },
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Top3Card(top3: List<TopCat>, totalExpense: Long, categories: List<CategoryEntity>) {
    val byName = categories.associateBy { it.name }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            top3.forEachIndexed { index, cat ->
                key(cat.name, index) {
                    val entity = byName[cat.name]
                    val fraction = if (totalExpense > 0) (cat.total.toDouble() / totalExpense).coerceIn(0.0, 1.0).toFloat() else 0f
                    val animatedFraction = animateFloatAsState(fraction, PennyMotion.spatial(), label = "category share")
                    val percent = "${(fraction * 100).toInt()}%"
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        CatIcon(entity?.colorIndex ?: index, entity?.icon ?: "📦", 36)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(cat.name, style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("¥${MoneyUtil.formatHeroAmount(cat.total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("占比 $percent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LinearProgressIndicator(progress = { animatedFraction.value }, modifier = Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics {},
                                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(report: MonthlyReportEntity, expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, PennyMotion.spatial(), label = "report expansion")
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reportMonthLabel(report.yearMonth), style = MaterialTheme.typography.titleMedium)
                Text("支出 ¥${MoneyUtil.formatHeroAmount(report.totalExpense)}", style = MaterialTheme.typography.bodyMedium)
                Text("收入 ¥${MoneyUtil.formatHeroAmount(report.totalIncome)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.incomeColor)
            }
            Icon(Icons.Default.ExpandMore, null, Modifier.rotate(rotation))
        }
    }
}

internal fun reportMonthLabel(ym: String): String = runCatching { YearMonth.parse(ym) }
    .fold({ "${it.year} 年 ${it.monthValue} 月" }, { ym })

@Composable
private fun TrendCard(trend: List<MonthlyTrend>) {
    if (trend.isEmpty() || trend.all { it.total == 0L }) {
        LedgerState("还没有支出趋势", "确认支出后，这里会按月显示变化。")
        return
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val max = trend.maxOf { it.total }.coerceAtLeast(1)
            // 横向条保留准确金额，不让六列数字在窄屏或大字体下互相覆盖。
            trend.forEachIndexed { index, row ->
                key(row.ym) {
                    val label = reportMonthLabel(row.ym)
                    val fraction = animateFloatAsState((row.total.toDouble() / max).coerceIn(0.0, 1.0).toFloat(),
                        PennyMotion.spatial(), label = "monthly expense")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text("$label${if (index == trend.lastIndex) " · 本月" else ""}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("¥${MoneyUtil.formatHeroAmount(row.total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.W600)
                        LinearProgressIndicator(progress = { fraction.value },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics {},
                            color = if (index == trend.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(days: List<DailyAggregate>, month: YearMonth) {
    val byDay = remember(days) { days.associate { it.day to it.total } }
    val max = days.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1L
    val leading = month.atDay(1).dayOfWeek.value - 1
    val today = LocalDate.now()
    val cells = (0 until ((leading + month.lengthOfMonth() + 6) / 7) * 7).map { index ->
        (index - leading + 1).takeIf { it in 1..month.lengthOfMonth() }
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(day, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        val date = day?.let(month::atDay)
                        val total = date?.let { byDay[it.toString()] } ?: 0L
                        val future = date != null && date > today
                        val fraction = (total.toDouble() / max).coerceIn(0.0, 1.0).toFloat()
                        val background by animateColorAsState(when {
                            day == null -> androidx.compose.ui.graphics.Color.Transparent
                            future -> MaterialTheme.colorScheme.surface
                            total == 0L -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f + fraction * 0.85f)
                        }, PennyMotion.effects(), label = "day heat")
                        Box(Modifier.weight(1f).heightIn(min = 36.dp).background(background, RoundedCornerShape(7.dp))
                            .padding(vertical = 8.dp).clearAndSetSemantics {
                                if (date != null) contentDescription = "${month.monthValue}月${date.dayOfMonth}日，${if (future) "尚未到来" else "支出${MoneyUtil.formatHeroAmount(total)}元"}"
                            }, contentAlignment = Alignment.Center) {
                            if (day != null) Text(day.toString(), style = MaterialTheme.typography.labelMedium,
                                color = if (fraction > 0.55f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Text(if (days.all { it.total == 0L }) "本月暂无已确认支出" else "颜色越深，支出越多 · 共 ${days.count { it.total > 0 }} 个支出日",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

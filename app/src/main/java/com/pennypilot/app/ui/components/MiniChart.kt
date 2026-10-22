package com.pennypilot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pennypilot.app.domain.usecase.ChartData
import com.pennypilot.app.ui.theme.priGradient
import com.pennypilot.app.util.MoneyUtil

@Composable
fun MiniChart(data: ChartData, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("支出分布 · 单位：${data.unit}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (data.values.isEmpty()) {
            Text("暂无可展示的支出数据", style = MaterialTheme.typography.bodyMedium)
        } else {
            val max = data.values.maxOrNull()?.coerceAtLeast(1) ?: 1L
            data.values.forEachIndexed { index, value ->
                val label = data.labels.getOrElse(index) { "第 ${index + 1} 项" }
                val highlighted = index == data.highlightIndex
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "$label，${MoneyUtil.formatHeroAmount(value)}${data.unit}"
                }) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)
                    Text(MoneyUtil.formatHeroAmount(value), style = MaterialTheme.typography.bodySmall,
                        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))) {
                        val fraction = (value.toDouble() / max).coerceIn(0.0, 1.0).toFloat()
                        if (fraction > 0f) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(
                                if (highlighted) Brush.horizontalGradient(MaterialTheme.priGradient)
                                else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondary)),
                                RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
    }
}

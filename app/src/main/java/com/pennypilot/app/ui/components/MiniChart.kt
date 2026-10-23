package com.pennypilot.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pennypilot.app.domain.usecase.ChartData
import com.pennypilot.app.ui.theme.PennyMotion
import com.pennypilot.app.ui.theme.priGradient
import com.pennypilot.app.util.MoneyUtil

@Composable
fun MiniChart(data: ChartData, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial())
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp)).padding(16.dp),
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
                key(label, index) {
                    // Start at the real value; scrolling back to a chart must not replay an entrance.
                    val fraction = animateFloatAsState(
                        (value.toDouble() / max).coerceIn(0.0, 1.0).toFloat(),
                        PennyMotion.spatial(), label = "chart value"
                    )
                    val brush = if (highlighted) Brush.horizontalGradient(MaterialTheme.priGradient)
                        else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondary))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "$label，${MoneyUtil.formatHeroAmount(value)}${data.unit}"
                    }) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal)
                        Text(MoneyUtil.formatHeroAmount(value), style = MaterialTheme.typography.bodySmall,
                            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Canvas(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))) {
                            val displayedFraction = fraction.value.coerceIn(0f, 1f)
                            if (displayedFraction > 0f) {
                                drawRoundRect(brush, size = Size(size.width * displayedFraction, size.height),
                                    cornerRadius = CornerRadius(4.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

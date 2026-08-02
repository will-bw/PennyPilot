package com.pennypilot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.domain.usecase.ChartData
import com.pennypilot.app.util.MoneyUtil

@Composable
fun MiniChart(data: ChartData, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .padding(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("支出分布", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(data.unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(74.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val maxVal = data.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            data.values.forEachIndexed { index, value ->
                val isHighlight = index == data.highlightIndex
                Column(
                    modifier = Modifier.weight(1f).height(74.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        MoneyUtil.formatHeroAmount(value),
                        fontSize = 10.sp,
                        color = if (isHighlight) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(((value.toFloat() / maxVal) * 50).dp.coerceAtLeast(4.dp))
                            .background(
                                if (isHighlight) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        data.labels.getOrElse(index) { "" },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

package com.pennypilot.app.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.util.MoneyUtil

@Composable
fun ReportScreen(vm: ReportViewModel = hiltViewModel()) {
    val reports by vm.reports.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("月度报告", style = MaterialTheme.typography.headlineLarge)
            Text("每月 1 日 09:00 自动生成", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (reports.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                    Text("还没有报告，下月 1 日会自动生成", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        items(reports) { report ->
            Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(report.yearMonth, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("¥${MoneyUtil.formatHeroAmount(report.totalExpense)}",
                        style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(12.dp))
                    // AI summary
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text(report.llmSummary, modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

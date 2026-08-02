package com.pennypilot.app.ui.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.ui.components.AmountText
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.util.MoneyUtil

@Composable
fun TransactionsScreen(vm: TransactionsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Month Hero
        item {
            MonthHero(
                expense = state.monthExpense,
                income = state.monthIncome,
                monthLabel = "${state.selectedMonth.year}年${state.selectedMonth.monthValue}月",
                onPrev = { vm.shiftMonth(-1) },
                onNext = { vm.shiftMonth(1) }
            )
        }

        // Day groups
        state.dayGroups.forEach { group ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(group.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (group.netAmount >= 0) "结余 +¥${MoneyUtil.formatHeroAmount(group.netAmount)}"
                        else "支出 ¥${MoneyUtil.formatHeroAmount(-group.netAmount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Card(shape = MaterialTheme.shapes.extraLarge) {
                    Column {
                        group.items.forEachIndexed { idx, txWithCat ->
                            if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            TransactionRow(
                                item = txWithCat,
                                onConfirm = { vm.confirmTx(txWithCat.tx.id) },
                                onIgnore = { vm.ignoreTx(txWithCat.tx.id) }
                            )
                        }
                    }
                }
            }
        }

        // Empty state
        if (state.dayGroups.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    Text("还没有记录，点 + 记一笔吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MonthHero(expense: Long, income: Long, monthLabel: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthLabel, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPrev) { Text("‹") }
            TextButton(onClick = onNext) { Text("›") }
        }
        Text(
            "¥${MoneyUtil.formatHeroAmount(expense)}",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(Modifier.height(8.dp))
        Text("收入 ¥${MoneyUtil.formatHeroAmount(income)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TransactionRow(item: TxWithCategory, onConfirm: () -> Unit, onIgnore: () -> Unit) {
    val tx = item.tx
    val cat = item.category

    Row(
        modifier = Modifier.fillMaxWidth().padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatIcon(
            colorIndex = cat?.colorIndex ?: 0,
            emoji = cat?.icon ?: "📦",
            size = 42
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant ?: tx.note ?: cat?.name ?: "未命名",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                tx.source.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tx.status == TxStatus.PENDING) {
                Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = onConfirm) { Text("记入") }
                    OutlinedButton(onClick = onIgnore) { Text("忽略") }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(amount = tx.amount, type = tx.type)
            if (cat != null) {
                Text(cat.name, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

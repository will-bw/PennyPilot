package com.pennypilot.app.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.theme.*
import com.pennypilot.app.util.MoneyUtil

@Composable
fun TransactionsScreen(vm: TransactionsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 108.dp),
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

        // Chips row (filter tabs)
        item {
            ChipsRow(pendingCount = state.pendingCount)
        }

        // Day groups
        state.dayGroups.forEach { group ->
            item {
                // day-head
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 18.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.label,
                        style = DayHeadDateStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (group.netAmount >= 0) "结余 +¥${MoneyUtil.formatHeroAmount(group.netAmount)}"
                        else "支出 ¥${MoneyUtil.formatHeroAmount(-group.netAmount)}",
                        style = DayHeadInfoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                // tx-card
                Card(
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column {
                        group.items.forEachIndexed { idx, txWithCat ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有记录，点 + 记一笔吧",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHero(
    expense: Long,
    income: Long,
    monthLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        // Eyebrow row with month nav circles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                monthLabel,
                style = EyebrowStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            // Nav circles: 28dp circle, 1dp border hairline
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = onPrev,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("‹", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = onNext,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Hero amount: ¥ at 24sp sub color + amount at 46sp
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "¥",
                style = HeroYenStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                MoneyUtil.formatHeroAmount(expense),
                style = HeroAmountStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Pills row: meta info
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Income pill (red)
            Pill(
                text = "收入 ¥${MoneyUtil.formatHeroAmount(income)}",
                bgColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                textColor = MaterialTheme.colorScheme.error
            )
            // Balance pill (green)
            val balance = income - expense
            if (balance >= 0) {
                Pill(
                    text = "结余 ¥${MoneyUtil.formatHeroAmount(balance)}",
                    bgColor = LightGreenSoft,
                    textColor = LightGreen
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(99.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = PillTextStyle,
            color = textColor
        )
    }
}

@Composable
private fun ChipsRow(pendingCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "全部" chip (selected by default)
        ChipItem(label = "全部", selected = true, badgeCount = 0)
        // "待确认" chip with pending badge
        ChipItem(label = "待确认", selected = false, badgeCount = pendingCount)
        // Category chips
        ChipItem(label = "餐饮", selected = false, badgeCount = 0)
        ChipItem(label = "交通", selected = false, badgeCount = 0)
        ChipItem(label = "购物", selected = false, badgeCount = 0)
        ChipItem(label = "生活", selected = false, badgeCount = 0)
    }
}

@Composable
private fun ChipItem(label: String, selected: Boolean, badgeCount: Int) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surface
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(99.dp))
            .border(1.dp, borderColor, RoundedCornerShape(99.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            style = ChipTextStyle,
            color = textColor
        )
        if (badgeCount > 0) {
            // Pending count badge
            Box(
                modifier = Modifier
                    .background(LightAmberSoft, RoundedCornerShape(99.dp))
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = "$badgeCount",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightAmber
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(item: TxWithCategory, onConfirm: () -> Unit, onIgnore: () -> Unit) {
    val tx = item.tx
    val cat = item.category
    val isPending = tx.status == TxStatus.PENDING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPending) Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            LightAmberSoft,
                            LightAmberSoft.copy(alpha = 0f)
                        ),
                        startX = 0f,
                        endX = Float.POSITIVE_INFINITY
                    )
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        // Cat icon: 42dp, 15dp radius
        CatIcon(
            colorIndex = cat?.colorIndex ?: 0,
            emoji = cat?.icon ?: "📦",
            size = 42
        )

        // Title + sub
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant ?: tx.note ?: cat?.name ?: "未命名",
                style = TxTitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            if (isPending) {
                // Pending badge + actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pending badge
                    Box(
                        modifier = Modifier
                            .background(LightAmberSoft, RoundedCornerShape(99.dp))
                            .border(
                                1.dp,
                                LightAmber.copy(alpha = 0.25f),
                                RoundedCornerShape(99.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "待确认",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = LightAmber
                        )
                    }
                    // Action pills
                    PendingActionPill(text = "记入", onClick = onConfirm, isPrimary = true)
                    PendingActionPill(text = "忽略", onClick = onIgnore, isPrimary = false)
                }
            } else {
                Text(
                    tx.source.label,
                    style = TxSubStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Amount + category label (right-aligned)
        Column(horizontalAlignment = Alignment.End) {
            val isExpense = tx.type == TxType.EXPENSE
            val amountColor = if (isExpense) MaterialTheme.colorScheme.onSurface
                              else MaterialTheme.colorScheme.error
            Text(
                text = MoneyUtil.formatForDisplay(tx.amount, isExpense),
                style = TxAmountStyle,
                color = amountColor
            )
            if (cat != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    cat.name,
                    style = CatLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun PendingActionPill(text: String, onClick: () -> Unit, isPrimary: Boolean) {
    val bgColor = if (isPrimary) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surface
    val textColor = if (isPrimary) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant

    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(99.dp))
            .border(
                if (isPrimary) 0.dp else 1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(99.dp)
            )
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

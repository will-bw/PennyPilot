package com.pennypilot.app.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TransactionsScreen(
    onEditTx: (Long) -> Unit = {},
    vm: TransactionsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val strip by vm.stripData.collectAsStateWithLifecycle()
    // 左滑删除的确认目标（null = 无待确认删除）
    var deleteTarget by remember { mutableStateOf<TxWithCategory?>(null) }

    // 英文月份 eyebrow（原型 "AUGUST · 本月支出"）
    val monthEyebrow = state.selectedMonth.month
        .getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase(Locale.ENGLISH) + " · 本月支出"
    // 日均支出 = 月度总支出 / 已过天数（历史月取全月天数）
    val today = LocalDate.now()
    val daysElapsed = if (state.selectedMonth.year == today.year && state.selectedMonth.month == today.month)
        today.dayOfMonth else state.selectedMonth.lengthOfMonth()
    val dailyAvg = if (daysElapsed > 0) state.monthExpense / daysElapsed else 0L

    Column(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 108.dp),
    ) {
        // Month Hero
        item {
            MonthHero(
                expense = state.monthExpense,
                income = state.monthIncome,
                dailyAvg = dailyAvg,
                monthLabel = monthEyebrow,
                onPrev = { vm.shiftMonth(-1) },
                onNext = { vm.shiftMonth(1) }
            )
        }

        // 近 30 日支出节奏条（定稿保留）
        item {
            MonthStrip(
                values = strip.first,
                windowStart = strip.second,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        // Chips row (filter tabs)
        item {
            ChipsRow(
                pendingCount = state.pendingCount,
                filter = state.filter,
                expenseCategories = state.expenseCategories,
                onSelect = { vm.setFilter(it) }
            )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.label,
                            style = DayHeadDateStyle,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            group.dateText,
                            style = DayHeadInfoStyle,
                            color = MaterialTheme.faintColor
                        )
                    }
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
                            if (txWithCat.tx.status == TxStatus.PENDING) {
                                TransactionRow(
                                    item = txWithCat,
                                    onConfirm = { vm.confirmTx(txWithCat.tx.id) },
                                    onIgnore = { vm.ignoreTx(txWithCat.tx.id) },
                                    onEdit = { onEditTx(txWithCat.tx.id) }
                                )
                            } else {
                                // 已确认行：左滑删除，点击进入编辑（设计 §7.3）
                                SwipeableTxRow(
                                    item = txWithCat,
                                    onEdit = { onEditTx(txWithCat.tx.id) },
                                    onRequestDelete = { deleteTarget = txWithCat }
                                )
                            }
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

    // 左滑删除二次确认
    deleteTarget?.let { target ->
        val title = target.tx.merchant ?: target.tx.note ?: target.category?.name ?: "该笔账目"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这笔记录？") },
            text = { Text("将删除「$title」，删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTx(target.tx.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
    }
}

@Composable
private fun MonthHero(
    expense: Long,
    income: Long,
    dailyAvg: Long,
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

        // Pills row: 收入（红点 + 暖红浅底）+ 日均支出（描边 pill）
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Income pill：红点 + red-soft 底
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
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                Text(
                    "收入 ¥${MoneyUtil.formatHeroAmount(income)}",
                    style = PillTextStyle,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // 日均 pill：卡片底 + 发丝描边
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    "日均 ¥${MoneyUtil.formatHeroAmount(dailyAvg)}",
                    style = PillTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 近 30 日支出节奏条：30 根圆角小柱，当天高亮主色 */
@Composable
private fun MonthStrip(values: List<Long>, windowStart: LocalDate, modifier: Modifier = Modifier) {
    val maxVal = values.maxOrNull()?.coerceAtLeast(1) ?: 1L
    val isDark = MaterialTheme.colorScheme.primary == DarkPrimary
    val barColor = if (isDark) DarkPrimary.copy(alpha = 0.28f) else Color(0xFFD9E0FA)
    val windowEnd = windowStart.plusDays(29)
    val today = LocalDate.now()

    Column(modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, v ->
                val hot = i == values.lastIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(((v.toFloat() / maxVal) * 44).dp.coerceAtLeast(4.dp))
                        .background(
                            if (hot) MaterialTheme.colorScheme.primary else barColor,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.5.dp, bottomEnd = 1.5.dp)
                        )
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${windowStart.monthValue}月${windowStart.dayOfMonth}日",
                fontSize = 10.sp, color = MaterialTheme.faintColor
            )
            Text("近 30 日支出节奏", fontSize = 10.sp, color = MaterialTheme.faintColor)
            Text(
                if (windowEnd == today) "今天" else "${windowEnd.monthValue}月${windowEnd.dayOfMonth}日",
                fontSize = 10.sp, color = MaterialTheme.faintColor
            )
        }
    }
}

@Composable
private fun ChipsRow(
    pendingCount: Int,
    filter: TxFilter,
    expenseCategories: List<com.pennypilot.app.data.db.entity.CategoryEntity>,
    onSelect: (TxFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChipItem(
            label = "全部",
            selected = filter == TxFilter.All,
            badgeCount = 0,
            selectedBadge = false,
            onClick = { onSelect(TxFilter.All) }
        )
        ChipItem(
            label = "待确认",
            selected = filter == TxFilter.Pending,
            badgeCount = pendingCount,
            selectedBadge = filter == TxFilter.Pending,
            onClick = { onSelect(TxFilter.Pending) }
        )
        expenseCategories.forEach { cat ->
            ChipItem(
                label = cat.name,
                selected = (filter as? TxFilter.Category)?.categoryId == cat.id,
                badgeCount = 0,
                selectedBadge = false,
                onClick = { onSelect(TxFilter.Category(cat.id)) }
            )
        }
    }
}

@Composable
private fun ChipItem(
    label: String,
    selected: Boolean,
    badgeCount: Int,
    selectedBadge: Boolean,
    onClick: () -> Unit
) {
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
            .clickable(onClick = onClick)
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
            // Pending count badge：选中态为半透明白底（原型 .chip.on .cnt）
            Box(
                modifier = Modifier
                    .background(
                        if (selectedBadge) Color.White.copy(alpha = 0.25f) else MaterialTheme.pendingAmberSoftColor,
                        RoundedCornerShape(99.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = "$badgeCount",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedBadge) MaterialTheme.colorScheme.onPrimary else MaterialTheme.pendingAmberColor
                )
            }
        }
    }
}

/** 已确认行左滑删除：滑到阈值弹确认对话框，不直接移除行（设计 §7.3） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTxRow(
    item: TxWithCategory,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { total -> total * 0.35f },
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRequestDelete()
            // 始终返回 false：行本身不消失，由确认对话框确认后 VM 删除驱动列表刷新
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    "删除",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 24.dp)
                )
            }
        }
    ) {
        // 不透明底避免滑动时背景穿透
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
            TransactionRow(
                item = item,
                onConfirm = {},
                onIgnore = {},
                onEdit = onEdit,
                onClick = onEdit
            )
        }
    }
}

@Composable
private fun TransactionRow(
    item: TxWithCategory,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit = {}
) {
    val tx = item.tx
    val cat = item.category
    val isPending = tx.status == TxStatus.PENDING
    // 原型副标题：「HH:mm · 来源」
    val timeText = remember(tx.occurredAt) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(tx.occurredAt))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPending) Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.pendingAmberSoftColor,
                            MaterialTheme.pendingAmberSoftColor.copy(alpha = 0f)
                        ),
                        startX = 0f,
                        endX = Float.POSITIVE_INFINITY
                    )
                ) else Modifier.clickable(onClick = onClick)
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

        // Title(+badge) + sub + pending actions
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    tx.merchant ?: tx.note ?: cat?.name ?: "未命名",
                    style = TxTitleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isPending) {
                    // 待确认 badge 与标题同行（原型 .badge-pending）
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.pendingAmberSoftColor, RoundedCornerShape(99.dp))
                            .border(
                                1.dp,
                                MaterialTheme.pendingAmberColor.copy(alpha = 0.25f),
                                RoundedCornerShape(99.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "待确认",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.pendingAmberColor
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "$timeText · ${tx.source.label}",
                style = TxSubStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isPending) {
                Spacer(Modifier.height(7.dp))
                // 行内操作：记入 / 忽略 / 编辑（原型 .pend-actions）
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PendingActionPill(text = "记入", onClick = onConfirm, isPrimary = true)
                    PendingActionPill(text = "忽略", onClick = onIgnore, isPrimary = false)
                    PendingActionPill(text = "编辑", onClick = onEdit, isPrimary = false)
                }
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

    // 用 Box + clickable 而非 TextButton，避免 M3 默认最小高度撑大胶囊（原型 5×12dp 内边距的紧凑胶囊）
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(99.dp))
            .border(
                if (isPrimary) 0.dp else 1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(99.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

package com.pennypilot.app.ui.transactions

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.AmountText
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.theme.*
import com.pennypilot.app.util.MoneyUtil
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(onEditTx: (Long) -> Unit = {}, vm: TransactionsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val strip by vm.stripData.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<TxWithCategory?>(null) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    val filtered = state.filter != TxFilter.All || state.query.isNotBlank()
    val daysElapsed = if (YearMonth.from(state.selectedMonth) == YearMonth.from(state.today))
        state.today.dayOfMonth else state.selectedMonth.lengthOfMonth()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "month") {
                MonthHero(state, daysElapsed, vm::shiftMonth, vm::returnToCurrentMonth, ledgerMotion())
            }
            if (!state.isLoading && state.error == null && strip.first.isNotEmpty()) {
                item(key = "strip") {
                    MonthStrip(strip.first, strip.second, state.today, ledgerMotion().padding(horizontal = 20.dp))
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    label = { Text("搜索本月账单") },
                    placeholder = { Text("商家、备注或分类") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (state.query.isNotEmpty()) ({
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "清空搜索") }
                    }) else null,
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = ledgerMotion().fillMaxWidth().padding(horizontal = 20.dp)
                )
            }
            item(key = "filters") {
                Row(
                    ledgerMotion().fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LedgerFilter("全部", state.filter == TxFilter.All) { vm.setFilter(TxFilter.All) }
                    LedgerFilter("待确认 ${state.pendingCount}", state.filter == TxFilter.Pending) { vm.setFilter(TxFilter.Pending) }
                    LedgerFilter("支出", state.filter == TxFilter.Type(TxType.EXPENSE)) { vm.setFilter(TxFilter.Type(TxType.EXPENSE)) }
                    LedgerFilter("收入", state.filter == TxFilter.Type(TxType.INCOME)) { vm.setFilter(TxFilter.Type(TxType.INCOME)) }
                    val category = (state.filter as? TxFilter.Category)?.let { f -> state.categories.find { it.id == f.categoryId } }
                    LedgerFilter(category?.name ?: "分类", state.filter is TxFilter.Category, opensMenu = true) { showCategories = true }
                }
            }
            when {
                state.isLoading -> item(key = "loading") {
                    LedgerState("正在读取账单", "稍候即可查看本月收支。", loading = true, modifier = ledgerMotion().padding(horizontal = 20.dp))
                }
                state.error != null -> item(key = "error") {
                    LedgerState("账单加载失败", state.error.orEmpty(), actionLabel = "重试", onAction = vm::retry,
                        modifier = ledgerMotion().padding(horizontal = 20.dp))
                }
                state.dayGroups.isEmpty() -> item(key = "empty") {
                    val pending = state.filter == TxFilter.Pending && state.query.isBlank()
                    LedgerState(
                        title = if (pending) "本月没有待确认记录" else if (filtered) "没有匹配的账单" else "这个月还没有记录",
                        message = if (pending) "待确认记录会显示在它发生的月份。" else if (filtered) "试试其他关键词，或清除筛选条件。" else "点击底部记账按钮，记下第一笔收支。",
                        actionLabel = if (filtered) "查看本月全部" else null,
                        onAction = if (filtered) vm::clearFilters else null,
                        modifier = ledgerMotion().padding(horizontal = 20.dp)
                    )
                }
                else -> {
                    item(key = "scope") {
                        Text(
                            if (state.filter == TxFilter.Pending) "以下为本月待确认记录，尚未计入收支统计"
                            else if (filtered) "以下为筛选结果；页首金额为全月已确认收支"
                            else "已确认记录计入收支，待确认记录单独标记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = ledgerMotion().padding(horizontal = 20.dp)
                        )
                    }
                    state.dayGroups.forEach { group ->
                        item(key = "day_${group.date}") {
                            Column(ledgerMotion().animateContentSize(PennyMotion.spatial()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${group.label} · ${group.dateText}", style = DayHeadDateStyle,
                                    modifier = Modifier.semantics { heading() })
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (group.expense > 0) Text("支出 ¥${MoneyUtil.formatHeroAmount(group.expense)}", style = MaterialTheme.typography.bodySmall)
                                    if (group.income > 0) Text("收入 ¥${MoneyUtil.formatHeroAmount(group.income)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.incomeColor)
                                    if (group.pendingCount > 0) Text("${group.pendingCount} 笔待确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.pendingAmberColor)
                                }
                            }
                        }
                        // 每笔账目独立惰性布局，长日期分组也不会一次性创建所有行。
                        items(group.items, key = { "tx_${it.tx.id}" }) { item ->
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = ledgerMotion().fillMaxWidth().padding(horizontal = 20.dp)
                            ) {
                                val busy = item.tx.id in state.processingIds
                                SwipeableTxRow(item, busy, { onEditTx(item.tx.id) }, { deleteTarget = item },
                                    { vm.confirmTx(item.tx.id) }, { vm.ignoreTx(item.tx.id) },
                                    item.tx.duplicateOf?.let { relatedId -> { onEditTx(relatedId) } })
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    if (showCategories) CategoryDialog(state.categories, state.filter, { showCategories = false }) {
        vm.setFilter(it); showCategories = false
    }
    deleteTarget?.let { target ->
        val title = target.tx.merchant?.takeIf { it.isNotBlank() } ?: target.tx.note?.takeIf { it.isNotBlank() } ?: target.category?.name ?: "该笔账目"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这笔记录？") },
            text = { Text("将删除「$title」，金额 ¥${MoneyUtil.formatHeroAmount(kotlin.math.abs(target.tx.amount))}。删除后不可恢复。") },
            confirmButton = { TextButton(onClick = { vm.deleteTx(target.tx.id); deleteTarget = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

// Stable keys keep an edited or confirmed transaction in place while its neighbors settle.
// animateItem is scoped to LazyItemScope, so this factory must retain that receiver.
@SuppressLint("ModifierFactoryExtensionFunction")
private fun LazyItemScope.ledgerMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = PennyMotion.effects(),
    placementSpec = PennyMotion.spatial(),
    fadeOutSpec = tween(PennyMotion.Exit)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthHero(state: TransactionsUiState, daysElapsed: Int, shift: (Int) -> Unit, returnToCurrent: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.animateContentSize(PennyMotion.spatial()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MonthHeading(state)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MonthNavigation(shift) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MonthHeading(state, Modifier.weight(1f))
                    MonthNavigation(shift)
                }
            }
        }
        AnimatedVisibility(
            visible = YearMonth.from(state.selectedMonth) != YearMonth.from(state.today),
            enter = fadeIn(PennyMotion.effects()),
            exit = fadeOut(tween(PennyMotion.Exit))
        ) {
            TextButton(onClick = returnToCurrent, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回本月") }
        }
        Text(
            if (state.isLoading || state.error != null) "—" else "¥${MoneyUtil.formatHeroAmount(state.monthExpense)}",
            style = HeroAmountStyle,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        )
        if (!state.isLoading && state.error == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill("收入 ¥${MoneyUtil.formatHeroAmount(state.monthIncome)}", true)
                SummaryPill("日均 ¥${MoneyUtil.formatHeroAmount(state.monthExpense / daysElapsed.coerceAtLeast(1))}", false)
            }
        }
    }
}

@Composable
private fun MonthHeading(state: TransactionsUiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        AnimatedContent(
            targetState = state.selectedMonth,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(PennyMotion.spatial()) { it / 8 * direction } +
                    fadeIn(tween(PennyMotion.Enter, delayMillis = PennyMotion.Exit))) togetherWith
                    (slideOutHorizontally(PennyMotion.spatial()) { -it / 8 * direction } + fadeOut(tween(PennyMotion.Exit)))
            },
            label = "ledger month"
        ) { month ->
            Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        Text("本月支出 · 已确认", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthNavigation(shift: (Int) -> Unit) {
    IconButton(onClick = { shift(-1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ChevronLeft, "上一个月") }
    IconButton(onClick = { shift(1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ChevronRight, "下一个月") }
}

@Composable
private fun SummaryPill(text: String, income: Boolean) {
    Text(text, style = PillTextStyle,
        color = if (income) MaterialTheme.incomeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(if (income) MaterialTheme.incomeSoftColor else MaterialTheme.colorScheme.surface, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun MonthStrip(values: List<Long>, windowStart: LocalDate, today: LocalDate, modifier: Modifier = Modifier) {
    val maxVal = values.maxOrNull()?.coerceAtLeast(1) ?: 1L
    val windowEnd = windowStart.plusDays(values.lastIndex.toLong())
    val total = values.sum()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clearAndSetSemantics {
                contentDescription = "${windowStart.monthValue}月${windowStart.dayOfMonth}日至${windowEnd.monthValue}月${windowEnd.dayOfMonth}日，已确认支出共${MoneyUtil.formatHeroAmount(total)}元"
            },
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, v ->
                key(windowStart.plusDays(i.toLong())) {
                    val fraction = animateFloatAsState(
                        (v.toDouble() / maxVal).coerceIn(0.0, 1.0).toFloat(),
                        PennyMotion.spatial(), label = "daily expense height"
                    )
                    val color = animateColorAsState(when { v == 0L -> MaterialTheme.colorScheme.outlineVariant
                        windowStart.plusDays(i.toLong()) == today -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f) }, PennyMotion.effects(), label = "daily expense color")
                    Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        val height = (size.height * fraction.value.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(color.value, topLeft = Offset(0f, size.height - height),
                            size = Size(size.width, height), cornerRadius = CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        Text("近 30 日支出 · ${windowStart.monthValue}/${windowStart.dayOfMonth}—${windowEnd.monthValue}/${windowEnd.dayOfMonth}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LedgerFilter(label: String, selected: Boolean, opensMenu: Boolean = false, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (opensMenu) ({ Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }) else null,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 220.dp), shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary))
}

@Composable
private fun CategoryDialog(categories: List<CategoryEntity>, filter: TxFilter, onDismiss: () -> Unit, onSelect: (TxFilter) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择分类") },
        text = {
            if (categories.isEmpty()) Text("暂无可用分类") else LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(categories, key = { it.id }) { cat ->
                    val selected = (filter as? TxFilter.Category)?.categoryId == cat.id
                    Surface(onClick = { onSelect(TxFilter.Category(cat.id)) }, shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }) {
                        Row(Modifier.padding(12.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(cat.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(if (cat.type == TxType.EXPENSE) "支出" else "收入", style = MaterialTheme.typography.bodySmall)
                            if (selected) Icon(Icons.Default.Check, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTxRow(item: TxWithCategory, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onConfirm: () -> Unit, onIgnore: () -> Unit, onReviewDuplicate: (() -> Unit)? = null) {
    val pending = item.tx.status == TxStatus.PENDING
    val canDelete by rememberUpdatedState(!busy && !pending)
    val requestDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart && canDelete) requestDelete()
        false
    })
    val armed = state.targetValue == SwipeToDismissBoxValue.EndToStart
    val iconScale by animateFloatAsState(if (armed) 1.12f else 1f, PennyMotion.spatial(), label = "delete threshold")
    val deleteBackground by animateColorAsState(
        if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
        PennyMotion.effects(), label = "delete background"
    )
    val deleteTint by animateColorAsState(
        if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error,
        PennyMotion.effects(), label = "delete icon"
    )
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !busy && !pending,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(deleteBackground), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Delete, null, tint = deleteTint,
                    modifier = Modifier.padding(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
            }
        }) {
        TransactionRow(item, busy, onEdit, onConfirm, onIgnore, onDelete, onReviewDuplicate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionRow(item: TxWithCategory, busy: Boolean, onEdit: () -> Unit, onConfirm: () -> Unit, onIgnore: () -> Unit, onDelete: (() -> Unit)? = null, onReviewDuplicate: (() -> Unit)? = null) {
    val tx = item.tx
    val pending = tx.status == TxStatus.PENDING
    val possibleDuplicate = tx.duplicateOf != null
    var menuOpen by remember { mutableStateOf(false) }
    val time = remember(tx.occurredAt) { java.time.Instant.ofEpochMilli(tx.occurredAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }
    val title = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.note?.takeIf { it.isNotBlank() } ?: item.category?.name ?: "未命名账目"
    val background by animateColorAsState(
        if (pending) MaterialTheme.pendingAmberSoftColor else MaterialTheme.colorScheme.surface,
        PennyMotion.effects(), label = "transaction status"
    )
    BoxWithConstraints(Modifier.fillMaxWidth().background(background)) {
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.2f
        Column(
            Modifier.fillMaxWidth().then(if (!pending) Modifier.clickable(enabled = !busy, onClickLabel = "编辑账目", onClick = onEdit) else Modifier)
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatIcon(item.category?.colorIndex ?: 0, item.category?.icon ?: "📦", size = 42)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = TxTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$time · ${tx.source.label} · ${item.category?.name ?: "未分类"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (compact) AmountText(tx.amount, tx.type, Modifier.fillMaxWidth(), TxAmountStyle)
                }
                if (!compact) AmountText(tx.amount, tx.type, Modifier.widthIn(max = 136.dp), TxAmountStyle)
                if (!pending && onDelete != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "$title，更多操作") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("编辑") }, onClick = { menuOpen = false; onEdit() },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) })
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = pending,
                enter = expandVertically(PennyMotion.spatial(), expandFrom = Alignment.Top) + fadeIn(PennyMotion.effects()),
                exit = shrinkVertically(PennyMotion.spatial(), shrinkTowards = Alignment.Top) + fadeOut(tween(PennyMotion.Exit))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (busy) "正在处理…" else if (possibleDuplicate) "疑似重复 · 尚未计入统计" else "待确认 · 尚未计入统计", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.pendingAmberColor, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (possibleDuplicate) {
                        Text("与另一笔记录相似。请核对是否为同一笔支付，也可能是连续发生的不同交易。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onReviewDuplicate != null) {
                            TextButton(onClick = onReviewDuplicate, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看相近记录") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(onClick = onConfirm, enabled = !busy && pending, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (possibleDuplicate) "仍然记入" else "记入") }
                        OutlinedButton(onClick = onIgnore, enabled = !busy && pending, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (possibleDuplicate) "重复，忽略" else "忽略") }
                        TextButton(onClick = onEdit, enabled = !busy && pending, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑") }
                    }
                }
            }
        }
    }
}

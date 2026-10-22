package com.pennypilot.app.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.R
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.theme.faintColor
import com.pennypilot.app.ui.theme.priGradient

/** Ivy 风格手动记账底部弹层：类型 + 分类横条 + 金额 + 备注 + 数字键盘 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadSheet(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (type: TxType, categoryId: Long, amountCents: Long, note: String) -> Unit
) {
    var txType by remember { mutableStateOf(TxType.EXPENSE) }
    var amount by remember { mutableStateOf(KeypadAmount()) }
    var note by remember { mutableStateOf("") }

    val pool = remember(categories, txType) {
        categories.filter { it.type == txType && it.enabled }
    }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pool) {
        if (pool.none { it.id == selectedId }) selectedId = pool.firstOrNull()?.id
    }

    val canSave = amount.cents > 0 && selectedId != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypePill("支出", txType == TxType.EXPENSE) { txType = TxType.EXPENSE }
                TypePill("收入", txType == TxType.INCOME) { txType = TxType.INCOME }
            }

            if (pool.isEmpty()) {
                Text(
                    "暂无该类型分类，请先到「我的 → 分类管理」添加",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.faintColor
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pool, key = { it.id }) { cat ->
                        val selected = cat.id == selectedId
                        Column(
                            modifier = Modifier
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedId = cat.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CatIcon(colorIndex = cat.colorIndex, emoji = cat.icon, size = 36)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat.name,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.W600,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    "¥",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    amount.display,
                    fontFamily = FontFamily.Serif,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            KeypadGrid(
                onDigit = { amount = amount.appendDigit(it) },
                onDot = { amount = amount.appendDot() },
                onBackspace = { amount = amount.backspace() }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(if (canSave) 1f else 0.45f)
                    .background(
                        Brush.linearGradient(MaterialTheme.priGradient),
                        RoundedCornerShape(15.dp)
                    )
                    .clickable(enabled = canSave) {
                        onSave(txType, selectedId!!, amount.cents, note.trim())
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "保存",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun RowScope.TypePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(99.dp)
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(99.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KeypadGrid(
    onDigit: (Char) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit
) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫')
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(15.dp)
                            )
                            .clickable {
                                when (key) {
                                    '.' -> onDot()
                                    '⌫' -> onBackspace()
                                    else -> onDigit(key)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == '⌫') {
                            Icon(
                                painter = painterResource(R.drawable.ic_backspace),
                                contentDescription = "退格",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                key.toString(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.W600,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                // 末行不足 3 键时占位，保持网格对齐
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f).height(52.dp))
                }
            }
        }
    }
}

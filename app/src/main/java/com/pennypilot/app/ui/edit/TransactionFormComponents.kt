package com.pennypilot.app.ui.edit

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionTypeSelector(type: TxType, enabled: Boolean = true, onSelect: (TxType) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(TxType.EXPENSE to "支出", TxType.INCOME to "收入").forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = type == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(label) }
        }
    }
}

@Composable
internal fun TransactionCategorySelection(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    enabled: Boolean = true,
    onSelect: (Long) -> Unit
) {
    if (categories.isEmpty()) {
        Text("暂无可用分类，请先在分类管理中添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 88.dp).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { category ->
                        val selected = category.id == selectedId
                        val shape = RoundedCornerShape(14.dp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(shape)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(category.id) })
                                .semantics(mergeDescendants = true) {}
                                .heightIn(min = 88.dp)
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CatIcon(category.colorIndex, category.icon, size = 34)
                            Text(
                                category.name + if (!category.enabled) "（已停用）" else "",
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 日期和时刻分别提交，取消任一弹窗都不会修改另一个字段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionDateTimeFields(occurredAt: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val local = remember(occurredAt) { Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { showDate = true },
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("日期", style = MaterialTheme.typography.labelMedium)
                Text(local.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(
            onClick = { showTime = true },
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("时间", style = MaterialTheme.typography.labelMedium)
                Text(local.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (showDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(date.atTime(local.toLocalTime()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    showDate = false
                }, enabled = picker.selectedDateMillis != null) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) { DatePicker(state = picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(local.hour, local.minute, DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择时间") },
            text = { TimeInput(state = picker) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(local.toLocalDate().atTime(picker.hour, picker.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    showTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } }
        )
    }
}

@Composable
internal fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放弃未保存的修改？") },
        text = { Text("离开后，本次修改不会保存。") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("继续编辑") } }
    )
}

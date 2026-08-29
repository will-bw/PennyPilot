package com.pennypilot.app.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    onBack: () -> Unit,
    vm: EditTransactionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 时间选择：先选日期，再选时刻（Material3 DatePicker + TimePicker）
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    val occurredText = remember(state.occurredAt) {
        SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())
            .format(Date(state.occurredAt))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (state.isEditing) "编辑账目" else "记一笔",
            style = MaterialTheme.typography.headlineLarge
        )

        // 支出 / 收入类型切换（设计 §7.3 编辑页字段）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypePill("支出", selected = state.type == TxType.EXPENSE) { vm.setType(TxType.EXPENSE) }
            TypePill("收入", selected = state.type == TxType.INCOME) { vm.setType(TxType.INCOME) }
        }

        // Amount input
        OutlinedTextField(
            value = state.amountText,
            onValueChange = vm::updateAmount,
            label = { Text("金额（元）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Category grid
        Text("分类", style = MaterialTheme.typography.titleMedium)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.categories) { cat ->
                val selected = cat.id == state.selectedCategoryId
                Column(
                    modifier = Modifier
                        .clickable { vm.selectCategory(cat.id) }
                        .alpha(if (selected) 1f else 0.6f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CatIcon(colorIndex = cat.colorIndex, emoji = cat.icon, size = 42)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Note
        OutlinedTextField(
            value = state.note, onValueChange = vm::updateNote,
            label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        // Merchant
        OutlinedTextField(
            value = state.merchant, onValueChange = vm::updateMerchant,
            label = { Text("商户") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        // Account tag（可选文本标签，设计 §3.1 account_tag）
        OutlinedTextField(
            value = state.accountTag, onValueChange = vm::updateAccountTag,
            label = { Text("账户标签（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        // 时间选择行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showDatePicker = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("时间", style = MaterialTheme.typography.titleMedium)
            Text(
                occurredText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(16.dp))

        // Save button
        Button(
            onClick = { vm.save(onBack) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium
        ) { Text("保存") }

        // Delete button (edit mode only)
        if (state.isEditing) {
            OutlinedButton(
                onClick = { vm.delete(onBack) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("删除") }
        }
    }

    // 日期选择：DatePickerState 以 UTC 午夜为基准，需先折算
    if (showDatePicker) {
        val currentLocalDate = Instant.ofEpochMilli(state.occurredAt)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = currentLocalDate
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        showTimePicker = true
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 时刻选择：与已选日期合成后写回
    if (showTimePicker) {
        val zoned = Instant.ofEpochMilli(state.occurredAt).atZone(ZoneId.systemDefault())
        val timePickerState = rememberTimePickerState(
            initialHour = zoned.hour,
            initialMinute = zoned.minute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: zoned.toLocalDate()
                    val ms = date.atTime(timePickerState.hour, timePickerState.minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    vm.setOccurredAt(ms)
                    pickedDate = null
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickedDate = null
                    showTimePicker = false
                }) { Text("取消") }
            },
            text = { TimePicker(state = timePickerState) }
        )
    }
}

/** 支出/收入切换胶囊（与流水页 chip 风格一致） */
@Composable
private fun TypePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
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
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

package com.pennypilot.app.ui.edit

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.theme.PennyMotion
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
    BoxWithConstraints(Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial())) {
        val columns = (maxWidth / 88.dp).toInt().coerceIn(2, 6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { category ->
                        val selected = category.id == selectedId
                        val shape = RoundedCornerShape(14.dp)
                        val containerColor by animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            PennyMotion.effects(), label = "categoryContainer"
                        )
                        val borderColor by animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            PennyMotion.effects(), label = "categoryBorder"
                        )
                        val label = category.name + if (!category.enabled) "（已停用）" else ""
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(shape)
                                .background(containerColor)
                                .border(1.dp, borderColor, shape)
                                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(category.id) })
                                .semantics(mergeDescendants = true) { contentDescription = label }
                                .heightIn(min = 96.dp)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CatIcon(category.colorIndex, category.icon, size = 34)
                                Text(
                                    label,
                                    modifier = Modifier.clearAndSetSemantics {},
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = selected,
                                modifier = Modifier.align(Alignment.TopEnd),
                                enter = fadeIn(PennyMotion.effects()) + scaleIn(PennyMotion.effects(), initialScale = .8f),
                                exit = fadeOut(tween(PennyMotion.Exit)) + scaleOut(tween(PennyMotion.Exit), targetScale = .8f)
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null,
                                    modifier = Modifier.padding(5.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 日期和时刻分别提交，取消任一弹窗都不会修改另一个字段。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TransactionDateTimeFields(occurredAt: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val local = remember(occurredAt) { Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()) }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 340.dp || fontScale > 1.2f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (stacked) 1 else 2) {
            DateTimeField("日期", local.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), Icons.Outlined.DateRange,
                enabled, Modifier.weight(1f), onClick = { showDate = true })
            DateTimeField("时间", local.format(DateTimeFormatter.ofPattern("HH:mm")), Icons.Outlined.AccessTime,
                enabled, Modifier.weight(1f), onClick = { showTime = true })
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
private fun DateTimeField(label: String, value: String, icon: ImageVector, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
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

/** Errors keep their text through the exit, then return the space to the form. */
@Composable
internal fun TransactionFormError(error: String?) {
    AnimatedContent(
        targetState = error,
        contentKey = { it != null },
        transitionSpec = {
            (fadeIn(PennyMotion.effects()) togetherWith fadeOut(tween(PennyMotion.Exit)))
                .using(SizeTransform { _, _ -> PennyMotion.spatial() })
        },
        label = "formError"
    ) { message ->
        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** A fixed button remains the interaction anchor while its content acknowledges saving. */
@Composable
internal fun TransactionSaveLabel(isSaving: Boolean, label: String = "保存账目") {
    AnimatedContent(
        targetState = isSaving,
        transitionSpec = {
            fadeIn(tween(PennyMotion.Feedback, delayMillis = PennyMotion.Exit)) togetherWith fadeOut(tween(PennyMotion.Exit))
        },
        label = "saveAction"
    ) { saving ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Text(if (saving) "正在保存…" else label)
        }
    }
}

package com.pennypilot.app.ui.input

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.theme.PennyMotion
import com.pennypilot.app.ui.edit.TransactionSaveLabel
import com.pennypilot.app.ui.theme.incomeColor
import com.pennypilot.app.util.MoneyUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiInputScreen(
    onNavigateToManual: () -> Unit,
    onViewLedger: () -> Unit,
    vm: AiInputViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.allCategories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showKeypad by rememberSaveable { mutableStateOf(false) }
    var keypadSaved by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.setError("未获得录音权限。你可以继续输入文字，或在系统设置中开启麦克风权限。")
        else vm.notifyMicrophoneGranted()
    }
    DisposableEffect(vm) { onDispose { vm.cancelRecording() } }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    LaunchedEffect(state.lastSave) {
        if (state.lastSave != null) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("记一笔", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(
                onClick = { focus.clearFocus(); vm.clearError(); keypadSaved = false; showKeypad = true },
                enabled = !state.isSaving && !state.isRecording && !state.isRecognizing,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("手动记账") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "flow_status") {
                InputFlowFeedback(state, onNavigateToManual, onViewLedger)
            }
            state.error?.let { error ->
                item(key = "error") {
                    LedgerState(
                        "操作未完成", error,
                        actionLabel = if (state.previewItems.isNotEmpty()) "继续核对账目" else "改用手动记账",
                        onAction = { vm.clearError(); if (state.previewItems.isEmpty()) { focus.clearFocus(); keypadSaved = false; showKeypad = true } },
                        modifier = Modifier.animateItem(fadeInSpec = PennyMotion.effects(), placementSpec = PennyMotion.spatial(), fadeOutSpec = tween(PennyMotion.Exit))
                    )
                }
            }
            items(state.displayedItems, key = { it.draftId }) { item ->
                PreviewCard(
                    item = item,
                    enabled = !state.isSaving,
                    modifier = Modifier.animateItem(
                        fadeInSpec = PennyMotion.effects(), placementSpec = PennyMotion.spatial(), fadeOutSpec = tween(PennyMotion.Exit)
                    ),
                    onEdit = { focus.clearFocus(); editingId = item.draftId },
                    onRemove = { removingId = item.draftId }
                )
            }
            if (!state.isSaving && state.savedCount > 0 && state.previewItems.isNotEmpty()) {
                item(key = "partial_saved") {
                    Text("已保存 ${state.savedCount} 笔，其余账目仍在预览中。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SnackbarHost(snackbar)
                AnimatedVisibility(
                    visible = state.displayedItems.isNotEmpty(),
                    enter = expandVertically(PennyMotion.spatial()) + fadeIn(PennyMotion.effects()),
                    exit = shrinkVertically(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit))
                ) {
                    Button(
                        onClick = { focus.clearFocus(); vm.saveAll {} },
                        enabled = state.previewItems.isNotEmpty() && !state.isSaving && !state.isParsing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = if (state.isSaving) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            disabledContentColor = if (state.isSaving) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    ) {
                        TransactionSaveLabel(
                            isSaving = state.isSaving,
                            label = if (state.previewItems.isEmpty()) "保存账目" else "确认并保存 ${state.previewItems.size} 笔"
                        )
                    }
                }
                InputDock(
                    text = state.inputText,
                    onTextChange = vm::updateInput,
                    onSend = { focus.clearFocus(); vm.sendForParsing() },
                    onMicPress = {
                        focus.clearFocus()
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onMicRelease = vm::stopRecording,
                    isRecording = state.isRecording,
                    isRecognizing = state.isRecognizing,
                    isSending = state.isParsing,
                    sendLabel = "识别账目",
                    enabled = !state.isSaving,
                    placeholder = if (state.previewItems.isEmpty()) "输入一笔或多笔账目…" else "继续输入，追加待确认账目…"
                )
            }
        }
    }
    if (showKeypad) {
        KeypadSheet(
            categories = categories, isSaving = state.isSaving, error = state.error,
            dismissRequested = keypadSaved,
            onDismiss = { if (!state.isSaving) { showKeypad = false; keypadSaved = false; vm.clearError() } },
            onSave = { type, categoryId, cents, note, occurredAt ->
                vm.saveManual(type, categoryId, cents, note, occurredAt) { keypadSaved = true }
            }
        )
    }
    state.previewItems.find { it.draftId == editingId }?.let { item ->
        PreviewEditorSheet(item, categories, onDismiss = { editingId = null }, onApply = vm::updatePreviewItem)
    }
    state.previewItems.find { it.draftId == removingId }?.let { item ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("移除这笔待确认账目？") },
            text = { Text("${item.merchant ?: item.note ?: item.categoryName} · ¥${MoneyUtil.formatHeroAmount(item.amount)}，尚未保存到账本。") },
            confirmButton = {
                TextButton(onClick = {
                    val index = state.previewItems.indexOfFirst { it.draftId == item.draftId }
                    if (index >= 0) vm.removePreviewItem(index)
                    removingId = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("保留") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(item: PreviewItem, enabled: Boolean, modifier: Modifier = Modifier, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(onClick = onEdit, enabled = enabled, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatIcon(item.categoryColorIndex, item.categoryIcon, size = 40)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.merchant ?: item.note ?: item.categoryName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.categoryName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        remember(item.occurredAt) { SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(item.occurredAt)) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.merchant != null && item.note != null) Text(item.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "${if (item.type == TxType.INCOME) "收入" else "支出"}  ¥${MoneyUtil.formatHeroAmount(item.amount)}",
                style = MaterialTheme.typography.titleLarge,
                color = if (item.type == TxType.INCOME) MaterialTheme.incomeColor else MaterialTheme.colorScheme.onSurface
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("移除")
                }
                FilledTonalButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("修改")
                }
            }
        }
    }
}

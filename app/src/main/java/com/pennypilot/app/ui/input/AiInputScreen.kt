package com.pennypilot.app.ui.input

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.R
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.theme.incomeColor
import com.pennypilot.app.util.MoneyUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiInputScreen(
    onNavigateToManual: () -> Unit,
    vm: AiInputViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.allCategories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    var showKeypad by rememberSaveable { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("记一笔", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { focus.clearFocus(); vm.clearError(); showKeypad = true },
                enabled = !state.isSaving && !state.isRecording && !state.isRecognizing,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("手动记账") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.previewItems.isEmpty() && !state.isParsing) {
                item(key = "example") {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("说一句，记下多笔账", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("“昨天午饭 25 元，打车去公司 32 元。”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("支持日期、金额和分类识别。先确认预览，再保存到账本。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            TextButton(onClick = onNavigateToManual, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开完整记账表单") }
                        }
                    }
                }
            }
            if (state.isParsing) {
                item(key = "parsing") { LedgerState("正在整理账目", "已有预览会保留，你也可以继续输入下一笔。", loading = true) }
            }
            state.error?.let { error ->
                item(key = "error") {
                    LedgerState(
                        "操作未完成", error,
                        actionLabel = if (state.previewItems.isNotEmpty()) "继续核对账目" else "改用手动记账",
                        onAction = { vm.clearError(); if (state.previewItems.isEmpty()) { focus.clearFocus(); showKeypad = true } }
                    )
                }
            }
            if (state.previewItems.isNotEmpty()) {
                item(key = "preview_heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("待确认 · ${state.previewItems.size} 笔", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("可逐笔修改金额、分类和时间。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.previewItems, key = { it.draftId }) { item ->
                    PreviewCard(
                        item = item,
                        enabled = !state.isSaving,
                        onEdit = { focus.clearFocus(); editingId = item.draftId },
                        onRemove = { removingId = item.draftId }
                    )
                }
            }
            if (state.savedCount > 0 && state.previewItems.isNotEmpty()) {
                item(key = "partial_saved") {
                    Text("已保存 ${state.savedCount} 笔，其余账目仍在预览中。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SnackbarHost(snackbar)
                if (state.previewItems.isNotEmpty()) {
                    Button(
                        onClick = { focus.clearFocus(); vm.saveAll {} },
                        enabled = !state.isSaving && !state.isParsing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isSaving) "正在保存…" else "保存 ${state.previewItems.size} 笔账目")
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
                    isSending = state.isParsing || state.isSaving,
                    enabled = !state.isSaving,
                    placeholder = if (state.previewItems.isEmpty()) "输入一笔或多笔账目…" else "继续输入，追加待确认账目…"
                )
            }
        }
    }
    if (showKeypad) {
        KeypadSheet(
            categories = categories, isSaving = state.isSaving, error = state.error,
            onDismiss = { if (!state.isSaving) { showKeypad = false; vm.clearError() } },
            onSave = { type, categoryId, cents, note, occurredAt ->
                vm.saveManual(type, categoryId, cents, note, occurredAt) { showKeypad = false }
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

@Composable
private fun PreviewCard(item: PreviewItem, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatIcon(item.categoryColorIndex, item.categoryIcon, size = 40)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.merchant ?: item.note ?: item.categoryName, style = MaterialTheme.typography.titleMedium)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("移除")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onEdit, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_pencil), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("修改")
                }
            }
        }
    }
}

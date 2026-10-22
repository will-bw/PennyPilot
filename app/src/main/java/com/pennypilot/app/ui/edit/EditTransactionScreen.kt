package com.pennypilot.app.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.LedgerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    onBack: () -> Unit,
    vm: EditTransactionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val leave: () -> Unit = {
        focusManager.clearFocus()
        if (state.hasChanges) showDiscard = true else onBack()
    }
    BackHandler(enabled = state.hasChanges || state.isSaving) {
        when {
            imeVisible -> focusManager.clearFocus()
            state.isSaving -> Unit
            else -> leave()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "编辑账目" else "手动记账") },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.isEditing && !state.isLoading && !state.loadFailed) {
                        IconButton(onClick = { focusManager.clearFocus(); showDelete = true }, enabled = !state.isSaving) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "删除这笔账目", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        bottomBar = {
            if (!state.isLoading && !state.loadFailed) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Button(
                            onClick = { focusManager.clearFocus(); vm.save(onBack) },
                            enabled = !state.isSaving && state.categories.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.isSaving) "正在保存…" else "保存账目")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when {
                state.isLoading -> item { LedgerState("正在加载账目", "请稍候…", loading = true) }
                state.loadFailed -> item { LedgerState("暂时无法编辑", state.error.orEmpty(), actionLabel = "返回流水", onAction = onBack) }
                else -> {
                    item { TransactionTypeSelector(state.type, enabled = !state.isSaving, onSelect = vm::setType) }
                    item {
                        OutlinedTextField(
                            value = state.amountText,
                            onValueChange = vm::updateAmount,
                            enabled = !state.isSaving,
                            label = { Text("金额（元）") },
                            prefix = { Text("¥") },
                            singleLine = true,
                            isError = state.amountError != null,
                            supportingText = { state.amountError?.let { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            textStyle = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("分类", style = MaterialTheme.typography.titleMedium)
                            TransactionCategorySelection(state.categories, state.selectedCategoryId, enabled = !state.isSaving, onSelect = vm::selectCategory)
                        }
                    }
                    item { TransactionDateTimeFields(state.occurredAt, enabled = !state.isSaving, onChange = vm::setOccurredAt) }
                    item {
                        OutlinedTextField(
                            value = state.note, onValueChange = vm::updateNote,
                            enabled = !state.isSaving, label = { Text("备注（可选）") },
                            maxLines = 4, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.merchant, onValueChange = vm::updateMerchant,
                            enabled = !state.isSaving, label = { Text("商户（可选）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.accountTag, onValueChange = vm::updateAccountTag,
                            enabled = !state.isSaving, label = { Text("账户标签（可选）") },
                            placeholder = { Text("例如：现金、银行卡") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
    if (showDiscard) {
        DiscardChangesDialog(onKeepEditing = { showDiscard = false }, onDiscard = { showDiscard = false; onBack() })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.isSaving) showDelete = false },
            title = { Text("删除这笔账目？") },
            text = { Text("删除后无法撤销。账目金额为 ¥${state.amountText}，请确认后继续。") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.delete(onBack) }, enabled = !state.isSaving) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("保留账目") } }
        )
    }
}

package com.pennypilot.app.ui.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.edit.DiscardChangesDialog
import com.pennypilot.app.ui.edit.TransactionCategorySelection
import com.pennypilot.app.ui.edit.TransactionDateTimeFields
import com.pennypilot.app.ui.edit.TransactionTypeSelector
import com.pennypilot.app.ui.edit.TransactionFormError
import com.pennypilot.app.ui.edit.TransactionSaveLabel
import com.pennypilot.app.ui.theme.PennyMotion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadSheet(
    categories: List<CategoryEntity>,
    isSaving: Boolean = false,
    error: String? = null,
    dismissRequested: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (type: TxType, categoryId: Long, amountCents: Long, note: String, occurredAt: Long) -> Unit
) {
    var txType by rememberSaveable { mutableStateOf(TxType.EXPENSE) }
    var amountText by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val initialTime = rememberSaveable { System.currentTimeMillis() }
    var occurredAt by rememberSaveable { mutableLongStateOf(initialTime) }
    var noteFocused by remember { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var allowDismiss by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val locked = isSaving || closing || dismissRequested
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pool = categories.filter { it.type == txType && it.enabled }
    val amount = KeypadAmount(amountText)
    val selectedCategory = pool.find { it.id == selectedId }
    val currentSaving by rememberUpdatedState(isSaving)
    val currentDirty by rememberUpdatedState(amountText != "0" || note.isNotBlank() || txType != TxType.EXPENSE || occurredAt != initialTime)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (closing) target == SheetValue.Hidden
            else if (target != SheetValue.Hidden || allowDismiss) true
            else if (currentSaving) false
            else if (currentDirty) { showDiscard = true; false }
            else true
        }
    )
    val dismissSmoothly: () -> Unit = {
        if (!closing) {
            closing = true
            allowDismiss = true
            focus.clearFocus()
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    // A gesture can cancel this child animation, but cannot reopen a saved or
                    // explicitly discarded form. A disposed composition has no active owner.
                    if (scope.isActive) currentOnDismiss()
                }
            }
        }
    }
    val requestDismiss: () -> Unit = {
        if (!locked) {
            focus.clearFocus()
            if (currentDirty) showDiscard = true else dismissSmoothly()
        }
    }
    // Keep the sheet mounted until its native exit completes, including after a successful save.
    LaunchedEffect(dismissRequested) {
        if (dismissRequested) dismissSmoothly()
    }
    LaunchedEffect(pool) {
        if (pool.none { it.id == selectedId }) selectedId = pool.firstOrNull()?.id
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("手动记账", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = requestDismiss, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TransactionTypeSelector(txType, enabled = !locked) { txType = it }
                OutlinedButton(
                    onClick = { focus.clearFocus(); keyboard?.hide() },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("金额（元）", style = MaterialTheme.typography.labelMedium)
                        Text("¥ ${amount.display}", style = MaterialTheme.typography.headlineLarge, maxLines = 1,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                        if (noteFocused) Text("点此继续输入金额", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = { focus.clearFocus(); showCategories = true },
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(if (selectedCategory == null) "选择分类" else "分类：${selectedCategory.name} · 更改")
                }
                if (pool.isEmpty()) {
                    Text("暂无可用分类，请先在分类管理中添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TransactionDateTimeFields(occurredAt, enabled = !locked) { occurredAt = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !locked,
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { noteFocused = it.isFocused }
                )
                AnimatedVisibility(
                    visible = !noteFocused,
                    enter = expandVertically(PennyMotion.spatial()) + fadeIn(PennyMotion.effects()),
                    exit = shrinkVertically(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit))
                ) {
                    KeypadGrid(
                        enabled = !locked && !noteFocused,
                        onDigit = { amountText = amount.appendDigit(it).display },
                        onDot = { amountText = amount.appendDot().display },
                        onBackspace = { amountText = amount.backspace().display }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransactionFormError(error)
                Button(
                    onClick = { focus.clearFocus(); onSave(txType, selectedId!!, amount.cents, note.trim(), occurredAt) },
                    enabled = amount.cents > 0 && selectedCategory != null && !locked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = if (isSaving || dismissRequested) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = if (isSaving || dismissRequested) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    TransactionSaveLabel(isSaving = isSaving, label = if (dismissRequested) "已保存" else "保存账目")
                }
            }
        }
    }
    if (showCategories) {
        AlertDialog(
            onDismissRequest = { showCategories = false },
            title = { Text("选择分类") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    TransactionCategorySelection(pool, selectedId) { selectedId = it; showCategories = false }
                }
            },
            confirmButton = { TextButton(onClick = { showCategories = false }) { Text("完成") } }
        )
    }
    if (showDiscard) {
        DiscardChangesDialog(
            onKeepEditing = { showDiscard = false },
            onDiscard = { showDiscard = false; dismissSmoothly() }
        )
    }
}

@Composable
private fun KeypadGrid(enabled: Boolean, onDigit: (Char) -> Unit, onDot: () -> Unit, onBackspace: () -> Unit) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫')
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    FilledTonalButton(
                        onClick = { when (key) { '.' -> onDot(); '⌫' -> onBackspace(); else -> onDigit(key) } },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.large,
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        if (key == '⌫') Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "删除最后一位", modifier = Modifier.size(22.dp))
                        else Text(key.toString(), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

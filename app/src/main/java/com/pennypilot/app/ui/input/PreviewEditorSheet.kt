package com.pennypilot.app.ui.input

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.ui.edit.DiscardChangesDialog
import com.pennypilot.app.ui.edit.TransactionCategorySelection
import com.pennypilot.app.ui.edit.TransactionDateTimeFields
import com.pennypilot.app.ui.edit.TransactionTypeSelector
import com.pennypilot.app.ui.edit.TransactionFormError
import com.pennypilot.app.util.MoneyUtil
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewEditorSheet(
    item: PreviewItem,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onApply: (PreviewItem) -> Boolean
) {
    val initialAmount = remember(item.draftId) { MoneyUtil.centsToYuanString(item.amount) }
    var amountText by rememberSaveable(item.draftId) { mutableStateOf(initialAmount) }
    var type by rememberSaveable(item.draftId) { mutableStateOf(item.type) }
    var categoryId by rememberSaveable(item.draftId) { mutableLongStateOf(item.categoryId) }
    var note by rememberSaveable(item.draftId) { mutableStateOf(item.note.orEmpty()) }
    var merchant by rememberSaveable(item.draftId) { mutableStateOf(item.merchant.orEmpty()) }
    var occurredAt by rememberSaveable(item.draftId) { mutableLongStateOf(item.occurredAt) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var allowDismiss by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val focus = LocalFocusManager.current
    val pool = categories.filter { it.enabled && it.type == type }
    val selectedCategory = pool.find { it.id == categoryId }
    val cents = MoneyUtil.parsePositiveYuanToCents(amountText)
    val dirty by rememberUpdatedState(amountText != initialAmount || type != item.type || categoryId != item.categoryId || note != item.note.orEmpty() || merchant != item.merchant.orEmpty() || occurredAt != item.occurredAt)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        if (closing) target == SheetValue.Hidden
        else if (target == SheetValue.Hidden && dirty && !allowDismiss) { showDiscard = true; false }
        else true
    })
    val dismissSmoothly: () -> Unit = {
        if (!closing) {
            closing = true
            allowDismiss = true
            focus.clearFocus()
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    // A second touch may cancel hide's child job. The accepted close still wins;
                    // disposal cancels the owning scope too and must not call back into the parent.
                    if (scope.isActive) currentOnDismiss()
                }
            }
        }
    }
    val requestDismiss: () -> Unit = {
        if (!closing) {
            focus.clearFocus()
            if (dirty && !allowDismiss) showDiscard = true else dismissSmoothly()
        }
    }
    LaunchedEffect(pool) {
        if (pool.none { it.id == categoryId }) categoryId = pool.firstOrNull()?.id ?: -1L
    }

    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState, dragHandle = null) {
        Column(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("修改待确认账目", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = requestDismiss, enabled = !closing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text("确认修改后返回预览，保存账目后才会计入账本。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TransactionTypeSelector(type = type, enabled = !closing, onSelect = { type = it })
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it }, enabled = !closing,
                    label = { Text("金额（元）") }, prefix = { Text("¥") }, singleLine = true,
                    isError = amountText.isNotEmpty() && cents == null,
                    supportingText = { TransactionFormError(if (amountText.isNotEmpty() && cents == null) "请输入 0.01～9,999,999.99 元，最多两位小数" else null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth()
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("分类", style = MaterialTheme.typography.titleMedium)
                    TransactionCategorySelection(pool, categoryId, enabled = !closing, onSelect = { categoryId = it })
                }
                TransactionDateTimeFields(occurredAt, enabled = !closing, onChange = { occurredAt = it })
                OutlinedTextField(value = merchant, enabled = !closing, onValueChange = { merchant = it }, label = { Text("商户（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, enabled = !closing, onValueChange = { note = it }, label = { Text("备注（可选）") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
            Button(
                onClick = {
                    val category = selectedCategory
                    if (!closing && cents != null && category != null && onApply(item.copy(
                        amount = cents, type = type, categoryId = category.id, categoryName = category.name,
                        categoryIcon = category.icon, categoryColorIndex = category.colorIndex,
                        note = note.trim().ifBlank { null }, merchant = merchant.trim().ifBlank { null }, occurredAt = occurredAt
                    ))) {
                        dismissSmoothly()
                    }
                },
                enabled = !closing && cents != null && selectedCategory != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.large
            ) { Text("确认修改") }
        }
    }
    if (showDiscard) {
        DiscardChangesDialog(onKeepEditing = { showDiscard = false }, onDiscard = { showDiscard = false; dismissSmoothly() })
    }
}

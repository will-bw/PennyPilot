package com.pennypilot.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.R
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon

@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    vm: CategoriesViewModel = hiltViewModel()
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    // 当前弹出编辑对话框的分类（null = 无）
    var editCat by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("分类管理", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { showAddDialog = true }) { Text("+ 新增") }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { cat ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CatIcon(colorIndex = cat.colorIndex, emoji = cat.icon, size = 36)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(if (cat.type == TxType.EXPENSE) "支出" else "收入")
                                    append(" · ")
                                    append(if (cat.isPreset) "预设" else "自定义")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Switch + Up/Down + Edit/Delete
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = cat.enabled, onCheckedChange = { vm.toggleEnabled(cat) })
                            Spacer(Modifier.width(4.dp))
                            // Up / Down
                            IconButton(onClick = { if (categories.indexOfFirst { it.id == cat.id} > 0) vm.moveCategory(cat, -1) }) {
                                Text("↑", fontSize = 12.sp)
                            }
                            IconButton(
                                onClick = { vm.moveCategory(cat, 1) },
                                enabled = categories.indexOfFirst { it.id == cat.id } < categories.lastIndex
                            ) {
                                Text("↓", fontSize = 12.sp)
                            }
                            // Edit / Delete buttons
                            Icon(
                                painter = painterResource(R.drawable.ic_pencil),
                                contentDescription = "编辑",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { editCat = cat }.size(30.dp)
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_trash),
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { deleteTarget = cat }.size(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Add dialog with type selector
    if (showAddDialog) {
        AddCategoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, icon, type ->
                vm.addCategory(name, icon, type)
                showAddDialog = false
            }
        )
    }

    // Edit category dialog
    editCat?.let { cat ->
        EditCategoryDialog(
            name = cat.name,
            icon = cat.icon,
            onConfirm = { newName, newIcon ->
                vm.renameCategory(cat, newName, newIcon)
                editCat = null
            },
            onDismiss = { editCat = null }
        )
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除该分类？") },
            text = { Text("将该分类名下所有交易改挂到「其他」，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(target)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, TxType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📌") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Emoji 图标") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypePill("支出", selected = type == TxType.EXPENSE) { type = TxType.EXPENSE }
                    TypePill("收入", selected = type == TxType.INCOME) { type = TxType.INCOME }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), icon.trim(), type) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EditCategoryDialog(
    name: String,
    icon: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember(name) { mutableStateOf(name) }
    var newIcon by remember(icon) { mutableStateOf(icon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("名称") }, singleLine = true
                )
                OutlinedTextField(
                    value = newIcon, onValueChange = { newIcon = it },
                    label = { Text("Emoji 图标") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank() && newIcon.isNotBlank(),
                onClick = { onConfirm(newName.trim(), newIcon.trim()) }
            ) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
            .padding(horizontal = 18.dp, vertical = 7.dp),
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

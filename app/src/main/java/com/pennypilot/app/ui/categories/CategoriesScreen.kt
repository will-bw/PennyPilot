package com.pennypilot.app.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon

@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    vm: CategoriesViewModel = hiltViewModel()
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

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
                        Switch(checked = cat.enabled, onCheckedChange = { vm.toggleEnabled(cat) })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCategoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, icon ->
                vm.addCategory(name, icon, TxType.EXPENSE)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📌") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Emoji 图标") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), icon.trim()) }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

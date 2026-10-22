package com.pennypilot.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.CategoryIconOptions
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.settings.ManagementHeader
import com.pennypilot.app.ui.settings.ManagementMessage

@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var selectedType by rememberSaveable { mutableStateOf(TxType.EXPENSE) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    val filtered = categories.filter { it.type == selectedType }
    val editing = categories.find { it.id == editingId }
    val deleting = categories.find { it.id == deletingId }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ManagementHeader("分类管理", "整理记账分类；停用后不再出现在新账目中，已有账目保留。", onBack)
                Spacer(Modifier.height(20.dp))
                TabRow(selectedTabIndex = if (selectedType == TxType.EXPENSE) 0 else 1) {
                    listOf(TxType.EXPENSE to "支出", TxType.INCOME to "收入").forEach { (type, label) ->
                        Tab(selected = selectedType == type, onClick = { selectedType = type }, text = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.clearError(); adding = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("新增${if (selectedType == TxType.EXPENSE) "支出" else "收入"}分类")
                }
            }
            if (error != null && !adding && editing == null && deleting == null) item { ManagementMessage(error!!, error = true) }
            if (!loaded) item { LedgerState("正在读取分类", "你的分类和排序会显示在这里。", loading = true) }
            else if (filtered.isEmpty()) item { LedgerState("还没有这类分类", "添加一个常用分类，之后记账时就能快速选择。") }
            else {
                item { Text("${filtered.count { it.enabled }} 个已启用 · ${filtered.size} 个分类", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(filtered, key = { _, category -> category.id }) { index, category ->
                    CategoryRow(category, busy, index > 0, index < filtered.lastIndex,
                        onToggle = { vm.toggleEnabled(category) },
                        onEdit = { vm.clearError(); editingId = category.id },
                        onDelete = { vm.clearError(); deletingId = category.id },
                        onMove = { vm.moveCategory(category, it) })
                }
            }
        }
    }
    if (adding || editing != null) {
        CategoryEditDialog(editing, selectedType, busy, error, onDismiss = { if (!busy) { adding = false; editingId = null; vm.clearError() } },
            onChange = vm::clearError, onSave = { name, icon, type ->
                val done = { adding = false; editingId = null }
                if (editing == null) vm.addCategory(name, icon, type, done) else vm.renameCategory(editing, name, icon, done)
            })
    }
    deleting?.let { category ->
        AlertDialog(onDismissRequest = { if (!busy) { deletingId = null; vm.clearError() } },
            title = { Text("删除「${category.name}」？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("该分类下的账目会移到同类型的「其他」，账目不会删除。分类删除后无法撤销。")
                error?.let { ManagementMessage(it, error = true) }
            } },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(category) { deletingId = null } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "正在删除…" else "删除分类", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingId = null; vm.clearError() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}

@Composable
private fun CategoryRow(category: CategoryEntity, busy: Boolean, canMoveUp: Boolean, canMoveDown: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CatIcon(colorIndex = category.colorIndex, emoji = category.icon, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if (category.enabled) "已启用" else "已停用"} · ${if (category.isPreset) "预设" else "自定义"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = category.enabled, onCheckedChange = { onToggle() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${category.name}分类${if (category.enabled) "已启用" else "已停用"}" })
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "管理${category.name}分类", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("编辑分类") }, leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onEdit() }, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("上移") }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onMove(-1) }, enabled = canMoveUp, modifier = Modifier.heightIn(min = 48.dp))
                    DropdownMenuItem(text = { Text("下移") }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(20.dp)) },
                        onClick = { menuOpen = false; onMove(1) }, enabled = canMoveDown, modifier = Modifier.heightIn(min = 48.dp))
                    if (!category.isPreset) DropdownMenuItem(text = { Text("删除分类", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryEditDialog(category: CategoryEntity?, initialType: TxType, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onChange: () -> Unit, onSave: (String, String, TxType) -> Unit) {
    var name by rememberSaveable(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var icon by rememberSaveable(category?.id) { mutableStateOf(category?.icon ?: "📌") }
    var customIcon by rememberSaveable(category?.id) {
        mutableStateOf(CategoryIconOptions.none { it.value.replace("\uFE0F", "") == icon.replace("\uFE0F", "") })
    }
    var type by rememberSaveable(category?.id) { mutableStateOf(category?.type ?: initialType) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (category == null) "新增分类" else "编辑分类") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it; onChange() }, label = { Text("分类名称") }, supportingText = { Text("同类型下不重名 · ${name.trim().length}/20 字") }, isError = name.trim().length > 20, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            CategoryIconPicker(icon, enabled = !busy, onSelect = { icon = it; customIcon = false; onChange() })
            if (customIcon) {
                OutlinedTextField(value = icon, onValueChange = { icon = it; onChange() }, label = { Text("自定义符号") },
                    supportingText = { Text("输入一个习惯使用的符号，或从上方选择常用图标。") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { customIcon = true; icon = ""; onChange() }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("使用自定义符号") }
            }
            if (category == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxType.EXPENSE to "支出", TxType.INCOME to "收入").forEach { (value, label) ->
                    FilterChip(selected = type == value, onClick = { type = value; onChange() }, label = { Text(label) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            error?.let { ManagementMessage(it, error = true) }
        } },
        confirmButton = { TextButton(onClick = { onSave(name, icon, type) }, enabled = !busy && name.isNotBlank() && icon.isNotBlank() && name.trim().length <= 20, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "保存中…" else "保存分类") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun CategoryIconPicker(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val normalized = value.replace("\uFE0F", "")
    val choice = CategoryIconOptions.firstOrNull { it.value.replace("\uFE0F", "") == normalized }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("分类图标 · ${choice?.label ?: "自定义"}", style = MaterialTheme.typography.titleSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = (maxWidth / 52.dp).toInt().coerceIn(3, 6)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CategoryIconOptions.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { option ->
                            val selected = option == choice
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option.value) })
                                .semantics { contentDescription = "${option.label}图标" }, contentAlignment = Alignment.Center) {
                                Icon(option.icon, contentDescription = null, modifier = Modifier.size(22.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

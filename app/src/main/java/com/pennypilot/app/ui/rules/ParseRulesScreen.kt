package com.pennypilot.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.data.parser.ParseRuleEngine
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.settings.ManagementHeader
import com.pennypilot.app.ui.settings.ManagementMessage
import com.pennypilot.app.util.MoneyUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PACKAGE_LABELS = mapOf("com.tencent.mm" to "微信", "com.eg.android.AlipayGphone" to "支付宝")

@Composable
fun ParseRulesScreen(onBack: () -> Unit) {
    val vm: ParseRulesViewModel = hiltViewModel()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var selectedPackage by rememberSaveable { mutableStateOf("com.tencent.mm") }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    val editRule = rules.find { it.id == editId }
    val visibleRules = rules.filter { it.appPackage == selectedPackage }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ManagementHeader("通知解析规则", "本地识别支付通知。数值越小越先匹配；修改后可先测试，再保存。", onBack)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PACKAGE_LABELS.forEach { (pkg, label) -> FilterChip(selected = selectedPackage == pkg,
                        onClick = { selectedPackage = pkg }, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.clearError(); adding = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("新增${PACKAGE_LABELS[selectedPackage]}规则")
                }
            }
            if (error != null && !adding && editRule == null && !resetting) item { ManagementMessage(error!!, error = true) }
            if (!loaded) item { LedgerState("正在读取规则", "本地解析规则会显示在这里。", loading = true) }
            else if (visibleRules.isEmpty()) item { LedgerState("还没有${PACKAGE_LABELS[selectedPackage]}规则", "添加规则或恢复预置规则，即可在本地识别通知；云端补充解析由独立开关控制。") }
            else {
                item { Text("${visibleRules.count { it.enabled }} 条已启用 · 点击规则可编辑", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visibleRules, key = { it.id }) { rule ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { vm.clearError(); editId = rule.id }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${if (rule.directionHint == "INCOME") "收入" else "支出"} · 优先级 ${rule.priority}", style = MaterialTheme.typography.titleMedium)
                                Text(rule.regexAmount, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(if (rule.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { vm.toggleEnabled(rule) }, enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${PACKAGE_LABELS[rule.appPackage]}优先级${rule.priority}规则" })
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.clearError(); resetting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("恢复全部预置规则") }
                Spacer(Modifier.height(8.dp))
                Text("恢复会替换微信与支付宝的所有规则，包括自定义修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (adding || editRule != null) {
        RuleEditDialog(editRule ?: ParseRuleEntity(appPackage = selectedPackage, regexAmount = ""), busy, error,
            onChange = vm::clearError,
            onDismiss = { if (!busy) { adding = false; editId = null; vm.clearError() } },
            onSave = { rule -> vm.save(rule) { adding = false; editId = null } },
            onDelete = { rule -> vm.delete(rule) { adding = false; editId = null } })
    }
    if (resetting) AlertDialog(onDismissRequest = { if (!busy) resetting = false },
        title = { Text("恢复全部预置规则？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("微信与支付宝的现有规则将被替换，自定义规则及修改会丢失。已有账目不受影响。")
            error?.let { ManagementMessage(it, error = true) } } },
        confirmButton = { TextButton(onClick = { vm.resetPresets { resetting = false } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "正在恢复…" else "确认恢复") } },
        dismissButton = { TextButton(onClick = { resetting = false; vm.clearError() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable
private fun RuleEditDialog(rule: ParseRuleEntity, busy: Boolean, saveError: String?, onChange: () -> Unit,
    onDismiss: () -> Unit, onSave: (ParseRuleEntity) -> Unit, onDelete: (ParseRuleEntity) -> Unit) {
    var pkg by rememberSaveable(rule.id) { mutableStateOf(rule.appPackage) }
    var amount by rememberSaveable(rule.id) { mutableStateOf(rule.regexAmount) }
    var merchant by rememberSaveable(rule.id) { mutableStateOf(rule.regexMerchant.orEmpty()) }
    var direction by rememberSaveable(rule.id) { mutableStateOf(rule.directionHint) }
    var priority by rememberSaveable(rule.id) { mutableStateOf(rule.priority.toString()) }
    var sample by rememberSaveable(rule.id) { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun changed() { validation = null; preview = null; onChange() }
    fun draft(): ParseRuleEntity? {
        validation = when {
            amount.isBlank() -> "请填写金额正则。"
            priority.toIntOrNull() == null -> "优先级需为 0 到 2147483647 的整数。"
            else -> try {
                when {
                    Regex(amount.trim()).toPattern().matcher("").groupCount() == 0 -> "请用括号建立第 1 个捕获组，用于提取金额。"
                    merchant.isNotBlank() && Regex(merchant.trim()).toPattern().matcher("").groupCount() == 0 -> "商户正则需包含第 1 个捕获组。"
                    else -> null
                }
            } catch (_: IllegalArgumentException) { "正则语法有误，请检查括号和转义字符。" }
        }
        return if (validation != null) null else rule.copy(appPackage = pkg, regexAmount = amount.trim(), regexMerchant = merchant.trim().ifBlank { null }, directionHint = direction, priority = priority.toInt())
    }
    AlertDialog(onDismissRequest = { if (!busy && !testing) onDismiss() },
        title = { Text(if (deleting) "删除这条规则？" else if (rule.id == 0L) "新增规则" else "编辑规则") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (deleting) Text("删除后，这条规则不再参与通知识别。已有账目保留。") else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PACKAGE_LABELS.forEach { (value, label) ->
                    FilterChip(selected = pkg == value, onClick = { pkg = value; changed() }, enabled = !busy && !testing, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                } }
                OutlinedTextField(amount, onValueChange = { amount = it; changed() }, label = { Text("金额正则") }, supportingText = { Text("第 1 个捕获组为金额，单位为元。") }, enabled = !busy && !testing, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(merchant, onValueChange = { merchant = it; changed() }, label = { Text("商户正则（可选）") }, supportingText = { Text("第 1 个捕获组为商户；留空则不提取。") }, enabled = !busy && !testing, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("EXPENSE" to "支出", "INCOME" to "收入").forEach { (value, label) ->
                    FilterChip(selected = direction == value, onClick = { direction = value; changed() }, enabled = !busy && !testing, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                } }
                OutlinedTextField(priority, onValueChange = { priority = it.filter(Char::isDigit); changed() }, label = { Text("优先级") }, supportingText = { Text("数值越小越先匹配。") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy && !testing, modifier = Modifier.fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("保存前试一试", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(sample, onValueChange = { sample = it; preview = null }, label = { Text("示例通知文本") }, enabled = !busy && !testing, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedButton(onClick = {
                    draft()?.let { candidate ->
                        testing = true
                        scope.launch {
                            try {
                                val parsed = withContext(Dispatchers.Default) { ParseRuleEngine.parse(sample, listOf(candidate.copy(enabled = true))) }
                                preview = parsed?.let { "${if (it.direction == "INCOME") "收入" else "支出"} ${MoneyUtil.centsToYuanString(it.amountCents)} 元 · ${it.merchant ?: "未提取商户"}" }
                                    ?: "未匹配到有效金额，请检查示例文本和正则。"
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { preview = "测试未完成，请检查规则后重试。"
                            } finally { testing = false }
                        }
                    }
                }, enabled = sample.isNotBlank() && !busy && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (testing) "正在测试…" else "测试这条规则") }
                preview?.let { ManagementMessage(it) }
                if (rule.id != 0L) TextButton(onClick = { deleting = true; onChange() }, enabled = !busy && !testing, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除规则", color = MaterialTheme.colorScheme.error) }
            }
            (validation ?: saveError)?.let { ManagementMessage(it, error = true) }
        } },
        confirmButton = { TextButton(onClick = { if (deleting) onDelete(rule) else draft()?.let(onSave) }, enabled = !busy && !testing, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "处理中…" else if (deleting) "确认删除" else "保存规则") } },
        dismissButton = { TextButton(onClick = { if (deleting) { deleting = false; onChange() } else onDismiss() }, enabled = !busy && !testing, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (deleting) "返回编辑" else "取消") } })
}

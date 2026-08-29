package com.pennypilot.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.ui.theme.PageSubStyle
import com.pennypilot.app.ui.theme.faintColor

private val PACKAGE_LABELS = mapOf(
    "com.tencent.mm" to "微信",
    "com.eg.android.AlipayGphone" to "支付宝"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParseRulesScreen(onBack: () -> Unit) {
    val viewModel: ParseRulesViewModel = hiltViewModel()
    val rules by viewModel.rules.collectAsState()

    var editingRule by remember { mutableStateOf<ParseRuleEntity?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 40.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("通知解析规则", style = MaterialTheme.typography.headlineLarge)
        Text(
            "正则热更新 · 按优先级逐条匹配，微信/支付宝改文案时在此调整",
            style = PageSubStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { editingRule = ParseRuleEntity(appPackage = "com.tencent.mm", regexAmount = "") }) {
                Text("＋ 新增规则")
            }
            TextButton(onClick = { showResetConfirm = true }) {
                Text("恢复预置规则", color = MaterialTheme.colorScheme.error)
            }
        }

        // 按 app 分组展示
        val grouped = rules.groupBy { it.appPackage }
        listOf("com.tencent.mm", "com.eg.android.AlipayGphone").forEach { pkg ->
            val pkgRules = grouped[pkg] ?: return@forEach
            Spacer(Modifier.height(20.dp))
            Text(
                PACKAGE_LABELS[pkg] ?: pkg,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(Modifier.height(10.dp))
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    pkgRules.sortedBy { it.priority }.forEachIndexed { index, rule ->
                        RuleRow(
                            rule = rule,
                            onClick = { editingRule = rule },
                            onToggle = { viewModel.toggleEnabled(rule) }
                        )
                        if (index < pkgRules.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline,
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                "暂无规则，通知将无法解析。可点击「恢复预置规则」。",
                fontSize = 13.sp,
                color = MaterialTheme.faintColor
            )
        }
    }

    // 编辑 / 新增弹窗
    editingRule?.let { rule ->
        RuleEditDialog(
            rule = rule,
            isNew = rule.id == 0L,
            onSave = { viewModel.save(it); editingRule = null },
            onDelete = { viewModel.delete(it); editingRule = null },
            onDismiss = { editingRule = null }
        )
    }

    // 恢复预置二次确认
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复预置规则？") },
            text = { Text("将清空当前全部规则并写回预置的微信/支付宝正则，自定义修改会丢失。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetPresets(); showResetConfirm = false }) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RuleRow(
    rule: ParseRuleEntity,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 优先级徽标
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("${rule.priority}", fontSize = 13.sp, fontWeight = FontWeight.W700)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChipBadge(
                    if (rule.directionHint == "INCOME") "收入" else "支出",
                    if (rule.directionHint == "INCOME") INCOME_FG else INK_FG
                )
                Text(
                    rule.regexAmount,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                rule.regexMerchant?.let { "商户：$it" } ?: "商户：不提取",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.faintColor,
                maxLines = 1
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
    }
}

/** 小号彩色文字徽标 */
@Composable
private fun AssistChipBadge(text: String, fg: Color) {
    Box(
        modifier = Modifier
            .background(fg.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.W600, color = fg)
    }
}

// 收支配色约定（设计文档 §7.1）：收入暖红 / 支出墨色
private val INCOME_FG = Color(0xFFFF8172)
private val INK_FG = Color(0xFF181C2E)

@Composable
private fun RuleEditDialog(
    rule: ParseRuleEntity,
    isNew: Boolean,
    onSave: (ParseRuleEntity) -> Unit,
    onDelete: (ParseRuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var appPackage by remember { mutableStateOf(rule.appPackage) }
    var regexAmount by remember { mutableStateOf(rule.regexAmount) }
    var regexMerchant by remember { mutableStateOf(rule.regexMerchant ?: "") }
    var direction by remember { mutableStateOf(rule.directionHint) }
    var priority by remember { mutableStateOf(rule.priority.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增规则" else "编辑规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 目标 App
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PACKAGE_LABELS.forEach { (pkg, label) ->
                        FilterChip(
                            selected = appPackage == pkg,
                            onClick = { appPackage = pkg },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = regexAmount,
                    onValueChange = { regexAmount = it; error = null },
                    label = { Text("金额正则（第 1 组为金额）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = regexMerchant,
                    onValueChange = { regexMerchant = it },
                    label = { Text("商户正则（可空）") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("EXPENSE" to "支出", "INCOME" to "收入").forEach { (value, label) ->
                        FilterChip(
                            selected = direction == value,
                            onClick = { direction = value },
                            label = { Text(label) }
                        )
                    }
                    OutlinedTextField(
                        value = priority,
                        onValueChange = { priority = it.filter(Char::isDigit) },
                        label = { Text("优先级") },
                        modifier = Modifier.width(96.dp)
                    )
                }
                error?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 保存前校验正则可编译，避免坏规则进库导致捕获服务整包失效
                val amount = regexAmount.trim()
                val valid = try {
                    Regex(amount); regexMerchant.takeIf { it.isNotBlank() }?.let { Regex(it) }; true
                } catch (_: Exception) { false }
                when {
                    amount.isEmpty() -> error = "金额正则不能为空"
                    !valid -> error = "正则语法错误，请检查"
                    else -> onSave(rule.copy(
                        appPackage = appPackage,
                        regexAmount = amount,
                        regexMerchant = regexMerchant.takeIf { it.isNotBlank() },
                        directionHint = direction,
                        priority = priority.toIntOrNull() ?: 0
                    ))
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { onDelete(rule) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

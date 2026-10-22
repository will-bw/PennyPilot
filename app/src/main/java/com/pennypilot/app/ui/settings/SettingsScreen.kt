package com.pennypilot.app.ui.settings

import android.content.ComponentName
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.service.NotificationCaptorService
import com.pennypilot.app.ui.theme.pendingAmberColor
import com.pennypilot.app.ui.theme.successColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen(
    onNavigateToCategories: () -> Unit,
    onNavigateToLlmSettings: () -> Unit,
    onNavigateToColorOsGuide: () -> Unit,
    onNavigateToParseRules: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val health by viewModel.serviceMonitor.health.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val cloudParsingEnabled by viewModel.cloudParsingEnabled.collectAsStateWithLifecycle()
    val savingCloud by viewModel.savingCloudPreference.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }
    var restoreDocuments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var isListenerEnabled by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val cn = ComponentName(context, NotificationCaptorService::class.java)
        isListenerEnabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.contains(cn.flattenToString()) == true
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportDb(context, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { restoreDocuments = it }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    val stale = health.isBound && health.lastNotificationTime > 0 &&
        System.currentTimeMillis() - health.lastNotificationTime > 24 * 3600 * 1000L
    val statusColor = when { !isListenerEnabled -> MaterialTheme.pendingAmberColor
        !health.isBound -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.successColor }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的", style = MaterialTheme.typography.headlineLarge)
                Text("设置好工具，安心管理账本", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = statusColor)
                        Text(when { !isListenerEnabled -> "自动捕获尚未授权"
                            !health.isBound -> "捕获服务未连接"
                            else -> "捕获服务已连接" }, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(when { !isListenerEnabled -> "授权通知使用权后，可识别微信和支付宝的支付通知。"
                        !health.isBound -> "通知使用权已授权，请检查后台运行设置。"
                        stale -> "超过 24 小时未收到通知；这不一定表示服务异常。"
                        else -> "仅识别支持的支付通知；捕获后请在流水中核对。" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("今日捕获 ${health.todayCaptureCount} 笔 · 通知权限${if (isListenerEnabled) "已授权" else "未授权"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNavigateToColorOsGuide) { Text("检查自动捕获设置") }
                }
            }
            SettingsSection("记账") {
                SettingRow(Icons.Outlined.Category, "分类管理", "调整分类、排序与启用状态", onClick = onNavigateToCategories)
                SettingRow(Icons.AutoMirrored.Outlined.Rule, "通知解析规则", "管理支持的通知格式与匹配顺序", onClick = onNavigateToParseRules)
                SettingRow(Icons.Outlined.BatteryChargingFull, "后台运行引导", "检查通知、电池与自启动设置", onClick = onNavigateToColorOsGuide)
            }
            SettingsSection("智能助手") {
                SettingRow(Icons.Outlined.AutoAwesome, "AI 服务设置", "账号登录、套餐用量与服务模型", onClick = onNavigateToLlmSettings)
                SettingRow(Icons.Outlined.Mic, "语音识别", "使用设备提供的语音服务")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().toggleable(value = cloudParsingEnabled, enabled = !savingCloud,
                    role = Role.Switch, onValueChange = viewModel::setCloudParsingEnabled).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingIcon(Icons.Outlined.CloudQueue)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("通知云端解析", style = MaterialTheme.typography.titleMedium)
                        Text("默认关闭。开启后，本地筛选出的支付通知正文会发送给所选 AI 服务，补充解析。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cloudParsingEnabled, onCheckedChange = null, enabled = !savingCloud)
                }
            }
            SettingsSection("账本数据") {
                SettingRow(Icons.Outlined.Backup, "备份与恢复", if (busy) "正在处理，请稍候…" else backupSubtitle(lastBackupAt),
                    enabled = !busy, onClick = { showBackupDialog = true })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                SettingRow(Icons.Outlined.Info, "关于 PennyPilot", appVersionName(context), onClick = { showAboutDialog = true })
            }
            Text("账本保存在本机。使用 AI 功能时，输入和相关查询数据会发送给所选服务。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    // 备份/恢复入口弹窗
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("备份与恢复") },
            text = { Text("选择保存位置，导出包含账单、分类、解析规则和月报的独立 .db 文件。恢复旧版备份时，如果有同名 -wal 文件，请一起选择。API Key 不包含在账本备份中。") },
            confirmButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    exportLauncher.launch("PennyPilot-${LocalDate.now()}.db")
                }) { Text("立即备份") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    restoreLauncher.launch(arrayOf("*/*"))
                }) { Text("从备份恢复") }
            }
        )
    }

    if (restoreDocuments.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { restoreDocuments = emptyList() },
            title = { Text("确认恢复账本？") },
            text = { Text("将用选中的备份替换当前账单、分类、解析规则和月报。建议先备份当前账本。AI 配置保持不变。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreDb(context, restoreDocuments)
                    restoreDocuments = emptyList()
                }, enabled = !busy) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreDocuments = emptyList() }) { Text("取消") }
            }
        )
    }

    // 关于弹窗
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("PennyPilot") },
            text = {
                Text(
                    "版本 ${appVersionName(context)}\n\n" +
                        "自用 AI 记账 App：一句话智能记账、微信/支付宝通知自动捕获、" +
                        "对话查账与月度报告。账本保存在本机；使用 AI 功能时，输入和相关查询数据会发送到所选供应商。系统备份遵循设备设置。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("好的") }
            }
        )
    }
}

/** 备份副标题：未备份 / 上次备份 X月X日 */
private fun backupSubtitle(lastBackupAt: Long): String {
    if (lastBackupAt <= 0) return "未备份 · 自选位置保存 .db"
    val date = Instant.ofEpochMilli(lastBackupAt).atZone(ZoneId.systemDefault())
    return "上次备份 ${date.monthValue}月${date.dayOfMonth}日"
}

/** 从 PackageManager 读版本号，避免引入 BuildConfig */
private fun appVersionName(context: android.content.Context): String {
    return try {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "v?"
    }
}


@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Surface(shape = MaterialTheme.shapes.extraLarge) { Column(Modifier.fillMaxWidth(), content = content) }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingIcon(icon, muted = onClick == null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = if (onClick == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                color = if (onClick == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingIcon(icon: ImageVector, muted: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (muted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer) {
        Icon(icon, contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(10.dp).size(22.dp))
    }
}

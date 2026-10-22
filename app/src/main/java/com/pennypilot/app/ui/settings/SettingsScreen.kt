package com.pennypilot.app.ui.settings

import android.content.ComponentName
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDate
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.pennypilot.app.service.CaptureServiceMonitor
import com.pennypilot.app.service.NotificationCaptorService
import com.pennypilot.app.ui.theme.PageSubStyle
import com.pennypilot.app.ui.theme.faintColor
import com.pennypilot.app.ui.theme.successColor
import java.time.Instant
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
    val serviceMonitor = viewModel.serviceMonitor
    val health by serviceMonitor.health.collectAsState()
    val lastBackupAt by viewModel.lastBackupAt.collectAsState()

    // 备份/恢复/关于弹窗状态
    var showBackupDialog by remember { mutableStateOf(false) }
    var restoreDocuments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val busy by viewModel.isBusy.collectAsState()
    val message by viewModel.message.collectAsState()
    val cloudParsingEnabled by viewModel.cloudParsingEnabled.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportDb(context, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        restoreDocuments = uris
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    var showAboutDialog by remember { mutableStateOf(false) }

    // 通知使用权状态：RESUME 时重查，从系统设置授权返回后立即刷新
    var isListenerEnabled by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val cn = ComponentName(context, NotificationCaptorService::class.java)
        isListenerEnabled = Settings.Secure
            .getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(cn.flattenToString()) == true
    }

    // 超 24h 无通知且服务绑定中 → 标红（设计文档 4.4 状态自检）
    val stale = health.isBound && health.lastNotificationTime > 0 &&
        System.currentTimeMillis() - health.lastNotificationTime > 24 * 3600 * 1000L
    val healthy = health.isBound && !stale
    val dotColor = if (healthy) MaterialTheme.successColor else MaterialTheme.colorScheme.error
    val statusText = when {
        !health.isBound -> "捕获服务未连接"
        stale -> "捕获服务运行中 · 超 24h 无通知"
        else -> "捕获服务运行中"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 108.dp)
    ) {
        // page-head：我的 + 副标题
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Text(
            "配置与数据中心",
            style = PageSubStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(18.dp))

        // 捕获服务健康卡：呼吸点 + 三指标格
        Card(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(start = 17.dp, end = 17.dp, top = 17.dp, bottom = 15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BreathingDot(dotColor)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            statusText,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.W700,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isListenerEnabled) "微信 · 支付宝通知监听正常" else "请开启通知使用权",
                            fontSize = 11.sp,
                            color = MaterialTheme.faintColor
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricBox("${health.todayCaptureCount} 笔", "今日捕获", Modifier.weight(1f))
                    MetricBox(
                        if (health.lastNotificationTime > 0) {
                            val mins = (System.currentTimeMillis() - health.lastNotificationTime) / 60000
                            if (mins < 1) "刚刚" else "${mins}分钟前"
                        } else "无记录",
                        "最近通知",
                        Modifier.weight(1f)
                    )
                    MetricBox(
                        if (isListenerEnabled) "已授权" else "未授权",
                        "权限状态",
                        Modifier.weight(1f),
                        valueColor = if (isListenerEnabled) MaterialTheme.successColor else null
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 记账分组
        SectionLabel("记账")
        Spacer(Modifier.height(11.dp))
        SettingsGroup {
            SettingsRow(
                icon = "🏷️", iconBg = Color(0xFFEDEFFD), iconFg = Color(0xFF33429F),
                title = "分类管理", subtitle = "增删改 · 排序 · 停用",
                isLast = false,
                onClick = onNavigateToCategories
            )
            SettingsRow(
                icon = "🧩", iconBg = Color(0xFFE4F6F0), iconFg = Color(0xFF1E9E7E),
                title = "通知解析规则", subtitle = "正则热更新 · 优先级匹配",
                isLast = false,
                onClick = onNavigateToParseRules
            )
            SettingsRow(
                icon = "🛡️", iconBg = Color(0xFFFFF3DF), iconFg = Color(0xFFB45309),
                title = "ColorOS 保活引导", subtitle = "自启动 · 电池优化 · 后台运行",
                value = if (isListenerEnabled) "已完成" else "未完成",
                valueColor = if (isListenerEnabled) MaterialTheme.successColor else null,
                onClick = onNavigateToColorOsGuide
            )
        }

        Spacer(Modifier.height(24.dp))

        // AI 分组
        SectionLabel("AI")
        Spacer(Modifier.height(11.dp))
        SettingsGroup {
            SettingsRow(
                icon = "✨", iconBg = Color(0xFFF0EAFF), iconFg = Color(0xFF7A5AE0),
                title = "LLM 供应商", subtitle = "主力 DeepSeek · 备用 Qwen",
                isLast = false,
                onClick = onNavigateToLlmSettings
            )
            SettingsRow(
                icon = "🎙️", iconBg = Color(0xFFE7F0FF), iconFg = Color(0xFF3B6FE0),
                title = "语音识别", subtitle = "使用设备提供的语音服务",
                onClick = null
            )
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知云端解析", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "默认关闭。开启后，仅将本地筛选出的支付通知交给所选 AI 供应商补充解析；通知正文会发送到该服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = cloudParsingEnabled, onCheckedChange = viewModel::setCloudParsingEnabled)
            }
        }

        Spacer(Modifier.height(24.dp))

        // 数据分组
        SectionLabel("数据")
        Spacer(Modifier.height(11.dp))
        SettingsGroup {
            SettingsRow(
                icon = "💾", iconBg = Color(0xFFFDE9F1), iconFg = Color(0xFFD8498B),
                title = "备份与恢复", subtitle = if (busy) "正在处理，请稍候" else backupSubtitle(lastBackupAt),
                isLast = false,
                onClick = { if (!busy) showBackupDialog = true }
            )
            SettingsRow(
                icon = "🧭", iconBg = Color(0xFFFCEBE8), iconFg = Color(0xFFDF4438),
                title = "关于 PennyPilot", subtitle = "自用 AI 记账 · 不上架",
                value = appVersionName(context),
                onClick = { showAboutDialog = true }
            )
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "PENNYPILOT · 让 AI 替你记账",
            fontSize = 10.5.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.faintColor,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
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

/** 绿色呼吸点：内实心 + 外圈扩散动画 */
@Composable
private fun BreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "breath")
    val haloScale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "haloScale"
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "haloAlpha"
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(18.dp)
                .scale(haloScale)
                .background(color.copy(alpha = haloAlpha * 0.6f), CircleShape)
        )
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.W700,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** 指标格：card2 底 + 发丝描边 + 15dp 圆角（原型 sg-item） */
@Composable
private fun MetricBox(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.faintColor
            )
        }
    }
}

/** 分组卡片：行间发丝分割（原型 set-group） */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 设置行：36dp 彩色圆角图标 + 标题/副标题 + 右值 + 箭头（原型 set-row） */
@Composable
private fun ColumnScope.SettingsRow(
    icon: String,
    iconBg: Color,
    iconFg: Color,
    title: String,
    subtitle: String,
    value: String? = null,
    valueColor: Color? = null,
    isLast: Boolean = true,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.faintColor
            )
        }
        if (value != null) {
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("›", color = MaterialTheme.faintColor)
    }
    // 行间发丝分割线（最后一行除外）
    if (!isLast) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

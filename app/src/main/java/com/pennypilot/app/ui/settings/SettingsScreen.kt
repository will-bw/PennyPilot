package com.pennypilot.app.ui.settings

import android.content.ComponentName
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
    var showRestoreDialog by remember { mutableStateOf(false) }
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
                title = "语音识别", subtitle = "系统离线识别引擎",
                value = "已开启", valueColor = MaterialTheme.successColor,
                onClick = null
            )
        }

        Spacer(Modifier.height(24.dp))

        // 数据分组
        SectionLabel("数据")
        Spacer(Modifier.height(11.dp))
        SettingsGroup {
            SettingsRow(
                icon = "💾", iconBg = Color(0xFFFDE9F1), iconFg = Color(0xFFD8498B),
                title = "备份与恢复", subtitle = backupSubtitle(lastBackupAt),
                isLast = false,
                onClick = { showBackupDialog = true }
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
            text = { Text("备份导出到 Documents/PennyPilot，可将该目录指向网盘同步。恢复会覆盖当前数据。") },
            confirmButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    val result = viewModel.exportDb(context)
                    Toast.makeText(
                        context,
                        result.fold({ "已备份：$it" }, { "备份失败：${it.message}" }),
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("立即备份") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupDialog = false
                    showRestoreDialog = true
                }) { Text("从备份恢复") }
            }
        )
    }

    // 恢复：选文件 + 二次确认
    if (showRestoreDialog) {
        RestoreDialog(
            backups = remember { viewModel.listBackups() },
            onRestore = { file ->
                showRestoreDialog = false
                val result = viewModel.restoreDb(context, file.absolutePath)
                Toast.makeText(
                    context,
                    result.fold(
                        { "恢复成功，请手动重启 App 生效" },
                        { "恢复失败：${it.message}" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            },
            onDismiss = { showRestoreDialog = false }
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
                        "对话查账与月度报告。数据仅存本机，永不上架。"
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
    if (lastBackupAt <= 0) return "未备份 · 导出 .db 到 Documents"
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

/** 恢复弹窗：列出已有备份 → 选中后二次确认 */
@Composable
private fun RestoreDialog(
    backups: List<java.io.File>,
    onRestore: (java.io.File) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingFile by remember { mutableStateOf<java.io.File?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pendingFile?.let { "确认恢复？" } ?: "选择备份文件") },
        text = {
            if (pendingFile == null) {
                if (backups.isEmpty()) {
                    Text("Documents/PennyPilot 目录下没有备份文件。")
                } else {
                    Column {
                        backups.forEach { file ->
                            Text(
                                file.name,
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pendingFile = file }
                                    .padding(vertical = 9.dp)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
                        }
                    }
                }
            } else {
                Text("恢复将用 ${pendingFile!!.name} 覆盖当前全部账单与设置，不可撤销。确定继续？")
            }
        },
        confirmButton = {
            if (pendingFile == null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = { pendingFile?.let(onRestore) }) {
                    Text("确认恢复", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (pendingFile != null) {
                TextButton(onClick = { pendingFile = null }) { Text("返回") }
            }
        }
    )
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

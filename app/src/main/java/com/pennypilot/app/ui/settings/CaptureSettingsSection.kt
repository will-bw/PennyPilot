package com.pennypilot.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.service.PaymentAccessibilityPermission
import com.pennypilot.app.ui.theme.PennyMotion
import com.pennypilot.app.ui.theme.pendingAmberColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 通知读取、App 自身提醒与页面读取是三个独立权限，分别展示真实状态与恢复入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureSettingsSection(viewModel: SettingsViewModel, onOpenBackgroundGuide: () -> Unit) {
    val context = LocalContext.current
    val health by viewModel.serviceMonitor.health.collectAsStateWithLifecycle()
    val notificationEnabled by viewModel.notificationCaptureEnabled.collectAsStateWithLifecycle()
    val screenEnabled by viewModel.screenCaptureEnabled.collectAsStateWithLifecycle()
    val saving by viewModel.savingCapturePreference.collectAsStateWithLifecycle()
    var listenerGranted by remember { mutableStateOf(false) }
    var accessibilityGranted by remember { mutableStateOf(false) }
    var remindersEnabled by remember { mutableStateOf(false) }
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var enableAfterDisclosure by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerGranted = isNotificationListenerGranted(context)
        accessibilityGranted = isPaymentAccessibilityGranted(context)
        remindersEnabled = NotificationHelper.captureNotificationsEnabled(context)
        viewModel.refreshCaptureStatistics()
    }

    fun openAccessibilitySettings() {
        actionMessage = openCaptureSystemSettings(
            context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            "无法直接打开，请在系统设置的「无障碍」中找到 PennyPilot。"
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("自动捕获", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("支付通知与支付结果页面互相补充，识别结果先进入流水待确认。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CaptureSettingsCard(Icons.Outlined.NotificationsActive, "支付通知捕获") {
            CaptureToggle(
                title = "读取支付通知",
                description = "支持微信、支付宝、美团、抖音的支付通知。应用没有发通知或隐藏金额时，通知捕获无法识别。",
                checked = notificationEnabled, enabled = !saving,
                onCheckedChange = viewModel::setNotificationCaptureEnabled
            )
            Text(
                when {
                    !listenerGranted -> "通知使用权未授权"
                    !notificationEnabled -> "已暂停 · 通知使用权仍已授权"
                    !health.isBound -> "已授权 · 通知监听服务未连接"
                    else -> "已授权 · 通知监听服务已连接"
                }, style = MaterialTheme.typography.bodySmall,
                color = if (!listenerGranted || (notificationEnabled && !health.isBound)) MaterialTheme.pendingAmberColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    actionMessage = openCaptureSystemSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        "无法直接打开，请在系统设置中搜索「通知使用权」。")
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (listenerGranted) "查看通知使用权" else "授予通知使用权")
                }
                if (listenerGranted && notificationEnabled && !health.isBound) {
                    TextButton(onClick = {
                        actionMessage = try {
                            NotificationListenerService.requestRebind(ComponentName(context, NotificationCaptorService::class.java))
                            "已请求重新连接。如仍未连接，可在系统中关闭再开启通知使用权。"
                        } catch (_: Exception) { "无法请求重新连接，请在系统中关闭再开启通知使用权。" }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新连接") }
                }
            }
        }

        CaptureSettingsCard(Icons.Outlined.TouchApp, "支付页面捕获") {
            CaptureToggle(
                title = "读取可见支付结果",
                description = "没有支付通知时，可补充识别微信、支付宝、美团和抖音的可见支付结果。默认关闭。",
                checked = screenEnabled, enabled = !saving,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        enableAfterDisclosure = true
                        showDisclosure = true
                    } else viewModel.setScreenCaptureEnabled(false)
                }
            )
            Text(
                when {
                    !screenEnabled && accessibilityGranted -> "已暂停 · 系统无障碍授权仍保留"
                    !screenEnabled -> "尚未开启"
                    !accessibilityGranted -> "应用内已开启 · 等待系统无障碍授权"
                    !health.isAccessibilityBound -> "已授权 · 页面捕获服务未连接"
                    else -> "已授权 · 页面捕获服务已连接"
                }, style = MaterialTheme.typography.bodySmall,
                color = if (screenEnabled && (!accessibilityGranted || !health.isAccessibilityBound)) MaterialTheme.pendingAmberColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("读取支持应用的可见文字，在本机判断是否为支付结果；只保存识别出的交易字段，不保存或上传整页，不读取密码或输入内容，也不截图。系统或应用可能限制读取，仍可能需要手动记账。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (screenEnabled || accessibilityGranted) {
                OutlinedButton(onClick = { enableAfterDisclosure = false; showDisclosure = true }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (accessibilityGranted) "查看无障碍授权" else "前往授权")
                }
            } else {
                FilledTonalButton(onClick = { enableAfterDisclosure = true; showDisclosure = true }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("了解并开启") }
            }
        }

        CaptureSettingsCard(Icons.Outlined.NotificationsNone, "PennyPilot 记账提醒") {
            Text(if (remindersEnabled) "应用通知与「记账确认」通知类别已允许。"
                else "记账提醒未允许。捕获成功的账目仍会保存在流水待确认中。",
                style = MaterialTheme.typography.bodyMedium,
                color = if (remindersEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.pendingAmberColor)
            Text("这是 PennyPilot 发出的确认提醒，与读取其他应用通知的「通知使用权」分开设置。静默提醒可能只出现在通知栏，不弹出横幅。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                actionMessage = openCaptureSystemSettings(context,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    "无法直接打开，请在系统应用通知设置中允许 PennyPilot 的「记账确认」通知。")
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (remindersEnabled) "查看提醒设置" else "允许记账提醒") }
        }

        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今日捕获 ${health.todayCaptureCount} 笔", style = MaterialTheme.typography.titleMedium)
                Text("完全重复会自动拦截；金额、商家等相似但不能确认的记录会标为疑似重复，留给你核对。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                health.lastError?.let { error -> ManagementNotice(error, error = true) }
                TextButton(onClick = { showDiagnostics = !showDiagnostics }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (showDiagnostics) "收起运行记录" else "查看运行记录与排查")
                    Icon(if (showDiagnostics) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
                AnimatedVisibility(showDiagnostics) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近成功捕获：${captureTimestamp(health.lastNotificationTime)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("本次运行已拦截重复 ${health.duplicateCount} 次",
                            style = MaterialTheme.typography.bodySmall)
                        Text("连接状态与运行记录随本次运行更新；最近没有捕获不一定代表故障。今日数量包含待确认记录。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenBackgroundGuide, modifier = Modifier.heightIn(min = 48.dp)) { Text("检查后台与电池设置") }
                    }
                }
            }
        }
        ManagementNotice(actionMessage)
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("支付页面捕获使用说明") },
            text = {
                Text("开启后，PennyPilot 会通过系统无障碍服务，读取微信、支付宝、美团、抖音的屏幕可见文字，在本机判断是否为支付结果，生成待确认账目。\n\n" +
                    "只保存识别出的交易字段，不保存或上传整页。不读取密码或输入内容，不截图，也不会代你点击、付款或操作其他应用。\n\n" +
                    "请在系统设置中自行确认授权。部分页面可能无法读取，不能保证覆盖每笔支付。你可随时在本页暂停，或在系统设置中撤销授权。",
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    if (!enableAfterDisclosure) openAccessibilitySettings()
                    else viewModel.setScreenCaptureEnabled(true) {
                        if (!accessibilityGranted) openAccessibilitySettings()
                    }
                }, enabled = !saving) {
                    Text(if (!enableAfterDisclosure) "前往系统设置" else if (accessibilityGranted) "同意并开启" else "同意并前往授权")
                }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CaptureSettingsCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun CaptureToggle(title: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun isNotificationListenerGranted(context: Context): Boolean {
    val component = ComponentName(context, NotificationCaptorService::class.java)
    return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
}

private fun isPaymentAccessibilityGranted(context: Context): Boolean {
    return PaymentAccessibilityPermission.isGranted(context)
}

private fun openCaptureSystemSettings(context: Context, intent: Intent, failure: String): String? = try {
    context.startActivity(intent)
    null
} catch (_: Exception) { failure }

private fun captureTimestamp(timestamp: Long): String = if (timestamp <= 0) "本次运行暂无记录" else
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

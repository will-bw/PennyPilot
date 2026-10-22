package com.pennypilot.app.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import com.pennypilot.app.service.NotificationCaptorService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
fun ColorOsGuideScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ManagementHeader("后台运行引导", "系统入口随手机品牌而异。通知授权用于自动捕获，其余设置可按需调整。", onBack)

        NotificationAccessGuideStep()
        BatteryOptimizationGuideStep()
        AppBackgroundGuideStep()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("常见问题", style = MaterialTheme.typography.titleMedium)
                Text("• 授权后仍无反应？→ 关闭再重新开启通知使用权", style = MaterialTheme.typography.bodySmall)
                Text("• 锁屏后收不到？→ 微信设置里开启「通知显示消息详情」", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 两个引导入口共享跳转和前台状态刷新，系统设置成功打开不等于已获得豁免。 */
@Composable
fun BatteryOptimizationGuideStep() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf<Boolean?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exempt = BatteryOptimizationSettings.isExempt(context)
    }
    val status = when (exempt) {
        true -> "系统状态：已设为不优化。"
        false -> "系统状态：未设为不优化。"
        null -> "系统状态：暂无法读取，请在系统设置中确认。"
    }
    GuideStep(
        step = 2,
        title = "检查电池优化",
        desc = "$status 打开系统列表后找到 PennyPilot；如后台捕获不稳定，可按需选择「不优化」。",
        buttonText = "打开电池优化设置"
    ) {
        val feedback = when (BatteryOptimizationSettings.open(context)) {
            BatterySettingsDestination.OPTIMIZATION_LIST -> null
            BatterySettingsDestination.APP_DETAILS ->
                "当前系统未提供电池优化列表，已打开应用详情，请查看电池或耗电管理。"
            BatterySettingsDestination.UNAVAILABLE ->
                "无法打开系统设置，请手动进入设置中的电池或应用耗电管理，找到 PennyPilot。"
        }
        feedback?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

/** 引导步骤卡片：首次启动引导页（ui.onboarding）也复用此组件 */
@Composable
fun GuideStep(step: Int, title: String, desc: String, buttonText: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$step. $title", style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(buttonText, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun NotificationAccessGuideStep() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val component = ComponentName(context, NotificationCaptorService::class.java).flattenToString()
        granted = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(component) == true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideStep(1, "通知使用权", if (granted) "已授权。可随时在系统设置中关闭。" else "尚未授权。自动捕获需要读取通知；不授权也能手动记账。",
            if (granted) "查看授权" else "开启通知使用权") {
            error = try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); null
            } catch (_: Exception) { "无法直接打开，请在系统设置中搜索「通知使用权」。" }
        }
        error?.let { ManagementMessage(it, error = true) }
    }
}

@Composable
fun AppBackgroundGuideStep() {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideStep(3, "自启动与后台运行", "如锁屏后捕获不稳定，可在应用详情的电池或耗电管理中允许后台运行。不同手机入口不同，请手动确认。", "打开应用详情") {
            error = try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))); null
            } catch (_: Exception) { "无法直接打开，请在系统设置的应用管理中找到 PennyPilot。" }
        }
        error?.let { ManagementMessage(it, error = true) }
    }
}

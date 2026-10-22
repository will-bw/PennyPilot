package com.pennypilot.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("ColorOS 保活引导", style = MaterialTheme.typography.headlineLarge)
        Text(
            "不同 ColorOS 版本的后台管理入口可能不同。先开启通知使用权，再按需要检查电池优化、自启动和后台运行。",
            style = MaterialTheme.typography.bodyLarge
        )

        // Step 1: Notification access
        GuideStep(
            step = 1,
            title = "开启通知使用权",
            desc = "设置 → 应用 → 特殊权限 → 通知使用权 → 开启 PennyPilot",
            buttonText = "去设置"
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Step 2: Battery optimization
        BatteryOptimizationGuideStep()

        // Step 3: Auto-start (manual)
        GuideStep(
            step = 3,
            title = "允许自启动 + 后台运行",
            desc = "设置 → 应用 → 应用管理 → PennyPilot → 耗电管理 → 允许自启动 + 允许后台运行",
            buttonText = "打开应用详情"
        ) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }

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
            Text("Step $step", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAction) { Text(buttonText) }
        }
    }
}

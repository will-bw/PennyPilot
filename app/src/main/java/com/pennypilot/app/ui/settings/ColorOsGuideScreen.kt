package com.pennypilot.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
            "OPPO 系统会积极杀后台，需要手动设置以下 3 步才能保证通知捕获正常工作。",
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
        GuideStep(
            step = 2,
            title = "关闭电池优化",
            desc = "弹出系统对话框，选择「不优化」",
            buttonText = "去设置"
        ) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            try { context.startActivity(intent) } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

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

@Composable
private fun GuideStep(step: Int, title: String, desc: String, buttonText: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Step $step", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAction) { Text(buttonText) }
        }
    }
}

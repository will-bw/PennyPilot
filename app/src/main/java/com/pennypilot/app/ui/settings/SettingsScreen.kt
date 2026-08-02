package com.pennypilot.app.ui.settings

import android.content.ComponentName
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.service.CaptureServiceMonitor
import com.pennypilot.app.service.NotificationCaptorService

@Composable
fun SettingsScreen(
    onNavigateToCategories: () -> Unit,
    onNavigateToLlmSettings: () -> Unit,
    onNavigateToColorOsGuide: () -> Unit,
    serviceMonitor: CaptureServiceMonitor
) {
    val context = LocalContext.current
    val health by serviceMonitor.health.collectAsState()

    // Check if notification listener is enabled
    val isListenerEnabled = remember {
        val cn = ComponentName(context, NotificationCaptorService::class.java)
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(cn.flattenToString()) == true
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)

        // Service Health Card
        Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Breathing dot
                    val dotColor = if (health.isBound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    Text("●", color = dotColor, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            if (health.isBound) "捕获服务运行中" else "捕获服务未连接",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isListenerEnabled) "微信 · 支付宝通知监听正常" else "请开启通知使用权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Modifier.weight(1f)
                    )
                }
            }
        }

        // Settings groups
        Text("记账", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsCard(onClick = onNavigateToCategories, icon = "🏷️", title = "分类管理", subtitle = "增删改 · 排序 · 停用")
        SettingsCard(onClick = onNavigateToColorOsGuide, icon = "🛡️", title = "ColorOS 保活引导", subtitle = "自启动 · 电池优化 · 后台运行")

        Text("AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        SettingsCard(onClick = onNavigateToLlmSettings, icon = "✨", title = "AI 设置", subtitle = "LLM 供应商 · API Key")

        Spacer(Modifier.height(24.dp))
        Text(
            "PENNYPILOT · 让 AI 替你记账",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun MetricBox(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(onClick: () -> Unit, icon: String, title: String, subtitle: String) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.pennypilot.app.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pennypilot.app.ui.settings.AppBackgroundGuideStep
import com.pennypilot.app.ui.settings.BatteryOptimizationGuideStep
import com.pennypilot.app.ui.settings.NotificationAccessGuideStep

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("欢迎使用 PennyPilot", style = MaterialTheme.typography.headlineLarge)
            Text("从第一笔收支开始。下面的授权用于自动捕获支付通知，也可以稍后再设置。",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NotificationAccessGuideStep()
            BatteryOptimizationGuideStep()
            AppBackgroundGuideStep()
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开始记账") }
            Text("这些设置可在「我的 → 后台运行引导」中随时调整。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

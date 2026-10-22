package com.pennypilot.app.ui.onboarding

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pennypilot.app.ui.settings.GuideStep
import com.pennypilot.app.ui.settings.BatteryOptimizationGuideStep
import com.pennypilot.app.ui.theme.PageSubStyle

/**
 * 首次启动 ColorOS 引导（设计文档 §4.3）：
 * 通知使用权 + 电池优化 + 自启动三步，完成或跳过后写 onboarding_done，不再打扰。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 32.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("欢迎使用 PennyPilot", style = MaterialTheme.typography.headlineLarge)
        Text(
            "微信/支付宝通知捕获需要通知使用权；如后台捕获不稳定，可按下面的步骤检查系统设置。",
            style = PageSubStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        GuideStep(
            step = 1,
            title = "开启通知使用权",
            desc = "设置 → 应用 → 特殊权限 → 通知使用权 → 开启 PennyPilot",
            buttonText = "去设置"
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        BatteryOptimizationGuideStep()

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

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onFinish, modifier = Modifier.weight(1f)) {
                Text("跳过，稍后设置")
            }
            Button(onClick = onFinish, modifier = Modifier.weight(1.4f)) {
                Text("完成设置，开始记账", fontWeight = FontWeight.W700)
            }
        }
    }
}

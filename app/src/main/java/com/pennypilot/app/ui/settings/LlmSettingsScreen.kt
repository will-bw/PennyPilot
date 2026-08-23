package com.pennypilot.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pennypilot.app.data.settings.SecureKeyStore

@Composable
fun LlmSettingsScreen(
    onBack: () -> Unit,
    keyStore: SecureKeyStore
) {
    var deepSeekKey by remember { mutableStateOf(keyStore.deepSeekKey) }
    var qwenKey by remember { mutableStateOf(keyStore.qwenKey) }
    var provider by remember { mutableStateOf(keyStore.activeProvider) }
    var deepSeekModel by remember { mutableStateOf(keyStore.deepSeekModel) }
    var qwenModel by remember { mutableStateOf(keyStore.qwenModel) }
    var qwenBaseUrl by remember { mutableStateOf(keyStore.qwenBaseUrl) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text("AI 设置", style = MaterialTheme.typography.headlineLarge)

        // Provider selector（无默认值，需手动选择）
        Text("主力供应商", style = MaterialTheme.typography.titleMedium)
        if (provider.isBlank()) {
            Text(
                "尚未选择供应商，请手动选择要使用的 AI 服务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = provider == "deepseek",
                onClick = { provider = "deepseek" },
                label = { Text("DeepSeek") }
            )
            FilterChip(
                selected = provider == "qwen",
                onClick = { provider = "qwen" },
                label = { Text("通义千问") }
            )
        }

        HorizontalDivider()

        // DeepSeek section
        Text("DeepSeek 配置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = deepSeekKey,
            onValueChange = { deepSeekKey = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deepSeekModel,
            onValueChange = { deepSeekModel = it },
            label = { Text("模型名称") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        // Qwen section
        Text("通义千问 / 百炼配置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = qwenKey,
            onValueChange = { qwenKey = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = qwenBaseUrl,
            onValueChange = { qwenBaseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = qwenModel,
            onValueChange = { qwenModel = it },
            label = { Text("模型名称") },
            placeholder = { Text("qwen-plus") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                keyStore.deepSeekKey = deepSeekKey.trim()
                keyStore.qwenKey = qwenKey.trim()
                keyStore.activeProvider = provider
                keyStore.deepSeekModel = deepSeekModel.trim().ifBlank { "deepseek-chat" }
                keyStore.qwenModel = qwenModel.trim().ifBlank { "qwen-plus" }
                keyStore.qwenBaseUrl = qwenBaseUrl.trim().ifBlank {
                    "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
                }
                saved = true
            },
            enabled = provider.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text(if (saved) "✓ 已保存（立即生效）" else "保存") }

        Text(
            "切换供应商或模型后立即生效，无需重启 App。API Key 加密存储于本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

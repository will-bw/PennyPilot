package com.pennypilot.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private data class AiConfiguration(val provider: String, val deepSeekKey: String, val qwenKey: String,
    val deepSeekModel: String, val qwenModel: String, val qwenBaseUrl: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LlmSettingsScreen(onBack: () -> Unit, viewModel: LlmSettingsViewModel) {
    val keyStore = viewModel.keyStore
    val auth by viewModel.chatGptAuth.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val accountBusy by viewModel.accountBusy.collectAsStateWithLifecycle()
    val accountId = auth.activeAccount?.id
    var originalModel by remember(accountId) { mutableStateOf(accountId?.let(keyStore::chatGptModel).orEmpty()) }
    var selectedModel by rememberSaveable(accountId) { mutableStateOf(originalModel) }
    // Secrets deliberately stay in memory, never in a saved-instance-state Bundle.
    var original by remember { mutableStateOf(AiConfiguration(keyStore.activeProvider, keyStore.deepSeekKey,
        keyStore.qwenKey, keyStore.deepSeekModel, keyStore.qwenModel, keyStore.qwenBaseUrl)) }
    // Keep the chosen service across the system-browser round trip without saving any API keys.
    var selectedProvider by rememberSaveable { mutableStateOf(original.provider) }
    var draft by remember { mutableStateOf(original.copy(provider = selectedProvider)) }
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(accountId, auth.isAuthorizing, auth.activeAccount?.canUsePlan) {
        error = null
        saved = false
    }
    val recoveryNeeded = remember { keyStore.needsReconfiguration }
    val scope = rememberCoroutineScope()
    val dirty = draft != original || (draft.provider == "chatgpt" && selectedModel != originalModel)
    val controlsEnabled = !saving && !accountBusy && !auth.isAuthorizing
    fun changed(value: AiConfiguration) {
        draft = value; selectedProvider = value.provider; saved = false; error = null
    }
    fun back() {
        if (saving) error = "正在保存，请稍候。"
        else if (dirty) discard = true
        else onBack()
    }
    BackHandler(enabled = dirty || saving) { back() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ManagementHeader("AI 设置", "使用 ChatGPT 套餐，或连接已有的 AI 服务。", ::back)
        if (recoveryNeeded && !saved) ManagementMessage("原 AI 密钥无法解密，请重新填写后保存。账本数据不受影响。", error = true)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 服务", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("chatgpt" to "ChatGPT", "deepseek" to "DeepSeek", "qwen" to "通义千问").forEach { (value, label) ->
                        FilterChip(selected = draft.provider == value, onClick = { changed(draft.copy(provider = value)) },
                            enabled = controlsEnabled, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text("切换服务会保留其他服务的配置，保存后立即生效。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (draft.provider) {
            "chatgpt" -> ChatGptAccountSection(viewModel, selectedModel, onModelSelected = {
                selectedModel = it; saved = false; error = null
            }, enabled = !saving)
            "deepseek" -> {
                Text("DeepSeek 配置", style = MaterialTheme.typography.titleMedium)
                SecretField(draft.deepSeekKey, { changed(draft.copy(deepSeekKey = it)) }, !saving, "DeepSeek API Key")
                OutlinedTextField(draft.deepSeekModel, { changed(draft.copy(deepSeekModel = it)) },
                    label = { Text("模型名称") }, supportingText = { Text("例如 deepseek-chat") },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            }
            "qwen" -> {
                Text("通义千问 / 百炼配置", style = MaterialTheme.typography.titleMedium)
                SecretField(draft.qwenKey, { changed(draft.copy(qwenKey = it)) }, !saving, "通义千问 API Key")
                OutlinedTextField(draft.qwenBaseUrl, { changed(draft.copy(qwenBaseUrl = it)) },
                    label = { Text("服务地址（Base URL）") }, supportingText = { Text("填写供应商提供的 HTTPS 兼容接口地址，以 /v1 结尾。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.qwenModel, { changed(draft.copy(qwenModel = it)) },
                    label = { Text("模型名称") }, supportingText = { Text("例如 qwen-plus，需与服务地址对应。") },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
            }
            else -> ManagementMessage("尚未选择供应商。选择后可配置 AI 记账与查询。")
        }
        error?.let { ManagementMessage(it, error = true) }
        if (saved) ManagementMessage("配置已保存并生效。连接将在下次使用 AI 时验证。")
        Button(onClick = {
            val value = draft.copy(deepSeekKey = draft.deepSeekKey.trim(), qwenKey = draft.qwenKey.trim(),
                deepSeekModel = draft.deepSeekModel.trim(), qwenModel = draft.qwenModel.trim(), qwenBaseUrl = draft.qwenBaseUrl.trim().trimEnd('/'))
            val activeKey = if (value.provider == "qwen") value.qwenKey else value.deepSeekKey
            val activeModel = if (value.provider == "qwen") value.qwenModel else value.deepSeekModel
            error = if (value.provider == "chatgpt") chatGptSelectionError(
                signedIn = auth.activeAccount?.isSignedIn == true,
                canUsePlan = auth.activeAccount?.canUsePlan == true,
                accountId = accountId,
                catalogAccountId = models.accountId,
                loading = models.loading,
                availableModels = models.models.map { it.slug },
                selectedModel = selectedModel
            ) else when {
                activeKey.isBlank() -> "请填写所选供应商的 API Key。"
                activeKey.any(Char::isWhitespace) -> "API Key 中不能包含空格或换行。"
                activeModel.isBlank() -> "请填写所选供应商的模型名称。"
                value.provider == "qwen" && !isSecureAiEndpoint(value.qwenBaseUrl) -> "请输入有效的 HTTPS 服务地址，不要附带密钥、查询参数或片段。"
                else -> null
            }
            if (error == null) {
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (value.provider == "chatgpt") {
                                check(viewModel.chatGptAuth.state.value.activeAccount?.id == accountId)
                                keyStore.setChatGptModel(requireNotNull(accountId), selectedModel)
                            }
                            keyStore.deepSeekKey = value.deepSeekKey
                            keyStore.qwenKey = value.qwenKey
                            keyStore.deepSeekModel = value.deepSeekModel
                            keyStore.qwenModel = value.qwenModel
                            keyStore.qwenBaseUrl = value.qwenBaseUrl
                            keyStore.activeProvider = value.provider
                            keyStore.clearRecoveryNotice()
                        }
                        draft = value; original = value; originalModel = selectedModel; saved = true
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { error = "配置未能完整保存，输入已保留，请重试。"; saved = false
                    } finally { saving = false }
                }
            }
        }, enabled = draft.provider.isNotBlank() && controlsEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (saving) { CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (saving) "正在保存…" else "保存配置")
        }
        Text("登录凭据与 API Key 加密保存在本机，不包含在账本备份中。使用 AI 功能时，输入与相关账本查询数据会发送给所选服务。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的配置？") },
        text = { Text("本次修改尚未保存，返回后需要重新填写。") },
        confirmButton = { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续编辑") } })
}

internal fun chatGptSelectionError(
    signedIn: Boolean, canUsePlan: Boolean, accountId: String?, catalogAccountId: String?,
    loading: Boolean, availableModels: List<String>, selectedModel: String
): String? = when {
    !signedIn || accountId == null -> "请先使用 ChatGPT 登录。"
    !canUsePlan -> "请先允许 PennyPilot 使用你的 ChatGPT 套餐。"
    loading -> "正在读取可用模型，请稍候。"
    accountId != catalogAccountId || availableModels.isEmpty() -> "请先刷新此账号的模型列表。"
    selectedModel !in availableModels -> "请选择此账号当前可用的模型。"
    else -> null
}

internal fun isSecureAiEndpoint(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
} catch (_: Exception) { false }

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, enabled: Boolean, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                if (visible) "隐藏密钥" else "显示密钥", modifier = Modifier.size(22.dp))
        } }, modifier = Modifier.fillMaxWidth())
}

package com.pennypilot.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatGptAccountSection(
    viewModel: LlmSettingsViewModel,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    enabled: Boolean
) {
    val auth by viewModel.chatGptAuth.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val busy by viewModel.accountBusy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val account = auth.activeAccount
    val context = LocalContext.current
    var modelMenu by remember { mutableStateOf(false) }
    var signOutId by remember { mutableStateOf<String?>(null) }
    val canAct = enabled && !busy && !auth.isAuthorizing
    val openBrowser: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    fun openUsage() {
        try { openBrowser(CHATGPT_USAGE_URL) } catch (_: Exception) { viewModel.showBrowserError() }
    }

    Text("使用 ChatGPT 套餐", style = MaterialTheme.typography.titleLarge)
    Text("用你登录 Codex 的同一个 ChatGPT 账号继续。在官方页面授权后，符合条件的 AI 请求将使用该账号的套餐用量。",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (auth.accounts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            auth.accounts.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .selectable(selected = account?.id == item.id, enabled = canAct,
                        role = Role.RadioButton, onClick = {
                            if (item.isSignedIn) viewModel.selectAccount(item.id)
                            else viewModel.signIn(item.id, openBrowser = openBrowser)
                        }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = account?.id == item.id, onClick = null, enabled = canAct)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.email, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("账号 ${item.id.takeLast(6)} · ${if (!item.isSignedIn) "已退出，点按重新登录" else if (item.canUsePlan) "套餐已授权" else "尚未授权套餐"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (auth.isAuthorizing) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("等待浏览器完成登录…", style = MaterialTheme.typography.bodyMedium)
        }
        Text("完成授权后返回 PennyPilot。取消或超时后可以重新开始。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                auth.loginUrl?.let { try { openBrowser(it) } catch (_: Exception) { viewModel.showBrowserError() } }
            }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续浏览器登录") }
            TextButton(onClick = viewModel.chatGptAuth::cancelSignIn, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("取消登录") }
        }
    } else {
        Button(onClick = { viewModel.signIn(openBrowser = openBrowser) }, enabled = canAct,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
            border = BorderStroke(1.dp, if (canAct) Color(0xFF646464) else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (auth.accounts.isEmpty()) "Continue with ChatGPT" else "添加 ChatGPT 账号")
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    auth.error?.let { ManagementMessage(it, error = true) }
    notice?.let { ManagementMessage(it) }

    if (account?.isSignedIn == true) {
        HorizontalDivider()
        if (!account.canUsePlan) {
            ManagementMessage("账号已登录，但尚未授权套餐使用。授权后才能读取模型并使用 AI。")
            OutlinedButton(onClick = {
                viewModel.signIn(account.id, requestPlanPermission = true, openBrowser = openBrowser)
            }, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("授权使用 ChatGPT 套餐") }
        } else {
            Text("AI 模型", style = MaterialTheme.typography.titleMedium)
            Text("额度与模型权限由账号及工作空间决定。达到限制时会提示，不会自动切换到其他付费服务。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth()) {
                val selected = models.models.firstOrNull { it.slug == selectedModel }
                OutlinedButton(onClick = { modelMenu = true },
                    enabled = canAct && !models.loading && models.models.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(selected?.displayName ?: "选择可用模型", modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false },
                    modifier = Modifier.heightIn(max = 320.dp).widthIn(max = 320.dp)) {
                    models.models.forEach { model ->
                        val isSelected = model.slug == selectedModel
                        DropdownMenuItem(text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(model.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (model.slug != model.displayName) Text(model.slug, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } }, onClick = { onModelSelected(model.slug); modelMenu = false },
                            trailingIcon = {
                                if (isSelected) Icon(Icons.Outlined.Check, contentDescription = null,
                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                else Spacer(Modifier.size(20.dp))
                            }, modifier = Modifier.heightIn(min = 56.dp).semantics { this.selected = isSelected })
                    }
                }
            }
            if (models.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取此账号的可用模型…", style = MaterialTheme.typography.bodyMedium)
            }
            models.error?.let { ManagementMessage(it, error = true) }
            if (!models.loading && models.error == null && selectedModel.isNotEmpty() &&
                models.models.none { it.slug == selectedModel }) {
                ManagementMessage("之前保存的模型已不在当前列表中，请重新选择。", error = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::refreshModels, enabled = canAct && !models.loading,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新模型") }
                TextButton(onClick = { viewModel.signIn(account.id, openBrowser = openBrowser) }, enabled = canAct,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("重新登录") }
            }
            Text("模型列表来自当前账号；保存配置后用于 AI 记账、问问和月报。实际访问权限以请求结果为准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { signOutId = account.id }, enabled = canAct,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("退出此账号") }
    }
    OutlinedButton(onClick = ::openUsage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("查看与管理套餐用量")
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp))
    }
    Text("具体额度、使用记录与本应用的使用限额，以 ChatGPT 官方设置为准。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (auth.showWelcome) AlertDialog(onDismissRequest = viewModel.chatGptAuth::dismissWelcome,
        title = { Text("正在使用你的 ChatGPT 套餐") },
        text = { Text("PennyPilot 中符合条件的 AI 请求会使用你的 ChatGPT 套餐。你可以在“查看与管理套餐用量”中调整本应用的限额。选择模型并保存配置后即可使用。") },
        confirmButton = { TextButton(onClick = viewModel.chatGptAuth::dismissWelcome) { Text("知道了") } })
    signOutId?.let { id -> AlertDialog(onDismissRequest = { signOutId = null },
        title = { Text("退出 ChatGPT 账号？") }, text = { Text("此设备将停止使用该账号。账本和其他服务配置会保留。") },
        confirmButton = { TextButton(onClick = { signOutId = null; viewModel.signOut(id) }) { Text("退出账号") } },
        dismissButton = { TextButton(onClick = { signOutId = null }) { Text("取消") } }) }
}

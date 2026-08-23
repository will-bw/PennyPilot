package com.pennypilot.app.ui.input

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.theme.faintColor
import com.pennypilot.app.ui.theme.priGradient
import com.pennypilot.app.util.MoneyUtil

@Composable
fun AiInputScreen(
    onNavigateToManual: () -> Unit,
    vm: AiInputViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 录音运行时权限：按住麦克风前先检查/申请，避免 SpeechRecognizer 静默失败
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording()
        else vm.setError("未获得录音权限，请在系统设置中开启麦克风权限后重试")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Page title
        Text("记一笔", style = MaterialTheme.typography.headlineLarge)

        // Hint card (indigo gradient)
        Card(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(MaterialTheme.priGradient)
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        "TRY SAYING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Text(
                        "\u201C昨天午饭 25 块，\n打车去公司 32。\u201D",
                        fontFamily = FontFamily.Serif,
                        fontSize = 16.5.sp,
                        color = Color.White,
                        lineHeight = 26.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "一句话可拆多笔 · 支持「昨天/上周二」等相对时间",
                        fontSize = 11.5.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 9.dp)
                    )
                }
            }
        }

        // AI Parse loading
        if (state.isParsing) {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(
                            "AI 解析中…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        // AI Parse Preview Card
        if (state.previewItems.isNotEmpty()) {
            PreviewCard(
                items = state.previewItems,
                onRemove = vm::removePreviewItem,
                onEditAll = onNavigateToManual,
                onSaveAll = { vm.saveAll { /* stay on page, show feedback */ } }
            )
        }

        // Error state with fallback
        if (state.error != null) {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "解析失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(state.error ?: "", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onNavigateToManual) {
                        Text("改用手动记账")
                    }
                }
            }
        }

        // Saved feedback
        if (state.savedCount > 0) {
            Text(
                "✓ 已保存 ${state.savedCount} 笔",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 底部提示（原型 add-tip）
        Text(
            "按住 🎤 说话，松手转文字，可修改后再发送\nLLM 解析失败时自动降级为手动表单",
            fontSize = 11.5.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.faintColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.weight(1f))

        // InputDock at bottom
        InputDock(
            text = state.inputText,
            onTextChange = vm::updateInput,
            onSend = { vm.sendForParsing() },
            onMicPress = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    vm.startRecording()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onMicRelease = { vm.stopRecording() },
            isRecording = state.isRecording,
            // 底部预留 26dp：FAB 凸出 TabBar 16dp + 10dp 间隙，避免 dock 与 FAB 互相遮挡
            modifier = Modifier.padding(bottom = 26.dp)
        )
    }
}

@Composable
private fun PreviewCard(
    items: List<PreviewItem>,
    onRemove: (Int) -> Unit,
    onEditAll: () -> Unit,
    onSaveAll: () -> Unit
) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            // Header
            Row(
                modifier = Modifier.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✦", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                Spacer(Modifier.width(7.dp))
                Text("AI 解析预览", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${items.size} 笔",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(99.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }

            // Items
            items.forEachIndexed { index, item ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CatIcon(
                        colorIndex = item.categoryColorIndex,
                        emoji = item.categoryIcon,
                        size = 38
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.merchant ?: item.note ?: item.categoryName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (item.note != null && item.merchant != null) {
                            Text(
                                item.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Category chip
                        Text(
                            item.categoryName,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        MoneyUtil.formatHeroAmount(item.amount),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onRemove(index) }) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Footer buttons：15dp 圆角，主按钮靛蓝渐变（原型 btn）
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 「再改改」跳转手动记账，不再与「保存全部」重复触发保存
                OutlinedButton(
                    onClick = onEditAll,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("再改改", fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            Brush.linearGradient(MaterialTheme.priGradient),
                            RoundedCornerShape(15.dp)
                        )
                        .clickable { onSaveAll() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "保存全部 · ${items.size} 笔",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

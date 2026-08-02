package com.pennypilot.app.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.CatIcon
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.theme.LightPrimary
import com.pennypilot.app.ui.theme.LightPrimaryDeep
import com.pennypilot.app.util.MoneyUtil

@Composable
fun AiInputScreen(
    onNavigateToManual: () -> Unit,
    vm: AiInputViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(listOf(LightPrimary, LightPrimaryDeep))
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

        Spacer(Modifier.weight(1f))

        // InputDock at bottom
        InputDock(
            text = state.inputText,
            onTextChange = vm::updateInput,
            onSend = { vm.sendForParsing() },
            onMicPress = { vm.startRecording() },
            onMicRelease = { vm.stopRecording() },
            isRecording = state.isRecording,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun PreviewCard(
    items: List<PreviewItem>,
    onRemove: (Int) -> Unit,
    onSaveAll: () -> Unit
) {
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            // Header
            Row(
                modifier = Modifier.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✦", color = LightPrimary, fontSize = 15.sp)
                Spacer(Modifier.width(7.dp))
                Text("AI 解析预览", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${items.size} 笔",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightPrimary,
                    modifier = Modifier
                        .background(LightPrimary.copy(alpha = 0.1f), RoundedCornerShape(99.dp))
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
                            color = LightPrimary,
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .background(
                                    LightPrimary.copy(alpha = 0.1f),
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

            // Footer buttons
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onSaveAll) {
                    Text("再改改")
                }
                Button(
                    onClick = onSaveAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存全部 · ${items.size} 笔")
                }
            }
        }
    }
}

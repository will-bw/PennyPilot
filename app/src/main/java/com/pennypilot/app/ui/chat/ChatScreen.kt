package com.pennypilot.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.components.MiniChart
import com.pennypilot.app.ui.theme.priGradient

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新消息/加载态变化时自动滚动到底部，保证键盘弹出后最新内容仍可见
    LaunchedEffect(state.messages.size, state.isLoading) {
        val total = state.messages.size + if (state.isLoading) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header – page-head: flex row, align bottom, space between, marginBottom 18dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("问问", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "用自然语言查账 · Function Calling 驱动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // 近 10 轮上下文提示 pill（原型 page-head 右侧）
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    "近 10 轮上下文",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(state.messages, key = { it.id }) { msg ->
                if (msg.role == "user") {
                    // User bubble (right, indigo gradient)
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            msg.text,
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .background(
                                    Brush.linearGradient(MaterialTheme.priGradient),
                                    RoundedCornerShape(19.dp, 19.dp, 5.dp, 19.dp)
                                )
                                .padding(12.dp)
                        )
                    }
                } else {
                    // AI bubble (left, card)
                    Card(
                        shape = RoundedCornerShape(5.dp, 19.dp, 19.dp, 19.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Column(Modifier.padding(13.dp)) {
                            Text("✦ PennyPilot", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(msg.text, fontSize = 14.sp, lineHeight = 23.sp)
                            if (msg.chartData != null) {
                                Spacer(Modifier.height(12.dp))
                                MiniChart(data = msg.chartData)
                            }
                        }
                    }
                }
            }

            // Loading indicator
            if (state.isLoading) {
                item {
                    Card(shape = RoundedCornerShape(5.dp, 19.dp, 19.dp, 19.dp)) {
                        Text("正在查询…", modifier = Modifier.padding(13.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Suggestion chips — 原型 chip 样式：卡片底 + 发丝描边 + 全圆角
        if (state.suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.suggestions.forEach { s ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(99.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(99.dp))
                            .clickable { vm.send(s) }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    ) {
                        Text(
                            s,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // InputDock
        InputDock(
            text = state.inputText,
            onTextChange = vm::updateInput,
            onSend = { vm.send() },
            onMicPress = { /* voice not implemented for chat yet */ },
            onMicRelease = { },
            placeholder = "问点什么，如「上周吃饭花了多少」",
            // 底部预留 26dp：FAB 凸出 TabBar 16dp + 10dp 间隙，避免 dock 与 FAB 互相遮挡
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 26.dp)
        )
    }
}

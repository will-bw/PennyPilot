package com.pennypilot.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.pennypilot.app.ui.theme.LightPrimary
import com.pennypilot.app.ui.theme.LightPrimaryDeep

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

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
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp),
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
                                    Brush.linearGradient(listOf(LightPrimary, LightPrimaryDeep)),
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
                            Text("✦ PennyPilot", fontSize = 11.sp, color = LightPrimary,
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

        // Suggestion chips
        if (state.suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.suggestions.forEach { s ->
                    SuggestionChip(
                        onClick = { vm.send(s) },
                        label = { Text(s, fontSize = 12.sp, maxLines = 1) }
                    )
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

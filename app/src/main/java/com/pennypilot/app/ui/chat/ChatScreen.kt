package com.pennypilot.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.InputDock
import com.pennypilot.app.ui.components.LedgerState
import com.pennypilot.app.ui.components.MiniChart
import com.pennypilot.app.ui.theme.PennyMotion

@Composable
fun ChatScreen(vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ChatContent(state, vm::updateInput, { vm.send() }, vm::send, vm::retry, vm::clearConversation)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatContent(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestion: (String) -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    val showLatestShortcut by remember {
        derivedStateOf { !followLatest && listState.canScrollForward }
    }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, autoScrolling) }
            .collect { (scrolling, canScrollForward, automatic) ->
                if (scrolling && !automatic) followLatest = !canScrollForward
            }
    }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.isLoading, keyboardHeight, followLatest) {
        val total = state.messages.size + if (state.isLoading) 1 else 0
        if (total > 0 && followLatest) {
            autoScrolling = true
            try {
                listState.animateScrollToItem(total - 1)
            } finally {
                autoScrolling = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("问问", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text("查收支、看变化，让账目更清楚", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            AnimatedVisibility(
                visible = state.messages.isNotEmpty(),
                enter = expandHorizontally(PennyMotion.spatial()) + fadeIn(PennyMotion.effects()),
                exit = shrinkHorizontally(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit))
            ) {
                IconButton(onClick = { confirmClear = true }, enabled = state.messages.isNotEmpty() && !state.isLoading) {
                    Icon(Icons.Outlined.DeleteOutline, "清空本次对话")
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.messages.isEmpty()) item(key = "conversation_empty") {
                Column(Modifier.animateItem(fadeInSpec = PennyMotion.effects(), placementSpec = PennyMotion.spatial(), fadeOutSpec = tween(PennyMotion.Exit))) {
                    LedgerState("从一个问题开始", "试着问问最近的支出、某个分类，或两个月的变化。回答可长按复制。")
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.suggestions.forEach { question ->
                            SuggestionChip(onClick = { followLatest = true; onSuggestion(question) }, enabled = !state.isLoading,
                                label = { Text(question, style = MaterialTheme.typography.labelLarge) },
                                modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                val user = message.role == "user"
                Box(Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = PennyMotion.effects(), placementSpec = PennyMotion.spatial(), fadeOutSpec = tween(PennyMotion.Exit)
                ), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(
                        modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(if (user) .88f else 1f),
                        color = when { user -> MaterialTheme.colorScheme.primaryContainer
                            message.isError -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface },
                        shape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                            else RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (user) "你" else if (message.isError) "未能完成查询" else "PennyPilot",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                            message.chartData?.let { MiniChart(data = it) }
                            if (message.isError && state.failedQuestion != null) {
                                TextButton(onClick = { followLatest = true; onRetry() }, enabled = !state.isLoading) { Text("重试这条问题") }
                            }
                        }
                    }
                }
            }
            if (state.isLoading) item(key = "conversation_loading") {
                Row(Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = PennyMotion.effects(), placementSpec = PennyMotion.spatial(), fadeOutSpec = tween(PennyMotion.Exit)
                ).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在查询账目…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AnimatedVisibility(
            visible = showLatestShortcut && state.messages.isNotEmpty(),
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = expandVertically(PennyMotion.spatial()) + fadeIn(PennyMotion.effects()),
            exit = shrinkVertically(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit))
        ) {
            TextButton(onClick = { followLatest = true }, enabled = showLatestShortcut,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("回到最新消息")
            }
        }
        InputDock(text = state.inputText, onTextChange = onInputChange, onSend = { followLatest = true; onSend() },
            onMicPress = {}, onMicRelease = {}, showMicrophone = false,
            isSending = state.isLoading, placeholder = "例如：上周吃饭花了多少？",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }

    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空本次对话？") },
        text = { Text("对话内容将移除，账本记录和正在输入的问题会保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; followLatest = true; onClear() }) { Text("清空对话") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("保留对话") } })
}

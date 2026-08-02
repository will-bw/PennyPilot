package com.pennypilot.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.domain.usecase.ChartData
import com.pennypilot.app.domain.usecase.InsightUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String, // "user" or "ai"
    val text: String,
    val chartData: ChartData? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val inputText: String = "",
    val suggestions: List<String> = emptyList()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val insightUseCase: InsightUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val allSuggestions = listOf(
        "这个月花了多少", "餐饮占比多少", "和上月比怎么样",
        "最大一笔支出是什么", "这周交通花了多少", "最近哪天花得最多"
    )

    init {
        _state.update { it.copy(suggestions = allSuggestions.shuffled().take(3)) }
    }

    fun updateInput(text: String) { _state.update { it.copy(inputText = text) } }

    fun send(question: String? = null) {
        val q = (question ?: _state.value.inputText).trim()
        if (q.isBlank() || _state.value.isLoading) return

        _state.update { s ->
            s.copy(
                messages = s.messages + ChatMessage(role = "user", text = q),
                inputText = "",
                isLoading = true,
                suggestions = emptyList()
            )
        }

        viewModelScope.launch {
            // Build history for context (last 10 rounds = 20 messages)
            val history = _state.value.messages.takeLast(20).map {
                Message(role = if (it.role == "user") "user" else "assistant", content = it.text)
            }

            val result = insightUseCase.ask(q, history)

            _state.update { s ->
                s.copy(
                    messages = s.messages + ChatMessage(
                        role = "ai", text = result.answer, chartData = result.chartData
                    ),
                    isLoading = false
                )
            }
        }
    }
}

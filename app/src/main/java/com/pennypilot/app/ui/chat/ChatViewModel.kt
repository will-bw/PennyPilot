package com.pennypilot.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.domain.usecase.ChartData
import com.pennypilot.app.domain.usecase.InsightUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val chartData: ChartData? = null,
    val isError: Boolean = false
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val inputText: String = "",
    val suggestions: List<String> = listOf("这个月花了多少", "餐饮占比多少", "和上月比怎么样"),
    val failedQuestion: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(private val insightUseCase: InsightUseCase) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    fun updateInput(text: String) { _state.update { it.copy(inputText = text) } }

    fun clearConversation() {
        if (!_state.value.isLoading) {
            _state.update { ChatUiState(inputText = it.inputText) }
        }
    }

    fun send(question: String? = null) {
        val q = (question ?: _state.value.inputText).trim()
        if (q.isBlank() || _state.value.isLoading) return
        ask(q, retry = false, clearInput = question == null)
    }

    fun retry() {
        if (_state.value.isLoading) return
        _state.value.failedQuestion?.let { ask(it, retry = true, clearInput = false) }
    }

    private fun ask(question: String, retry: Boolean, clearInput: Boolean) {
        val messages = _state.value.messages.filterNot { it.isError }
        // 重试不重复追加用户问题，也不把失败提示当作上下文发给模型。
        val history = (if (retry) messages.dropLast(1) else messages).takeLast(20).map {
            Message(if (it.role == "user") "user" else "assistant", it.text)
        }
        _state.update {
            it.copy(
                messages = if (retry) messages else messages + ChatMessage(role = "user", text = question),
                inputText = if (clearInput) "" else it.inputText,
                isLoading = true,
                suggestions = emptyList(),
                failedQuestion = null
            )
        }
        viewModelScope.launch {
            try {
                val result = insightUseCase.ask(question, history)
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(role = "ai", text = result.answer,
                            chartData = result.chartData, isError = result.isError),
                        isLoading = false,
                        failedQuestion = question.takeIf { result.isError }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, failedQuestion = question,
                    messages = it.messages + ChatMessage(role = "ai", text = "查询暂时不可用，请稍后重试。", isError = true)) }
            }
        }
    }
}

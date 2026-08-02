package com.pennypilot.app.ui.input

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.asr.SpeechRecognizerManager
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.domain.usecase.ParseTextUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiInputUiState(
    val inputText: String = "",
    val isParsing: Boolean = false,
    val isRecording: Boolean = false,
    val previewItems: List<PreviewItem> = emptyList(),
    val error: String? = null,
    val savedCount: Int = 0
)

data class PreviewItem(
    val amount: Long,
    val type: TxType,
    val categoryId: Long,
    val categoryName: String,
    val categoryColorIndex: Int,
    val categoryIcon: String,
    val note: String?,
    val merchant: String?,
    val occurredAt: Long
)

@HiltViewModel
class AiInputViewModel @Inject constructor(
    application: Application,
    private val parseTextUseCase: ParseTextUseCase,
    private val txDao: TransactionDao,
    private val categoryDao: CategoryDao
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AiInputUiState())
    val state: StateFlow<AiInputUiState> = _state.asStateFlow()

    val speechManager = SpeechRecognizerManager(application)

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text, error = null) }
    }

    fun sendForParsing() {
        val text = _state.value.inputText.trim()
        if (text.isBlank()) return

        _state.update { it.copy(isParsing = true, error = null, previewItems = emptyList(), savedCount = 0) }

        viewModelScope.launch {
            val result = parseTextUseCase.execute(text)
            if (result.error != null) {
                _state.update { it.copy(isParsing = false, error = result.error) }
            } else {
                val items = result.transactions.map { tx ->
                    val category = categoryDao.getById(tx.categoryId)
                    PreviewItem(
                        amount = tx.amount,
                        type = tx.type,
                        categoryId = tx.categoryId,
                        categoryName = tx.categoryName,
                        categoryColorIndex = category?.colorIndex ?: 0,
                        categoryIcon = category?.icon ?: "📦",
                        note = tx.note,
                        merchant = tx.merchant,
                        occurredAt = tx.occurredAt
                    )
                }
                _state.update { it.copy(isParsing = false, previewItems = items, inputText = "") }
            }
        }
    }

    fun removePreviewItem(index: Int) {
        _state.update { s ->
            s.copy(previewItems = s.previewItems.filterIndexed { i, _ -> i != index })
        }
    }

    fun saveAll(onDone: () -> Unit) {
        val items = _state.value.previewItems
        if (items.isEmpty()) return

        viewModelScope.launch {
            items.forEach { item ->
                val amount = if (item.type == TxType.EXPENSE) item.amount else -item.amount
                txDao.insert(
                    TransactionEntity(
                        amount = amount,
                        type = item.type,
                        categoryId = item.categoryId,
                        note = item.note,
                        merchant = item.merchant,
                        occurredAt = item.occurredAt,
                        source = TxSource.AI_TEXT,
                        status = TxStatus.CONFIRMED
                    )
                )
            }
            _state.update { it.copy(previewItems = emptyList(), savedCount = items.size) }
            onDone()
        }
    }

    fun startRecording() {
        _state.update { it.copy(isRecording = true) }
        speechManager.startListening { result ->
            _state.update { it.copy(inputText = result, isRecording = false) }
        }
    }

    fun stopRecording() {
        speechManager.stopListening()
        _state.update { it.copy(isRecording = false) }
    }

    fun reset() {
        _state.update { AiInputUiState() }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}

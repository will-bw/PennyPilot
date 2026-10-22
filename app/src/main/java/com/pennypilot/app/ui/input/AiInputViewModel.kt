package com.pennypilot.app.ui.input

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.asr.SpeechRecognizerManager
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.domain.usecase.ParseTextUseCase
import com.pennypilot.app.util.MoneyUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

data class AiInputUiState(
    val inputText: String = "",
    val isParsing: Boolean = false,
    val isRecording: Boolean = false,
    val isRecognizing: Boolean = false,
    val isSaving: Boolean = false,
    val previewItems: List<PreviewItem> = emptyList(),
    val error: String? = null,
    val savedCount: Int = 0,
    val message: String? = null
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
    val occurredAt: Long,
    val draftId: String = UUID.randomUUID().toString()
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

    // 预览卡分类选择器数据源：按条目类型在 UI 层过滤
    val allCategories = categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val speechManager = SpeechRecognizerManager(application)
    private var inputRevision = 0L

    fun updateInput(text: String) {
        inputRevision++
        _state.update { it.copy(inputText = text, error = null) }
    }

    /** 供 UI 层上报错误（如录音权限被拒） */
    fun setError(message: String) {
        _state.update { it.copy(error = message, isRecording = false, isRecognizing = false) }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
    fun notifyMicrophoneGranted() { _state.update { it.copy(message = "麦克风已授权，请再次按住语音按钮开始说话。") } }
    fun clearMessage() { _state.update { it.copy(message = null) } }

    fun sendForParsing() {
        val text = _state.value.inputText.trim()
        if (text.isBlank() || _state.value.isParsing || _state.value.isSaving || _state.value.isRecording || _state.value.isRecognizing) return
        val submittedRevision = inputRevision

        _state.update { it.copy(inputText = "", isParsing = true, error = null, savedCount = 0) }

        viewModelScope.launch {
            try {
                val result = parseTextUseCase.execute(text)
                if (result.error != null) {
                    _state.update {
                        it.copy(
                            isParsing = false,
                            error = result.error,
                            inputText = if (inputRevision == submittedRevision) text else it.inputText
                        )
                    }
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
                    _state.update { it.copy(isParsing = false, previewItems = it.previewItems + items) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isParsing = false,
                        error = "解析失败，请重试：${e.message ?: "数据不可用"}",
                        inputText = if (inputRevision == submittedRevision) text else it.inputText
                    )
                }
            }
        }
    }

    fun removePreviewItem(index: Int) {
        if (_state.value.isSaving) return
        _state.update { s ->
            s.copy(previewItems = s.previewItems.filterIndexed { i, _ -> i != index })
        }
    }

    fun updatePreviewItem(updated: PreviewItem): Boolean {
        if (_state.value.isSaving || !MoneyUtil.isValidAmountCents(updated.amount)) return false
        if (_state.value.previewItems.none { it.draftId == updated.draftId }) return false
        _state.update { s -> s.copy(previewItems = s.previewItems.map { if (it.draftId == updated.draftId) updated else it }, error = null) }
        return true
    }

    /** 预览卡分类 chip 点击后改选分类（设计 §7.3：可点 chip → 分类网格选择器） */
    fun changePreviewCategory(index: Int, category: CategoryEntity) {
        if (_state.value.isSaving) return
        _state.update { s ->
            s.copy(previewItems = s.previewItems.mapIndexed { i, item ->
                if (i == index) item.copy(
                    categoryId = category.id,
                    categoryName = category.name,
                    categoryColorIndex = category.colorIndex,
                    categoryIcon = category.icon
                ) else item
            })
        }
    }

    fun saveAll(onDone: () -> Unit) {
        val items = _state.value.previewItems
        if (items.isEmpty() || _state.value.isSaving || _state.value.isParsing) return
        if (items.any { !MoneyUtil.isValidAmountCents(it.amount) }) {
            setError("金额必须大于 0 且不超过 9,999,999.99 元")
            return
        }

        // 同步锁定保存入口；每笔成功后才移除对应草稿，失败时仍可重试余下条目。
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
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
                    _state.update { s -> s.copy(previewItems = s.previewItems.filterNot { it.draftId == item.draftId }, savedCount = s.savedCount + 1) }
                }
                _state.update { it.copy(isSaving = false, message = "已保存 ${items.size} 笔账目") }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "保存失败，请重试：${e.message ?: "数据库不可用"}") }
            }
        }
    }

    fun saveManual(
        type: TxType,
        categoryId: Long,
        amountCents: Long,
        note: String,
        occurredAt: Long = System.currentTimeMillis(),
        onDone: () -> Unit
    ) {
        if (_state.value.isSaving) return
        if (!MoneyUtil.isValidAmountCents(amountCents)) {
            setError("金额必须大于 0 且不超过 9,999,999.99 元")
            return
        }
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val amount = if (type == TxType.EXPENSE) amountCents else -amountCents
                txDao.insert(
                    TransactionEntity(
                        amount = amount,
                        type = type,
                        categoryId = categoryId,
                        note = note.ifBlank { null },
                        merchant = null,
                        occurredAt = occurredAt,
                        source = TxSource.MANUAL,
                        status = TxStatus.CONFIRMED
                    )
                )
                _state.update { it.copy(isSaving = false, message = "已保存 1 笔账目") }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "保存失败，请重试：${e.message ?: "数据库不可用"}") }
            }
        }
    }

    fun startRecording() {
        if (_state.value.isRecording || _state.value.isRecognizing || _state.value.isSaving) return
        _state.update { it.copy(isRecording = true, error = null) }
        speechManager.startListening(
            onResult = { result ->
                // 语音补在当前草稿后，异步结果不能覆盖已有文字。
                updateInput(listOf(_state.value.inputText.trim(), result.trim()).filter { it.isNotEmpty() }.joinToString("，"))
                _state.update { it.copy(isRecording = false, isRecognizing = false) }
            },
            onError = ::setError
        )
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return
        speechManager.stopListening()
        _state.update { it.copy(isRecording = false, isRecognizing = true) }
    }

    fun cancelRecording() {
        speechManager.cancel()
        _state.update { it.copy(isRecording = false, isRecognizing = false) }
    }

    fun reset() {
        if (_state.value.isParsing || _state.value.isSaving) return
        speechManager.cancel()
        inputRevision++
        _state.update { AiInputUiState() }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}

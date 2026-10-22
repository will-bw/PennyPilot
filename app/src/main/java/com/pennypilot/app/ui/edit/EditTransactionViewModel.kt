package com.pennypilot.app.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.util.MoneyUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditUiState(
    val amountText: String = "",
    val type: TxType = TxType.EXPENSE,
    val selectedCategoryId: Long? = null,
    val note: String = "",
    val merchant: String = "",
    val accountTag: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val categories: List<CategoryEntity> = emptyList(),
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val hasChanges: Boolean = false,
    val amountError: String? = null,
    val error: String? = null
)

private data class EditDraft(
    val amount: String, val type: TxType, val categoryId: Long?, val note: String,
    val merchant: String, val accountTag: String, val occurredAt: Long
)

private fun EditUiState.draft() = EditDraft(amountText, type, selectedCategoryId, note, merchant, accountTag, occurredAt)

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val txId: Long = savedStateHandle.get<Long>("id") ?: 0L
    private val _state = MutableStateFlow(EditUiState(isEditing = txId > 0))
    val state: StateFlow<EditUiState> = _state.asStateFlow()
    private var allCategories = emptyList<CategoryEntity>()
    private var original: EditDraft? = null
    private var originalCategoryId: Long? = null

    init {
        viewModelScope.launch {
            try {
                if (txId > 0) {
                    val tx = txDao.getById(txId) ?: error("这笔账目已不存在，请返回流水列表")
                    originalCategoryId = tx.categoryId
                    _state.update { it.copy(
                        amountText = MoneyUtil.centsToYuanString(tx.amount), type = tx.type,
                        selectedCategoryId = tx.categoryId, note = tx.note.orEmpty(),
                        merchant = tx.merchant.orEmpty(), accountTag = tx.accountTag.orEmpty(), occurredAt = tx.occurredAt
                    ) }
                }
                catDao.observeAll().collect { categories ->
                    allCategories = categories
                    val next = withCategories(_state.value).copy(isLoading = false)
                    if (original == null) original = next.draft()
                    _state.value = next.copy(hasChanges = next.draft() != original)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loadFailed = true, error = e.message ?: "账目加载失败，请返回后重试") }
            }
        }
    }

    private fun withCategories(s: EditUiState): EditUiState {
        val cats = allCategories.filter { (it.enabled || it.id == originalCategoryId) && it.type == s.type }
        return s.copy(categories = cats, selectedCategoryId = s.selectedCategoryId.takeIf { id -> cats.any { it.id == id } } ?: cats.firstOrNull()?.id)
    }

    private fun edit(change: (EditUiState) -> EditUiState) {
        if (_state.value.isSaving || _state.value.isLoading || _state.value.loadFailed) return
        _state.update { old ->
            val next = change(old)
            next.copy(hasChanges = next.draft() != original, error = null)
        }
    }

    fun updateAmount(v: String) = edit { it.copy(amountText = v, amountError = null) }
    fun updateNote(v: String) = edit { it.copy(note = v) }
    fun updateMerchant(v: String) = edit { it.copy(merchant = v) }
    fun updateAccountTag(v: String) = edit { it.copy(accountTag = v) }
    fun selectCategory(id: Long) = edit { s -> if (s.categories.any { it.id == id }) s.copy(selectedCategoryId = id) else s }
    fun setType(t: TxType) = edit { withCategories(it.copy(type = t)) }
    fun setOccurredAt(ms: Long) = edit { it.copy(occurredAt = ms) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.isSaving || s.isLoading || s.loadFailed) return
        val cents = MoneyUtil.parsePositiveYuanToCents(s.amountText)
        if (cents == null) {
            _state.update { it.copy(amountError = "请输入 0.01～9,999,999.99 元，最多两位小数") }
            return
        }
        if (s.selectedCategoryId == null || s.categories.none { it.id == s.selectedCategoryId }) {
            _state.update { it.copy(error = "请先选择可用分类") }
            return
        }
        _state.update { it.copy(isSaving = true, error = null, amountError = null) }
        viewModelScope.launch {
            try {
                val amount = if (s.type == TxType.EXPENSE) cents else -cents
                if (s.isEditing) {
                    val existing = txDao.getById(txId) ?: error("账目已不存在，请返回列表")
                    txDao.update(existing.copy(
                        amount = amount, type = s.type, categoryId = s.selectedCategoryId,
                        note = s.note.ifBlank { null }, merchant = s.merchant.ifBlank { null },
                        accountTag = s.accountTag.ifBlank { null }, occurredAt = s.occurredAt
                    ))
                } else {
                    txDao.insert(TransactionEntity(
                        amount = amount, type = s.type, categoryId = s.selectedCategoryId,
                        note = s.note.ifBlank { null }, merchant = s.merchant.ifBlank { null },
                        accountTag = s.accountTag.ifBlank { null }, occurredAt = s.occurredAt,
                        source = TxSource.MANUAL, status = TxStatus.CONFIRMED
                    ))
                }
                original = s.draft()
                _state.update { it.copy(isSaving = false, hasChanges = false) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "保存失败，修改已保留，请重试。${e.message.orEmpty()}") }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val s = _state.value
        if (s.isSaving || s.isLoading || s.loadFailed || !s.isEditing) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                txDao.deleteById(txId)
                _state.update { it.copy(isSaving = false, hasChanges = false) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "删除失败，请重试。${e.message.orEmpty()}") }
            }
        }
    }
}

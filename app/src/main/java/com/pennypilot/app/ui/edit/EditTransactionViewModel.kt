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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditUiState(
    val amountText: String = "",
    val type: TxType = TxType.EXPENSE,
    val selectedCategoryId: Long? = null,
    val note: String = "",
    val merchant: String = "",
    val categories: List<CategoryEntity> = emptyList(),
    val isEditing: Boolean = false
)

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    private val txDao: TransactionDao,
    private val catDao: CategoryDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val txId: Long = savedStateHandle.get<Long>("id") ?: 0L

    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    init {
        // 分类列表跟随交易类型联动：编辑收入账单时展示收入分类，避免选中错误类型的分类
        @OptIn(ExperimentalCoroutinesApi::class)
        val categoriesByType = _state
            .map { it.type }
            .distinctUntilChanged()
            .flatMapLatest { type -> catDao.observeByType(type) }

        viewModelScope.launch {
            categoriesByType.collect { cats ->
                _state.update { s ->
                    s.copy(
                        categories = cats,
                        // 当前选中分类不在列表内（如类型切换/分类被删）时回退到第一个
                        selectedCategoryId = if (cats.any { it.id == s.selectedCategoryId }) {
                            s.selectedCategoryId
                        } else {
                            cats.firstOrNull()?.id
                        }
                    )
                }
            }
        }
        if (txId > 0L) {
            viewModelScope.launch {
                txDao.getById(txId)?.let { tx ->
                    _state.update {
                        it.copy(
                            amountText = MoneyUtil.centsToYuanString(tx.amount),
                            type = tx.type,
                            selectedCategoryId = tx.categoryId,
                            note = tx.note ?: "",
                            merchant = tx.merchant ?: "",
                            isEditing = true
                        )
                    }
                }
            }
        }
    }

    fun updateAmount(v: String) { _state.update { it.copy(amountText = v) } }
    fun updateNote(v: String) { _state.update { it.copy(note = v) } }
    fun updateMerchant(v: String) { _state.update { it.copy(merchant = v) } }
    fun selectCategory(id: Long) { _state.update { it.copy(selectedCategoryId = id) } }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val cents = MoneyUtil.parseYuanToCents(s.amountText)
        if (cents == 0L || s.selectedCategoryId == null) return

        viewModelScope.launch {
            val amount = if (s.type == TxType.EXPENSE) cents else -cents
            if (s.isEditing) {
                val existing = txDao.getById(txId) ?: return@launch
                txDao.update(existing.copy(
                    amount = amount, type = s.type,
                    categoryId = s.selectedCategoryId, note = s.note.ifBlank { null },
                    merchant = s.merchant.ifBlank { null }
                ))
            } else {
                txDao.insert(TransactionEntity(
                    amount = amount, type = s.type,
                    categoryId = s.selectedCategoryId, note = s.note.ifBlank { null },
                    merchant = s.merchant.ifBlank { null },
                    occurredAt = System.currentTimeMillis(), source = TxSource.MANUAL, status = TxStatus.CONFIRMED
                ))
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            if (txId > 0) txDao.deleteById(txId)
            onDone()
        }
    }
}

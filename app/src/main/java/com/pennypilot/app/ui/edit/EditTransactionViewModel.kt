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
    val accountTag: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val categories: List<CategoryEntity> = emptyList(),
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null
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
                            accountTag = tx.accountTag ?: "",
                            occurredAt = tx.occurredAt,
                            isEditing = true
                        )
                    }
                }
            }
        }
    }

    fun updateAmount(v: String) { _state.update { it.copy(amountText = v, error = null) } }
    fun updateNote(v: String) { _state.update { it.copy(note = v) } }
    fun updateMerchant(v: String) { _state.update { it.copy(merchant = v) } }
    fun updateAccountTag(v: String) { _state.update { it.copy(accountTag = v) } }
    fun selectCategory(id: Long) { _state.update { it.copy(selectedCategoryId = id) } }
    /** 切换支出/收入：分类列表随 type 联动刷新，选中分类失效时自动回退第一个 */
    fun setType(t: TxType) { _state.update { it.copy(type = t) } }
    fun setOccurredAt(ms: Long) { _state.update { it.copy(occurredAt = ms) } }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.isSaving) return
        val cents = MoneyUtil.parsePositiveYuanToCents(s.amountText)
        if (cents == null || s.selectedCategoryId == null) {
            _state.update { it.copy(error = if (cents == null) "请输入大于 0、不超过 9,999,999.99 元且最多两位小数的金额" else "请先选择分类") }
            return
        }
        _state.update { it.copy(isSaving = true, error = null) }

        viewModelScope.launch {
            try {
                val amount = if (s.type == TxType.EXPENSE) cents else -cents
                if (s.isEditing) {
                    val existing = txDao.getById(txId) ?: error("账目已不存在，请返回列表")
                    txDao.update(existing.copy(
                        amount = amount, type = s.type,
                        categoryId = s.selectedCategoryId, note = s.note.ifBlank { null },
                        merchant = s.merchant.ifBlank { null },
                        accountTag = s.accountTag.ifBlank { null },
                        occurredAt = s.occurredAt
                    ))
                } else {
                    txDao.insert(TransactionEntity(
                        amount = amount, type = s.type,
                        categoryId = s.selectedCategoryId, note = s.note.ifBlank { null },
                        merchant = s.merchant.ifBlank { null },
                        accountTag = s.accountTag.ifBlank { null },
                        occurredAt = s.occurredAt, source = TxSource.MANUAL, status = TxStatus.CONFIRMED
                    ))
                }
                _state.update { it.copy(isSaving = false) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "保存失败，请重试：${e.message ?: "数据库不可用"}") }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                if (txId > 0) txDao.deleteById(txId)
                _state.update { it.copy(isSaving = false) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "删除失败，请重试：${e.message ?: "数据库不可用"}") }
            }
        }
    }
}

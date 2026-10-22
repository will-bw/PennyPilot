package com.pennypilot.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.CategoryColors
import com.pennypilot.app.domain.model.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val catDao: CategoryDao,
    private val txDao: TransactionDao,
    private val database: AppDatabase
) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    val categories = catDao.observeAll()
        .onEach { _loaded.value = true }
        .catch { _loaded.value = true; _error.value = "分类读取失败，请返回后重试。" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearError() { _error.value = null }
    fun clearMessage() { _message.value = null }

    fun addCategory(name: String, icon: String, type: TxType, onSuccess: () -> Unit) =
        perform("分类已添加", onSuccess) {
            validate(name, icon, type)
            val all = catDao.observeAll().first()
            catDao.insert(CategoryEntity(name = name.trim(), icon = icon.trim(),
                colorIndex = CategoryColors.nextIndex(catDao.maxColorIndex()), type = type,
                sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1, isPreset = false))
        }

    fun toggleEnabled(cat: CategoryEntity) = perform(if (cat.enabled) "分类已停用，已有账目保留" else "分类已启用") {
        val current = requireNotNull(catDao.getById(cat.id)) { "分类已变更，请重新打开。" }
        catDao.update(current.copy(enabled = !current.enabled))
    }

    fun renameCategory(cat: CategoryEntity, name: String, icon: String, onSuccess: () -> Unit) =
        perform("分类已保存", onSuccess) {
            val current = requireNotNull(catDao.getById(cat.id)) { "分类已变更，请重新打开。" }
            validate(name, icon, current.type, current.id)
            catDao.update(current.copy(name = name.trim(), icon = icon.trim()))
        }

    fun deleteCategory(cat: CategoryEntity, onSuccess: () -> Unit) = perform("分类已删除，账目已移至「其他」", onSuccess) {
        val current = requireNotNull(catDao.getById(cat.id)) { "分类已变更，请重新打开。" }
        require(!current.isPreset) { "预设分类不能删除，可在列表中停用。" }
        val all = catDao.observeAll().first()
        val fallback = all.find { it.type == current.type && it.name == "其他" && it.id != current.id }
            ?: requireNotNull(catDao.getById(catDao.insert(CategoryEntity(
                name = "其他", icon = if (current.type == TxType.EXPENSE) "📦" else "💰",
                colorIndex = CategoryColors.nextIndex(catDao.maxColorIndex()), type = current.type,
                sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1, isPreset = true))))
        if (!fallback.enabled) catDao.update(fallback.copy(enabled = true))
        txDao.reassignCategory(current.id, fallback.id)
        catDao.deleteById(current.id)
    }

    fun moveCategory(cat: CategoryEntity, delta: Int) = perform("分类顺序已更新") {
        val list = catDao.observeAll().first().filter { it.type == cat.type }
        val index = list.indexOfFirst { it.id == cat.id }
        val target = index + delta
        if (index >= 0 && target in list.indices) {
            // 为同类型列表重新编号，兼容旧数据中重复的 sortOrder。
            val reordered = list.toMutableList().apply { add(target, removeAt(index)) }
            reordered.forEachIndexed { order, item -> catDao.update(item.copy(sortOrder = order)) }
        }
    }

    private suspend fun validate(name: String, icon: String, type: TxType, exceptId: Long? = null) {
        require(name.trim().isNotEmpty()) { "请填写分类名称。" }
        require(name.trim().length <= 20) { "分类名称最多 20 个字。" }
        require(icon.trim().isNotEmpty()) { "请选择或填写分类图标。" }
        require(catDao.observeAll().first().none { it.id != exceptId && it.type == type && it.name == name.trim() }) {
            "该类型下已有同名分类，请换一个名称。"
        }
    }

    private fun perform(message: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        val revision = LedgerRevision.current
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                database.withTransaction {
                    require(LedgerRevision.current == revision) { "账本已恢复，请重新打开此页面。" }
                    action()
                }
                _message.value = message
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                _error.value = e.message ?: "输入有误，请检查后重试。"
            } catch (_: Exception) {
                _error.value = "操作未完成，内容已保留，请重试。"
            } finally {
                _busy.value = false
            }
        }
    }
}

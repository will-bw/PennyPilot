package com.pennypilot.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.TransactionDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.CategoryColors
import com.pennypilot.app.domain.model.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val catDao: CategoryDao,
    private val txDao: TransactionDao
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = catDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String, icon: String, type: TxType) {
        viewModelScope.launch {
            val maxIdx = catDao.maxColorIndex()
            val maxOrder = categories.value.maxOfOrNull { it.sortOrder } ?: -1
            catDao.insert(CategoryEntity(
                name = name, icon = icon,
                colorIndex = CategoryColors.nextIndex(maxIdx),
                type = type, sortOrder = maxOrder + 1, isPreset = false
            ))
        }
    }

    fun toggleEnabled(cat: CategoryEntity) {
        viewModelScope.launch {
            catDao.update(cat.copy(enabled = !cat.enabled))
        }
    }

    /** 改名/改图标（设计 M1：分类管理「改」） */
    fun renameCategory(cat: CategoryEntity, name: String, icon: String) {
        viewModelScope.launch {
            catDao.update(cat.copy(name = name, icon = icon))
        }
    }

    /**
     * 删除分类（设计 M1：分类管理「删」）。
     * 名下交易先改挂到同类型「其他」兜底分类（不存在则创建），避免流水页分类落空。
     */
    fun deleteCategory(cat: CategoryEntity) {
        viewModelScope.launch {
            // 先在全量列表（含禁用）中找同类型「其他」，找不到再新建
            val fallback = categories.value
                .find { it.type == cat.type && it.name == "其他" && it.id != cat.id }
                ?: catDao.getById(
                    catDao.insert(CategoryEntity(
                        name = "其他", icon = "📦",
                        colorIndex = CategoryColors.nextIndex(catDao.maxColorIndex()),
                        type = cat.type,
                        sortOrder = (categories.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                        isPreset = true
                    ))
                )!!
            if (!fallback.enabled) catDao.update(fallback.copy(enabled = true))
            txDao.reassignCategory(cat.id, fallback.id)
            catDao.deleteById(cat.id)
        }
    }

    /** 上移/下移排序（设计 M1：分类管理「排序」），delta = -1 上移，+1 下移 */
    fun moveCategory(cat: CategoryEntity, delta: Int) {
        val list = categories.value
        val idx = list.indexOfFirst { it.id == cat.id }
        val swapIdx = idx + delta
        if (idx < 0 || swapIdx !in list.indices) return
        val other = list[swapIdx]
        viewModelScope.launch {
            catDao.update(cat.copy(sortOrder = other.sortOrder))
            catDao.update(other.copy(sortOrder = cat.sortOrder))
        }
    }
}

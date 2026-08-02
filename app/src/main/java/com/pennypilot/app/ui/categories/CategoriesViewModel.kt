package com.pennypilot.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.CategoryColors
import com.pennypilot.app.domain.model.TxType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val catDao: CategoryDao
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
}

package com.pennypilot.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.dao.ParseRuleDao
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知解析规则管理：微信/支付宝改文案时热更正则，不用发版（设计文档 §3.3）。
 */
@HiltViewModel
class ParseRulesViewModel @Inject constructor(
    private val parseRuleDao: ParseRuleDao
) : ViewModel() {

    val rules = parseRuleDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleEnabled(rule: ParseRuleEntity) = viewModelScope.launch {
        parseRuleDao.update(rule.copy(enabled = !rule.enabled))
    }

    /** id == 0 视为新增 */
    fun save(rule: ParseRuleEntity) = viewModelScope.launch {
        if (rule.id == 0L) parseRuleDao.insert(rule) else parseRuleDao.update(rule)
    }

    fun delete(rule: ParseRuleEntity) = viewModelScope.launch {
        parseRuleDao.delete(rule)
    }

    /** 清空后重写预置规则 */
    fun resetPresets() = viewModelScope.launch {
        parseRuleDao.deleteAll()
        parseRuleDao.insertAll(AppDatabase.PRESET_RULES)
    }
}

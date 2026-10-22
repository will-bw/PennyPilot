package com.pennypilot.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.dao.ParseRuleDao
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ParseRulesViewModel @Inject constructor(
    private val parseRuleDao: ParseRuleDao,
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
    val rules = parseRuleDao.observeAll().onEach { _loaded.value = true }
        .catch { _loaded.value = true; _error.value = "规则读取失败，请返回后重试。" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearError() { _error.value = null }
    fun clearMessage() { _message.value = null }
    fun toggleEnabled(rule: ParseRuleEntity) = perform(if (rule.enabled) "规则已停用" else "规则已启用") {
        val current = parseRuleDao.observeAll().first().find { it.id == rule.id }
        requireNotNull(current) { "规则已变更，请重新打开。" }
        parseRuleDao.update(current.copy(enabled = !current.enabled))
    }

    fun save(rule: ParseRuleEntity, onSuccess: () -> Unit) = perform("规则已保存", onSuccess) {
        require(rule.regexAmount.isNotBlank()) { "请填写金额正则。" }
        require(Regex(rule.regexAmount).toPattern().matcher("").groupCount() > 0) { "金额正则需要一个捕获组，用于提取金额。" }
        rule.regexMerchant?.let { require(Regex(it).toPattern().matcher("").groupCount() > 0) { "商户正则需要一个捕获组。" } }
        if (rule.id == 0L) parseRuleDao.insert(rule) else {
            require(parseRuleDao.observeAll().first().any { it.id == rule.id }) { "规则已变更，请重新打开。" }
            parseRuleDao.update(rule)
        }
    }

    fun delete(rule: ParseRuleEntity, onSuccess: () -> Unit) = perform("规则已删除", onSuccess) {
        parseRuleDao.delete(rule)
    }

    fun resetPresets(onSuccess: () -> Unit) = perform("已恢复预置规则", onSuccess) {
        parseRuleDao.deleteAll()
        parseRuleDao.insertAll(AppDatabase.PRESET_RULES)
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
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { _error.value = e.message ?: "规则有误，请检查后重试。"
            } catch (_: Exception) { _error.value = "操作未完成，规则内容已保留，请重试。"
            } finally { _busy.value = false }
        }
    }
}

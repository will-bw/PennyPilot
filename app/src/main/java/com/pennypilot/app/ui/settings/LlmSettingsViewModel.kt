package com.pennypilot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennypilot.app.data.llm.UserFacingLlmException
import com.pennypilot.app.data.openai.ChatGptModel
import com.pennypilot.app.data.openai.ChatGptModelRepository
import com.pennypilot.app.data.openai.auth.ChatGptAuthManager
import com.pennypilot.app.data.settings.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ChatGptModelsState(
    val accountId: String? = null,
    val models: List<ChatGptModel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * AI 设置页 ViewModel：复用 Hilt 单例 [SecureKeyStore]，
 * 避免在 Composable 中重复创建 EncryptedSharedPreferences / MasterKey。
 */
@HiltViewModel
class LlmSettingsViewModel @Inject constructor(
    val keyStore: SecureKeyStore,
    val chatGptAuth: ChatGptAuthManager,
    private val modelRepository: ChatGptModelRepository
) : ViewModel() {
    private val _models = MutableStateFlow(ChatGptModelsState())
    val models = _models.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _accountBusy = MutableStateFlow(false)
    val accountBusy = _accountBusy.asStateFlow()
    private var modelJob: Job? = null

    init {
        viewModelScope.launch {
            chatGptAuth.state.map { state ->
                state.activeAccount?.let { Triple(it.id, it.isSignedIn, it.canUsePlan) }
            }.distinctUntilChanged().collect { refreshModels() }
        }
    }

    fun refreshModels() {
        modelJob?.cancel()
        val account = chatGptAuth.state.value.activeAccount
        if (account == null || !account.isSignedIn || !account.canUsePlan) {
            _models.value = ChatGptModelsState(accountId = account?.id)
            return
        }
        _models.value = ChatGptModelsState(accountId = account.id, loading = true)
        modelJob = viewModelScope.launch {
            try {
                val result = modelRepository.listModels()
                if (chatGptAuth.state.value.activeAccount?.id == account.id) {
                    _models.value = ChatGptModelsState(accountId = account.id, models = result,
                        error = if (result.isEmpty()) "此账号暂未返回可选模型，请稍后刷新或检查套餐授权。" else null)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (chatGptAuth.state.value.activeAccount?.id == account.id) {
                    _models.value = ChatGptModelsState(accountId = account.id,
                        error = (e as? UserFacingLlmException)?.message ?: "模型列表读取失败，请检查网络后重试。")
                }
            }
        }
    }

    fun signIn(accountId: String? = null, requestPlanPermission: Boolean = false, openBrowser: (String) -> Unit) {
        if (chatGptAuth.state.value.isAuthorizing || _accountBusy.value) return
        _notice.value = null
        viewModelScope.launch {
            try {
                val url = chatGptAuth.beginSignIn(accountId, requestPlanPermission)
                try { openBrowser(url) } catch (_: Exception) {
                    chatGptAuth.cancelSignIn()
                    _notice.value = "无法打开系统浏览器，请安装或启用浏览器后重新登录。"
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _notice.value = (e as? UserFacingLlmException)?.message ?: "暂时无法开始登录，请重试。"
            }
        }
    }

    fun selectAccount(id: String) = accountAction { chatGptAuth.selectAccount(id) }

    fun signOut(id: String) = accountAction {
        modelJob?.cancel()
        _notice.value = chatGptAuth.signOut(id) ?: "已退出此账号。其他 AI 服务的配置仍保留。"
    }

    private fun accountAction(action: suspend () -> Unit) {
        if (_accountBusy.value || chatGptAuth.state.value.isAuthorizing) return
        _accountBusy.value = true
        _notice.value = null
        viewModelScope.launch {
            try { action()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _notice.value = (e as? UserFacingLlmException)?.message ?: "账号操作未完成，请重试。"
            } finally { _accountBusy.value = false }
        }
    }

    fun showBrowserError() { _notice.value = "无法打开浏览器，请检查系统浏览器设置。" }
}

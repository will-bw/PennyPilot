package com.pennypilot.app.data.openai.auth

import android.content.Context
import com.pennypilot.app.data.llm.UserFacingLlmException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Official public-client OAuth. No Codex private credentials, client IDs or backend routes are used. */
@Singleton
class ChatGptAuthManager internal constructor(
    private val store: ChatGptCredentialStore,
    private val transport: ChatGptAuthTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        EncryptedChatGptCredentialStore(context), OfficialChatGptAuthTransport(),
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val mutableState = MutableStateFlow(ChatGptAuthState())
    val state: StateFlow<ChatGptAuthState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var snapshot: AuthSnapshot? = null
    private val attemptLock = Any()
    private var pending: PendingSignIn? = null
    // Retry a newly issued registration after an unusable code without dynamically registering it again.
    private var retryClientId: String? = null
    private class PendingSignIn(val request: AuthorizationRequest, val selectedSubject: String?, val server: LoopbackCallbackServer) {
        var job: Job? = null
    }

    init {
        scope.launch {
            try {
                mutex.withLock {
                    val saved = withContext(Dispatchers.IO) { store.read() }
                    val loaded = saved ?: AuthSnapshot("urn:uuid:${UUID.randomUUID()}")
                    require(loaded.hostId.startsWith("urn:uuid:") && loaded.accounts.map { it.clientId }.distinct().size == loaded.accounts.size)
                    if (saved == null) withContext(Dispatchers.IO) { store.write(loaded) }
                    snapshot = loaded
                    publish(loaded)
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "ChatGPT 安全存储暂不可用，请检查设备锁屏与系统密钥；原有凭据未被覆盖") }
            } finally { ready.complete(Unit) }
        }
    }

    /** Wait for encrypted credentials to load before choosing the active account on a cold start. */
    suspend fun awaitReady() { ready.await() }

    suspend fun beginSignIn(accountId: String? = null, requestPlanPermission: Boolean = false): String {
        ready.await()
        return mutex.withLock {
            val current = requireSnapshot()
            val account = accountId?.let { id -> current.accounts.find { it.clientId == id }
                ?: throw UserFacingLlmException("未找到保存的 ChatGPT 账号") }
            cancelSignIn()
            try {
                val oauthState = ChatGptAuthProtocol.randomSecret()
                val server = withContext(Dispatchers.IO) { LoopbackCallbackServer(oauthState) }
                val request = AuthorizationRequest(
                    clientId = account?.clientId ?: retryClientId ?: DYNAMIC_CLIENT_ID,
                    hostId = current.hostId, redirectUri = server.redirectUri,
                    state = oauthState, nonce = ChatGptAuthProtocol.randomSecret(), verifier = ChatGptAuthProtocol.randomSecret(),
                    loginHint = account?.email?.takeUnless { it == "未提供邮箱" }, requestPlanPermission = requestPlanPermission
                )
                val attempt = PendingSignIn(request, account?.subject, server)
                val url = ChatGptAuthProtocol.authorizationUrl(request)
                synchronized(attemptLock) {
                    pending = attempt
                    mutableState.update { it.copy(isAuthorizing = true, loginUrl = url, error = null) }
                    attempt.job = scope.launch { finishSignIn(attempt) }
                }
                url
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw UserFacingLlmException("无法启动 ChatGPT 登录，请稍后重试") }
        }
    }

    fun cancelSignIn() {
        synchronized(attemptLock) {
            pending?.let { it.server.close(); it.job?.cancel() }
            pending = null
            mutableState.update { it.copy(isAuthorizing = false, loginUrl = null) }
        }
    }

    private suspend fun finishSignIn(attempt: PendingSignIn) {
        var issuedId: String? = null
        try {
            val callback = attempt.server.awaitCallback()
            if (callback.error != null) throw AuthProtocolException(
                if (callback.error == "access_denied") "已取消 ChatGPT 授权，可稍后重新登录" else "ChatGPT 授权未完成，请重新登录"
            )
            issuedId = ChatGptAuthProtocol.issuedClientId(attempt.request.clientId, callback.clientId)
            val response = transport.exchange(attempt.request, callback.code!!, issuedId)
            val tokens = parseTokenResponse(response, now())
            val idToken = tokens.idToken ?: throw AuthProtocolException("ChatGPT 未返回身份凭据，请重新登录")
            val identity = transport.verifyIdentity(idToken, issuedId, attempt.request.nonce, now())
            if (attempt.selectedSubject != null && identity.subject != attempt.selectedSubject)
                throw AuthProtocolException("登录账号与所选的 ChatGPT 账号不一致，请重试")
            mutex.withLock {
                val current = requireSnapshot()
                if (!isCurrent(attempt)) return@withLock
                val existing = current.accounts.find { it.clientId == issuedId }
                if (existing != null && existing.subject != identity.subject)
                    throw AuthProtocolException("ChatGPT 账号身份与原有注册信息不一致")
                val welcome = PLAN_SCOPE in tokens.scopes && existing?.welcomeShown != true
                val replacement = StoredAccount(issuedId, identity.email, identity.subject, tokens,
                    welcomeShown = welcome || existing?.welcomeShown == true)
                val next = current.copy(activeId = issuedId, accounts = current.accounts.filterNot { it.clientId == issuedId } + replacement)
                // Cancellation/logout are serialized against the final durable write as well as the UI update.
                withContext(NonCancellable + Dispatchers.IO) {
                    synchronized(attemptLock) {
                        if (pending !== attempt) return@synchronized
                        store.write(next)
                        snapshot = next
                        retryClientId = null
                        pending = null
                        publish(next, welcome = welcome)
                        mutableState.update { it.copy(isAuthorizing = false, loginUrl = null, error = null) }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            synchronized(attemptLock) {
                if (pending === attempt) {
                    if (failure is OAuthEndpointException && failure.errorCode == "invalid_grant" &&
                        attempt.selectedSubject == null && issuedId != null) retryClientId = issuedId
                    pending = null
                    mutableState.update { it.copy(isAuthorizing = false, loginUrl = null, error = safeFailure(failure)) }
                }
            }
        } finally { attempt.server.close() }
    }

    suspend fun getAccessToken(): String {
        ready.await()
        return mutex.withLock {
            val current = requireSnapshot()
            val account = current.accounts.find { it.clientId == current.activeId }
                ?: throw UserFacingLlmException("请先在 AI 设置中登录 ChatGPT")
            val tokens = account.tokens ?: throw UserFacingLlmException("ChatGPT 登录已失效，请重新登录此账号")
            if (PLAN_SCOPE !in tokens.scopes) throw UserFacingLlmException("请在 AI 设置中允许 PennyPilot 使用 ChatGPT 套餐额度")
            if (tokens.expiresAt > now() + 60 || (tokens.expiresAt > now() && tokens.earliestRefreshAt > now())) return@withLock tokens.accessToken
            val refreshToken = tokens.refreshToken
            if (refreshToken == null) {
                clearTokens(current, account.clientId)
                throw UserFacingLlmException("ChatGPT 登录已到期，请重新登录此账号")
            }
            // Once refresh starts, finish storing a rotated token even if the requesting screen closes.
            withContext(NonCancellable) {
                try {
                    val response = transport.refresh(account.clientId, refreshToken)
                    val refreshed = parseTokenResponse(response, now(), tokens)
                    // A newly returned ID token must be verified; an unchanged retained hint is not reused as authentication.
                    response["id_token"]?.let {
                        val identity = transport.verifyIdentity(refreshed.idToken!!, account.clientId, null, now())
                        if (identity.subject != account.subject) throw AuthProtocolException("ChatGPT 续期返回的账号不一致，请重新登录")
                    }
                    persist(current.copy(accounts = current.accounts.map {
                        if (it.clientId == account.clientId) it.copy(tokens = refreshed) else it
                    }))
                    if (PLAN_SCOPE !in refreshed.scopes) throw UserFacingLlmException("ChatGPT 套餐授权未启用，请在 AI 设置中重新授权")
                    refreshed.accessToken
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: OAuthEndpointException) {
                    if (failure.errorCode in TERMINAL_REFRESH_ERRORS) {
                        clearTokens(current, account.clientId)
                        throw UserFacingLlmException("ChatGPT 登录已失效，请重新登录此账号")
                    }
                    throw UserFacingLlmException(safeFailure(failure))
                } catch (failure: UserFacingLlmException) { throw failure }
                catch (failure: Exception) { throw UserFacingLlmException(safeFailure(failure)) }
            }
        }
    }

    suspend fun selectAccount(accountId: String) {
        ready.await()
        mutex.withLock {
            val current = requireSnapshot()
            val account = current.accounts.find { it.clientId == accountId }
                ?: throw UserFacingLlmException("未找到保存的 ChatGPT 账号")
            if (account.tokens == null) throw UserFacingLlmException("此 ChatGPT 账号已退出，请重新登录")
            cancelSignIn()
            persist(current.copy(activeId = accountId))
        }
    }

    suspend fun signOut(accountId: String): String? {
        ready.await()
        return mutex.withLock {
            val current = requireSnapshot()
            val account = current.accounts.find { it.clientId == accountId } ?: return@withLock null
            cancelSignIn()
            // Complete local cleanup even if a UI coroutine is cancelled during the remote attempt.
            withContext(NonCancellable) {
                val revoked = try {
                    account.tokens?.refreshToken?.let { transport.revoke(accountId, it) } ?: (account.tokens == null)
                } catch (_: Exception) { false }
                clearTokens(current, accountId)
                if (revoked) null else "已在本机退出；未能确认远程会话已撤销，可前往 ChatGPT 设置断开 PennyPilot"
            }
        }
    }

    fun dismissWelcome() { mutableState.update { it.copy(showWelcome = false) } }

    private fun requireSnapshot(): AuthSnapshot = snapshot
        ?: throw UserFacingLlmException("ChatGPT 安全存储不可用，无法读取或保存登录凭据")
    private fun isCurrent(attempt: PendingSignIn): Boolean = synchronized(attemptLock) { pending === attempt }
    private suspend fun clearTokens(current: AuthSnapshot, accountId: String) = persist(current.copy(
        activeId = current.activeId.takeUnless { it == accountId },
        accounts = current.accounts.map { if (it.clientId == accountId) it.copy(tokens = null) else it }
    ))
    private suspend fun persist(next: AuthSnapshot) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                store.write(next)
                snapshot = next
                publish(next)
            }
        } catch (_: Exception) { throw UserFacingLlmException("ChatGPT 安全凭据保存失败，请重试") }
    }
    private fun publish(value: AuthSnapshot, welcome: Boolean = false) {
        val accounts = value.accounts.map { it.presentation() }
        mutableState.update { it.copy(accounts = accounts, activeAccount = accounts.find { account -> account.id == value.activeId },
            showWelcome = welcome || (it.showWelcome && it.activeAccount?.id == value.activeId)) }
    }
    private fun safeFailure(failure: Exception): String = when (failure) {
        is AuthProtocolException -> failure.safeMessage
        is OAuthEndpointException -> when (failure.errorCode) {
            "invalid_client" -> "ChatGPT 应用注册配置无效，请检查登录配置"
            "invalid_grant" -> "ChatGPT 授权已失效，请重新登录"
            else -> "ChatGPT 认证服务暂不可用，请稍后重试"
        }
        else -> "ChatGPT 登录处理失败，请稍后重试"
    }
    private companion object {
        val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused")
    }
}

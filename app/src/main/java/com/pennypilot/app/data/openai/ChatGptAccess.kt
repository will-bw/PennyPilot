package com.pennypilot.app.data.openai

import com.pennypilot.app.data.openai.auth.ChatGptAuthManager

/** Small adapter makes account races testable without Android or real credentials. */
internal interface ChatGptAccess {
    suspend fun awaitReady() = Unit
    fun account(): AccountAccess?
    suspend fun token(): String
}

internal data class AccountAccess(val id: String, val signedIn: Boolean, val canUsePlan: Boolean)

internal class AuthManagerAccess(private val auth: ChatGptAuthManager) : ChatGptAccess {
    override suspend fun awaitReady() = auth.awaitReady()
    override fun account(): AccountAccess? = auth.state.value.activeAccount?.let {
        AccountAccess(it.id, it.isSignedIn, it.canUsePlan)
    }
    override suspend fun token(): String = auth.getAccessToken()
}

internal fun ChatGptAccess.requireAccount(): String {
    val account = account() ?: throw OpenAiErrors.notSignedIn()
    if (!account.signedIn) throw OpenAiErrors.notSignedIn()
    if (!account.canUsePlan) throw OpenAiErrors.planNotGranted()
    return account.id
}

internal fun ChatGptAccess.requireSameAccount(id: String) {
    val current = account()
    if (current?.id != id || !current.signedIn || !current.canUsePlan) {
        throw OpenAiErrors.accountChanged()
    }
}

internal fun requireBearerToken(token: String): String {
    if (token.isBlank() || token.any { it <= ' ' || it >= '\u007f' }) throw OpenAiErrors.notSignedIn()
    return token
}

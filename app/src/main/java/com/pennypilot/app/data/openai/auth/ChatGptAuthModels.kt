package com.pennypilot.app.data.openai.auth

import kotlinx.serialization.Serializable

/** Safe presentation data: credentials never enter UI state. The issued client ID identifies a registration. */
data class ChatGptAccount(
    val id: String,
    val email: String,
    val subject: String,
    val canUsePlan: Boolean,
    val isSignedIn: Boolean
)

data class ChatGptAuthState(
    val accounts: List<ChatGptAccount> = emptyList(),
    val activeAccount: ChatGptAccount? = null,
    val isAuthorizing: Boolean = false,
    val loginUrl: String? = null,
    val error: String? = null,
    val showWelcome: Boolean = false
)

@Serializable
internal data class AuthSnapshot(
    val hostId: String,
    val activeId: String? = null,
    val accounts: List<StoredAccount> = emptyList()
)

@Serializable
internal data class StoredAccount(
    val clientId: String,
    val email: String,
    val subject: String,
    val tokens: StoredTokens? = null,
    val welcomeShown: Boolean = false
) {
    fun presentation() = ChatGptAccount(
        id = clientId, email = email, subject = subject,
        canUsePlan = tokens?.scopes?.contains(PLAN_SCOPE) == true,
        isSignedIn = tokens != null
    )
}

@Serializable
internal class StoredTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val scopes: Set<String>,
    val expiresAt: Long,
    val earliestRefreshAt: Long = 0
) {
    override fun toString() = "StoredTokens([REDACTED])"
}

internal const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
internal const val OPENAI_RESOURCE = "https://api.openai.com/v1"
internal const val OPENAI_ISSUER = "https://auth.openai.com"
internal const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"

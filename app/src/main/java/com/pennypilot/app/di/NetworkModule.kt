package com.pennypilot.app.di

import android.content.Context
import com.pennypilot.app.data.llm.DynamicLlmClient
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.settings.SecureKeyStore
import com.pennypilot.app.data.openai.auth.ChatGptAuthManager
import dagger.hilt.EntryPoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideSecureKeyStore(@ApplicationContext context: Context): SecureKeyStore {
        return SecureKeyStore(context)
    }

    /**
     * 提供动态路由的 LLM 客户端：实时跟随用户在 AI 设置中手动选择的供应商，
     * 切换后下一次请求立即生效，无需重启。
     */
    @Provides
    @Singleton
    fun provideLLMClient(keyStore: SecureKeyStore, auth: ChatGptAuthManager): LLMClient {
        return DynamicLlmClient(keyStore, auth)
    }
}

/** WorkManager 的普通 Worker 也复用同一个认证会话，避免并发刷新旋转令牌。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiClientEntryPoint {
    fun llmClient(): LLMClient
}

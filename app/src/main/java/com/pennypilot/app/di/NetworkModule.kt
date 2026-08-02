package com.pennypilot.app.di

import android.content.Context
import com.pennypilot.app.data.llm.DeepSeekClient
import com.pennypilot.app.data.llm.LLMClient
import com.pennypilot.app.data.llm.QwenClient
import com.pennypilot.app.data.settings.SecureKeyStore
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

    @Provides
    @Singleton
    fun provideLLMClient(keyStore: SecureKeyStore): LLMClient {
        return when (keyStore.activeProvider) {
            "qwen" -> QwenClient(
                apiKey = keyStore.qwenKey,
                baseUrl = keyStore.qwenBaseUrl,
                model = keyStore.qwenModel
            )
            else -> DeepSeekClient(
                apiKey = keyStore.deepSeekKey,
                model = keyStore.deepSeekModel
            )
        }
    }
}

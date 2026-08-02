package com.pennypilot.app.di

import android.content.Context
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.ParseRuleDao
import com.pennypilot.app.data.db.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val db = AppDatabase.build(context)
        // Seed preset categories on first launch
        CoroutineScope(Dispatchers.IO).launch {
            if (db.categoryDao().maxColorIndex() == -1) {
                db.categoryDao().insertAll(AppDatabase.PRESET_CATEGORIES)
            }
            if (db.parseRuleDao().count() == 0) {
                db.parseRuleDao().insertAll(AppDatabase.PRESET_RULES)
            }
        }
        return db
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideParseRuleDao(db: AppDatabase): ParseRuleDao = db.parseRuleDao()

    @Provides
    fun provideInsightDao(db: AppDatabase): InsightDao = db.insightDao()
}

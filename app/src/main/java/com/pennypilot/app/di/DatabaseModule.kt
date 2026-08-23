package com.pennypilot.app.di

import android.content.Context
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.dao.CategoryDao
import com.pennypilot.app.data.db.dao.InsightDao
import com.pennypilot.app.data.db.dao.MonthlyReportDao
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
        // 记录单例引用，供非 Hilt 组件（Service / Receiver / Worker）通过 AppDatabaseHolder 访问
        AppDatabaseHolder.set(db)
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

    @Provides
    fun provideMonthlyReportDao(db: AppDatabase): MonthlyReportDao = db.monthlyReportDao()
}

/**
 * Hilt 单例数据库的静态持有器：供无法注入的组件
 * （NotificationListenerService / BroadcastReceiver / Worker）访问。
 * 由 [DatabaseModule.provideDatabase] 在首次创建时写入。
 */
object AppDatabaseHolder {
    @Volatile
    private var instance: AppDatabase? = null

    fun set(db: AppDatabase) {
        instance = db
    }

    /**
     * 优先返回 Hilt 单例；若在 Hilt 首次注入前被访问
     * （如服务先于页面启动），则创建并缓存同一实例作为兜底。
     */
    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: AppDatabase.build(context.applicationContext).also { instance = it }
        }
}

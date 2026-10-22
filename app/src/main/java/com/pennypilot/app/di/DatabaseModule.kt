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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabaseHolder.get(context)

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
 * Hilt 和系统组件通过同一个入口创建，避免两个连接池和初始化竞态。
 */
object AppDatabaseHolder {
    @Volatile
    private var instance: AppDatabase? = null

    /**
     * 优先返回 Hilt 单例；若在 Hilt 首次注入前被访问
     * （如服务先于页面启动），则创建并缓存同一实例作为兜底。
     */
    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: AppDatabase.build(context.applicationContext).also { instance = it }
        }
}

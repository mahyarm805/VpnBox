package com.vpnbox.di

import android.content.Context
import com.vpnbox.data.api.ApiClient
import com.vpnbox.data.db.AppDatabase
import com.vpnbox.data.db.ProxyChainDao
import com.vpnbox.data.db.ServerDao
import com.vpnbox.data.repository.ServerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideServerDao(database: AppDatabase): ServerDao {
        return database.serverDao()
    }

    @Provides
    @Singleton
    fun provideProxyChainDao(database: AppDatabase): ProxyChainDao {
        return database.proxyChainDao()
    }

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: ServerDao,
        proxyChainDao: ProxyChainDao
    ): ServerRepository {
        return ServerRepository(serverDao, proxyChainDao)
    }

    @Provides
    @Singleton
    fun provideApiClient(): ApiClient {
        return ApiClient()
    }
}

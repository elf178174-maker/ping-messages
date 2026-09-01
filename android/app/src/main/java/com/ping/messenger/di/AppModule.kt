package com.ping.messenger.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * A scope that outlives any single screen, used for work that must finish even if the user
     * navigates away (sending a queued message, flushing read receipts).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        dispatchers: com.ping.messenger.core.common.DispatcherProvider,
    ): CoroutineScope = CoroutineScope(SupervisorJob()) + dispatchers.io

    @Provides
    @Singleton
    fun provideDispatchers(): com.ping.messenger.core.common.DispatcherProvider =
        com.ping.messenger.core.common.DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideAppContext(@ApplicationContext context: Context): Context = context
}

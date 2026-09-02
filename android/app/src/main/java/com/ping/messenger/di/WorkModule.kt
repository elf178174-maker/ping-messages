package com.ping.messenger.di

import com.ping.messenger.core.work.SyncScheduler
import com.ping.messenger.data.repository.SyncTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the sync trigger.
 *
 * Repositories depend on the [SyncTrigger] interface rather than on WorkManager directly, so
 * the data layer stays testable without an Android scheduler — a test supplies a no-op trigger
 * and asserts on the outbox rows instead.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {

    @Binds
    @Singleton
    abstract fun bindSyncTrigger(impl: SyncScheduler): SyncTrigger
}

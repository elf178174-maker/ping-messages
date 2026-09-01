package com.ping.messenger.di

import com.ping.messenger.core.backup.BackupDestination
import com.ping.messenger.core.backup.LocalBackupDestination
import com.ping.messenger.data.repository.AuthRepositoryImpl
import com.ping.messenger.data.repository.CallRepositoryImpl
import com.ping.messenger.data.repository.ConversationRepositoryImpl
import com.ping.messenger.data.repository.GroupRepositoryImpl
import com.ping.messenger.data.repository.MediaRepositoryImpl
import com.ping.messenger.data.repository.MessageRepositoryImpl
import com.ping.messenger.data.repository.SettingsRepositoryImpl
import com.ping.messenger.data.repository.StatusRepositoryImpl
import com.ping.messenger.data.repository.UserRepositoryImpl
import com.ping.messenger.domain.repository.AuthRepository
import com.ping.messenger.domain.repository.CallRepository
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.GroupRepository
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.SettingsRepository
import com.ping.messenger.domain.repository.StatusRepository
import com.ping.messenger.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds @Singleton
    abstract fun bindStatusRepository(impl: StatusRepositoryImpl): StatusRepository

    @Binds @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    /**
     * The only backup destination this build ships. Swapping in a cloud provider is a one-line
     * change here — see docs/BACKUP.md.
     */
    @Binds @Singleton
    abstract fun bindBackupDestination(impl: LocalBackupDestination): BackupDestination
}

@Module
@InstallIn(SingletonComponent::class)
object MapperModule {
    @Provides
    @Singleton
    fun provideEntityMapper(json: Json) = com.ping.messenger.data.mapper.EntityMapper(json)
}

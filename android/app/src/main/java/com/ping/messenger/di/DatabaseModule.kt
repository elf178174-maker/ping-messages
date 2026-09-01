package com.ping.messenger.di

import android.content.Context
import androidx.room.Room
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.crypto.TinkCryptoService
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.CallDao
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.DeviceSessionDao
import com.ping.messenger.data.local.dao.FolderDao
import com.ping.messenger.data.local.dao.GroupDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.OutboxDao
import com.ping.messenger.data.local.dao.ReminderDao
import com.ping.messenger.data.local.dao.ScheduledMessageDao
import com.ping.messenger.data.local.dao.StatusDao
import com.ping.messenger.data.local.dao.UserDao
import dagger.Binds
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
    fun provideDatabase(@ApplicationContext context: Context): PingDatabase =
        Room.databaseBuilder(context, PingDatabase::class.java, PingDatabase.NAME)
            .addMigrations(*PingDatabase.MIGRATIONS)
            // Foreign keys are what make "delete a conversation" a single statement instead of
            // a hand-written cascade that eventually misses a table.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun provideUserDao(db: PingDatabase): UserDao = db.userDao()
    @Provides fun provideConversationDao(db: PingDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: PingDatabase): MessageDao = db.messageDao()
    @Provides fun provideGroupDao(db: PingDatabase): GroupDao = db.groupDao()
    @Provides fun provideStatusDao(db: PingDatabase): StatusDao = db.statusDao()
    @Provides fun provideCallDao(db: PingDatabase): CallDao = db.callDao()
    @Provides fun provideOutboxDao(db: PingDatabase): OutboxDao = db.outboxDao()
    @Provides fun provideFolderDao(db: PingDatabase): FolderDao = db.folderDao()
    @Provides fun provideScheduledDao(db: PingDatabase): ScheduledMessageDao = db.scheduledMessageDao()
    @Provides fun provideReminderDao(db: PingDatabase): ReminderDao = db.reminderDao()
    @Provides fun provideDeviceSessionDao(db: PingDatabase): DeviceSessionDao = db.deviceSessionDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @Binds
    @Singleton
    abstract fun bindCryptoService(impl: TinkCryptoService): CryptoService
}

package com.ping.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import com.ping.messenger.data.local.entity.AttachmentEntity
import com.ping.messenger.data.local.entity.CallRecordEntity
import com.ping.messenger.data.local.entity.ChatFolderEntity
import com.ping.messenger.data.local.entity.ChatFolderMemberEntity
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.DeviceSessionEntity
import com.ping.messenger.data.local.entity.GroupEntity
import com.ping.messenger.data.local.entity.GroupMemberEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.MessageFtsEntity
import com.ping.messenger.data.local.entity.OutboxEntity
import com.ping.messenger.data.local.entity.PollEntity
import com.ping.messenger.data.local.entity.PollOptionEntity
import com.ping.messenger.data.local.entity.PollVoteEntity
import com.ping.messenger.data.local.entity.ReactionEntity
import com.ping.messenger.data.local.entity.ReceiptEntity
import com.ping.messenger.data.local.entity.ReminderEntity
import com.ping.messenger.data.local.entity.ScheduledMessageEntity
import com.ping.messenger.data.local.entity.StatusPostEntity
import com.ping.messenger.data.local.entity.StatusViewEntity
import com.ping.messenger.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        AttachmentEntity::class,
        ReactionEntity::class,
        ReceiptEntity::class,
        PollEntity::class,
        PollOptionEntity::class,
        PollVoteEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        StatusPostEntity::class,
        StatusViewEntity::class,
        CallRecordEntity::class,
        OutboxEntity::class,
        ChatFolderEntity::class,
        ChatFolderMemberEntity::class,
        ScheduledMessageEntity::class,
        ReminderEntity::class,
        DeviceSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PingDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun statusDao(): StatusDao
    abstract fun callDao(): CallDao
    abstract fun outboxDao(): OutboxDao
    abstract fun folderDao(): FolderDao
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun reminderDao(): ReminderDao
    abstract fun deviceSessionDao(): DeviceSessionDao

    companion object {
        const val NAME = "ping.db"

        /**
         * Migrations live here as the schema evolves. Version 1 is the initial schema, so the
         * list is empty; `exportSchema = true` writes app/schemas/1.json, which is what future
         * migrations get validated against.
         *
         * Destructive fallback is deliberately never enabled: silently wiping a user's message
         * history because a migration was forgotten is not an acceptable failure mode.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()
    }
}

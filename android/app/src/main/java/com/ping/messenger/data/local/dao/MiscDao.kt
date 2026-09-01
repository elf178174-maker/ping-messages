package com.ping.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.ping.messenger.data.local.entity.CallRecordEntity
import com.ping.messenger.data.local.entity.ChatFolderEntity
import com.ping.messenger.data.local.entity.ChatFolderMemberEntity
import com.ping.messenger.data.local.entity.DeviceSessionEntity
import com.ping.messenger.data.local.entity.OutboxEntity
import com.ping.messenger.data.local.entity.OutboxOperation
import com.ping.messenger.data.local.entity.ReminderEntity
import com.ping.messenger.data.local.entity.ScheduledMessageEntity
import com.ping.messenger.data.local.entity.StatusPostEntity
import com.ping.messenger.data.local.entity.StatusViewEntity
import com.ping.messenger.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------

data class StatusPostWithViews(
    @Embedded val post: StatusPostEntity,
    @Relation(parentColumn = "id", entityColumn = "statusId")
    val views: List<StatusViewEntity> = emptyList(),
)

@Dao
interface StatusDao {

    @Upsert
    suspend fun upsert(post: StatusPostEntity)

    @Upsert
    suspend fun upsertAll(posts: List<StatusPostEntity>)

    @Upsert
    suspend fun upsertViews(views: List<StatusViewEntity>)

    /**
     * Every live status post, joined to its author.
     *
     * Expiry is applied in the query as well as by the cleanup worker, so a post never lingers
     * in the UI just because the worker has not run yet.
     */
    @Transaction
    @Query("SELECT * FROM status_posts WHERE expiresAt > :now ORDER BY createdAt ASC")
    fun observeActive(now: Long): Flow<List<StatusPostWithViews>>

    @Transaction
    @Query("SELECT * FROM status_posts WHERE isMine = 1 AND expiresAt > :now ORDER BY createdAt ASC")
    fun observeMine(now: Long): Flow<List<StatusPostWithViews>>

    @Query("SELECT * FROM status_posts WHERE id = :id")
    suspend fun findById(id: String): StatusPostEntity?

    @Query("UPDATE status_posts SET seenByMe = 1 WHERE id = :id")
    suspend fun markSeen(id: String)

    @Query("DELETE FROM status_posts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM status_posts WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT * FROM status_posts WHERE expiresAt <= :now")
    suspend fun expired(now: Long): List<StatusPostEntity>

    @Query("SELECT MIN(expiresAt) FROM status_posts WHERE expiresAt > :now")
    suspend fun nextExpiryAfter(now: Long): Long?

    @Query("SELECT COUNT(*) FROM status_posts WHERE seenByMe = 0 AND isMine = 0 AND expiresAt > :now")
    fun observeUnseenCount(now: Long): Flow<Int>
}

// ---------------------------------------------------------------------------

data class CallRecordWithPeer(
    @Embedded val record: CallRecordEntity,
    @Embedded(prefix = "p_") val peer: UserEntity?,
)

@Dao
interface CallDao {

    @Upsert
    suspend fun upsert(record: CallRecordEntity)

    @Query(
        """
        SELECT c.*,
               u.id AS p_id, u.username AS p_username, u.displayName AS p_displayName,
               u.about AS p_about, u.avatarUrl AS p_avatarUrl, u.phoneNumber AS p_phoneNumber,
               u.isContact AS p_isContact, u.isBlocked AS p_isBlocked, u.isOnline AS p_isOnline,
               u.lastSeenAt AS p_lastSeenAt, u.presenceHidden AS p_presenceHidden,
               u.publicKey AS p_publicKey, u.keyFingerprint AS p_keyFingerprint,
               u.updatedAt AS p_updatedAt
        FROM call_records c
        LEFT JOIN users u ON u.id = c.peerId
        ORDER BY c.startedAt DESC
        LIMIT :limit
        """,
    )
    fun observeHistory(limit: Int = 200): Flow<List<CallRecordWithPeer>>

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun findById(id: String): CallRecordEntity?

    @Query("UPDATE call_records SET outcome = :outcome, durationSeconds = :duration WHERE id = :id")
    suspend fun finish(
        id: String,
        outcome: com.ping.messenger.domain.model.CallOutcome,
        duration: Long,
    )

    @Query("DELETE FROM call_records")
    suspend fun clearAll()

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM call_records WHERE outcome = 'MISSED' AND startedAt > :since")
    fun observeMissedSince(since: Long): Flow<Int>
}

// ---------------------------------------------------------------------------

@Dao
interface OutboxDao {

    @Upsert
    suspend fun upsert(entry: OutboxEntity): Long

    /**
     * The next batch of work the sync engine should attempt.
     *
     * [now] filters out rows still inside their exponential backoff window, so a repeatedly
     * failing operation cannot starve the ones behind it.
     */
    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY id ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 50): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE entityId = :entityId AND operation = :operation")
    suspend fun deleteFor(entityId: String, operation: OutboxOperation)

    @Query(
        """
        UPDATE outbox
        SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt, lastError = :error
        WHERE id = :id
        """,
    )
    suspend fun recordFailure(id: Long, nextAttemptAt: Long, error: String?)

    @Query("UPDATE outbox SET nextAttemptAt = 0 WHERE id = :id")
    suspend fun retryNow(id: Long)

    @Query("UPDATE outbox SET nextAttemptAt = 0")
    suspend fun retryAllNow()

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

// ---------------------------------------------------------------------------

data class FolderWithMembers(
    @Embedded val folder: ChatFolderEntity,
    @Relation(parentColumn = "id", entityColumn = "folderId")
    val members: List<ChatFolderMemberEntity> = emptyList(),
)

@Dao
interface FolderDao {

    @Upsert
    suspend fun upsert(folder: ChatFolderEntity)

    @Upsert
    suspend fun addMembers(members: List<ChatFolderMemberEntity>)

    @Transaction
    @Query("SELECT * FROM chat_folders ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderWithMembers>>

    @Query("DELETE FROM chat_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_folder_members WHERE folderId = :folderId AND conversationId = :conversationId")
    suspend fun removeMember(folderId: String, conversationId: String)

    @Query("DELETE FROM chat_folder_members WHERE folderId = :folderId")
    suspend fun clearMembers(folderId: String)
}

// ---------------------------------------------------------------------------

@Dao
interface ScheduledMessageDao {

    @Upsert
    suspend fun upsert(entry: ScheduledMessageEntity)

    @Query("SELECT * FROM scheduled_messages ORDER BY scheduledFor ASC")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE conversationId = :conversationId ORDER BY scheduledFor ASC")
    fun observeFor(conversationId: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE scheduledFor <= :now")
    suspend fun due(now: Long): List<ScheduledMessageEntity>

    @Query("SELECT MIN(scheduledFor) FROM scheduled_messages WHERE scheduledFor > :now")
    suspend fun nextDueAfter(now: Long): Long?

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReminderDao {

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE isDone = 0 ORDER BY remindAt ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE remindAt <= :now AND isDone = 0")
    suspend fun due(now: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET isDone = 1 WHERE id = :id")
    suspend fun markDone(id: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DeviceSessionDao {

    @Upsert
    suspend fun upsertAll(sessions: List<DeviceSessionEntity>)

    @Query("SELECT * FROM device_sessions ORDER BY isCurrent DESC, lastActiveAt DESC")
    fun observeAll(): Flow<List<DeviceSessionEntity>>

    @Query("DELETE FROM device_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM device_sessions WHERE isCurrent = 0")
    suspend fun deleteOthers()

    @Query("DELETE FROM device_sessions")
    suspend fun clear()
}

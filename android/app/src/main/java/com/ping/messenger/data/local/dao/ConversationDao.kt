package com.ping.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * A conversation joined with the two rows the chat list always needs alongside it: the last
 * message, and (for a direct chat) the other participant.
 *
 * Doing this as one query rather than three keeps the chat list to a single database read per
 * emission, which matters because it re-emits on every incoming message.
 */
data class ConversationWithDetails(
    @Embedded val conversation: ConversationEntity,
    @Embedded(prefix = "msg_") val lastMessage: MessageEntity?,
    @Embedded(prefix = "usr_") val otherUser: UserEntity?,
)

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE type = 'DIRECT' AND otherUserId = :userId LIMIT 1")
    suspend fun findDirectWith(userId: String): ConversationEntity?

    /**
     * The chat list. Ordered pinned-first, then by recency — the same shape as the
     * `(isArchived, isPinned, lastActivityAt)` index, so SQLite can satisfy it without a sort.
     */
    @Transaction
    @Query(
        """
        SELECT c.*,
               m.id AS msg_id, m.conversationId AS msg_conversationId, m.senderId AS msg_senderId,
               m.kind AS msg_kind, m.text AS msg_text, m.createdAt AS msg_createdAt,
               m.editedAt AS msg_editedAt, m.status AS msg_status, m.isOutgoing AS msg_isOutgoing,
               m.isStarred AS msg_isStarred, m.isDeleted AS msg_isDeleted,
               m.deletedForEveryone AS msg_deletedForEveryone,
               m.replyToId AS msg_replyToId, m.replyToSenderId AS msg_replyToSenderId,
               m.replyToSenderName AS msg_replyToSenderName, m.replyToText AS msg_replyToText,
               m.replyToKind AS msg_replyToKind, m.replyToThumbnail AS msg_replyToThumbnail,
               m.forwardedFrom AS msg_forwardedFrom, m.forwardCount AS msg_forwardCount,
               m.mentionsJson AS msg_mentionsJson, m.expiresAt AS msg_expiresAt,
               m.scheduledFor AS msg_scheduledFor, m.isEncrypted AS msg_isEncrypted,
               m.decryptionFailed AS msg_decryptionFailed, m.translatedText AS msg_translatedText,
               m.translatedFrom AS msg_translatedFrom, m.serverSeq AS msg_serverSeq,
               m.editHistoryJson AS msg_editHistoryJson, m.linkPreviewJson AS msg_linkPreviewJson,
               m.locationJson AS msg_locationJson, m.contactJson AS msg_contactJson,
               m.callEventJson AS msg_callEventJson, m.systemEventJson AS msg_systemEventJson,
               u.id AS usr_id, u.username AS usr_username, u.displayName AS usr_displayName,
               u.about AS usr_about, u.avatarUrl AS usr_avatarUrl, u.phoneNumber AS usr_phoneNumber,
               u.isContact AS usr_isContact, u.isBlocked AS usr_isBlocked, u.isOnline AS usr_isOnline,
               u.lastSeenAt AS usr_lastSeenAt, u.presenceHidden AS usr_presenceHidden,
               u.publicKey AS usr_publicKey, u.keyFingerprint AS usr_keyFingerprint,
               u.updatedAt AS usr_updatedAt
        FROM conversations c
        LEFT JOIN messages m ON m.id = c.lastMessageId
        LEFT JOIN users u ON u.id = c.otherUserId
        WHERE c.isArchived = :archived
        ORDER BY c.isPinned DESC, c.lastActivityAt DESC
        """,
    )
    fun observeConversations(archived: Boolean): Flow<List<ConversationWithDetails>>

    @Query("SELECT COUNT(*) FROM conversations WHERE isArchived = 1")
    fun observeArchivedCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM conversations WHERE isMuted = 0")
    fun observeTotalUnread(): Flow<Int>

    @Query("UPDATE conversations SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE conversations SET isArchived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query(
        """
        UPDATE conversations
        SET isMuted = :muted, mutedUntil = :until, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun setMuted(id: String, muted: Boolean, until: Long?, now: Long)

    @Query(
        """
        UPDATE conversations
        SET unreadCount = 0, mentionCount = 0, markedUnread = 0, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun clearUnread(id: String, now: Long)

    @Query("UPDATE conversations SET markedUnread = 1, updatedAt = :now WHERE id = :id")
    suspend fun markUnread(id: String, now: Long)

    @Query(
        """
        UPDATE conversations
        SET unreadCount = unreadCount + 1,
            mentionCount = mentionCount + :mentionDelta
        WHERE id = :id
        """,
    )
    suspend fun incrementUnread(id: String, mentionDelta: Int)

    @Query("UPDATE conversations SET draft = :draft, draftUpdatedAt = :now WHERE id = :id")
    suspend fun setDraft(id: String, draft: String?, now: Long)

    @Query("UPDATE conversations SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: String, notes: String)

    @Query("UPDATE conversations SET wallpaperId = :wallpaperId WHERE id = :id")
    suspend fun setWallpaper(id: String, wallpaperId: String?)

    @Query("UPDATE conversations SET pinnedMessageId = :messageId WHERE id = :id")
    suspend fun setPinnedMessage(id: String, messageId: String?)

    @Query("UPDATE conversations SET disappearingAfterMs = :durationMs WHERE id = :id")
    suspend fun setDisappearing(id: String, durationMs: Long?)

    @Query(
        """
        UPDATE conversations
        SET notificationsEnabled = :enabled, notificationSound = :sound
        WHERE id = :id
        """,
    )
    suspend fun setNotificationSettings(id: String, enabled: Boolean, sound: String?)

    @Query(
        """
        UPDATE conversations
        SET lastMessageId = :messageId, lastActivityAt = :timestamp, updatedAt = :timestamp
        WHERE id = :id AND (lastActivityAt <= :timestamp OR lastMessageId IS NULL)
        """,
    )
    suspend fun touchLastMessage(id: String, messageId: String, timestamp: Long)

    @Query("UPDATE conversations SET title = :title, avatarUrl = :avatarUrl WHERE id = :id")
    suspend fun updateHeader(id: String, title: String, avatarUrl: String?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT * FROM conversations
        WHERE title LIKE '%' || :query || '%'
        ORDER BY isPinned DESC, lastActivityAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 20): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(conversation: ConversationEntity): Long

    @Query("SELECT id FROM conversations")
    suspend fun allIds(): List<String>

    /** Export pagination for backups; ordered by id so pages cannot overlap or skip. */
    @Query("SELECT * FROM conversations ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

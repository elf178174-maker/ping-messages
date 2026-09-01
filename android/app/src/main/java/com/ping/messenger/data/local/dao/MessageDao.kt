package com.ping.messenger.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.ping.messenger.data.local.entity.AttachmentEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.MessageFtsEntity
import com.ping.messenger.data.local.entity.PollEntity
import com.ping.messenger.data.local.entity.PollOptionEntity
import com.ping.messenger.data.local.entity.PollVoteEntity
import com.ping.messenger.data.local.entity.ReactionEntity
import com.ping.messenger.data.local.entity.ReceiptEntity
import com.ping.messenger.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

/** A message with everything needed to render one bubble. */
data class MessageWithRelations(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val reactions: List<ReactionEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val receipts: List<ReceiptEntity> = emptyList(),
    // Modelled as a list because Room's @Relation is a one-to-many construct; a message has
    // at most one poll, which the mapper takes with firstOrNull().
    @Relation(parentColumn = "id", entityColumn = "messageId", entity = PollEntity::class)
    val polls: List<PollWithOptions> = emptyList(),
)

data class PollWithOptions(
    @Embedded val poll: PollEntity,
    @Relation(parentColumn = "id", entityColumn = "pollId")
    val options: List<PollOptionEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "pollId")
    val votes: List<PollVoteEntity> = emptyList(),
)

/** One hit from a full-text search, with just enough context to render a result row. */
data class MessageSearchHit(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val isGroup: Boolean,
    val avatarUrl: String?,
)

@Dao
abstract class MessageDao {

    // ---- Writes -----------------------------------------------------------

    @Upsert
    abstract suspend fun upsertMessage(message: MessageEntity)

    @Upsert
    abstract suspend fun upsertMessages(messages: List<MessageEntity>)

    @Upsert
    abstract suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Upsert
    abstract suspend fun upsertReactions(reactions: List<ReactionEntity>)

    @Upsert
    abstract suspend fun upsertReceipts(receipts: List<ReceiptEntity>)

    @Upsert
    abstract suspend fun upsertPoll(poll: PollEntity)

    @Upsert
    abstract suspend fun upsertPollOptions(options: List<PollOptionEntity>)

    @Upsert
    abstract suspend fun upsertPollVotes(votes: List<PollVoteEntity>)

    @Delete
    abstract suspend fun deleteReaction(reaction: ReactionEntity)

    @Query("DELETE FROM poll_votes WHERE pollId = :pollId AND userId = :userId")
    abstract suspend fun clearPollVotes(pollId: String, userId: String)

    /**
     * Writes a message and its full-text index entry together.
     *
     * The FTS table is maintained here rather than by a trigger so that the two writes are
     * always in one transaction — a message that exists but is unsearchable, or an index row
     * pointing at a deleted message, is worse than a slightly slower insert.
     */
    @Transaction
    open suspend fun saveMessage(
        message: MessageEntity,
        attachments: List<AttachmentEntity> = emptyList(),
    ) {
        upsertMessage(message)
        if (attachments.isNotEmpty()) upsertAttachments(attachments)
        reindex(message)
    }

    @Transaction
    open suspend fun saveMessages(messages: List<MessageEntity>) {
        upsertMessages(messages)
        messages.forEach { reindex(it) }
    }

    @Transaction
    open suspend fun reindex(message: MessageEntity) {
        deleteFts(message.id)
        val searchable = message.text.takeIf { it.isNotBlank() && !message.isDeleted }
        if (searchable != null) {
            insertFts(
                MessageFtsEntity(
                    messageId = message.id,
                    conversationId = message.conversationId,
                    body = searchable,
                ),
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFts(entity: MessageFtsEntity)

    @Query("DELETE FROM message_fts WHERE messageId = :messageId")
    abstract suspend fun deleteFts(messageId: String)

    // ---- Reads ------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun findById(id: String): MessageWithRelations?

    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    abstract fun observeById(id: String): Flow<MessageWithRelations?>

    /**
     * The transcript, newest first.
     *
     * Paging keeps a long history off the heap: only the pages the user has actually scrolled
     * to are materialised, and Room invalidates the source when any message row changes.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND scheduledFor IS NULL
        ORDER BY createdAt DESC, id DESC
        """,
    )
    abstract fun pagedForConversation(conversationId: String): PagingSource<Int, MessageWithRelations>

    @Transaction
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND scheduledFor IS NULL
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecent(conversationId: String, limit: Int): Flow<List<MessageWithRelations>>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 'READ'
        """,
    )
    abstract suspend fun unreadCount(conversationId: String): Int

    @Transaction
    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY createdAt DESC")
    abstract fun observeStarred(): Flow<List<MessageWithRelations>>

    @Query("SELECT * FROM messages WHERE status IN ('PENDING', 'SENDING') ORDER BY createdAt ASC")
    abstract suspend fun pendingMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND id = :messageId")
    abstract suspend fun findRaw(conversationId: String, messageId: String): MessageEntity?

    @Query("SELECT MAX(serverSeq) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun latestServerSeq(conversationId: String): Long?

    /** Paging backwards through history starts from the oldest row already held locally. */
    @Query("SELECT MIN(serverSeq) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun oldestServerSeq(conversationId: String): Long?

    /**
     * Index of a message inside the transcript ordering, used to scroll to a search hit or a
     * reply target without loading everything in between.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversationId = :conversationId
          AND scheduledFor IS NULL
          AND (createdAt > :createdAt OR (createdAt = :createdAt AND id > :messageId))
        """,
    )
    abstract suspend fun positionOf(conversationId: String, messageId: String, createdAt: Long): Int

    // ---- Mutations --------------------------------------------------------

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: String, status: MessageStatus)

    /**
     * Applies a receipt without ever moving a message backwards through its lifecycle: a
     * DELIVERED receipt that arrives after the READ receipt is ignored.
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE id = :id
          AND CASE status
                WHEN 'PENDING' THEN 0 WHEN 'SENDING' THEN 1 WHEN 'SENT' THEN 2
                WHEN 'DELIVERED' THEN 3 WHEN 'READ' THEN 4 ELSE -1 END
              < CASE :status
                WHEN 'PENDING' THEN 0 WHEN 'SENDING' THEN 1 WHEN 'SENT' THEN 2
                WHEN 'DELIVERED' THEN 3 WHEN 'READ' THEN 4 ELSE -1 END
        """,
    )
    abstract suspend fun advanceStatus(id: String, status: MessageStatus)

    @Query(
        """
        UPDATE messages SET status = 'READ'
        WHERE conversationId = :conversationId AND isOutgoing = 1 AND status IN ('SENT', 'DELIVERED')
        """,
    )
    abstract suspend fun markOutgoingRead(conversationId: String)

    @Query(
        """
        UPDATE messages SET status = 'READ'
        WHERE conversationId = :conversationId AND isOutgoing = 0 AND status != 'READ'
        """,
    )
    abstract suspend fun markIncomingRead(conversationId: String)

    @Query("UPDATE messages SET isStarred = :starred WHERE id = :id")
    abstract suspend fun setStarred(id: String, starred: Boolean)

    @Query(
        """
        UPDATE messages
        SET text = :text, editedAt = :editedAt, editHistoryJson = :history
        WHERE id = :id
        """,
    )
    abstract suspend fun applyEdit(id: String, text: String, editedAt: Long, history: String?)

    @Transaction
    open suspend fun edit(id: String, text: String, editedAt: Long, history: String?) {
        applyEdit(id, text, editedAt, history)
        findRawById(id)?.let { reindex(it) }
    }

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun findRawById(id: String): MessageEntity?

    @Query(
        """
        UPDATE messages
        SET isDeleted = 1, deletedForEveryone = :forEveryone, text = '', translatedText = NULL
        WHERE id = :id
        """,
    )
    abstract suspend fun applySoftDelete(id: String, forEveryone: Boolean)

    @Transaction
    open suspend fun softDelete(id: String, forEveryone: Boolean) {
        applySoftDelete(id, forEveryone)
        deleteFts(id)
        deleteAttachmentsFor(id)
    }

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    abstract suspend fun deleteAttachmentsFor(messageId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    abstract suspend fun deleteHard(id: String)

    @Transaction
    open suspend fun purge(id: String) {
        deleteFts(id)
        deleteHard(id)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteAllIn(conversationId: String)

    @Query("UPDATE messages SET translatedText = :text, translatedFrom = :from WHERE id = :id")
    abstract suspend fun setTranslation(id: String, text: String?, from: String?)

    @Query("UPDATE messages SET serverSeq = :seq, status = :status WHERE id = :id")
    abstract suspend fun confirmSent(id: String, seq: Long?, status: MessageStatus)

    // ---- Disappearing messages -------------------------------------------

    @Query("SELECT id FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    abstract suspend fun expiredIds(now: Long): List<String>

    @Query("SELECT MIN(expiresAt) FROM messages WHERE expiresAt IS NOT NULL AND expiresAt > :now")
    abstract suspend fun nextExpiryAfter(now: Long): Long?

    @Transaction
    open suspend fun deleteExpired(now: Long): Int {
        val ids = expiredIds(now)
        ids.forEach { purge(it) }
        return ids.size
    }

    // ---- Scheduled --------------------------------------------------------

    @Query("SELECT * FROM messages WHERE scheduledFor IS NOT NULL ORDER BY scheduledFor ASC")
    abstract fun observeScheduled(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE scheduledFor IS NOT NULL AND scheduledFor <= :now")
    abstract suspend fun dueScheduled(now: Long): List<MessageEntity>

    @Query("UPDATE messages SET scheduledFor = NULL, createdAt = :now, status = 'PENDING' WHERE id = :id")
    abstract suspend fun releaseScheduled(id: String, now: Long)

    // ---- Search -----------------------------------------------------------

    /**
     * Full-text search across every conversation.
     *
     * [query] must already be a sanitised FTS MATCH expression — see
     * [com.ping.messenger.core.common.FtsQuery.sanitise], which strips the operators that would
     * otherwise let a stray apostrophe throw a syntax error.
     */
    @Query(
        """
        SELECT f.messageId AS messageId,
               m.conversationId AS conversationId,
               c.title AS conversationTitle,
               m.senderId AS senderId,
               m.text AS body,
               m.createdAt AS createdAt,
               (c.type = 'GROUP') AS isGroup,
               c.avatarUrl AS avatarUrl
        FROM message_fts f
        JOIN messages m ON m.id = f.messageId
        JOIN conversations c ON c.id = m.conversationId
        WHERE message_fts MATCH :query AND m.isDeleted = 0
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchAll(query: String, limit: Int = 60): List<MessageSearchHit>

    @Query(
        """
        SELECT f.messageId AS messageId,
               m.conversationId AS conversationId,
               c.title AS conversationTitle,
               m.senderId AS senderId,
               m.text AS body,
               m.createdAt AS createdAt,
               (c.type = 'GROUP') AS isGroup,
               c.avatarUrl AS avatarUrl
        FROM message_fts f
        JOIN messages m ON m.id = f.messageId
        JOIN conversations c ON c.id = m.conversationId
        WHERE message_fts MATCH :query
          AND m.conversationId = :conversationId
          AND m.isDeleted = 0
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun searchInConversation(
        conversationId: String,
        query: String,
        limit: Int = 200,
    ): List<MessageSearchHit>

    // ---- Media gallery ----------------------------------------------------

    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.messageId
        WHERE a.conversationId = :conversationId
          AND a.kind IN ('IMAGE', 'VIDEO', 'GIF')
          AND m.isDeleted = 0
        ORDER BY a.createdAt DESC
        """,
    )
    abstract fun observeGalleryMedia(conversationId: String): Flow<List<AttachmentEntity>>

    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.messageId
        WHERE a.conversationId = :conversationId
          AND a.kind IN ('DOCUMENT', 'AUDIO')
          AND m.isDeleted = 0
        ORDER BY a.createdAt DESC
        """,
    )
    abstract fun observeGalleryDocuments(conversationId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    abstract suspend fun findAttachment(id: String): AttachmentEntity?

    @Query(
        """
        UPDATE attachments
        SET transferState = :state, transferProgress = :progress, transferError = :error
        WHERE id = :id
        """,
    )
    abstract suspend fun setTransfer(
        id: String,
        state: com.ping.messenger.domain.model.TransferState,
        progress: Float,
        error: String?,
    )

    @Query("UPDATE attachments SET localPath = :path, transferState = 'COMPLETE', transferProgress = 1.0 WHERE id = :id")
    abstract suspend fun setLocalPath(id: String, path: String)

    @Query("UPDATE attachments SET remoteUrl = :url WHERE id = :id")
    abstract suspend fun setRemoteUrl(id: String, url: String)

    // ---- Storage accounting ----------------------------------------------

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM attachments WHERE kind = :kind AND localPath IS NOT NULL")
    abstract suspend fun bytesForKind(kind: com.ping.messenger.domain.model.MessageKind): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM attachments WHERE localPath IS NOT NULL")
    abstract suspend fun totalMediaBytes(): Long

    @Query("SELECT COUNT(*) FROM messages")
    abstract suspend fun messageCount(): Int

    @Query("SELECT * FROM attachments WHERE localPath IS NOT NULL ORDER BY sizeBytes DESC LIMIT :limit")
    abstract suspend fun largestAttachments(limit: Int): List<AttachmentEntity>
}

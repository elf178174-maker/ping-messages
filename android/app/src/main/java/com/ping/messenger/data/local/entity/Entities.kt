package com.ping.messenger.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ping.messenger.domain.model.CallDirection
import com.ping.messenger.domain.model.CallOutcome
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.TransferState

/**
 * The on-device schema.
 *
 * Design notes that apply throughout:
 *
 *  - IDs are client-generated UUID strings. An outgoing message gets its id before it reaches
 *    the network so the optimistic row and the server's acknowledgement are the same row, and
 *    a retry can never duplicate it.
 *  - Every list ordering the UI performs has a covering index. The chat list orders by
 *    (pinned, lastActivityAt) and the transcript by (conversationId, createdAt); both are
 *    indexed in exactly that shape.
 *  - Deleting a conversation cascades to its messages, and deleting a message cascades to its
 *    attachments, reactions, receipts and poll rows, so cleanup is a single delete.
 */

// ---------------------------------------------------------------------------

@Entity(
    tableName = "users",
    indices = [
        Index(value = ["username"], unique = true),
        Index(value = ["isContact"]),
        Index(value = ["displayName"]),
    ],
)
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val about: String = "",
    val avatarUrl: String? = null,
    /** Only ever populated for the signed-in user, or a contact who chose to share it. */
    val phoneNumber: String? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeenAt: Long? = null,
    /** True when the peer's privacy settings hide presence from us. */
    val presenceHidden: Boolean = true,
    /** Base64 X25519 public key used to seal messages to this user. */
    val publicKey: String? = null,
    /** Fingerprint of [publicKey]; a change is surfaced as a security notice. */
    val keyFingerprint: String? = null,
    val updatedAt: Long = 0,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["isArchived", "isPinned", "lastActivityAt"]),
        Index(value = ["otherUserId"]),
        Index(value = ["type"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val type: ConversationType,
    val title: String,
    val avatarUrl: String? = null,
    /** For DIRECT conversations, the other participant. Null for groups. */
    val otherUserId: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val mutedUntil: Long? = null,
    val markedUnread: Boolean = false,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val draft: String? = null,
    val draftUpdatedAt: Long = 0,
    val lastMessageId: String? = null,
    val lastActivityAt: Long = 0,
    /** Null means disappearing messages are off. */
    val disappearingAfterMs: Long? = null,
    val wallpaperId: String? = null,
    val pinnedMessageId: String? = null,
    /** Private, never-synced notes the user keeps about this conversation. */
    val notes: String = "",
    val notificationSound: String? = null,
    val notificationsEnabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["conversationId", "isStarred"]),
        Index(value = ["senderId"]),
        Index(value = ["status"]),
        Index(value = ["scheduledFor"]),
        Index(value = ["expiresAt"]),
        Index(value = ["serverSeq"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String = "",
    val createdAt: Long,
    val editedAt: Long? = null,
    val status: MessageStatus = MessageStatus.PENDING,
    val isOutgoing: Boolean = false,
    val isStarred: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedForEveryone: Boolean = false,

    // Reply quote, denormalised so rendering a transcript never needs a second query.
    val replyToId: String? = null,
    val replyToSenderId: String? = null,
    val replyToSenderName: String? = null,
    val replyToText: String? = null,
    val replyToKind: MessageKind? = null,
    val replyToThumbnail: String? = null,

    val forwardedFrom: String? = null,
    val forwardCount: Int = 0,

    /** JSON array of user ids mentioned in [text]. */
    val mentionsJson: String? = null,
    val expiresAt: Long? = null,
    val scheduledFor: Long? = null,
    val isEncrypted: Boolean = true,
    val decryptionFailed: Boolean = false,
    val translatedText: String? = null,
    val translatedFrom: String? = null,

    /** Monotonic server-assigned sequence used to detect gaps when syncing. */
    val serverSeq: Long? = null,

    // Payloads that are small, rarely queried, and shaped differently per message kind. Storing
    // them as JSON keeps the table from growing a column per variant.
    val editHistoryJson: String? = null,
    val linkPreviewJson: String? = null,
    val locationJson: String? = null,
    val contactJson: String? = null,
    val callEventJson: String? = null,
    val systemEventJson: String? = null,
)

/**
 * Full-text index over message bodies, kept in step with [MessageEntity] by
 * [com.ping.messenger.data.local.dao.MessageDao]'s transactional writes.
 *
 * A standalone FTS table (rather than `contentEntity`) is used deliberately: message rows have
 * string primary keys, and an external-content FTS table would need an integer rowid mapping
 * that buys nothing here.
 */
@Fts4
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val messageId: String,
    val conversationId: String,
    val body: String,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["kind"]),
        Index(value = ["transferState"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val conversationId: String,
    val kind: MessageKind,
    val remoteUrl: String? = null,
    val localPath: String? = null,
    val thumbnailPath: String? = null,
    val blurHash: String? = null,
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    /** JSON array of floats, 0..1. */
    val waveformJson: String? = null,
    val transferState: TransferState = TransferState.IDLE,
    val transferProgress: Float = 0f,
    val transferError: String? = null,
    /** Symmetric key (base64) this blob was encrypted with before upload. */
    val mediaKey: String? = null,
    val createdAt: Long = 0,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "userId", "emoji"],
    indices = [Index(value = ["messageId"])],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReactionEntity(
    val messageId: String,
    val userId: String,
    val emoji: String,
    val createdAt: Long = 0,
)

enum class ReceiptType { DELIVERED, READ }

@Entity(
    tableName = "receipts",
    primaryKeys = ["messageId", "userId", "type"],
    indices = [Index(value = ["messageId"])],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReceiptEntity(
    val messageId: String,
    val userId: String,
    val type: ReceiptType,
    val at: Long,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "polls",
    indices = [Index(value = ["messageId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PollEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val question: String,
    val allowsMultipleAnswers: Boolean = false,
    val closesAt: Long? = null,
    val isClosed: Boolean = false,
)

@Entity(
    tableName = "poll_options",
    indices = [Index(value = ["pollId"])],
    foreignKeys = [
        ForeignKey(
            entity = PollEntity::class,
            parentColumns = ["id"],
            childColumns = ["pollId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PollOptionEntity(
    @PrimaryKey val id: String,
    val pollId: String,
    val text: String,
    val position: Int,
)

@Entity(
    tableName = "poll_votes",
    primaryKeys = ["optionId", "userId"],
    indices = [Index(value = ["optionId"]), Index(value = ["pollId"])],
    foreignKeys = [
        ForeignKey(
            entity = PollOptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["optionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PollVoteEntity(
    val optionId: String,
    val pollId: String,
    val userId: String,
    val at: Long,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "groups",
    indices = [Index(value = ["conversationId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val name: String,
    val description: String = "",
    val avatarUrl: String? = null,
    val createdAt: Long = 0,
    val createdById: String = "",
    val createdByName: String = "",
    val inviteCode: String? = null,
    val sendPermission: GroupPermission = GroupPermission.EVERYONE,
    val editInfoPermission: GroupPermission = GroupPermission.ADMINS_ONLY,
    val addMembersPermission: GroupPermission = GroupPermission.EVERYONE,
    val myRole: GroupRole = GroupRole.MEMBER,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "userId"],
    indices = [Index(value = ["groupId"]), Index(value = ["userId"])],
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GroupMemberEntity(
    val groupId: String,
    val userId: String,
    val role: GroupRole = GroupRole.MEMBER,
    val joinedAt: Long = 0,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "status_posts",
    indices = [
        Index(value = ["authorId", "createdAt"]),
        Index(value = ["expiresAt"]),
    ],
)
data class StatusPostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val kind: StatusKind,
    val text: String = "",
    val mediaUrl: String? = null,
    val localPath: String? = null,
    val backgroundColor: Long? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val seenByMe: Boolean = false,
    val isMine: Boolean = false,
    val durationMs: Long = 5_000,
)

@Entity(
    tableName = "status_views",
    primaryKeys = ["statusId", "userId"],
    indices = [Index(value = ["statusId"])],
    foreignKeys = [
        ForeignKey(
            entity = StatusPostEntity::class,
            parentColumns = ["id"],
            childColumns = ["statusId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StatusViewEntity(
    val statusId: String,
    val userId: String,
    val at: Long,
)

// ---------------------------------------------------------------------------

@Entity(
    tableName = "call_records",
    indices = [Index(value = ["startedAt"]), Index(value = ["peerId"])],
)
data class CallRecordEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val peerId: String,
    val isVideo: Boolean,
    val isGroup: Boolean = false,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val startedAt: Long,
    val durationSeconds: Long = 0,
    /** JSON array of display names, for group calls. */
    val participantsJson: String? = null,
)

// ---------------------------------------------------------------------------

/** What an [OutboxEntity] row asks the sync engine to do. */
enum class OutboxOperation {
    SEND_MESSAGE,
    EDIT_MESSAGE,
    DELETE_MESSAGE,
    REACT,
    UNREACT,
    READ_RECEIPT,
    STAR,
    PIN_MESSAGE,
    VOTE_POLL,
    SEND_STATUS,
    DELETE_STATUS,
    STATUS_VIEW,
    UPDATE_PROFILE,
    UPDATE_PRIVACY,
    GROUP_UPDATE,
    BLOCK_USER,
    REPORT_USER,
}

/**
 * The offline outbox.
 *
 * Every mutation the app performs is written here inside the same transaction that updates the
 * local tables, then drained by [com.ping.messenger.core.work.SyncWorker]. That is what makes
 * the app work offline without special-casing each feature: the UI only ever writes locally.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["nextAttemptAt"]),
        Index(value = ["entityId"]),
        Index(value = ["operation"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: OutboxOperation,
    /** The message / status / user id the operation acts on. */
    val entityId: String,
    val conversationId: String? = null,
    /** JSON request body. */
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
)

// ---------------------------------------------------------------------------

@Entity(tableName = "chat_folders")
data class ChatFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String? = null,
    val includeGroups: Boolean = false,
    val includeUnreadOnly: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "chat_folder_members",
    primaryKeys = ["folderId", "conversationId"],
    indices = [Index(value = ["folderId"]), Index(value = ["conversationId"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatFolderMemberEntity(
    val folderId: String,
    val conversationId: String,
)

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["scheduledFor"]), Index(value = ["conversationId"])],
)
data class ScheduledMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val text: String,
    val scheduledFor: Long,
    /** JSON array of local file paths to attach. */
    val attachmentsJson: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "reminders",
    indices = [Index(value = ["remindAt"]), Index(value = ["conversationId"])],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String? = null,
    val note: String,
    val remindAt: Long,
    val isDone: Boolean = false,
)

@Entity(tableName = "device_sessions")
data class DeviceSessionEntity(
    @PrimaryKey val id: String,
    val deviceName: String,
    val platform: String,
    val lastActiveAt: Long,
    val createdAt: Long,
    val isCurrent: Boolean,
    val ipCountry: String? = null,
)

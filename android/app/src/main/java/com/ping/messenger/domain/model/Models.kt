package com.ping.messenger.domain.model

import kotlin.time.Duration
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The domain vocabulary of Ping.
 *
 * These types are what view-models and use-cases speak. They are deliberately independent of
 * both Room entities (which carry storage concerns like foreign keys and denormalised columns)
 * and network DTOs (which carry wire-format concerns like nullable fields and ISO strings).
 */

// ---------------------------------------------------------------------------
// Users and presence
// ---------------------------------------------------------------------------

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val about: String = "",
    val avatarUrl: String? = null,
    val phoneNumber: String? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val presence: Presence = Presence.Unknown,
    val publicKey: String? = null,
) {
    val handle: String get() = "@$username"

    /** Initials used by the avatar fallback, e.g. "Ada Lovelace" -> "AL". */
    val initials: String
        get() = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { username.take(1).uppercase() }
}

sealed interface Presence {
    /** The other party's privacy settings hide their presence from us. */
    data object Unknown : Presence
    data object Online : Presence
    data class LastSeen(val at: Long) : Presence
}

// ---------------------------------------------------------------------------
// Conversations
// ---------------------------------------------------------------------------

enum class ConversationType { DIRECT, GROUP }

data class Conversation(
    val id: String,
    val type: ConversationType,
    val title: String,
    val avatarUrl: String? = null,
    val lastMessage: MessagePreview? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val mutedUntil: Long? = null,
    val markedUnread: Boolean = false,
    val draft: String? = null,
    val typingUserNames: List<String> = emptyList(),
    val otherUserId: String? = null,
    val presence: Presence = Presence.Unknown,
    val disappearingAfter: Duration? = null,
    val wallpaperId: String? = null,
    val folderIds: Set<String> = emptySet(),
    val isVerifiedEncryption: Boolean = true,
    val pinnedMessageId: String? = null,
    val updatedAt: Long = 0L,
    val notes: String = "",
) {
    val isGroup: Boolean get() = type == ConversationType.GROUP
    val hasUnread: Boolean get() = unreadCount > 0 || markedUnread
}

data class MessagePreview(
    val id: String,
    val senderId: String,
    val senderName: String?,
    val text: String,
    val kind: MessageKind,
    val timestamp: Long,
    val status: MessageStatus,
    val isOutgoing: Boolean,
)

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

enum class MessageKind {
    TEXT, IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT, CONTACT, LOCATION, POLL, GIF, SYSTEM, CALL_EVENT,
}

/**
 * The lifecycle of an outgoing message. Incoming messages are always [READ] or [DELIVERED].
 *
 * The order matters: [maxOf] is used to merge receipts arriving out of order, so a DELIVERED
 * receipt can never downgrade a message that is already READ.
 */
enum class MessageStatus {
    /** Written to the local database, not yet handed to the network. */
    PENDING,

    /** In flight, or waiting in the offline outbox. */
    SENDING,

    /** The server acknowledged it. */
    SENT,

    /** At least one of the recipient's devices has it. */
    DELIVERED,

    /** The recipient opened the conversation. */
    READ,

    /** Permanently failed; the user can retry. */
    FAILED,
    ;

    val isTerminalFailure: Boolean get() = this == FAILED
    val isInFlight: Boolean get() = this == PENDING || this == SENDING
}

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String = "",
    val senderAvatarUrl: String? = null,
    val kind: MessageKind = MessageKind.TEXT,
    val text: String = "",
    val createdAt: Long,
    val editedAt: Long? = null,
    val status: MessageStatus = MessageStatus.PENDING,
    val isOutgoing: Boolean = false,
    val isStarred: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedForEveryone: Boolean = false,
    val replyTo: MessageQuote? = null,
    val forwardedFrom: String? = null,
    val forwardCount: Int = 0,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val mentions: List<String> = emptyList(),
    val poll: Poll? = null,
    val location: GeoPoint? = null,
    val contactCard: ContactCard? = null,
    val callEvent: CallEvent? = null,
    val systemEvent: SystemEvent? = null,
    val linkPreview: LinkPreview? = null,
    val expiresAt: Long? = null,
    val scheduledFor: Long? = null,
    val isEncrypted: Boolean = true,
    val decryptionFailed: Boolean = false,
    val editHistory: List<MessageEdit> = emptyList(),
    val translatedText: String? = null,
    val deliveredTo: List<Receipt> = emptyList(),
    val readBy: List<Receipt> = emptyList(),
) {
    val isEdited: Boolean get() = editedAt != null
    val hasMedia: Boolean get() = attachments.isNotEmpty()

    /** Text worth showing in the chat list and in notifications. */
    val previewText: String
        get() = when {
            isDeleted -> ""
            text.isNotBlank() -> text
            kind == MessageKind.POLL -> poll?.question.orEmpty()
            kind == MessageKind.LOCATION -> ""
            else -> attachments.firstOrNull()?.fileName.orEmpty()
        }
}

data class MessageQuote(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val kind: MessageKind,
    val thumbnailUrl: String? = null,
)

@Serializable
data class MessageEdit(val text: String, val editedAt: Long)

data class Receipt(val userId: String, val userName: String, val at: Long)

data class Reaction(val emoji: String, val userIds: List<String>, val reactedByMe: Boolean) {
    val count: Int get() = userIds.size
}

// ---------------------------------------------------------------------------
// Attachments and media
// ---------------------------------------------------------------------------

enum class TransferState { IDLE, QUEUED, RUNNING, COMPLETE, FAILED }

data class Attachment(
    val id: String,
    val messageId: String,
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
    /** Peak amplitudes, 0..1, used to draw the voice-message waveform. */
    val waveform: List<Float> = emptyList(),
    val transferState: TransferState = TransferState.IDLE,
    val transferProgress: Float = 0f,
    val transferError: String? = null,
) {
    val isDownloaded: Boolean get() = localPath != null
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f
}

@Serializable
data class LinkPreview(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    /** Non-null while a live location share is running. */
    val liveUntil: Long? = null,
)

data class ContactCard(
    val displayName: String,
    val username: String? = null,
    val phoneNumbers: List<String> = emptyList(),
    val userId: String? = null,
)

// ---------------------------------------------------------------------------
// Polls
// ---------------------------------------------------------------------------

data class Poll(
    val id: String,
    val question: String,
    val options: List<PollOption>,
    val allowsMultipleAnswers: Boolean = false,
    val closesAt: Long? = null,
    val isClosed: Boolean = false,
) {
    val totalVotes: Int get() = options.sumOf { it.voterIds.size }
    val myVotes: List<String> get() = options.filter { it.votedByMe }.map { it.id }
}

data class PollOption(
    val id: String,
    val text: String,
    val voterIds: List<String> = emptyList(),
    val votedByMe: Boolean = false,
) {
    val voteCount: Int get() = voterIds.size
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

enum class GroupRole { MEMBER, ADMIN, OWNER }

enum class GroupPermission { EVERYONE, ADMINS_ONLY }

data class Group(
    val id: String,
    val conversationId: String,
    val name: String,
    val description: String = "",
    val avatarUrl: String? = null,
    val createdAt: Long = 0,
    val createdByName: String = "",
    val members: List<GroupMember> = emptyList(),
    val inviteCode: String? = null,
    val sendPermission: GroupPermission = GroupPermission.EVERYONE,
    val editInfoPermission: GroupPermission = GroupPermission.ADMINS_ONLY,
    val addMembersPermission: GroupPermission = GroupPermission.EVERYONE,
    val myRole: GroupRole = GroupRole.MEMBER,
) {
    val memberCount: Int get() = members.size
    val admins: List<GroupMember> get() = members.filter { it.role != GroupRole.MEMBER }
    val isAdmin: Boolean get() = myRole != GroupRole.MEMBER
    val canSend: Boolean get() = sendPermission == GroupPermission.EVERYONE || isAdmin
    val canEditInfo: Boolean get() = editInfoPermission == GroupPermission.EVERYONE || isAdmin
    val canAddMembers: Boolean get() = addMembersPermission == GroupPermission.EVERYONE || isAdmin
}

data class GroupMember(
    val userId: String,
    val displayName: String,
    val username: String,
    val avatarUrl: String? = null,
    val role: GroupRole = GroupRole.MEMBER,
    val joinedAt: Long = 0,
)

// ---------------------------------------------------------------------------
// System events shown inline in the transcript
// ---------------------------------------------------------------------------

sealed interface SystemEvent {
    data class GroupCreated(val byName: String) : SystemEvent
    data class MembersAdded(val byName: String, val names: List<String>) : SystemEvent
    data class MemberRemoved(val byName: String, val name: String) : SystemEvent
    data class MemberLeft(val name: String) : SystemEvent
    data class GroupRenamed(val byName: String, val newName: String) : SystemEvent
    data class GroupIconChanged(val byName: String) : SystemEvent
    data class AdminGranted(val byName: String, val name: String) : SystemEvent
    data class AdminRevoked(val byName: String, val name: String) : SystemEvent
    data class DisappearingChanged(val byName: String, val duration: Duration?) : SystemEvent
    data class SecurityCodeChanged(val name: String) : SystemEvent
    data object EncryptionEnabled : SystemEvent
}

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

enum class CallDirection { INCOMING, OUTGOING }

enum class CallOutcome { COMPLETED, MISSED, DECLINED, FAILED, ONGOING }

data class CallEvent(
    val callId: String,
    val isVideo: Boolean,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val durationSeconds: Long = 0,
)

data class CallRecord(
    val id: String,
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val isVideo: Boolean,
    val isGroup: Boolean = false,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val startedAt: Long,
    val durationSeconds: Long = 0,
    val participantNames: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Status / stories
// ---------------------------------------------------------------------------

enum class StatusKind { TEXT, IMAGE, VIDEO }

data class StatusPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorAvatarUrl: String? = null,
    val kind: StatusKind,
    val text: String = "",
    val mediaUrl: String? = null,
    val localPath: String? = null,
    val backgroundColor: Long? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val viewers: List<Receipt> = emptyList(),
    val seenByMe: Boolean = false,
    val isMine: Boolean = false,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt

    companion object {
        const val DEFAULT_DURATION_MS = 5_000L
        val LIFETIME: Duration = 24.hours
    }
}

/** All the status posts by one author, grouped for the story-ring UI. */
data class StatusThread(
    val authorId: String,
    val authorName: String,
    val authorAvatarUrl: String? = null,
    val posts: List<StatusPost>,
    val isMine: Boolean = false,
    val isMuted: Boolean = false,
) {
    val unseenCount: Int get() = posts.count { !it.seenByMe }
    val hasUnseen: Boolean get() = unseenCount > 0
    val latestAt: Long get() = posts.maxOfOrNull { it.createdAt } ?: 0L
}

// ---------------------------------------------------------------------------
// Privacy, security and sessions
// ---------------------------------------------------------------------------

enum class PrivacyAudience { EVERYONE, CONTACTS, NOBODY }

data class PrivacySettings(
    val lastSeen: PrivacyAudience = PrivacyAudience.CONTACTS,
    val onlineStatus: PrivacyAudience = PrivacyAudience.EVERYONE,
    val profilePhoto: PrivacyAudience = PrivacyAudience.CONTACTS,
    val about: PrivacyAudience = PrivacyAudience.CONTACTS,
    val status: PrivacyAudience = PrivacyAudience.CONTACTS,
    val groups: PrivacyAudience = PrivacyAudience.CONTACTS,
    val calls: PrivacyAudience = PrivacyAudience.EVERYONE,
    val readReceipts: Boolean = true,
    val typingIndicators: Boolean = true,
)

data class DeviceSession(
    val id: String,
    val deviceName: String,
    val platform: String,
    val lastActiveAt: Long,
    val createdAt: Long,
    val isCurrent: Boolean,
    val ipCountry: String? = null,
)

// ---------------------------------------------------------------------------
// Organisation extras (folders, scheduled messages, reminders, backups)
// ---------------------------------------------------------------------------

data class ChatFolder(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val conversationIds: Set<String> = emptySet(),
    val includeGroups: Boolean = false,
    val includeUnreadOnly: Boolean = false,
    val sortOrder: Int = 0,
) {
    companion object {
        const val ALL_ID = "__all__"
        const val UNREAD_ID = "__unread__"
        const val GROUPS_ID = "__groups__"
    }
}

data class ScheduledMessage(
    val id: String,
    val conversationId: String,
    val conversationTitle: String,
    val text: String,
    val scheduledFor: Long,
    val attachmentPaths: List<String> = emptyList(),
)

data class Reminder(
    val id: String,
    val conversationId: String,
    val messageId: String?,
    val note: String,
    val remindAt: Long,
    val isDone: Boolean = false,
)

enum class BackupState { IDLE, RUNNING, SUCCESS, FAILED }

data class BackupStatus(
    val state: BackupState = BackupState.IDLE,
    val progress: Float = 0f,
    val lastBackupAt: Long? = null,
    val lastBackupSizeBytes: Long = 0,
    val destination: String = "local",
    val includeMedia: Boolean = false,
    val error: String? = null,
)

/** Supported disappearing-message durations, in the order they appear in the picker. */
val DisappearingDurations: List<Duration?> = listOf(null, 1.days, 7.days, 90.days)

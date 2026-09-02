package com.ping.messenger.core.backup

import com.ping.messenger.data.local.entity.AttachmentEntity
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.UserEntity
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.model.TransferState
import kotlinx.serialization.Serializable

/**
 * The archive's own record types.
 *
 * These deliberately duplicate parts of the Room entities instead of serialising the entities
 * directly. An archive is a *format*: once a user has a `.pingbak` file on disk, its shape is a
 * compatibility promise. Serialising entities would silently change that promise every time a
 * column is added or renamed, and a rename would make old archives unreadable with no warning.
 *
 * What is deliberately left out: presence, unread counters, transfer progress, outbox state and
 * anything else that is a cache of the server or of a moment in time. Restoring a stale online
 * indicator would be worse than not restoring one.
 */
object ArchiveEntries {
    const val MANIFEST = "manifest.json"
    const val USERS = "users.jsonl"
    const val CONVERSATIONS = "conversations.jsonl"
    const val MESSAGES = "messages.jsonl"
    const val ATTACHMENTS = "attachments.jsonl"
    const val MEDIA_PREFIX = "media/"
}

@Serializable
data class BackupManifest(
    /** Bumped only when a reader written for an older version could misread the archive. */
    val version: Int = FORMAT_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val userCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val attachmentCount: Int,
    val includesMedia: Boolean,
    /** True when the archive is sealed with a device key rather than a passphrase. */
    val deviceKeyed: Boolean,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class ArchivedUser(
    val id: String,
    val username: String,
    val displayName: String,
    val about: String = "",
    val avatarUrl: String? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val publicKey: String? = null,
)

@Serializable
data class ArchivedConversation(
    val id: String,
    val type: String,
    val title: String,
    val avatarUrl: String? = null,
    val otherUserId: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val lastMessageId: String? = null,
    val lastActivityAt: Long = 0,
    val disappearingAfterMs: Long? = null,
    val wallpaperId: String? = null,
    val pinnedMessageId: String? = null,
    val notes: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class ArchivedMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: String,
    val text: String = "",
    val createdAt: Long,
    val editedAt: Long? = null,
    val isOutgoing: Boolean = false,
    val isStarred: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedForEveryone: Boolean = false,
    val replyToId: String? = null,
    val replyToSenderId: String? = null,
    val replyToSenderName: String? = null,
    val replyToText: String? = null,
    val forwardedFrom: String? = null,
    val mentionsJson: String? = null,
    val serverSeq: Long? = null,
    val editHistoryJson: String? = null,
    val linkPreviewJson: String? = null,
    val locationJson: String? = null,
    val contactJson: String? = null,
    val callEventJson: String? = null,
    val systemEventJson: String? = null,
)

@Serializable
data class ArchivedAttachment(
    val id: String,
    val messageId: String,
    val conversationId: String,
    val kind: String,
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val waveformJson: String? = null,
    val remoteUrl: String? = null,
    val mediaKey: String? = null,
    /** File name inside the archive's `media/` directory, when the bytes were included. */
    val mediaEntry: String? = null,
    val createdAt: Long = 0,
)

// ---------------------------------------------------------------------------
// Entity <-> archive record
// ---------------------------------------------------------------------------

internal fun UserEntity.toArchived() = ArchivedUser(
    id = id,
    username = username,
    displayName = displayName,
    about = about,
    avatarUrl = avatarUrl,
    isContact = isContact,
    isBlocked = isBlocked,
    publicKey = publicKey,
)

internal fun ArchivedUser.toEntity() = UserEntity(
    id = id,
    username = username,
    displayName = displayName,
    about = about,
    avatarUrl = avatarUrl,
    isContact = isContact,
    isBlocked = isBlocked,
    publicKey = publicKey,
)

internal fun ConversationEntity.toArchived() = ArchivedConversation(
    id = id,
    type = type.name,
    title = title,
    avatarUrl = avatarUrl,
    otherUserId = otherUserId,
    isPinned = isPinned,
    isArchived = isArchived,
    isMuted = isMuted,
    lastMessageId = lastMessageId,
    lastActivityAt = lastActivityAt,
    disappearingAfterMs = disappearingAfterMs,
    wallpaperId = wallpaperId,
    pinnedMessageId = pinnedMessageId,
    notes = notes,
    createdAt = createdAt,
)

internal fun ArchivedConversation.toEntity() = ConversationEntity(
    id = id,
    type = enumByName(type, ConversationType.DIRECT),
    title = title,
    avatarUrl = avatarUrl,
    otherUserId = otherUserId,
    isPinned = isPinned,
    isArchived = isArchived,
    isMuted = isMuted,
    lastMessageId = lastMessageId,
    lastActivityAt = lastActivityAt,
    disappearingAfterMs = disappearingAfterMs,
    wallpaperId = wallpaperId,
    pinnedMessageId = pinnedMessageId,
    notes = notes,
    createdAt = createdAt,
)

internal fun MessageEntity.toArchived() = ArchivedMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    kind = kind.name,
    text = text,
    createdAt = createdAt,
    editedAt = editedAt,
    isOutgoing = isOutgoing,
    isStarred = isStarred,
    isDeleted = isDeleted,
    deletedForEveryone = deletedForEveryone,
    replyToId = replyToId,
    replyToSenderId = replyToSenderId,
    replyToSenderName = replyToSenderName,
    replyToText = replyToText,
    forwardedFrom = forwardedFrom,
    mentionsJson = mentionsJson,
    serverSeq = serverSeq,
    editHistoryJson = editHistoryJson,
    linkPreviewJson = linkPreviewJson,
    locationJson = locationJson,
    contactJson = contactJson,
    callEventJson = callEventJson,
    systemEventJson = systemEventJson,
)

internal fun ArchivedMessage.toEntity() = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    kind = enumByName(kind, MessageKind.TEXT),
    text = text,
    createdAt = createdAt,
    editedAt = editedAt,
    // A restored message is history, not something still in flight, so it lands in a terminal
    // state. Restoring PENDING would put it back in the send queue and re-send it.
    status = if (isOutgoing) MessageStatus.SENT else MessageStatus.READ,
    isOutgoing = isOutgoing,
    isStarred = isStarred,
    isDeleted = isDeleted,
    deletedForEveryone = deletedForEveryone,
    replyToId = replyToId,
    replyToSenderId = replyToSenderId,
    replyToSenderName = replyToSenderName,
    replyToText = replyToText,
    forwardedFrom = forwardedFrom,
    mentionsJson = mentionsJson,
    // Already plaintext on this device; re-encrypting on restore would need the peer's key.
    isEncrypted = false,
    serverSeq = serverSeq,
    editHistoryJson = editHistoryJson,
    linkPreviewJson = linkPreviewJson,
    locationJson = locationJson,
    contactJson = contactJson,
    callEventJson = callEventJson,
    systemEventJson = systemEventJson,
)

internal fun AttachmentEntity.toArchived(mediaEntry: String?) = ArchivedAttachment(
    id = id,
    messageId = messageId,
    conversationId = conversationId,
    kind = kind.name,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    waveformJson = waveformJson,
    remoteUrl = remoteUrl,
    mediaKey = mediaKey,
    mediaEntry = mediaEntry,
    createdAt = createdAt,
)

internal fun ArchivedAttachment.toEntity(localPath: String?) = AttachmentEntity(
    id = id,
    messageId = messageId,
    conversationId = conversationId,
    kind = enumByName(kind, MessageKind.DOCUMENT),
    remoteUrl = remoteUrl,
    localPath = localPath,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    waveformJson = waveformJson,
    // If the bytes came back the file is complete; if they did not, it can be re-downloaded.
    transferState = if (localPath != null) TransferState.COMPLETE else TransferState.IDLE,
    transferProgress = if (localPath != null) 1f else 0f,
    mediaKey = mediaKey,
    createdAt = createdAt,
)

/**
 * Enums are stored by name so a reordering cannot reinterpret old data, and an unknown name
 * from a newer build falls back rather than throwing — one unrecognised value must not abort a
 * restore of thousands of good rows.
 */
private inline fun <reified T : Enum<T>> enumByName(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

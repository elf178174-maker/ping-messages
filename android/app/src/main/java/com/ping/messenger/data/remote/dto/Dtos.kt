package com.ping.messenger.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types.
 *
 * Kept separate from both domain models and Room entities so the server's shape can change
 * (a renamed field, a new optional block) without that change rippling into the UI. Every
 * field the server might omit is nullable with a default, so an older client never crashes on
 * a newer response.
 */

// ---- Envelope -------------------------------------------------------------

@Serializable
data class ApiErrorDto(
    val error: String,
    val message: String? = null,
    val code: String? = null,
    val fields: Map<String, String>? = null,
    val retryAfter: Long? = null,
)

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

// ---- Auth -----------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val username: String,
    val displayName: String,
    val deviceName: String,
    val platform: String = "android",
    val publicKey: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceName: String,
    val platform: String = "android",
    val publicKey: String? = null,
    /** Present only when the account has two-step verification enabled. */
    val twoStepPin: String? = null,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
    val deviceId: String,
    val emailVerified: Boolean = false,
    val requiresTwoStep: Boolean = false,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@Serializable
data class VerifyEmailRequest(val email: String, val code: String)

@Serializable
data class ResendCodeRequest(val email: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class TwoStepRequest(val pin: String?, val currentPassword: String)

@Serializable
data class UsernameAvailabilityDto(val username: String, val available: Boolean)

@Serializable
data class MessageResponse(val ok: Boolean = true, val message: String? = null)

// ---- Users ----------------------------------------------------------------

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val about: String = "",
    val avatarUrl: String? = null,
    val phoneNumber: String? = null,
    val publicKey: String? = null,
    val isOnline: Boolean = false,
    val lastSeenAt: Long? = null,
    /** True when the server withheld presence because of the peer's privacy settings. */
    val presenceHidden: Boolean = true,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val about: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class PrivacySettingsDto(
    val lastSeen: String = "CONTACTS",
    val onlineStatus: String = "EVERYONE",
    val profilePhoto: String = "CONTACTS",
    val about: String = "CONTACTS",
    val status: String = "CONTACTS",
    val groups: String = "EVERYONE",
    val calls: String = "EVERYONE",
    val readReceipts: Boolean = true,
    val typingIndicators: Boolean = true,
)

/**
 * Contact discovery. The client sends salted hashes of the phone numbers in the address book,
 * never the numbers themselves, and the server answers with the ones it recognises.
 */
@Serializable
data class ContactDiscoveryRequest(val hashes: List<String>)

@Serializable
data class ContactDiscoveryResponse(val matches: Map<String, UserDto> = emptyMap())

@Serializable
data class BlockRequest(val userId: String)

@Serializable
data class ReportRequest(
    val userId: String,
    val reason: String,
    val messageIds: List<String> = emptyList(),
    val note: String? = null,
)

// ---- Conversations and messages -------------------------------------------

@Serializable
data class ConversationDto(
    val id: String,
    val type: String,
    val title: String = "",
    val avatarUrl: String? = null,
    val otherUser: UserDto? = null,
    val group: GroupDto? = null,
    val lastMessage: MessageDto? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val disappearingAfterMs: Long? = null,
    val pinnedMessageId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class CreateConversationRequest(
    val participantIds: List<String>,
    val type: String = "DIRECT",
)

@Serializable
data class MessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val kind: String = "TEXT",
    /** Ciphertext when [isEncrypted]; plaintext otherwise. */
    val body: String = "",
    val isEncrypted: Boolean = true,
    val encryptionAlgorithm: String? = null,
    /** For group messages: this device's copy of the content key. */
    val wrappedKey: String? = null,
    val createdAt: Long = 0,
    val editedAt: Long? = null,
    val serverSeq: Long = 0,
    val replyToId: String? = null,
    val forwardedFrom: String? = null,
    val mentions: List<String> = emptyList(),
    val expiresAt: Long? = null,
    val isDeleted: Boolean = false,
    val attachments: List<AttachmentDto> = emptyList(),
    val reactions: List<ReactionDto> = emptyList(),
    val poll: PollDto? = null,
    val location: LocationDto? = null,
    val contact: ContactCardDto? = null,
    val systemEvent: SystemEventDto? = null,
    val callEvent: CallEventDto? = null,
    val deliveredTo: List<ReceiptDto> = emptyList(),
    val readBy: List<ReceiptDto> = emptyList(),
)

@Serializable
data class SendMessageRequest(
    /** Client-generated, so a retry is idempotent server-side. */
    val id: String,
    val conversationId: String,
    val kind: String,
    val body: String,
    val isEncrypted: Boolean = true,
    val encryptionAlgorithm: String? = null,
    /** userId -> sealed content key, for group messages. */
    val wrappedKeys: Map<String, String> = emptyMap(),
    val replyToId: String? = null,
    val forwardedFrom: String? = null,
    val mentions: List<String> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    val poll: CreatePollRequest? = null,
    val location: LocationDto? = null,
    val contact: ContactCardDto? = null,
    val expiresAt: Long? = null,
    val scheduledFor: Long? = null,
)

@Serializable
data class EditMessageRequest(val body: String)

@Serializable
data class DeleteMessageRequest(val forEveryone: Boolean = false)

@Serializable
data class ReactionDto(
    val emoji: String,
    val userId: String,
    val createdAt: Long = 0,
)

@Serializable
data class ReactRequest(val emoji: String, val remove: Boolean = false)

@Serializable
data class ReceiptDto(val userId: String, val userName: String = "", val at: Long = 0)

@Serializable
data class ReadReceiptRequest(val conversationId: String, val upToMessageId: String)

@Serializable
data class AttachmentDto(
    val id: String,
    val kind: String,
    val url: String? = null,
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val blurHash: String? = null,
    val waveform: List<Float> = emptyList(),
    /** Sealed AES key for the blob; the server stores it opaquely. */
    val mediaKey: String? = null,
)

@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    val liveUntil: Long? = null,
)

@Serializable
data class ContactCardDto(
    val displayName: String,
    val username: String? = null,
    val phoneNumbers: List<String> = emptyList(),
    val userId: String? = null,
)

@Serializable
data class SystemEventDto(
    val type: String,
    val actorName: String = "",
    val targetNames: List<String> = emptyList(),
    val value: String? = null,
)

@Serializable
data class CallEventDto(
    val callId: String,
    val isVideo: Boolean = false,
    val direction: String = "INCOMING",
    val outcome: String = "COMPLETED",
    val durationSeconds: Long = 0,
)

// ---- Polls ----------------------------------------------------------------

@Serializable
data class PollDto(
    val id: String,
    val question: String,
    val options: List<PollOptionDto> = emptyList(),
    val allowsMultipleAnswers: Boolean = false,
    val closesAt: Long? = null,
    val isClosed: Boolean = false,
)

@Serializable
data class PollOptionDto(
    val id: String,
    val text: String,
    val voterIds: List<String> = emptyList(),
)

@Serializable
data class CreatePollRequest(
    val question: String,
    val options: List<String>,
    val allowsMultipleAnswers: Boolean = false,
    val closesAt: Long? = null,
)

@Serializable
data class VotePollRequest(val optionIds: List<String>)

// ---- Groups ---------------------------------------------------------------

@Serializable
data class GroupDto(
    val id: String,
    val conversationId: String,
    val name: String,
    val description: String = "",
    val avatarUrl: String? = null,
    val createdAt: Long = 0,
    val createdById: String = "",
    val createdByName: String = "",
    val inviteCode: String? = null,
    val sendPermission: String = "EVERYONE",
    val editInfoPermission: String = "ADMINS_ONLY",
    val addMembersPermission: String = "EVERYONE",
    val myRole: String = "MEMBER",
    val members: List<GroupMemberDto> = emptyList(),
)

@Serializable
data class GroupMemberDto(
    val userId: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val role: String = "MEMBER",
    val joinedAt: Long = 0,
    val publicKey: String? = null,
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String = "",
    val memberIds: List<String>,
    val avatarUrl: String? = null,
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val sendPermission: String? = null,
    val editInfoPermission: String? = null,
    val addMembersPermission: String? = null,
)

@Serializable
data class GroupMembersRequest(val userIds: List<String>)

@Serializable
data class SetRoleRequest(val role: String)

@Serializable
data class InviteLinkDto(val code: String, val url: String)

// ---- Media ----------------------------------------------------------------

@Serializable
data class UploadTicketRequest(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: String,
)

/**
 * A pre-signed upload target. The client PUTs the (already encrypted) bytes straight to object
 * storage, so message payloads never travel through the API process.
 */
@Serializable
data class UploadTicketDto(
    val attachmentId: String,
    val uploadUrl: String,
    val downloadUrl: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long = 0,
)

// ---- Status ---------------------------------------------------------------

@Serializable
data class StatusPostDto(
    val id: String,
    val authorId: String,
    val authorName: String = "",
    val authorAvatarUrl: String? = null,
    val kind: String = "TEXT",
    val text: String = "",
    val mediaUrl: String? = null,
    val backgroundColor: Long? = null,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val durationMs: Long = 5000,
    val viewers: List<ReceiptDto> = emptyList(),
    val seenByMe: Boolean = false,
)

@Serializable
data class CreateStatusRequest(
    val kind: String,
    val text: String = "",
    val mediaUrl: String? = null,
    val backgroundColor: Long? = null,
    val durationMs: Long = 5000,
    val audience: String = "CONTACTS",
    val excludedUserIds: List<String> = emptyList(),
)

// ---- Calls ----------------------------------------------------------------

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class CallConfigDto(
    val iceServers: List<IceServerDto> = emptyList(),
    val enabled: Boolean = false,
)

@Serializable
data class StartCallRequest(
    val conversationId: String,
    val isVideo: Boolean,
    val calleeIds: List<String>,
)

@Serializable
data class CallSessionDto(
    val callId: String,
    val conversationId: String,
    val isVideo: Boolean,
    val participants: List<String> = emptyList(),
    val startedAt: Long = 0,
)

@Serializable
data class CallRecordDto(
    val id: String,
    val conversationId: String,
    val peerId: String,
    val peerName: String = "",
    val peerAvatarUrl: String? = null,
    val isVideo: Boolean = false,
    val isGroup: Boolean = false,
    val direction: String = "OUTGOING",
    val outcome: String = "COMPLETED",
    val startedAt: Long = 0,
    val durationSeconds: Long = 0,
    val participantNames: List<String> = emptyList(),
)

// ---- Devices --------------------------------------------------------------

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val lastActiveAt: Long = 0,
    val createdAt: Long = 0,
    val isCurrent: Boolean = false,
    val ipCountry: String? = null,
    val publicKey: String? = null,
)

@Serializable
data class RegisterDeviceKeyRequest(val publicKey: String)

// ---- Search ---------------------------------------------------------------

@Serializable
data class SearchResponseDto(
    val users: List<UserDto> = emptyList(),
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
)

// ---- Sync -----------------------------------------------------------------

@Serializable
data class SyncResponseDto(
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val statuses: List<StatusPostDto> = emptyList(),
    val calls: List<CallRecordDto> = emptyList(),
    @SerialName("deletedMessageIds") val deletedMessageIds: List<String> = emptyList(),
    val serverTime: Long = 0,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

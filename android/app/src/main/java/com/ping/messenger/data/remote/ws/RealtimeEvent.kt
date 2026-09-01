package com.ping.messenger.data.remote.ws

import com.ping.messenger.data.remote.dto.CallEventDto
import com.ping.messenger.data.remote.dto.ConversationDto
import com.ping.messenger.data.remote.dto.GroupDto
import com.ping.messenger.data.remote.dto.MessageDto
import com.ping.messenger.data.remote.dto.ReactionDto
import com.ping.messenger.data.remote.dto.StatusPostDto
import com.ping.messenger.data.remote.dto.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The realtime protocol.
 *
 * A single polymorphic envelope over one WebSocket carries messages, receipts, presence,
 * typing, group changes and WebRTC signalling. One socket rather than several keeps the radio
 * asleep more of the time, which is the dominant battery cost in a messaging app.
 *
 * kotlinx.serialization's sealed-class polymorphism discriminates on `"t"`. Unknown event types
 * decode to [Unknown] rather than throwing, so a server that grows a new event does not break
 * older clients.
 */
@Serializable
sealed interface RealtimeEvent {

    // ---- Server -> client ------------------------------------------------

    @Serializable
    @SerialName("message")
    data class NewMessage(val message: MessageDto) : RealtimeEvent

    @Serializable
    @SerialName("message.updated")
    data class MessageUpdated(val message: MessageDto) : RealtimeEvent

    @Serializable
    @SerialName("message.deleted")
    data class MessageDeleted(
        val messageId: String,
        val conversationId: String,
        val forEveryone: Boolean = true,
    ) : RealtimeEvent

    @Serializable
    @SerialName("receipt")
    data class ReceiptUpdate(
        val conversationId: String,
        val messageIds: List<String>,
        val userId: String,
        /** "DELIVERED" or "READ". */
        val type: String,
        val at: Long,
    ) : RealtimeEvent

    @Serializable
    @SerialName("reaction")
    data class ReactionUpdate(
        val messageId: String,
        val conversationId: String,
        val reaction: ReactionDto,
        val removed: Boolean = false,
    ) : RealtimeEvent

    @Serializable
    @SerialName("typing")
    data class Typing(
        val conversationId: String,
        val userId: String,
        val userName: String = "",
        val isTyping: Boolean,
    ) : RealtimeEvent

    @Serializable
    @SerialName("presence")
    data class PresenceUpdate(
        val userId: String,
        val isOnline: Boolean,
        val lastSeenAt: Long? = null,
        val hidden: Boolean = false,
    ) : RealtimeEvent

    @Serializable
    @SerialName("conversation")
    data class ConversationUpserted(val conversation: ConversationDto) : RealtimeEvent

    @Serializable
    @SerialName("group")
    data class GroupUpdated(val group: GroupDto) : RealtimeEvent

    @Serializable
    @SerialName("user")
    data class UserUpdated(val user: UserDto) : RealtimeEvent

    @Serializable
    @SerialName("status")
    data class StatusPosted(val status: StatusPostDto) : RealtimeEvent

    @Serializable
    @SerialName("status.deleted")
    data class StatusDeleted(val statusId: String) : RealtimeEvent

    @Serializable
    @SerialName("call.event")
    data class CallHistoryEvent(val event: CallEventDto, val conversationId: String) : RealtimeEvent

    // ---- WebRTC signalling (bidirectional) -------------------------------

    @Serializable
    @SerialName("call.invite")
    data class CallInvite(
        val callId: String,
        val conversationId: String,
        val fromUserId: String,
        val fromName: String = "",
        val fromAvatarUrl: String? = null,
        val isVideo: Boolean = false,
        val isGroup: Boolean = false,
    ) : RealtimeEvent

    @Serializable
    @SerialName("call.offer")
    data class CallOffer(
        val callId: String,
        val fromUserId: String,
        val sdp: String,
    ) : RealtimeEvent

    @Serializable
    @SerialName("call.answer")
    data class CallAnswer(
        val callId: String,
        val fromUserId: String,
        val sdp: String,
    ) : RealtimeEvent

    @Serializable
    @SerialName("call.ice")
    data class CallIceCandidate(
        val callId: String,
        val fromUserId: String,
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    ) : RealtimeEvent

    @Serializable
    @SerialName("call.hangup")
    data class CallHangup(
        val callId: String,
        val fromUserId: String,
        /** "declined", "ended", "busy", "timeout" */
        val reason: String = "ended",
    ) : RealtimeEvent

    // ---- Client -> server -------------------------------------------------

    @Serializable
    @SerialName("typing.set")
    data class SetTyping(val conversationId: String, val isTyping: Boolean) : RealtimeEvent

    @Serializable
    @SerialName("presence.set")
    data class SetPresence(val isActive: Boolean) : RealtimeEvent

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val conversationIds: List<String>) : RealtimeEvent

    @Serializable
    @SerialName("ping")
    data object Heartbeat : RealtimeEvent

    // ---- Control ----------------------------------------------------------

    @Serializable
    @SerialName("error")
    data class ServerError(val code: String, val message: String = "") : RealtimeEvent

    /** Anything this build does not recognise. */
    @Serializable
    @SerialName("unknown")
    data object Unknown : RealtimeEvent
}

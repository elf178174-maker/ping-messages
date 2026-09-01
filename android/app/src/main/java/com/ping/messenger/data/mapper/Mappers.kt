package com.ping.messenger.data.mapper

import com.ping.messenger.data.local.dao.ConversationWithDetails
import com.ping.messenger.data.local.dao.GroupMemberWithUser
import com.ping.messenger.data.local.dao.GroupWithMembers
import com.ping.messenger.data.local.dao.MessageWithRelations
import com.ping.messenger.data.local.dao.StatusPostWithViews
import com.ping.messenger.data.local.entity.AttachmentEntity
import com.ping.messenger.data.local.entity.CallRecordEntity
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.DeviceSessionEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.ReceiptType
import com.ping.messenger.data.local.entity.StatusPostEntity
import com.ping.messenger.data.local.entity.UserEntity
import com.ping.messenger.data.remote.dto.AttachmentDto
import com.ping.messenger.data.remote.dto.CallRecordDto
import com.ping.messenger.data.remote.dto.ContactCardDto
import com.ping.messenger.data.remote.dto.DeviceDto
import com.ping.messenger.data.remote.dto.GroupDto
import com.ping.messenger.data.remote.dto.LocationDto
import com.ping.messenger.data.remote.dto.MessageDto
import com.ping.messenger.data.remote.dto.PrivacySettingsDto
import com.ping.messenger.data.remote.dto.StatusPostDto
import com.ping.messenger.data.remote.dto.SystemEventDto
import com.ping.messenger.data.remote.dto.UserDto
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.CallDirection
import com.ping.messenger.domain.model.CallEvent
import com.ping.messenger.domain.model.CallOutcome
import com.ping.messenger.domain.model.CallRecord
import com.ping.messenger.domain.model.ContactCard
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.DeviceSession
import com.ping.messenger.domain.model.GeoPoint
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.GroupMember
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.model.LinkPreview
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageEdit
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessagePreview
import com.ping.messenger.domain.model.MessageQuote
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.model.Poll
import com.ping.messenger.domain.model.PollOption
import com.ping.messenger.domain.model.Presence
import com.ping.messenger.domain.model.PrivacyAudience
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.domain.model.Reaction
import com.ping.messenger.domain.model.Receipt
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.StatusPost
import com.ping.messenger.domain.model.SystemEvent
import com.ping.messenger.domain.model.TransferState
import com.ping.messenger.domain.model.User
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Translation between the three representations of every concept: wire (DTO), storage
 * (entity) and domain (model).
 *
 * Keeping all of it here means a field rename touches one file, and it makes the asymmetries
 * explicit — for instance, a DTO carries ciphertext while the entity carries plaintext, because
 * decryption happens on the way in.
 */
class EntityMapper(private val json: Json) {

    // ---- Users ------------------------------------------------------------

    fun UserDto.toEntity(existing: UserEntity? = null): UserEntity = UserEntity(
        id = id,
        username = username,
        displayName = displayName,
        about = about,
        avatarUrl = avatarUrl,
        phoneNumber = phoneNumber ?: existing?.phoneNumber,
        isContact = isContact || existing?.isContact == true,
        isBlocked = isBlocked,
        isOnline = isOnline,
        lastSeenAt = lastSeenAt,
        presenceHidden = presenceHidden,
        publicKey = publicKey ?: existing?.publicKey,
        keyFingerprint = existing?.keyFingerprint,
        updatedAt = updatedAt,
    )

    fun UserEntity.toDomain(): User = User(
        id = id,
        username = username,
        displayName = displayName,
        about = about,
        avatarUrl = avatarUrl,
        phoneNumber = phoneNumber,
        isContact = isContact,
        isBlocked = isBlocked,
        presence = presenceOf(this),
        publicKey = publicKey,
    )

    private fun presenceOf(user: UserEntity): Presence = when {
        user.presenceHidden -> Presence.Unknown
        user.isOnline -> Presence.Online
        user.lastSeenAt != null -> Presence.LastSeen(user.lastSeenAt)
        else -> Presence.Unknown
    }

    // ---- Conversations ----------------------------------------------------

    fun ConversationWithDetails.toDomain(
        typingNames: List<String> = emptyList(),
        folderIds: Set<String> = emptySet(),
    ): Conversation {
        val c = conversation
        return Conversation(
            id = c.id,
            type = c.type,
            title = c.title.ifBlank { otherUser?.displayName.orEmpty() },
            avatarUrl = c.avatarUrl ?: otherUser?.avatarUrl,
            lastMessage = lastMessage?.toPreview(otherUser?.displayName),
            unreadCount = c.unreadCount,
            mentionCount = c.mentionCount,
            isPinned = c.isPinned,
            isArchived = c.isArchived,
            isMuted = c.isMuted,
            mutedUntil = c.mutedUntil,
            markedUnread = c.markedUnread,
            draft = c.draft?.takeIf { it.isNotBlank() },
            typingUserNames = typingNames,
            otherUserId = c.otherUserId,
            presence = otherUser?.let { presenceOf(it) } ?: Presence.Unknown,
            disappearingAfter = c.disappearingAfterMs?.milliseconds,
            wallpaperId = c.wallpaperId,
            folderIds = folderIds,
            pinnedMessageId = c.pinnedMessageId,
            updatedAt = c.lastActivityAt,
            notes = c.notes,
        )
    }

    private fun MessageEntity.toPreview(fallbackSenderName: String?): MessagePreview = MessagePreview(
        id = id,
        senderId = senderId,
        senderName = if (isOutgoing) null else fallbackSenderName,
        text = text,
        kind = kind,
        timestamp = createdAt,
        status = status,
        isOutgoing = isOutgoing,
    )

    // ---- Messages ---------------------------------------------------------

    fun MessageWithRelations.toDomain(
        currentUserId: String,
        senderName: String = "",
        senderAvatarUrl: String? = null,
    ): Message {
        val m = message
        val grouped = reactions.groupBy { it.emoji }
        return Message(
            id = m.id,
            conversationId = m.conversationId,
            senderId = m.senderId,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl,
            kind = m.kind,
            text = m.text,
            createdAt = m.createdAt,
            editedAt = m.editedAt,
            status = m.status,
            isOutgoing = m.isOutgoing,
            isStarred = m.isStarred,
            isDeleted = m.isDeleted,
            deletedForEveryone = m.deletedForEveryone,
            replyTo = m.replyToId?.let {
                MessageQuote(
                    messageId = it,
                    senderId = m.replyToSenderId.orEmpty(),
                    senderName = m.replyToSenderName.orEmpty(),
                    text = m.replyToText.orEmpty(),
                    kind = m.replyToKind ?: MessageKind.TEXT,
                    thumbnailUrl = m.replyToThumbnail,
                )
            },
            forwardedFrom = m.forwardedFrom,
            forwardCount = m.forwardCount,
            attachments = attachments.map { it.toDomain() },
            reactions = grouped.map { (emoji, rows) ->
                Reaction(
                    emoji = emoji,
                    userIds = rows.map { it.userId },
                    reactedByMe = rows.any { it.userId == currentUserId },
                )
            }.sortedByDescending { it.count },
            mentions = m.mentionsJson.decodeStringList(),
            poll = polls.firstOrNull()?.let { p ->
                Poll(
                    id = p.poll.id,
                    question = p.poll.question,
                    options = p.options.sortedBy { it.position }.map { option ->
                        val voters = p.votes.filter { it.optionId == option.id }
                        PollOption(
                            id = option.id,
                            text = option.text,
                            voterIds = voters.map { it.userId },
                            votedByMe = voters.any { it.userId == currentUserId },
                        )
                    },
                    allowsMultipleAnswers = p.poll.allowsMultipleAnswers,
                    closesAt = p.poll.closesAt,
                    isClosed = p.poll.isClosed,
                )
            },
            location = m.locationJson?.decode<LocationDto>()?.let {
                GeoPoint(it.latitude, it.longitude, it.label, it.liveUntil)
            },
            contactCard = m.contactJson?.decode<ContactCardDto>()?.let {
                ContactCard(it.displayName, it.username, it.phoneNumbers, it.userId)
            },
            callEvent = m.callEventJson?.decode<com.ping.messenger.data.remote.dto.CallEventDto>()
                ?.let {
                    CallEvent(
                        callId = it.callId,
                        isVideo = it.isVideo,
                        direction = enumOf(it.direction, CallDirection.INCOMING),
                        outcome = enumOf(it.outcome, CallOutcome.COMPLETED),
                        durationSeconds = it.durationSeconds,
                    )
                },
            systemEvent = m.systemEventJson?.decode<SystemEventDto>()?.toDomain(),
            linkPreview = m.linkPreviewJson?.decode<LinkPreview>(),
            expiresAt = m.expiresAt,
            scheduledFor = m.scheduledFor,
            isEncrypted = m.isEncrypted,
            decryptionFailed = m.decryptionFailed,
            editHistory = m.editHistoryJson?.decode<List<MessageEdit>>().orEmpty(),
            translatedText = m.translatedText,
            deliveredTo = receipts.filter { it.type == ReceiptType.DELIVERED }
                .map { Receipt(it.userId, "", it.at) },
            readBy = receipts.filter { it.type == ReceiptType.READ }
                .map { Receipt(it.userId, "", it.at) },
        )
    }

    /**
     * A server message becomes a local row. [plaintext] is supplied by the caller because
     * decryption is asynchronous and needs the crypto service, which this mapper does not own.
     */
    fun MessageDto.toEntity(
        currentUserId: String,
        plaintext: String,
        decryptionFailed: Boolean = false,
        quote: MessageQuote? = null,
    ): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        kind = enumOf(kind, MessageKind.TEXT),
        text = plaintext,
        createdAt = createdAt,
        editedAt = editedAt,
        status = if (senderId == currentUserId) MessageStatus.SENT else MessageStatus.DELIVERED,
        isOutgoing = senderId == currentUserId,
        isDeleted = isDeleted,
        deletedForEveryone = isDeleted,
        replyToId = replyToId ?: quote?.messageId,
        replyToSenderId = quote?.senderId,
        replyToSenderName = quote?.senderName,
        replyToText = quote?.text,
        replyToKind = quote?.kind,
        forwardedFrom = forwardedFrom,
        mentionsJson = mentions.takeIf { it.isNotEmpty() }?.let { encodeStringList(it) },
        expiresAt = expiresAt,
        isEncrypted = isEncrypted,
        decryptionFailed = decryptionFailed,
        serverSeq = serverSeq.takeIf { it > 0 },
        locationJson = location?.let { encode(LocationDto.serializer(), it) },
        contactJson = contact?.let { encode(ContactCardDto.serializer(), it) },
        callEventJson = callEvent?.let {
            encode(com.ping.messenger.data.remote.dto.CallEventDto.serializer(), it)
        },
        systemEventJson = systemEvent?.let { encode(SystemEventDto.serializer(), it) },
    )

    fun AttachmentDto.toEntity(messageId: String, conversationId: String, createdAt: Long) =
        AttachmentEntity(
            id = id,
            messageId = messageId,
            conversationId = conversationId,
            kind = enumOf(kind, MessageKind.DOCUMENT),
            remoteUrl = url,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            durationMs = durationMs,
            blurHash = blurHash,
            waveformJson = waveform.takeIf { it.isNotEmpty() }?.let {
                json.encodeToString(ListSerializer(Float.serializer()), it)
            },
            mediaKey = mediaKey,
            transferState = TransferState.IDLE,
            createdAt = createdAt,
        )

    fun AttachmentEntity.toDomain(): Attachment = Attachment(
        id = id,
        messageId = messageId,
        kind = kind,
        remoteUrl = remoteUrl,
        localPath = localPath,
        thumbnailPath = thumbnailPath,
        blurHash = blurHash,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        waveform = waveformJson?.let {
            runCatching { json.decodeFromString(ListSerializer(Float.serializer()), it) }
                .getOrDefault(emptyList())
        }.orEmpty(),
        transferState = transferState,
        transferProgress = transferProgress,
        transferError = transferError,
    )

    private fun SystemEventDto.toDomain(): SystemEvent = when (type) {
        "GROUP_CREATED" -> SystemEvent.GroupCreated(actorName)
        "MEMBERS_ADDED" -> SystemEvent.MembersAdded(actorName, targetNames)
        "MEMBER_REMOVED" -> SystemEvent.MemberRemoved(actorName, targetNames.firstOrNull().orEmpty())
        "MEMBER_LEFT" -> SystemEvent.MemberLeft(actorName)
        "GROUP_RENAMED" -> SystemEvent.GroupRenamed(actorName, value.orEmpty())
        "GROUP_ICON_CHANGED" -> SystemEvent.GroupIconChanged(actorName)
        "ADMIN_GRANTED" -> SystemEvent.AdminGranted(actorName, targetNames.firstOrNull().orEmpty())
        "ADMIN_REVOKED" -> SystemEvent.AdminRevoked(actorName, targetNames.firstOrNull().orEmpty())
        "DISAPPEARING_CHANGED" -> SystemEvent.DisappearingChanged(
            actorName,
            value?.toLongOrNull()?.milliseconds,
        )
        "SECURITY_CODE_CHANGED" -> SystemEvent.SecurityCodeChanged(actorName)
        else -> SystemEvent.EncryptionEnabled
    }

    // ---- Groups -----------------------------------------------------------

    fun GroupDto.toEntity() = com.ping.messenger.data.local.entity.GroupEntity(
        id = id,
        conversationId = conversationId,
        name = name,
        description = description,
        avatarUrl = avatarUrl,
        createdAt = createdAt,
        createdById = createdById,
        createdByName = createdByName,
        inviteCode = inviteCode,
        sendPermission = enumOf(sendPermission, GroupPermission.EVERYONE),
        editInfoPermission = enumOf(editInfoPermission, GroupPermission.ADMINS_ONLY),
        addMembersPermission = enumOf(addMembersPermission, GroupPermission.EVERYONE),
        myRole = enumOf(myRole, GroupRole.MEMBER),
        updatedAt = System.currentTimeMillis(),
    )

    fun GroupWithMembers.toDomain(memberProfiles: Map<String, UserEntity> = emptyMap()): Group =
        Group(
            id = group.id,
            conversationId = group.conversationId,
            name = group.name,
            description = group.description,
            avatarUrl = group.avatarUrl,
            createdAt = group.createdAt,
            createdByName = group.createdByName,
            members = members.map { member ->
                val profile = memberProfiles[member.userId]
                GroupMember(
                    userId = member.userId,
                    displayName = profile?.displayName.orEmpty(),
                    username = profile?.username.orEmpty(),
                    avatarUrl = profile?.avatarUrl,
                    role = member.role,
                    joinedAt = member.joinedAt,
                )
            },
            inviteCode = group.inviteCode,
            sendPermission = group.sendPermission,
            editInfoPermission = group.editInfoPermission,
            addMembersPermission = group.addMembersPermission,
            myRole = group.myRole,
        )

    fun GroupMemberWithUser.toDomain(): GroupMember = GroupMember(
        userId = member.userId,
        displayName = user?.displayName.orEmpty(),
        username = user?.username.orEmpty(),
        avatarUrl = user?.avatarUrl,
        role = member.role,
        joinedAt = member.joinedAt,
    )

    // ---- Status -----------------------------------------------------------

    fun StatusPostDto.toEntity(currentUserId: String) = StatusPostEntity(
        id = id,
        authorId = authorId,
        kind = enumOf(kind, StatusKind.TEXT),
        text = text,
        mediaUrl = mediaUrl,
        backgroundColor = backgroundColor,
        createdAt = createdAt,
        expiresAt = expiresAt,
        seenByMe = seenByMe,
        isMine = authorId == currentUserId,
        durationMs = durationMs,
    )

    fun StatusPostWithViews.toDomain(author: UserEntity?): StatusPost = StatusPost(
        id = post.id,
        authorId = post.authorId,
        authorName = author?.displayName.orEmpty(),
        authorAvatarUrl = author?.avatarUrl,
        kind = post.kind,
        text = post.text,
        mediaUrl = post.mediaUrl,
        localPath = post.localPath,
        backgroundColor = post.backgroundColor,
        createdAt = post.createdAt,
        expiresAt = post.expiresAt,
        viewers = views.map { Receipt(it.userId, "", it.at) },
        seenByMe = post.seenByMe,
        isMine = post.isMine,
        durationMs = post.durationMs,
    )

    // ---- Calls ------------------------------------------------------------

    fun CallRecordDto.toEntity() = CallRecordEntity(
        id = id,
        conversationId = conversationId,
        peerId = peerId,
        isVideo = isVideo,
        isGroup = isGroup,
        direction = enumOf(direction, CallDirection.OUTGOING),
        outcome = enumOf(outcome, CallOutcome.COMPLETED),
        startedAt = startedAt,
        durationSeconds = durationSeconds,
        participantsJson = participantNames.takeIf { it.isNotEmpty() }
            ?.let { encodeStringList(it) },
    )

    fun CallRecordEntity.toDomain(peer: UserEntity?): CallRecord = CallRecord(
        id = id,
        conversationId = conversationId,
        peerId = peerId,
        peerName = peer?.displayName.orEmpty(),
        peerAvatarUrl = peer?.avatarUrl,
        isVideo = isVideo,
        isGroup = isGroup,
        direction = direction,
        outcome = outcome,
        startedAt = startedAt,
        durationSeconds = durationSeconds,
        participantNames = participantsJson.decodeStringList(),
    )

    // ---- Devices and privacy ---------------------------------------------

    fun DeviceDto.toEntity() = DeviceSessionEntity(
        id = id,
        deviceName = name,
        platform = platform,
        lastActiveAt = lastActiveAt,
        createdAt = createdAt,
        isCurrent = isCurrent,
        ipCountry = ipCountry,
    )

    fun DeviceSessionEntity.toDomain() = DeviceSession(
        id = id,
        deviceName = deviceName,
        platform = platform,
        lastActiveAt = lastActiveAt,
        createdAt = createdAt,
        isCurrent = isCurrent,
        ipCountry = ipCountry,
    )

    fun PrivacySettingsDto.toDomain() = PrivacySettings(
        lastSeen = enumOf(lastSeen, PrivacyAudience.CONTACTS),
        onlineStatus = enumOf(onlineStatus, PrivacyAudience.EVERYONE),
        profilePhoto = enumOf(profilePhoto, PrivacyAudience.CONTACTS),
        about = enumOf(about, PrivacyAudience.CONTACTS),
        status = enumOf(status, PrivacyAudience.CONTACTS),
        groups = enumOf(groups, PrivacyAudience.CONTACTS),
        calls = enumOf(calls, PrivacyAudience.EVERYONE),
        readReceipts = readReceipts,
        typingIndicators = typingIndicators,
    )

    fun PrivacySettings.toDto() = PrivacySettingsDto(
        lastSeen = lastSeen.name,
        onlineStatus = onlineStatus.name,
        profilePhoto = profilePhoto.name,
        about = about.name,
        status = status.name,
        groups = groups.name,
        calls = calls.name,
        readReceipts = readReceipts,
        typingIndicators = typingIndicators,
    )

    fun ConversationEntity.withHeaderFrom(user: UserEntity) = copy(
        title = user.displayName,
        avatarUrl = user.avatarUrl,
    )

    fun directConversation(id: String, other: UserEntity, now: Long) = ConversationEntity(
        id = id,
        type = ConversationType.DIRECT,
        title = other.displayName,
        avatarUrl = other.avatarUrl,
        otherUserId = other.id,
        createdAt = now,
        updatedAt = now,
        lastActivityAt = now,
    )

    // ---- JSON helpers -----------------------------------------------------

    fun encodeStringList(values: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values)

    private fun String?.decodeStringList(): List<String> = this?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }
            .getOrDefault(emptyList())
    }.orEmpty()

    fun <T> encode(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): String =
        json.encodeToString(serializer, value)

    private inline fun <reified T> String.decode(): T? =
        runCatching { json.decodeFromString<T>(this) }.getOrNull()

    fun encodeEditHistory(history: List<MessageEdit>): String =
        json.encodeToString(ListSerializer(MessageEdit.serializer()), history)
}

/** Decodes an enum by name with a documented fallback. Shared by every DTO conversion. */
inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

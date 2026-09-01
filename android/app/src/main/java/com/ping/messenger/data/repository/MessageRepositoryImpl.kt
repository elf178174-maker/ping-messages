package com.ping.messenger.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.DispatcherProvider
import com.ping.messenger.core.common.FtsQuery
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.TextUtils
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.OutboxDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.local.entity.AttachmentEntity
import com.ping.messenger.data.local.entity.MessageEntity
import com.ping.messenger.data.local.entity.OutboxEntity
import com.ping.messenger.data.local.entity.OutboxOperation
import com.ping.messenger.data.local.entity.PollEntity
import com.ping.messenger.data.local.entity.PollOptionEntity
import com.ping.messenger.data.local.entity.PollVoteEntity
import com.ping.messenger.data.local.entity.ReactionEntity
import com.ping.messenger.data.local.entity.UserEntity
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.ContactCardDto
import com.ping.messenger.data.remote.dto.LocationDto
import com.ping.messenger.data.remote.ws.RealtimeClient
import com.ping.messenger.data.remote.ws.RealtimeEvent
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageEdit
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.OutgoingMessage
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Sending, editing, reacting to and reading messages.
 *
 * ## The write path
 *
 * Every mutation follows the same three steps, inside one database transaction:
 *
 *   1. apply the change to local tables (so the UI updates immediately),
 *   2. append a row to the outbox describing the network call it implies,
 *   3. nudge the sync engine.
 *
 * Nothing here awaits the network. That is what makes the app usable offline without a single
 * "are we online?" branch in the UI: an unsent message and a sent one differ only by a status
 * column, and the outbox drains whenever connectivity allows.
 *
 * ## Idempotency
 *
 * Message ids are generated on the client. A retry re-sends the same id, so a request that
 * actually succeeded but whose response was lost cannot produce a duplicate.
 */
@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val database: PingDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val outboxDao: OutboxDao,
    private val crypto: CryptoService,
    private val tokenStore: TokenStore,
    private val realtime: RealtimeClient,
    private val preferences: AppPreferences,
    private val mapper: EntityMapper,
    private val dispatchers: DispatcherProvider,
    private val syncTrigger: SyncTrigger,
) : MessageRepository {

    /** conversationId -> display names currently typing. Purely in-memory; not persisted. */
    private val typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val currentUserId: String get() = tokenStore.currentUserId.orEmpty()

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    override fun pagedMessages(conversationId: String): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            // A screenful is ~15 bubbles; 40 keeps a fling smooth without holding a whole
            // history in memory. placeholders off because variable-height bubbles make
            // placeholder sizing wrong and cause scroll jumps.
            pageSize = 40,
            prefetchDistance = 20,
            initialLoadSize = 60,
            enablePlaceholders = false,
            maxSize = 400,
        ),
        pagingSourceFactory = { messageDao.pagedForConversation(conversationId) },
    ).flow.map { paging ->
        val me = currentUserId
        paging.map { row -> with(mapper) { row.toDomain(me) } }
    }

    override fun observeRecent(conversationId: String, limit: Int): Flow<List<Message>> =
        messageDao.observeRecent(conversationId, limit).map { rows ->
            val me = currentUserId
            val senders = senderLookup(rows.map { it.message.senderId })
            rows.map { row ->
                val sender = senders[row.message.senderId]
                with(mapper) {
                    row.toDomain(me, sender?.displayName.orEmpty(), sender?.avatarUrl)
                }
            }
        }

    override fun observeMessage(id: String): Flow<Message?> =
        messageDao.observeById(id).map { row ->
            row?.let { with(mapper) { it.toDomain(currentUserId) } }
        }

    override fun observeStarred(): Flow<List<Message>> =
        messageDao.observeStarred().map { rows ->
            val me = currentUserId
            rows.map { with(mapper) { it.toDomain(me) } }
        }

    override fun observeScheduled(conversationId: String?): Flow<List<Message>> =
        messageDao.observeScheduled().map { rows ->
            rows.filter { conversationId == null || it.conversationId == conversationId }
                .map { entity ->
                    with(mapper) {
                        com.ping.messenger.data.local.dao.MessageWithRelations(entity)
                            .toDomain(currentUserId)
                    }
                }
        }

    override fun observeGalleryMedia(conversationId: String) =
        messageDao.observeGalleryMedia(conversationId).map { list ->
            with(mapper) { list.map { it.toDomain() } }
        }

    override fun observeGalleryDocuments(conversationId: String) =
        messageDao.observeGalleryDocuments(conversationId).map { list ->
            with(mapper) { list.map { it.toDomain() } }
        }

    override fun observeTyping(conversationId: String): Flow<List<String>> =
        typing.map { it[conversationId].orEmpty() }

    // -----------------------------------------------------------------------
    // Sending
    // -----------------------------------------------------------------------

    override suspend fun send(message: OutgoingMessage): Outcome<String> =
        runCatchingAppSuspend {
            withContext(dispatchers.io) {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val conversation = conversationDao.findById(message.conversationId)
                    ?: throw AppError.NotFound("conversation")

                val quote = message.replyToId?.let { replyId ->
                    messageDao.findRawById(replyId)?.let { original ->
                        val sender = userDao.findById(original.senderId)
                        Triple(original, sender, original.kind)
                    }
                }

                val expiresAt = conversation.disappearingAfterMs?.let { now + it }
                val kind = resolveKind(message)

                val entity = MessageEntity(
                    id = id,
                    conversationId = message.conversationId,
                    senderId = currentUserId,
                    kind = kind,
                    text = message.text,
                    createdAt = now,
                    status = if (message.scheduledFor != null) MessageStatus.PENDING else MessageStatus.SENDING,
                    isOutgoing = true,
                    replyToId = message.replyToId,
                    replyToSenderId = quote?.first?.senderId,
                    replyToSenderName = quote?.second?.displayName,
                    replyToText = quote?.first?.text,
                    replyToKind = quote?.third,
                    forwardedFrom = message.forwardedFromMessageId,
                    mentionsJson = message.mentions.takeIf { it.isNotEmpty() }
                        ?.let { mapper.encodeStringList(it) },
                    expiresAt = expiresAt,
                    scheduledFor = message.scheduledFor,
                    isEncrypted = true,
                    locationJson = message.location?.let {
                        mapper.encode(
                            LocationDto.serializer(),
                            LocationDto(it.latitude, it.longitude, it.label, it.liveUntil),
                        )
                    },
                    contactJson = message.contactUserId?.let { userId ->
                        userDao.findById(userId)?.let { contact ->
                            mapper.encode(
                                ContactCardDto.serializer(),
                                ContactCardDto(
                                    displayName = contact.displayName,
                                    username = contact.username,
                                    userId = contact.id,
                                ),
                            )
                        }
                    },
                )

                val attachments = message.attachmentPaths.map { path ->
                    val file = File(path)
                    AttachmentEntity(
                        id = UUID.randomUUID().toString(),
                        messageId = id,
                        conversationId = message.conversationId,
                        kind = kind,
                        localPath = path,
                        fileName = file.name,
                        mimeType = mimeTypeFor(file),
                        sizeBytes = file.length(),
                        transferState = com.ping.messenger.domain.model.TransferState.QUEUED,
                        createdAt = now,
                    )
                }

                database.withTransaction {
                    messageDao.saveMessage(entity, attachments)
                    if (message.pollQuestion != null) savePoll(id, message)
                    if (message.scheduledFor == null) {
                        conversationDao.touchLastMessage(message.conversationId, id, now)
                        enqueue(OutboxOperation.SEND_MESSAGE, id, message.conversationId, id)
                    }
                }

                if (message.scheduledFor == null) syncTrigger.requestSync()
                id
            }
        }

    override suspend fun retry(messageId: String): Outcome<Unit> = runCatchingAppSuspend {
        database.withTransaction {
            messageDao.setStatus(messageId, MessageStatus.SENDING)
            val entity = messageDao.findRawById(messageId)
            enqueue(OutboxOperation.SEND_MESSAGE, messageId, entity?.conversationId, messageId)
        }
        syncTrigger.requestSync()
    }

    override suspend fun edit(messageId: String, newText: String): Outcome<Unit> =
        runCatchingAppSuspend {
            val existing = messageDao.findRawById(messageId) ?: throw AppError.NotFound("message")
            if (!existing.isOutgoing) throw AppError.Forbidden("Only your own messages can be edited")

            val now = System.currentTimeMillis()
            val history = buildList {
                add(MessageEdit(existing.text, existing.editedAt ?: existing.createdAt))
            }
            database.withTransaction {
                messageDao.edit(messageId, newText, now, mapper.encodeEditHistory(history))
                enqueue(OutboxOperation.EDIT_MESSAGE, messageId, existing.conversationId, newText)
            }
            syncTrigger.requestSync()
        }

    override suspend fun delete(messageId: String, forEveryone: Boolean): Outcome<Unit> =
        runCatchingAppSuspend {
            val existing = messageDao.findRawById(messageId) ?: return@runCatchingAppSuspend
            database.withTransaction {
                if (forEveryone) {
                    messageDao.softDelete(messageId, true)
                    enqueue(
                        OutboxOperation.DELETE_MESSAGE,
                        messageId,
                        existing.conversationId,
                        "true",
                    )
                } else {
                    messageDao.purge(messageId)
                }
            }
            if (forEveryone) syncTrigger.requestSync()
        }

    override suspend fun toggleReaction(messageId: String, emoji: String): Outcome<Unit> =
        runCatchingAppSuspend {
            val existing = messageDao.findById(messageId) ?: throw AppError.NotFound("message")
            val mine = existing.reactions.firstOrNull {
                it.userId == currentUserId && it.emoji == emoji
            }
            database.withTransaction {
                if (mine != null) {
                    messageDao.deleteReaction(mine)
                    enqueue(
                        OutboxOperation.UNREACT,
                        messageId,
                        existing.message.conversationId,
                        emoji,
                    )
                } else {
                    messageDao.upsertReactions(
                        listOf(
                            ReactionEntity(
                                messageId = messageId,
                                userId = currentUserId,
                                emoji = emoji,
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                    )
                    enqueue(OutboxOperation.REACT, messageId, existing.message.conversationId, emoji)
                }
            }
            syncTrigger.requestSync()
        }

    override suspend fun setStarred(messageId: String, starred: Boolean): Outcome<Unit> =
        runCatchingAppSuspend {
            val existing = messageDao.findRawById(messageId)
            database.withTransaction {
                messageDao.setStarred(messageId, starred)
                enqueue(
                    OutboxOperation.STAR,
                    messageId,
                    existing?.conversationId,
                    starred.toString(),
                )
            }
            syncTrigger.requestSync()
        }

    override suspend fun forward(
        messageIds: List<String>,
        toConversationIds: List<String>,
    ): Outcome<Unit> = runCatchingAppSuspend {
        for (target in toConversationIds) {
            for (sourceId in messageIds) {
                val source = messageDao.findById(sourceId) ?: continue
                send(
                    OutgoingMessage(
                        conversationId = target,
                        text = source.message.text,
                        kind = source.message.kind,
                        attachmentPaths = source.attachments.mapNotNull { it.localPath },
                        forwardedFromMessageId = sourceId,
                    ),
                )
            }
        }
    }

    override suspend fun votePoll(messageId: String, optionIds: List<String>): Outcome<Unit> =
        runCatchingAppSuspend {
            val row = messageDao.findById(messageId) ?: throw AppError.NotFound("poll")
            val poll = row.polls.firstOrNull()?.poll ?: throw AppError.NotFound("poll")
            val now = System.currentTimeMillis()

            database.withTransaction {
                messageDao.clearPollVotes(poll.id, currentUserId)
                messageDao.upsertPollVotes(
                    optionIds.map {
                        PollVoteEntity(optionId = it, pollId = poll.id, userId = currentUserId, at = now)
                    },
                )
                enqueue(
                    OutboxOperation.VOTE_POLL,
                    messageId,
                    row.message.conversationId,
                    mapper.encodeStringList(optionIds),
                )
            }
            syncTrigger.requestSync()
        }

    override suspend fun setPinnedMessage(
        conversationId: String,
        messageId: String?,
    ): Outcome<Unit> = runCatchingAppSuspend {
        conversationDao.setPinnedMessage(conversationId, messageId)
        api.setPinnedMessage(conversationId, messageId)
        Unit
    }

    // -----------------------------------------------------------------------
    // History and search
    // -----------------------------------------------------------------------

    override suspend fun loadOlder(conversationId: String): Outcome<Int> = runCatchingAppSuspend {
        val page = api.messages(
            conversationId = conversationId,
            beforeSeq = messageDao.oldestServerSeq(conversationId),
            limit = 50,
        )
        val decrypted = page.items.map { decryptToEntity(it) }
        database.withTransaction { messageDao.saveMessages(decrypted) }
        decrypted.size
    }

    override suspend fun searchInConversation(conversationId: String, query: String): List<Message> {
        val match = FtsQuery.sanitise(query) ?: return emptyList()
        val hits = messageDao.searchInConversation(conversationId, match)
        return hits.mapNotNull { hit ->
            messageDao.findById(hit.messageId)?.let { with(mapper) { it.toDomain(currentUserId) } }
        }
    }

    override suspend fun cancelScheduled(messageId: String) {
        messageDao.purge(messageId)
    }

    /**
     * Translation is intentionally a no-op unless a provider is configured.
     *
     * Ping does not ship an embedded translation model, and silently shipping message text to
     * a third-party API would contradict the privacy promise the rest of the app makes. The
     * plumbing (storage column, UI affordance, this call) is real; enabling it is a deliberate,
     * documented configuration step. See docs/TRANSLATION.md.
     */
    override suspend fun translate(messageId: String, targetLanguage: String): Outcome<Unit> {
        val enabled = preferences.chat.first().translationEnabled
        if (!enabled) {
            return Outcome.Failure(
                AppError.Forbidden("Translation is off. Enable it in Settings ▸ Chats."),
            )
        }
        return Outcome.Failure(
            AppError.Forbidden("No translation provider is configured for this build."),
        )
    }

    override suspend fun clearTranslation(messageId: String) {
        messageDao.setTranslation(messageId, null, null)
    }

    override suspend fun positionOf(conversationId: String, messageId: String): Int? {
        val entity = messageDao.findRawById(messageId) ?: return null
        return messageDao.positionOf(conversationId, messageId, entity.createdAt)
    }

    // -----------------------------------------------------------------------
    // Typing
    // -----------------------------------------------------------------------

    override suspend fun setTyping(conversationId: String, typing: Boolean) {
        if (!preferences.privacy.first().typingIndicators) return
        realtime.send(RealtimeEvent.SetTyping(conversationId, typing))
    }

    /** Called by the sync engine when a typing event arrives. */
    fun applyTyping(conversationId: String, userName: String, isTyping: Boolean) {
        typing.value = typing.value.toMutableMap().apply {
            val names = get(conversationId).orEmpty().toMutableList()
            if (isTyping) {
                if (userName !in names) names += userName
            } else {
                names -= userName
            }
            if (names.isEmpty()) remove(conversationId) else put(conversationId, names)
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /**
     * Decrypts a server message into a storable row.
     *
     * A failure here is recorded on the row rather than thrown: one undecryptable message
     * (a key rotation the sender did not see, a corrupted blob) must not abort the sync of
     * everything around it.
     */
    suspend fun decryptToEntity(dto: com.ping.messenger.data.remote.dto.MessageDto): MessageEntity {
        if (!dto.isEncrypted) {
            return with(mapper) { dto.toEntity(currentUserId, dto.body) }
        }
        return try {
            val payload = com.ping.messenger.core.crypto.EncryptedPayload(
                ciphertext = dto.body,
                algorithm = dto.encryptionAlgorithm
                    ?: com.ping.messenger.core.crypto.EncryptedPayload.ALGORITHM_HPKE_X25519,
            )
            val plaintext = if (dto.wrappedKey != null) {
                crypto.decryptGroup(payload, dto.wrappedKey)
            } else {
                crypto.decrypt(payload)
            }
            with(mapper) { dto.toEntity(currentUserId, String(plaintext)) }
        } catch (e: Exception) {
            with(mapper) { dto.toEntity(currentUserId, "", decryptionFailed = true) }
        }
    }

    private suspend fun savePoll(messageId: String, message: OutgoingMessage) {
        val pollId = UUID.randomUUID().toString()
        messageDao.upsertPoll(
            PollEntity(
                id = pollId,
                messageId = messageId,
                question = message.pollQuestion.orEmpty(),
                allowsMultipleAnswers = message.pollAllowsMultiple,
            ),
        )
        messageDao.upsertPollOptions(
            message.pollOptions.mapIndexed { index, text ->
                PollOptionEntity(
                    id = UUID.randomUUID().toString(),
                    pollId = pollId,
                    text = text,
                    position = index,
                )
            },
        )
    }

    private suspend fun enqueue(
        operation: OutboxOperation,
        entityId: String,
        conversationId: String?,
        payload: String,
    ) {
        outboxDao.upsert(
            OutboxEntity(
                operation = operation,
                entityId = entityId,
                conversationId = conversationId,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun senderLookup(ids: List<String>): Map<String, UserEntity> =
        userDao.findByIds(ids.distinct()).associateBy { it.id }

    private fun resolveKind(message: OutgoingMessage): MessageKind = when {
        message.pollQuestion != null -> MessageKind.POLL
        message.location != null -> MessageKind.LOCATION
        message.contactUserId != null -> MessageKind.CONTACT
        message.kind != MessageKind.TEXT -> message.kind
        message.attachmentPaths.isNotEmpty() -> MessageKind.DOCUMENT
        else -> MessageKind.TEXT
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}

/** Lets repositories ask for a sync without depending on WorkManager directly. */
interface SyncTrigger {
    fun requestSync()
    fun requestMediaTransfer()
}

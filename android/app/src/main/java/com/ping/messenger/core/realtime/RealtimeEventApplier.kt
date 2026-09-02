package com.ping.messenger.core.realtime

import android.util.Log
import androidx.room.withTransaction
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.core.notification.MessageNotifier
import com.ping.messenger.core.work.SyncScheduler
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.StatusDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.local.entity.ReceiptEntity
import com.ping.messenger.data.local.entity.ReceiptType
import com.ping.messenger.data.local.entity.ReactionEntity
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.ws.RealtimeClient
import com.ping.messenger.data.remote.ws.RealtimeEvent
import com.ping.messenger.data.repository.MessageRepositoryImpl
import com.ping.messenger.di.ApplicationScope
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.domain.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies inbound realtime events to the local database.
 *
 * This is what makes the UI reactive without any screen subscribing to the socket: an incoming
 * message is written to Room, and every Flow watching that table updates. The chat list, the
 * open conversation, the tab badge and the notification all follow from one write.
 *
 * Started once from [com.ping.messenger.PingApplication] and lives for the process.
 */
@Singleton
class RealtimeEventApplier @Inject constructor(
    private val realtimeClient: RealtimeClient,
    private val database: PingDatabase,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val statusDao: StatusDao,
    private val messageRepository: MessageRepositoryImpl,
    private val conversationRepository: ConversationRepository,
    private val notifier: MessageNotifier,
    private val tokenStore: TokenStore,
    private val syncScheduler: SyncScheduler,
    private val mapper: EntityMapper,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true

        scope.launch {
            realtimeClient.events.collect { event ->
                runCatching { apply(event) }
                    .onFailure { Log.w(TAG, "failed to apply ${event::class.simpleName}", it) }
            }
        }
    }

    private suspend fun apply(event: RealtimeEvent) {
        val me = tokenStore.currentUserId ?: return

        when (event) {
            is RealtimeEvent.NewMessage -> {
                val entity = messageRepository.decryptToEntity(event.message)
                val isMine = entity.senderId == me

                database.withTransaction {
                    messageDao.saveMessage(entity)
                    conversationDao.touchLastMessage(
                        entity.conversationId,
                        entity.id,
                        entity.createdAt,
                    )
                    if (!isMine) {
                        // A mention bumps a separate counter so the chat list can show the
                        // @ marker independently of the plain unread count.
                        val mentionsMe = event.message.mentions.contains(me)
                        conversationDao.incrementUnread(
                            entity.conversationId,
                            if (mentionsMe) 1 else 0,
                        )
                    }
                }

                if (!isMine) notifyFor(entity.conversationId)
            }

            is RealtimeEvent.MessageUpdated -> {
                val entity = messageRepository.decryptToEntity(event.message)
                messageDao.saveMessage(entity)
            }

            is RealtimeEvent.MessageDeleted -> {
                messageDao.softDelete(event.messageId, event.forEveryone)
            }

            is RealtimeEvent.ReceiptUpdate -> {
                val type = if (event.type == "READ") ReceiptType.READ else ReceiptType.DELIVERED
                val status = if (type == ReceiptType.READ) MessageStatus.READ else MessageStatus.DELIVERED

                database.withTransaction {
                    messageDao.upsertReceipts(
                        event.messageIds.map {
                            ReceiptEntity(it, event.userId, type, event.at)
                        },
                    )
                    // advanceStatus, not setStatus: a DELIVERED receipt arriving after READ
                    // must not downgrade the tick.
                    event.messageIds.forEach { messageDao.advanceStatus(it, status) }
                }
            }

            is RealtimeEvent.ReactionUpdate -> {
                val row = ReactionEntity(
                    messageId = event.messageId,
                    userId = event.reaction.userId,
                    emoji = event.reaction.emoji,
                    createdAt = event.reaction.createdAt,
                )
                if (event.removed) {
                    messageDao.deleteReaction(row)
                } else {
                    messageDao.upsertReactions(listOf(row))
                }
            }

            is RealtimeEvent.Typing -> {
                messageRepository.applyTyping(event.conversationId, event.userName, event.isTyping)
            }

            is RealtimeEvent.PresenceUpdate -> {
                userDao.setPresence(
                    id = event.userId,
                    online = event.isOnline,
                    lastSeenAt = event.lastSeenAt,
                    hidden = event.hidden,
                )
            }

            is RealtimeEvent.UserUpdated -> {
                with(mapper) {
                    userDao.upsert(event.user.toEntity(userDao.findById(event.user.id)))
                }
            }

            is RealtimeEvent.ConversationUpserted,
            is RealtimeEvent.GroupUpdated,
            -> {
                // Group and conversation shapes involve several tables; a full refresh is
                // simpler and cheap enough at the frequency these change.
                conversationRepository.refresh()
            }

            is RealtimeEvent.StatusPosted -> {
                with(mapper) { statusDao.upsert(event.status.toEntity(me)) }
            }

            is RealtimeEvent.StatusDeleted -> statusDao.delete(event.statusId)

            is RealtimeEvent.ServerError -> {
                Log.w(TAG, "server error: ${event.code} ${event.message}")
            }

            // Call signalling is handled by CallManager, which observes the same event flow.
            else -> Unit
        }
    }

    /** Posts or updates the notification for a conversation that just received a message. */
    private suspend fun notifyFor(conversationId: String) {
        val conversation = conversationRepository.observeConversation(conversationId).first()
            ?: return
        val history = messageRepository.observeRecent(conversationId, limit = 6).first()
        val me = tokenStore.currentUserId?.let { userDao.findById(it) }

        notifier.notifyConversation(
            conversation = conversation,
            // MessagingStyle wants oldest first; the DAO returns newest first.
            history = history.reversed(),
            selfName = me?.displayName.orEmpty(),
        )
    }

    private companion object {
        const val TAG = "RealtimeEventApplier"
    }
}

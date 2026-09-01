package com.ping.messenger.data.repository

import androidx.room.withTransaction
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.FolderDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.local.entity.ChatFolderEntity
import com.ping.messenger.data.local.entity.ChatFolderMemberEntity
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.OutboxEntity
import com.ping.messenger.data.local.entity.OutboxOperation
import com.ping.messenger.data.local.dao.OutboxDao
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.CreateConversationRequest
import com.ping.messenger.data.remote.dto.ReadReceiptRequest
import com.ping.messenger.domain.model.ChatFolder
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.repository.ConversationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The chat list and per-conversation settings.
 *
 * Chat-list state (pin, mute, archive, read) is applied locally first and reconciled with the
 * server afterwards. These are low-stakes, high-frequency actions where a spinner would be
 * more annoying than a rare, silently-retried divergence.
 */
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val database: PingDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val folderDao: FolderDao,
    private val outboxDao: OutboxDao,
    private val tokenStore: TokenStore,
    private val mapper: EntityMapper,
    private val messageRepository: MessageRepositoryImpl,
    private val syncTrigger: SyncTrigger,
) : ConversationRepository {

    override fun observeChats(archived: Boolean): Flow<List<Conversation>> =
        combine(
            conversationDao.observeConversations(archived),
            folderDao.observeAll(),
        ) { rows, folders ->
            val membership = folders.flatMap { folder ->
                folder.members.map { it.conversationId to folder.folder.id }
            }.groupBy({ it.first }, { it.second })

            rows.map { row ->
                with(mapper) {
                    row.toDomain(folderIds = membership[row.conversation.id].orEmpty().toSet())
                }
            }
        }

    override fun observeConversation(id: String): Flow<Conversation?> =
        combine(
            conversationDao.observeConversations(archived = false),
            conversationDao.observeConversations(archived = true),
            messageRepository.observeTyping(id),
        ) { active, archived, typingNames ->
            (active + archived).firstOrNull { it.conversation.id == id }?.let { row ->
                with(mapper) { row.toDomain(typingNames = typingNames) }
            }
        }

    override fun observeArchivedCount(): Flow<Int> = conversationDao.observeArchivedCount()

    override fun observeTotalUnread(): Flow<Int> = conversationDao.observeTotalUnread()

    override fun observeFolders(): Flow<List<ChatFolder>> = folderDao.observeAll().map { rows ->
        rows.map { row ->
            ChatFolder(
                id = row.folder.id,
                name = row.folder.name,
                emoji = row.folder.emoji,
                conversationIds = row.members.map { it.conversationId }.toSet(),
                includeGroups = row.folder.includeGroups,
                includeUnreadOnly = row.folder.includeUnreadOnly,
                sortOrder = row.folder.sortOrder,
            )
        }
    }

    /**
     * Opens (or creates) the one-to-one chat with [userId].
     *
     * If a local conversation already exists it is returned without a round trip, so tapping a
     * contact is instant even offline.
     */
    override suspend fun openDirectChat(userId: String): Outcome<String> = runCatchingAppSuspend {
        conversationDao.findDirectWith(userId)?.let { return@runCatchingAppSuspend it.id }

        val user = userDao.findById(userId)
            ?: with(mapper) {
                api.user(userId).let { dto ->
                    dto.toEntity(null).also { userDao.upsert(it) }
                }
            }

        val dto = api.createConversation(
            CreateConversationRequest(participantIds = listOf(userId), type = "DIRECT"),
        )
        val entity = ConversationEntity(
            id = dto.id,
            type = ConversationType.DIRECT,
            title = user.displayName,
            avatarUrl = user.avatarUrl,
            otherUserId = userId,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
            lastActivityAt = dto.updatedAt,
            disappearingAfterMs = dto.disappearingAfterMs,
        )
        conversationDao.upsert(entity)
        entity.id
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        conversationDao.setPinned(id, pinned, System.currentTimeMillis())
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        conversationDao.setArchived(id, archived, System.currentTimeMillis())
    }

    override suspend fun setMuted(id: String, muted: Boolean, until: Long?) {
        conversationDao.setMuted(id, muted, until, System.currentTimeMillis())
    }

    /**
     * Marks the conversation read locally and, if the user has read receipts enabled, tells the
     * server. The receipt goes through the outbox so it survives being offline.
     */
    override suspend fun markRead(id: String) {
        val latest = messageDao.observeRecent(id, 1).first().firstOrNull()?.message
        database.withTransaction {
            conversationDao.clearUnread(id, System.currentTimeMillis())
            messageDao.markIncomingRead(id)
            if (latest != null && !latest.isOutgoing) {
                outboxDao.upsert(
                    OutboxEntity(
                        operation = OutboxOperation.READ_RECEIPT,
                        entityId = latest.id,
                        conversationId = id,
                        payload = latest.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        syncTrigger.requestSync()
    }

    override suspend fun markUnread(id: String) {
        conversationDao.markUnread(id, System.currentTimeMillis())
    }

    override suspend fun saveDraft(id: String, draft: String?) {
        conversationDao.setDraft(id, draft?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
    }

    override suspend fun saveNotes(id: String, notes: String) = conversationDao.setNotes(id, notes)

    override suspend fun setWallpaper(id: String, wallpaperId: String?) =
        conversationDao.setWallpaper(id, wallpaperId)

    override suspend fun setDisappearing(id: String, duration: Duration?): Outcome<Unit> =
        runCatchingAppSuspend {
            val millis = duration?.inWholeMilliseconds
            conversationDao.setDisappearing(id, millis)
            api.setDisappearing(id, millis)
            Unit
        }

    override suspend fun setNotificationSettings(id: String, enabled: Boolean, sound: String?) =
        conversationDao.setNotificationSettings(id, enabled, sound)

    override suspend fun delete(id: String): Outcome<Unit> = runCatchingAppSuspend {
        conversationDao.delete(id)
        api.deleteConversation(id)
        Unit
    }

    override suspend fun refresh(): Outcome<Unit> = runCatchingAppSuspend {
        val remote = api.conversations()
        database.withTransaction {
            for (dto in remote) {
                val existing = conversationDao.findById(dto.id)
                dto.otherUser?.let { other ->
                    with(mapper) { userDao.upsert(other.toEntity(userDao.findById(other.id))) }
                }
                conversationDao.upsert(
                    ConversationEntity(
                        id = dto.id,
                        type = if (dto.type == "GROUP") ConversationType.GROUP else ConversationType.DIRECT,
                        title = dto.title.ifBlank { dto.otherUser?.displayName.orEmpty() },
                        avatarUrl = dto.avatarUrl ?: dto.otherUser?.avatarUrl,
                        otherUserId = dto.otherUser?.id,
                        // Local-only flags must survive a refresh.
                        isPinned = existing?.isPinned ?: false,
                        isArchived = existing?.isArchived ?: false,
                        isMuted = existing?.isMuted ?: false,
                        mutedUntil = existing?.mutedUntil,
                        markedUnread = existing?.markedUnread ?: false,
                        unreadCount = dto.unreadCount,
                        mentionCount = dto.mentionCount,
                        draft = existing?.draft,
                        draftUpdatedAt = existing?.draftUpdatedAt ?: 0,
                        lastMessageId = dto.lastMessage?.id ?: existing?.lastMessageId,
                        lastActivityAt = dto.lastMessage?.createdAt ?: dto.updatedAt,
                        disappearingAfterMs = dto.disappearingAfterMs,
                        wallpaperId = existing?.wallpaperId,
                        pinnedMessageId = dto.pinnedMessageId,
                        notes = existing?.notes.orEmpty(),
                        notificationSound = existing?.notificationSound,
                        notificationsEnabled = existing?.notificationsEnabled ?: true,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt,
                    ),
                )
                dto.lastMessage?.let { message ->
                    messageDao.saveMessage(messageRepository.decryptToEntity(message))
                }
            }
        }
    }

    override suspend fun searchConversations(query: String): List<Conversation> {
        if (query.isBlank()) return emptyList()
        val ids = conversationDao.search(query).map { it.id }.toSet()
        return observeChats(archived = false).first().filter { it.id in ids }
    }

    // ---- Folders ----------------------------------------------------------

    override suspend fun createFolder(name: String, emoji: String?, conversationIds: Set<String>) {
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            folderDao.upsert(ChatFolderEntity(id = id, name = name, emoji = emoji))
            folderDao.addMembers(conversationIds.map { ChatFolderMemberEntity(id, it) })
        }
    }

    override suspend fun updateFolder(folder: ChatFolder) {
        database.withTransaction {
            folderDao.upsert(
                ChatFolderEntity(
                    id = folder.id,
                    name = folder.name,
                    emoji = folder.emoji,
                    includeGroups = folder.includeGroups,
                    includeUnreadOnly = folder.includeUnreadOnly,
                    sortOrder = folder.sortOrder,
                ),
            )
            folderDao.clearMembers(folder.id)
            folderDao.addMembers(folder.conversationIds.map { ChatFolderMemberEntity(folder.id, it) })
        }
    }

    override suspend fun deleteFolder(id: String) = folderDao.delete(id)
}

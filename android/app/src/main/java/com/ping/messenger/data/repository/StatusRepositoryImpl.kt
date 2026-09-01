package com.ping.messenger.data.repository

import androidx.room.withTransaction
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.StatusDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.CreateStatusRequest
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.StatusThread
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.OutgoingMessage
import com.ping.messenger.domain.repository.StatusRepository
import com.ping.messenger.domain.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Status updates ("stories").
 *
 * Expiry is enforced in three places on purpose: the query filters on `expiresAt`, a periodic
 * worker deletes expired rows, and the server refuses to serve them. Relying on any one of
 * those alone leaves a window where an expired post is still visible.
 */
@Singleton
class StatusRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val database: PingDatabase,
    private val statusDao: StatusDao,
    private val userDao: UserDao,
    private val tokenStore: TokenStore,
    private val mediaRepository: MediaRepository,
    private val messageRepository: Provider<MessageRepository>,
    private val conversationRepository: Provider<ConversationRepository>,
    private val mapper: EntityMapper,
) : StatusRepository {

    private val now: Long get() = System.currentTimeMillis()

    override fun observeThreads(): Flow<List<StatusThread>> =
        statusDao.observeActive(now).map { rows ->
            val authors = userDao.findByIds(rows.map { it.post.authorId }.distinct())
                .associateBy { it.id }
            rows.groupBy { it.post.authorId }
                .map { (authorId, posts) ->
                    val author = authors[authorId]
                    StatusThread(
                        authorId = authorId,
                        authorName = author?.displayName.orEmpty(),
                        authorAvatarUrl = author?.avatarUrl,
                        posts = posts
                            .sortedBy { it.post.createdAt }
                            .map { with(mapper) { it.toDomain(author) } },
                        isMine = posts.firstOrNull()?.post?.isMine == true,
                    )
                }
                // Unseen threads first, then most recent — the ordering every story UI uses,
                // because it puts the thing the user came for at the front of the rail.
                .sortedWith(
                    compareByDescending<StatusThread> { it.isMine }
                        .thenByDescending { it.hasUnseen }
                        .thenByDescending { it.latestAt },
                )
        }

    override fun observeMyThread(): Flow<StatusThread?> =
        statusDao.observeMine(now).map { rows ->
            if (rows.isEmpty()) return@map null
            val me = tokenStore.currentUserId?.let { userDao.findById(it) }
            StatusThread(
                authorId = me?.id.orEmpty(),
                authorName = me?.displayName.orEmpty(),
                authorAvatarUrl = me?.avatarUrl,
                posts = rows.sortedBy { it.post.createdAt }.map { with(mapper) { it.toDomain(me) } },
                isMine = true,
            )
        }

    override fun observeUnseenCount(): Flow<Int> = statusDao.observeUnseenCount(now)

    override suspend fun post(
        kind: StatusKind,
        text: String,
        localMediaPath: String?,
        backgroundColor: Long?,
    ): Outcome<Unit> = runCatchingAppSuspend {
        val mediaUrl = localMediaPath?.let { path ->
            val id = java.util.UUID.randomUUID().toString()
            val mime = if (kind == StatusKind.VIDEO) "video/mp4" else "image/jpeg"
            when (
                val upload = mediaRepository.upload(
                    id, path, mime,
                    if (kind == StatusKind.VIDEO) {
                        com.ping.messenger.domain.model.MessageKind.VIDEO
                    } else {
                        com.ping.messenger.domain.model.MessageKind.IMAGE
                    },
                )
            ) {
                is Outcome.Success -> upload.value
                is Outcome.Failure -> throw upload.error
            }
        }

        val dto = api.createStatus(
            CreateStatusRequest(
                kind = kind.name,
                text = text,
                mediaUrl = mediaUrl,
                backgroundColor = backgroundColor,
            ),
        )
        with(mapper) {
            statusDao.upsert(
                dto.toEntity(tokenStore.currentUserId.orEmpty()).copy(localPath = localMediaPath),
            )
        }
    }

    override suspend fun markSeen(statusId: String) {
        statusDao.markSeen(statusId)
        runCatching { api.viewStatus(statusId) }
    }

    override suspend fun delete(statusId: String): Outcome<Unit> = runCatchingAppSuspend {
        statusDao.delete(statusId)
        api.deleteStatus(statusId)
        Unit
    }

    override suspend fun refresh(): Outcome<Unit> = runCatchingAppSuspend {
        val remote = api.statuses()
        val me = tokenStore.currentUserId.orEmpty()
        database.withTransaction {
            with(mapper) { statusDao.upsertAll(remote.map { it.toEntity(me) }) }
            statusDao.deleteExpired(now)
        }
    }

    override suspend fun purgeExpired(): Int = statusDao.deleteExpired(now)

    /** A status reply is an ordinary direct message that quotes the status. */
    override suspend fun replyTo(
        statusId: String,
        authorId: String,
        text: String,
    ): Outcome<Unit> = runCatchingAppSuspend {
        val conversationId = when (val opened = conversationRepository.get().openDirectChat(authorId)) {
            is Outcome.Success -> opened.value
            is Outcome.Failure -> throw opened.error
        }
        val post = statusDao.findById(statusId)
        val quoted = post?.text?.takeIf { it.isNotBlank() }?.let { "“$it”\n\n" }.orEmpty()
        messageRepository.get().send(
            OutgoingMessage(conversationId = conversationId, text = quoted + text),
        )
        Unit
    }
}

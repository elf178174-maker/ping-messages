package com.ping.messenger.data.repository

import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.dao.GroupDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.BlockRequest
import com.ping.messenger.data.remote.dto.ContactDiscoveryRequest
import com.ping.messenger.data.remote.dto.ReportRequest
import com.ping.messenger.data.remote.dto.UpdateProfileRequest
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.domain.repository.UserRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val userDao: UserDao,
    private val groupDao: GroupDao,
    private val tokenStore: TokenStore,
    private val crypto: CryptoService,
    private val mediaRepository: MediaRepository,
    private val mapper: EntityMapper,
) : UserRepository {

    override fun observeMe(): Flow<User?> = tokenStore.signedInUserId.flatMapLatest { id ->
        if (id == null) flowOf(null) else observeUser(id)
    }

    override fun observeUser(id: String): Flow<User?> = userDao.observeById(id).map { entity ->
        entity?.let { with(mapper) { it.toDomain() } }
    }

    override fun observeContacts(): Flow<List<User>> = userDao.observeContacts().map { list ->
        with(mapper) { list.map { it.toDomain() } }
    }

    override fun observeBlocked(): Flow<List<User>> = userDao.observeBlocked().map { list ->
        with(mapper) { list.map { it.toDomain() } }
    }

    override suspend fun findByUsername(username: String): Outcome<User> = runCatchingAppSuspend {
        val clean = username.removePrefix("@").trim().lowercase()
        val dto = api.userByUsername(clean)
        with(mapper) {
            val entity = dto.toEntity(userDao.findById(dto.id))
            userDao.upsert(entity)
            entity.toDomain()
        }
    }

    override suspend fun searchUsers(query: String): Outcome<List<User>> = runCatchingAppSuspend {
        val results = api.searchUsers(query.removePrefix("@").trim())
        with(mapper) {
            val entities = results.map { it.toEntity(userDao.findById(it.id)) }
            userDao.upsertAll(entities)
            entities.map { it.toDomain() }
        }
    }

    override suspend fun searchLocal(query: String): List<User> =
        with(mapper) { userDao.search(query).map { it.toDomain() } }

    override suspend fun addContact(userId: String): Outcome<Unit> = runCatchingAppSuspend {
        userDao.setContact(userId, true)
        api.addContact(userId)
        Unit
    }

    override suspend fun removeContact(userId: String): Outcome<Unit> = runCatchingAppSuspend {
        userDao.setContact(userId, false)
        api.removeContact(userId)
        Unit
    }

    override suspend fun block(userId: String): Outcome<Unit> = runCatchingAppSuspend {
        userDao.setBlocked(userId, true)
        api.block(BlockRequest(userId))
        Unit
    }

    override suspend fun unblock(userId: String): Outcome<Unit> = runCatchingAppSuspend {
        userDao.setBlocked(userId, false)
        api.unblock(userId)
        Unit
    }

    override suspend fun report(
        userId: String,
        reason: String,
        messageIds: List<String>,
        note: String?,
    ): Outcome<Unit> = runCatchingAppSuspend {
        api.report(ReportRequest(userId, reason, messageIds, note))
        Unit
    }

    override suspend fun updateProfile(
        displayName: String?,
        about: String?,
        username: String?,
    ): Outcome<User> = runCatchingAppSuspend {
        val dto = api.updateProfile(
            UpdateProfileRequest(
                displayName = displayName?.trim(),
                about = about?.trim(),
                username = username?.trim()?.lowercase(),
            ),
        )
        with(mapper) {
            val entity = dto.toEntity(userDao.findById(dto.id))
            userDao.upsert(entity)
            entity.toDomain()
        }
    }

    override suspend fun updateAvatar(localPath: String): Outcome<User> = runCatchingAppSuspend {
        val attachmentId = java.util.UUID.randomUUID().toString()
        val url = when (
            val upload = mediaRepository.upload(
                attachmentId = attachmentId,
                localPath = localPath,
                mimeType = "image/jpeg",
                kind = com.ping.messenger.domain.model.MessageKind.IMAGE,
            )
        ) {
            is Outcome.Success -> upload.value
            is Outcome.Failure -> throw upload.error
        }
        val dto = api.updateProfile(UpdateProfileRequest(avatarUrl = url))
        with(mapper) {
            val entity = dto.toEntity(userDao.findById(dto.id))
            userDao.upsert(entity)
            entity.toDomain()
        }
    }

    override suspend fun groupsInCommon(userId: String): List<Group> =
        groupDao.groupsInCommon(userId).mapNotNull { entity ->
            groupDao.findById(entity.id)?.let { with(mapper) { it.toDomain() } }
        }

    override suspend fun refreshContacts(): Outcome<Unit> = runCatchingAppSuspend {
        val remote = api.contacts()
        with(mapper) {
            userDao.upsertAll(
                remote.map { it.toEntity(userDao.findById(it.id)).copy(isContact = true) },
            )
        }
        val blocked = api.blockedUsers()
        with(mapper) {
            userDao.upsertAll(
                blocked.map { it.toEntity(userDao.findById(it.id)).copy(isBlocked = true) },
            )
        }
    }

    override suspend fun discoverFromPhoneContacts(
        hashedNumbers: List<String>,
    ): Outcome<List<User>> = runCatchingAppSuspend {
        val response = api.discoverContacts(ContactDiscoveryRequest(hashedNumbers))
        with(mapper) {
            val entities = response.matches.values.map {
                it.toEntity(userDao.findById(it.id)).copy(isContact = true)
            }
            userDao.upsertAll(entities)
            entities.map { it.toDomain() }
        }
    }

    /**
     * The safety number for a conversation: a stable, order-independent combination of both
     * parties' key fingerprints, so both devices display the same string.
     */
    override suspend fun securityCodeFor(userId: String): String? {
        val theirs = userDao.findById(userId)?.publicKey ?: return null
        val mine = crypto.publicKey() ?: return null
        val fingerprints = listOf(crypto.fingerprintOf(mine), crypto.fingerprintOf(theirs)).sorted()
        return fingerprints.joinToString("\n")
    }
}

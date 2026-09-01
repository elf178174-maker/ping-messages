package com.ping.messenger.data.repository

import androidx.room.withTransaction
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.GroupDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.local.entity.ConversationEntity
import com.ping.messenger.data.local.entity.GroupMemberEntity
import com.ping.messenger.data.local.entity.UserEntity
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.mapper.enumOf
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.CreateGroupRequest
import com.ping.messenger.data.remote.dto.GroupDto
import com.ping.messenger.data.remote.dto.GroupMembersRequest
import com.ping.messenger.data.remote.dto.SetRoleRequest
import com.ping.messenger.data.remote.dto.UpdateGroupRequest
import com.ping.messenger.domain.model.ConversationType
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.repository.GroupRepository
import com.ping.messenger.domain.repository.MediaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val database: PingDatabase,
    private val groupDao: GroupDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val mediaRepository: MediaRepository,
    private val mapper: EntityMapper,
) : GroupRepository {

    override fun observeGroup(conversationId: String): Flow<Group?> =
        groupDao.observeByConversation(conversationId).flatMapMembers()

    /**
     * Joins the membership rows to their user profiles.
     *
     * Done as a second observation rather than one big SQL join so that a profile update (a new
     * avatar, a name change) refreshes the member list without the group row itself changing.
     */
    private fun Flow<com.ping.messenger.data.local.dao.GroupWithMembers?>.flatMapMembers(): Flow<Group?> =
        kotlinx.coroutines.flow.flow {
            collect { row ->
                if (row == null) {
                    emit(null)
                } else {
                    val profiles = userDao.findByIds(row.members.map { it.userId })
                        .associateBy { it.id }
                    emit(with(mapper) { row.toDomain(profiles) })
                }
            }
        }

    override suspend fun create(
        name: String,
        description: String,
        memberIds: List<String>,
    ): Outcome<String> = runCatchingAppSuspend {
        val dto = api.createGroup(CreateGroupRequest(name.trim(), description.trim(), memberIds))
        persist(dto)
        dto.conversationId
    }

    override suspend fun updateInfo(
        groupId: String,
        name: String?,
        description: String?,
    ): Outcome<Unit> = runCatchingAppSuspend {
        val dto = api.updateGroup(groupId, UpdateGroupRequest(name = name?.trim(), description = description?.trim()))
        persist(dto)
    }

    override suspend fun updateAvatar(groupId: String, localPath: String): Outcome<Unit> =
        runCatchingAppSuspend {
            val attachmentId = java.util.UUID.randomUUID().toString()
            val url = when (
                val upload = mediaRepository.upload(
                    attachmentId, localPath, "image/jpeg",
                    com.ping.messenger.domain.model.MessageKind.IMAGE,
                )
            ) {
                is Outcome.Success -> upload.value
                is Outcome.Failure -> throw upload.error
            }
            persist(api.updateGroup(groupId, UpdateGroupRequest(avatarUrl = url)))
        }

    override suspend fun addMembers(groupId: String, userIds: List<String>): Outcome<Unit> =
        runCatchingAppSuspend { persist(api.addMembers(groupId, GroupMembersRequest(userIds))) }

    override suspend fun removeMember(groupId: String, userId: String): Outcome<Unit> =
        runCatchingAppSuspend { persist(api.removeMember(groupId, userId)) }

    override suspend fun setRole(groupId: String, userId: String, role: GroupRole): Outcome<Unit> =
        runCatchingAppSuspend { persist(api.setMemberRole(groupId, userId, SetRoleRequest(role.name))) }

    override suspend fun setPermissions(
        groupId: String,
        send: GroupPermission,
        editInfo: GroupPermission,
        addMembers: GroupPermission,
    ): Outcome<Unit> = runCatchingAppSuspend {
        persist(
            api.updateGroup(
                groupId,
                UpdateGroupRequest(
                    sendPermission = send.name,
                    editInfoPermission = editInfo.name,
                    addMembersPermission = addMembers.name,
                ),
            ),
        )
    }

    override suspend fun leave(groupId: String): Outcome<Unit> = runCatchingAppSuspend {
        api.leaveGroup(groupId)
        val group = groupDao.findById(groupId)
        group?.let { conversationDao.delete(it.group.conversationId) }
        Unit
    }

    override suspend fun inviteLink(groupId: String): Outcome<String> = runCatchingAppSuspend {
        api.inviteLink(groupId).url
    }

    override suspend fun resetInviteLink(groupId: String): Outcome<String> = runCatchingAppSuspend {
        val link = api.resetInviteLink(groupId)
        groupDao.updateInviteCode(groupId, link.code)
        link.url
    }

    override suspend fun joinByCode(code: String): Outcome<String> = runCatchingAppSuspend {
        val dto = api.joinByInvite(code.substringAfterLast('/'))
        persist(dto)
        dto.conversationId
    }

    /**
     * Writes a group and its conversation shell together.
     *
     * The conversation row must exist before the group row because of the foreign key, and the
     * group's name/avatar are mirrored onto the conversation so the chat list can render
     * without joining.
     */
    private suspend fun persist(dto: GroupDto) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val existing = conversationDao.findById(dto.conversationId)
            conversationDao.upsert(
                ConversationEntity(
                    id = dto.conversationId,
                    type = ConversationType.GROUP,
                    title = dto.name,
                    avatarUrl = dto.avatarUrl,
                    isPinned = existing?.isPinned ?: false,
                    isArchived = existing?.isArchived ?: false,
                    isMuted = existing?.isMuted ?: false,
                    mutedUntil = existing?.mutedUntil,
                    unreadCount = existing?.unreadCount ?: 0,
                    mentionCount = existing?.mentionCount ?: 0,
                    draft = existing?.draft,
                    lastMessageId = existing?.lastMessageId,
                    lastActivityAt = existing?.lastActivityAt ?: now,
                    wallpaperId = existing?.wallpaperId,
                    notes = existing?.notes.orEmpty(),
                    notificationsEnabled = existing?.notificationsEnabled ?: true,
                    createdAt = dto.createdAt,
                    updatedAt = now,
                ),
            )

            with(mapper) { groupDao.upsert(dto.toEntity()) }

            // Member profiles arrive inline with the group, which saves a fan-out of /users
            // calls when opening a large group for the first time.
            userDao.upsertAll(
                dto.members.map { member ->
                    val existingUser = userDao.findById(member.userId)
                    UserEntity(
                        id = member.userId,
                        username = member.username.ifBlank { existingUser?.username.orEmpty() },
                        displayName = member.displayName.ifBlank { existingUser?.displayName.orEmpty() },
                        about = existingUser?.about.orEmpty(),
                        avatarUrl = member.avatarUrl ?: existingUser?.avatarUrl,
                        isContact = existingUser?.isContact ?: false,
                        isBlocked = existingUser?.isBlocked ?: false,
                        publicKey = member.publicKey ?: existingUser?.publicKey,
                        keyFingerprint = existingUser?.keyFingerprint,
                        updatedAt = now,
                    )
                },
            )

            groupDao.clearMembers(dto.id)
            groupDao.upsertMembers(
                dto.members.map {
                    GroupMemberEntity(
                        groupId = dto.id,
                        userId = it.userId,
                        role = enumOf(it.role, GroupRole.MEMBER),
                        joinedAt = it.joinedAt,
                    )
                },
            )
        }
    }
}

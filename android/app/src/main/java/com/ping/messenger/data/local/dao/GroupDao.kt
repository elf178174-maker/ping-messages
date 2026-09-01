package com.ping.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.ping.messenger.data.local.entity.GroupEntity
import com.ping.messenger.data.local.entity.GroupMemberEntity
import com.ping.messenger.data.local.entity.UserEntity
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import kotlinx.coroutines.flow.Flow

data class GroupWithMembers(
    @Embedded val group: GroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val members: List<GroupMemberEntity> = emptyList(),
)

/** A membership row joined to the member's profile, which is what the member list renders. */
data class GroupMemberWithUser(
    @Embedded val member: GroupMemberEntity,
    @Embedded(prefix = "u_") val user: UserEntity?,
)

@Dao
interface GroupDao {

    @Upsert
    suspend fun upsert(group: GroupEntity)

    @Upsert
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Transaction
    @Query("SELECT * FROM groups WHERE conversationId = :conversationId")
    fun observeByConversation(conversationId: String): Flow<GroupWithMembers?>

    @Transaction
    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun findById(id: String): GroupWithMembers?

    @Query("SELECT * FROM groups WHERE conversationId = :conversationId")
    suspend fun findByConversation(conversationId: String): GroupEntity?

    @Query(
        """
        SELECT gm.*,
               u.id AS u_id, u.username AS u_username, u.displayName AS u_displayName,
               u.about AS u_about, u.avatarUrl AS u_avatarUrl, u.phoneNumber AS u_phoneNumber,
               u.isContact AS u_isContact, u.isBlocked AS u_isBlocked, u.isOnline AS u_isOnline,
               u.lastSeenAt AS u_lastSeenAt, u.presenceHidden AS u_presenceHidden,
               u.publicKey AS u_publicKey, u.keyFingerprint AS u_keyFingerprint,
               u.updatedAt AS u_updatedAt
        FROM group_members gm
        LEFT JOIN users u ON u.id = gm.userId
        WHERE gm.groupId = :groupId
        ORDER BY
            CASE gm.role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,
            u.displayName COLLATE NOCASE ASC
        """,
    )
    fun observeMembers(groupId: String): Flow<List<GroupMemberWithUser>>

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND userId = :userId")
    suspend fun removeMember(groupId: String, userId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("UPDATE group_members SET role = :role WHERE groupId = :groupId AND userId = :userId")
    suspend fun setRole(groupId: String, userId: String, role: GroupRole)

    @Query("UPDATE groups SET name = :name, description = :description WHERE id = :id")
    suspend fun updateInfo(id: String, name: String, description: String)

    @Query("UPDATE groups SET avatarUrl = :avatarUrl WHERE id = :id")
    suspend fun updateAvatar(id: String, avatarUrl: String?)

    @Query("UPDATE groups SET inviteCode = :code WHERE id = :id")
    suspend fun updateInviteCode(id: String, code: String?)

    @Query(
        """
        UPDATE groups
        SET sendPermission = :send, editInfoPermission = :edit, addMembersPermission = :add
        WHERE id = :id
        """,
    )
    suspend fun updatePermissions(
        id: String,
        send: GroupPermission,
        edit: GroupPermission,
        add: GroupPermission,
    )

    @Query("UPDATE groups SET myRole = :role WHERE id = :id")
    suspend fun setMyRole(id: String, role: GroupRole)

    /** Groups the signed-in user shares with [userId], shown on a contact's profile. */
    @Query(
        """
        SELECT g.* FROM groups g
        JOIN group_members gm ON gm.groupId = g.id
        WHERE gm.userId = :userId
        ORDER BY g.name COLLATE NOCASE ASC
        """,
    )
    suspend fun groupsInCommon(userId: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<GroupEntity>
}

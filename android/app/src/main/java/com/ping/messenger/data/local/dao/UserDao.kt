package com.ping.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ping.messenger.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Upsert
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE username = :username COLLATE NOCASE LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE isContact = 1 AND isBlocked = 0 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeContacts(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE isBlocked = 1 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeBlocked(): Flow<List<UserEntity>>

    @Query(
        """
        SELECT * FROM users
        WHERE isBlocked = 0
          AND (displayName LIKE '%' || :query || '%' OR username LIKE '%' || :query || '%')
        ORDER BY isContact DESC, displayName COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 30): List<UserEntity>

    @Query("UPDATE users SET isBlocked = :blocked WHERE id = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    @Query("UPDATE users SET isContact = :isContact WHERE id = :id")
    suspend fun setContact(id: String, isContact: Boolean)

    @Query(
        """
        UPDATE users
        SET isOnline = :online, lastSeenAt = :lastSeenAt, presenceHidden = :hidden
        WHERE id = :id
        """,
    )
    suspend fun setPresence(id: String, online: Boolean, lastSeenAt: Long?, hidden: Boolean)

    @Query("UPDATE users SET publicKey = :publicKey, keyFingerprint = :fingerprint WHERE id = :id")
    suspend fun setPublicKey(id: String, publicKey: String?, fingerprint: String?)

    @Query("SELECT keyFingerprint FROM users WHERE id = :id")
    suspend fun fingerprintOf(id: String): String?

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    /** Export pagination for backups; ordered by id so pages cannot overlap or skip. */
    @Query("SELECT * FROM users ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<UserEntity>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE isContact = 1")
    fun observeContactCount(): Flow<Int>
}

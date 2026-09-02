package com.ping.messenger.domain.repository

import androidx.paging.PagingData
import com.ping.messenger.core.backup.BackupHandle
import com.ping.messenger.core.backup.BackupManifest
import com.ping.messenger.core.backup.RestoreSummary
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.BackupStatus
import com.ping.messenger.domain.model.CallRecord
import com.ping.messenger.domain.model.ChatFolder
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.DeviceSession
import com.ping.messenger.domain.model.GeoPoint
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.domain.model.Reminder
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.StatusThread
import com.ping.messenger.domain.model.User
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/** Where the app is in its session lifecycle. Drives the top-level navigation graph. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class NeedsEmailVerification(val email: String) : AuthState
    data class SignedIn(val user: User) : AuthState
}

interface AuthRepository {
    val authState: Flow<AuthState>
    val currentUserId: String?

    suspend fun register(
        email: String,
        password: String,
        username: String,
        displayName: String,
    ): Outcome<Unit>

    suspend fun login(email: String, password: String, twoStepPin: String? = null): Outcome<Unit>
    suspend fun verifyEmail(email: String, code: String): Outcome<Unit>
    suspend fun resendVerificationCode(email: String): Outcome<Unit>
    suspend fun requestPasswordReset(email: String): Outcome<Unit>
    suspend fun resetPassword(token: String, newPassword: String): Outcome<Unit>
    suspend fun changePassword(current: String, new: String): Outcome<Unit>
    suspend fun setTwoStepPin(pin: String?, currentPassword: String): Outcome<Unit>
    suspend fun isUsernameAvailable(username: String): Outcome<Boolean>
    suspend fun signOut()
    suspend fun deleteAccount(): Outcome<Unit>
    suspend fun refreshCurrentUser(): Outcome<User>
}

interface UserRepository {
    fun observeMe(): Flow<User?>
    fun observeUser(id: String): Flow<User?>
    fun observeContacts(): Flow<List<User>>
    fun observeBlocked(): Flow<List<User>>

    suspend fun findByUsername(username: String): Outcome<User>
    suspend fun searchUsers(query: String): Outcome<List<User>>
    suspend fun searchLocal(query: String): List<User>
    suspend fun addContact(userId: String): Outcome<Unit>
    suspend fun removeContact(userId: String): Outcome<Unit>
    suspend fun block(userId: String): Outcome<Unit>
    suspend fun unblock(userId: String): Outcome<Unit>
    suspend fun report(userId: String, reason: String, messageIds: List<String>, note: String?): Outcome<Unit>
    suspend fun updateProfile(displayName: String?, about: String?, username: String?): Outcome<User>
    suspend fun updateAvatar(localPath: String): Outcome<User>
    suspend fun groupsInCommon(userId: String): List<Group>
    suspend fun refreshContacts(): Outcome<Unit>
    /** Matches local address-book numbers by salted hash; never uploads raw numbers. */
    suspend fun discoverFromPhoneContacts(hashedNumbers: List<String>): Outcome<List<User>>
    suspend fun securityCodeFor(userId: String): String?
}

interface ConversationRepository {
    fun observeChats(archived: Boolean = false): Flow<List<Conversation>>
    fun observeConversation(id: String): Flow<Conversation?>
    fun observeArchivedCount(): Flow<Int>
    fun observeTotalUnread(): Flow<Int>
    fun observeFolders(): Flow<List<ChatFolder>>

    suspend fun openDirectChat(userId: String): Outcome<String>
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun setMuted(id: String, muted: Boolean, until: Long? = null)
    suspend fun markRead(id: String)
    suspend fun markUnread(id: String)
    suspend fun saveDraft(id: String, draft: String?)
    suspend fun saveNotes(id: String, notes: String)
    suspend fun setWallpaper(id: String, wallpaperId: String?)
    suspend fun setDisappearing(id: String, duration: Duration?): Outcome<Unit>
    suspend fun setNotificationSettings(id: String, enabled: Boolean, sound: String?)
    suspend fun delete(id: String): Outcome<Unit>
    suspend fun refresh(): Outcome<Unit>
    suspend fun searchConversations(query: String): List<Conversation>

    suspend fun createFolder(name: String, emoji: String?, conversationIds: Set<String>)
    suspend fun updateFolder(folder: ChatFolder)
    suspend fun deleteFolder(id: String)
}

/** A message being composed, including everything an attachment sheet can add. */
data class OutgoingMessage(
    val conversationId: String,
    val text: String = "",
    val kind: MessageKind = MessageKind.TEXT,
    val attachmentPaths: List<String> = emptyList(),
    val replyToId: String? = null,
    val forwardedFromMessageId: String? = null,
    val mentions: List<String> = emptyList(),
    val location: GeoPoint? = null,
    val contactUserId: String? = null,
    val pollQuestion: String? = null,
    val pollOptions: List<String> = emptyList(),
    val pollAllowsMultiple: Boolean = false,
    val scheduledFor: Long? = null,
)

interface MessageRepository {
    fun pagedMessages(conversationId: String): Flow<PagingData<Message>>
    fun observeRecent(conversationId: String, limit: Int = 50): Flow<List<Message>>
    fun observeMessage(id: String): Flow<Message?>
    fun observeStarred(): Flow<List<Message>>
    fun observeScheduled(conversationId: String? = null): Flow<List<Message>>
    fun observeGalleryMedia(conversationId: String): Flow<List<Attachment>>
    fun observeGalleryDocuments(conversationId: String): Flow<List<Attachment>>

    /** Writes locally and enqueues; returns immediately so the UI can be optimistic. */
    suspend fun send(message: OutgoingMessage): Outcome<String>
    suspend fun retry(messageId: String): Outcome<Unit>
    suspend fun edit(messageId: String, newText: String): Outcome<Unit>
    suspend fun delete(messageId: String, forEveryone: Boolean): Outcome<Unit>
    suspend fun toggleReaction(messageId: String, emoji: String): Outcome<Unit>
    suspend fun setStarred(messageId: String, starred: Boolean): Outcome<Unit>
    suspend fun forward(messageIds: List<String>, toConversationIds: List<String>): Outcome<Unit>
    suspend fun votePoll(messageId: String, optionIds: List<String>): Outcome<Unit>
    suspend fun setPinnedMessage(conversationId: String, messageId: String?): Outcome<Unit>
    suspend fun loadOlder(conversationId: String): Outcome<Int>
    suspend fun searchInConversation(conversationId: String, query: String): List<Message>
    suspend fun cancelScheduled(messageId: String)
    suspend fun translate(messageId: String, targetLanguage: String): Outcome<Unit>
    suspend fun clearTranslation(messageId: String)
    suspend fun positionOf(conversationId: String, messageId: String): Int?
    suspend fun setTyping(conversationId: String, typing: Boolean)
    fun observeTyping(conversationId: String): Flow<List<String>>
}

interface GroupRepository {
    fun observeGroup(conversationId: String): Flow<Group?>

    suspend fun create(name: String, description: String, memberIds: List<String>): Outcome<String>
    suspend fun updateInfo(groupId: String, name: String?, description: String?): Outcome<Unit>
    suspend fun updateAvatar(groupId: String, localPath: String): Outcome<Unit>
    suspend fun addMembers(groupId: String, userIds: List<String>): Outcome<Unit>
    suspend fun removeMember(groupId: String, userId: String): Outcome<Unit>
    suspend fun setRole(groupId: String, userId: String, role: GroupRole): Outcome<Unit>
    suspend fun setPermissions(
        groupId: String,
        send: GroupPermission,
        editInfo: GroupPermission,
        addMembers: GroupPermission,
    ): Outcome<Unit>
    suspend fun leave(groupId: String): Outcome<Unit>
    suspend fun inviteLink(groupId: String): Outcome<String>
    suspend fun resetInviteLink(groupId: String): Outcome<String>
    suspend fun joinByCode(code: String): Outcome<String>
}

interface StatusRepository {
    fun observeThreads(): Flow<List<StatusThread>>
    fun observeMyThread(): Flow<StatusThread?>
    fun observeUnseenCount(): Flow<Int>

    suspend fun post(
        kind: StatusKind,
        text: String,
        localMediaPath: String?,
        backgroundColor: Long?,
    ): Outcome<Unit>
    suspend fun markSeen(statusId: String)
    suspend fun delete(statusId: String): Outcome<Unit>
    suspend fun refresh(): Outcome<Unit>
    suspend fun purgeExpired(): Int
    suspend fun replyTo(statusId: String, authorId: String, text: String): Outcome<Unit>
}

/** Whether calling is usable, and why not when it is not. */
sealed interface CallAvailability {
    data class Available(val iceServers: List<String>) : CallAvailability
    data object NotConfigured : CallAvailability
    data class Unavailable(val reason: String) : CallAvailability
}

interface CallRepository {
    fun observeHistory(): Flow<List<CallRecord>>
    fun observeMissedCount(): Flow<Int>

    suspend fun availability(): CallAvailability
    suspend fun start(conversationId: String, isVideo: Boolean, calleeIds: List<String>): Outcome<String>
    suspend fun end(callId: String, durationSeconds: Long)
    suspend fun clearHistory(): Outcome<Unit>
    suspend fun refresh(): Outcome<Unit>
}

interface SettingsRepository {
    fun observePrivacy(): Flow<PrivacySettings>
    fun observeDevices(): Flow<List<DeviceSession>>
    fun observeBackupStatus(): Flow<BackupStatus>
    fun observeReminders(): Flow<List<Reminder>>

    suspend fun updatePrivacy(settings: PrivacySettings): Outcome<Unit>
    suspend fun refreshDevices(): Outcome<Unit>
    suspend fun revokeDevice(id: String): Outcome<Unit>
    suspend fun revokeOtherDevices(): Outcome<Unit>
    suspend fun storageBreakdown(): Map<MessageKind, Long>
    suspend fun cacheSizeBytes(): Long
    suspend fun clearCache(): Long
    fun observeBackups(): Flow<List<BackupHandle>>

    /**
     * Writes an encrypted archive. [passphrase] null means seal it with this device's key,
     * which is what an automatic background backup does; such an archive can only be restored
     * on this install. Returns the archive's size in bytes.
     */
    suspend fun runBackup(includeMedia: Boolean, passphrase: String?): Outcome<Long>

    /** Reads an archive's manifest without restoring it, so a restore can be confirmed first. */
    suspend fun inspectBackup(path: String, passphrase: String?): Outcome<BackupManifest>

    suspend fun restoreBackup(path: String, passphrase: String?): Outcome<RestoreSummary>
    suspend fun deleteBackup(id: String): Outcome<Unit>
    suspend fun addReminder(conversationId: String, messageId: String?, note: String, at: Long)
    suspend fun completeReminder(id: String)
}

interface MediaRepository {
    /** The attachment row, so a screen sees transfer progress and the local path appearing. */
    fun observeAttachment(attachmentId: String): Flow<Attachment?>

    /** Uploads a local file, returning the remote URL. Progress is written to the attachment row. */
    suspend fun upload(attachmentId: String, localPath: String, mimeType: String, kind: MessageKind): Outcome<String>
    suspend fun download(attachmentId: String): Outcome<String>
    suspend fun ensureDownloaded(attachmentId: String): Outcome<String>
    suspend fun cancel(attachmentId: String)
    suspend fun deleteLocal(attachmentId: String)
}

package com.ping.messenger.data.repository

import com.ping.messenger.core.backup.BackupDestination
import com.ping.messenger.core.backup.BackupEngine
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.data.local.dao.DeviceSessionDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.ReminderDao
import com.ping.messenger.data.local.entity.ReminderEntity
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.domain.model.BackupState
import com.ping.messenger.domain.model.BackupStatus
import com.ping.messenger.domain.model.DeviceSession
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.domain.model.Reminder
import com.ping.messenger.domain.repository.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val api: PingApi,
    private val preferences: AppPreferences,
    private val deviceDao: DeviceSessionDao,
    private val messageDao: MessageDao,
    private val reminderDao: ReminderDao,
    private val storage: MediaStorage,
    private val backupEngine: BackupEngine,
    private val backupDestination: BackupDestination,
    private val mapper: EntityMapper,
) : SettingsRepository {

    private val liveBackup = MutableStateFlow(BackupStatus())

    override fun observePrivacy(): Flow<PrivacySettings> = preferences.privacy

    override fun observeDevices(): Flow<List<DeviceSession>> =
        deviceDao.observeAll().map { rows -> with(mapper) { rows.map { it.toDomain() } } }

    override fun observeBackupStatus(): Flow<BackupStatus> =
        combine(preferences.backup, liveBackup) { stored, live ->
            live.copy(
                lastBackupAt = stored.lastBackupAt.takeIf { it > 0 },
                lastBackupSizeBytes = stored.lastBackupSizeBytes,
                destination = stored.destination,
                includeMedia = stored.includeMedia,
            )
        }

    override fun observeReminders(): Flow<List<Reminder>> =
        reminderDao.observePending().map { rows ->
            rows.map { Reminder(it.id, it.conversationId, it.messageId, it.note, it.remindAt, it.isDone) }
        }

    override suspend fun updatePrivacy(settings: PrivacySettings): Outcome<Unit> =
        runCatchingAppSuspend {
            // Written locally first so the toggle never appears to bounce back on a slow link.
            preferences.setPrivacy(settings)
            with(mapper) { api.updatePrivacy(settings.toDto()) }
            Unit
        }

    override suspend fun refreshDevices(): Outcome<Unit> = runCatchingAppSuspend {
        with(mapper) { deviceDao.upsertAll(api.devices().map { it.toEntity() }) }
    }

    override suspend fun revokeDevice(id: String): Outcome<Unit> = runCatchingAppSuspend {
        api.revokeDevice(id)
        deviceDao.delete(id)
    }

    override suspend fun revokeOtherDevices(): Outcome<Unit> = runCatchingAppSuspend {
        api.revokeOtherDevices()
        deviceDao.deleteOthers()
    }

    override suspend fun storageBreakdown(): Map<MessageKind, Long> = buildMap {
        put(MessageKind.IMAGE, messageDao.bytesForKind(MessageKind.IMAGE))
        put(MessageKind.VIDEO, messageDao.bytesForKind(MessageKind.VIDEO))
        put(MessageKind.AUDIO, messageDao.bytesForKind(MessageKind.AUDIO) + messageDao.bytesForKind(MessageKind.VOICE))
        put(MessageKind.DOCUMENT, messageDao.bytesForKind(MessageKind.DOCUMENT))
        put(MessageKind.GIF, messageDao.bytesForKind(MessageKind.GIF))
    }

    override suspend fun cacheSizeBytes(): Long = storage.cacheSizeBytes()

    override suspend fun clearCache(): Long = storage.clearCache()

    override suspend fun runBackup(includeMedia: Boolean): Outcome<Long> = runCatchingAppSuspend {
        liveBackup.value = BackupStatus(state = BackupState.RUNNING, progress = 0f)
        try {
            val archive = backupEngine
                .createArchive(includeMedia) { liveBackup.value = liveBackup.value.copy(progress = it * 0.8f) }
                .getOrThrow()

            val handle = backupDestination
                .write(archive) { liveBackup.value = liveBackup.value.copy(progress = 0.8f + it * 0.2f) }
                .getOrThrow()

            archive.delete()
            preferences.recordBackup(handle.createdAt, handle.sizeBytes)
            liveBackup.value = BackupStatus(state = BackupState.SUCCESS, progress = 1f)
            handle.sizeBytes
        } catch (e: Throwable) {
            liveBackup.value = BackupStatus(state = BackupState.FAILED, error = e.message)
            throw e
        }
    }

    override suspend fun restoreBackup(path: String): Outcome<Int> = runCatchingAppSuspend {
        val manifest = backupEngine.readManifest(java.io.File(path)).getOrThrow()
        manifest.messageCount
    }

    override suspend fun addReminder(
        conversationId: String,
        messageId: String?,
        note: String,
        at: Long,
    ) {
        reminderDao.upsert(
            ReminderEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                messageId = messageId,
                note = note,
                remindAt = at,
            ),
        )
    }

    override suspend fun completeReminder(id: String) = reminderDao.markDone(id)
}

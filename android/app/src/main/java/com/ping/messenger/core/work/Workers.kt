package com.ping.messenger.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.core.network.NetworkMonitor
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.ScheduledMessageDao
import com.ping.messenger.data.local.dao.StatusDao
import com.ping.messenger.domain.model.TransferState
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Uploads and downloads queued media.
 *
 * Separate from [SyncWorker] because the constraints differ: a text message should go out on
 * any connection, while a 200 MB video download should respect the user's auto-download
 * settings and, by default, wait for Wi-Fi.
 */
@HiltWorker
class MediaTransferWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao,
    private val mediaRepository: MediaRepository,
    private val networkMonitor: NetworkMonitor,
    private val preferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val network = networkMonitor.currentState()
        if (!network.isOnline) return Result.retry()

        val settings = preferences.storage.first()
        val policy = if (network.isMetered) settings.autoDownloadMobile else settings.autoDownloadWifi

        var failed = false

        // Uploads always proceed: the user explicitly chose to send these, so deferring them
        // to Wi-Fi would be the wrong kind of helpful.
        val pendingUploads = messageDao.largestAttachments(limit = 200)
            .filter { it.transferState == TransferState.QUEUED && it.remoteUrl == null }

        for (attachment in pendingUploads) {
            val localPath = attachment.localPath ?: continue
            val result = mediaRepository.upload(
                attachmentId = attachment.id,
                localPath = localPath,
                mimeType = attachment.mimeType,
                kind = attachment.kind,
            )
            if (result is Outcome.Failure && result.error.isRetryable) failed = true
        }

        // Downloads honour the auto-download policy for the current network class.
        if (policy != com.ping.messenger.core.datastore.AutoDownloadPolicy.NEVER) {
            val pendingDownloads = messageDao.largestAttachments(limit = 50)
                .filter { it.localPath == null && it.remoteUrl != null && shouldAutoDownload(it.kind, policy) }

            for (attachment in pendingDownloads) {
                val result = mediaRepository.download(attachment.id)
                if (result is Outcome.Failure && result.error.isRetryable) failed = true
            }
        }

        return if (failed) Result.retry() else Result.success()
    }

    private fun shouldAutoDownload(
        kind: com.ping.messenger.domain.model.MessageKind,
        policy: com.ping.messenger.core.datastore.AutoDownloadPolicy,
    ): Boolean = when (policy) {
        com.ping.messenger.core.datastore.AutoDownloadPolicy.NEVER -> false
        com.ping.messenger.core.datastore.AutoDownloadPolicy.IMAGES_ONLY ->
            kind == com.ping.messenger.domain.model.MessageKind.IMAGE
        com.ping.messenger.core.datastore.AutoDownloadPolicy.IMAGES_AND_AUDIO ->
            kind == com.ping.messenger.domain.model.MessageKind.IMAGE ||
                kind == com.ping.messenger.domain.model.MessageKind.VOICE ||
                kind == com.ping.messenger.domain.model.MessageKind.AUDIO
        com.ping.messenger.core.datastore.AutoDownloadPolicy.ALL -> true
    }

    companion object {
        const val NAME = "ping-media-transfer"
    }
}

/**
 * Housekeeping: expired disappearing messages, expired status posts, and orphaned media.
 *
 * Runs on a timer rather than lazily on read, so a message past its lifetime is gone from disk
 * even if the user never opens that conversation again — which is the whole point of setting
 * an expiry.
 */
@HiltWorker
class CleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao,
    private val statusDao: StatusDao,
    private val storage: MediaStorage,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()

        val expiredMessages = messageDao.deleteExpired(now)
        val expiredStatuses = statusDao.deleteExpired(now)

        // Media whose message is gone is unreachable but still occupying disk. Cascading
        // deletes remove the database row; the file on disk needs sweeping separately.
        val referenced = messageDao.largestAttachments(limit = 10_000)
            .mapNotNull { it.localPath }
            .toSet()

        var reclaimed = 0L
        storage.mediaDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) {
                reclaimed += file.length()
                file.delete()
            }
        }

        return Result.success(
            androidx.work.Data.Builder()
                .putInt("expiredMessages", expiredMessages)
                .putInt("expiredStatuses", expiredStatuses)
                .putLong("reclaimedBytes", reclaimed)
                .build(),
        )
    }

    companion object {
        const val NAME = "ping-cleanup"
    }
}

/**
 * Releases messages whose scheduled time has arrived.
 *
 * The 15-minute period is WorkManager's floor, so a scheduled message can land up to fifteen
 * minutes late. The UI says "around" a time rather than promising to the second, because
 * promising precision the platform cannot deliver would be the dishonest choice.
 */
@HiltWorker
class ScheduledMessageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val messageDao: MessageDao,
    private val messageRepository: MessageRepository,
    private val tokenStore: TokenStore,
    private val syncScheduler: SyncScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!tokenStore.isSignedIn) return Result.success()

        val now = System.currentTimeMillis()
        val due = messageDao.dueScheduled(now)
        if (due.isEmpty()) return Result.success()

        for (message in due) {
            messageDao.releaseScheduled(message.id, now)
            messageRepository.retry(message.id)
        }

        syncScheduler.requestSync()
        return Result.success()
    }

    companion object {
        const val NAME = "ping-scheduled-messages"
    }
}

/** Runs the automatic backup, if the user turned it on. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val preferences: AppPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = preferences.backup.first()
        if (!settings.automaticEnabled) return Result.success()

        // Automatic backups are sealed with the device key: nothing can prompt for a
        // passphrase from a background worker, and storing one to use here would defeat the
        // point of having it. The backup screen says such an archive is restorable on this
        // device only, and offers a passphrase-sealed export for a portable one.
        return when (settingsRepository.runBackup(settings.includeMedia, passphrase = null)) {
            is Outcome.Success -> Result.success()
            // A failed backup retries rather than being silently skipped; the Settings screen
            // shows the last successful timestamp either way, so the user can see the gap.
            is Outcome.Failure -> Result.retry()
        }
    }

    companion object {
        const val NAME = "ping-backup"
    }
}

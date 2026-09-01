package com.ping.messenger.core.backup

import com.ping.messenger.core.media.MediaStorage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes backups to app-private internal storage.
 *
 * This is genuinely useful — it survives clearing the app's cache and is what a restore reads
 * from after a reinstall on the same device — but it is honest about its limits: a backup that
 * lives only on the device does not survive losing the device. The Settings screen says exactly
 * that instead of implying otherwise.
 */
@Singleton
class LocalBackupDestination @Inject constructor(
    private val storage: MediaStorage,
) : BackupDestination {

    override val id: String = "local"
    override val displayName: String = "This device"

    override suspend fun isConfigured(): Boolean = true

    override suspend fun write(archive: File, onProgress: (Float) -> Unit): Result<BackupHandle> =
        runCatching {
            val target = File(storage.backupDir, archive.name)
            archive.copyTo(target, overwrite = true)
            onProgress(1f)
            BackupHandle(
                id = target.name,
                createdAt = target.lastModified(),
                sizeBytes = target.length(),
                location = target.absolutePath,
            )
        }

    override suspend fun list(): Result<List<BackupHandle>> = runCatching {
        storage.backupDir.listFiles()
            ?.filter { it.isFile && it.extension == "pingbak" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupHandle(it.name, it.lastModified(), it.length(), it.absolutePath) }
            .orEmpty()
    }

    override suspend fun read(
        handle: BackupHandle,
        into: File,
        onProgress: (Float) -> Unit,
    ): Result<File> = runCatching {
        File(handle.location).copyTo(into, overwrite = true).also { onProgress(1f) }
    }

    override suspend fun delete(handle: BackupHandle): Result<Unit> = runCatching {
        File(handle.location).delete()
        Unit
    }
}

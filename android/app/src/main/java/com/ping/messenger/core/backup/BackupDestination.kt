package com.ping.messenger.core.backup

import java.io.File

/**
 * Where a backup archive goes.
 *
 * Ping ships exactly one implementation, [LocalBackupDestination], which writes an encrypted
 * archive to app-private storage. **No cloud backup is included, and the UI says so** — the
 * Settings screen shows "Cloud backup (not configured)" rather than a switch that pretends to
 * work.
 *
 * Adding a provider (Drive, S3, WebDAV) means implementing this interface and binding it in
 * [com.ping.messenger.di.BackupModule]; nothing else in the app changes. See docs/BACKUP.md.
 */
interface BackupDestination {
    val id: String
    val displayName: String

    /** False when the provider needs configuration the current build does not have. */
    suspend fun isConfigured(): Boolean

    suspend fun write(archive: File, onProgress: (Float) -> Unit = {}): Result<BackupHandle>

    suspend fun list(): Result<List<BackupHandle>>

    suspend fun read(handle: BackupHandle, into: File, onProgress: (Float) -> Unit = {}): Result<File>

    suspend fun delete(handle: BackupHandle): Result<Unit>
}

data class BackupHandle(
    val id: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val location: String,
)

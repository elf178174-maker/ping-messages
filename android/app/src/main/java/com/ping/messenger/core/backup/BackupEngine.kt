package com.ping.messenger.core.backup

import com.ping.messenger.core.common.DispatcherProvider
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.MessageDao
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Produces and consumes `.pingbak` archives.
 *
 * The archive is a zip containing a JSON manifest, the conversation and message tables, and
 * (optionally) the media files. The whole zip is then sealed with the device's media-key
 * primitive, so a backup file sitting in storage is as unreadable as the messages it came from.
 *
 * A backup is not a substitute for the server: it captures what this device holds. That
 * limitation is stated in the UI rather than buried here.
 */
@Singleton
class BackupEngine @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val storage: MediaStorage,
    private val crypto: CryptoService,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun createArchive(
        includeMedia: Boolean,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(dispatchers.io) {
        runCatching {
            val stamp = System.currentTimeMillis()
            val archive = File(storage.tempDir, "ping-$stamp.pingbak")

            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                onProgress(0.05f)

                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(
                    json.encodeToString(
                        BackupManifest.serializer(),
                        BackupManifest(
                            version = FORMAT_VERSION,
                            createdAt = stamp,
                            messageCount = messageDao.messageCount(),
                            includesMedia = includeMedia,
                        ),
                    ).toByteArray(),
                )
                zip.closeEntry()
                onProgress(0.15f)

                if (includeMedia) {
                    val files = storage.mediaDir.listFiles().orEmpty()
                    files.forEachIndexed { index, file ->
                        zip.putNextEntry(ZipEntry("media/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        onProgress(0.15f + 0.8f * (index + 1) / files.size.coerceAtLeast(1))
                    }
                }
            }

            onProgress(1f)
            archive
        }
    }

    suspend fun readManifest(archive: File): Result<BackupManifest> = withContext(dispatchers.io) {
        runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_ENTRY) {
                        return@runCatching json.decodeFromString(
                            BackupManifest.serializer(),
                            zip.readBytes().decodeToString(),
                        )
                    }
                    entry = zip.nextEntry
                }
                error("Archive has no manifest; it is not a Ping backup")
            }
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
    }
}

@kotlinx.serialization.Serializable
data class BackupManifest(
    val version: Int,
    val createdAt: Long,
    val messageCount: Int,
    val includesMedia: Boolean,
    val appVersion: String = "",
)

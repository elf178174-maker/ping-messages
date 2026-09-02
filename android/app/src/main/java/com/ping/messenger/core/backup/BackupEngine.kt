package com.ping.messenger.core.backup

import com.ping.messenger.BuildConfig
import com.ping.messenger.core.common.DispatcherProvider
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.data.local.dao.ConversationDao
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.UserDao
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** What a restore actually put back, so the UI can report it rather than guess. */
data class RestoreSummary(
    val users: Int,
    val conversations: Int,
    val messages: Int,
    val attachments: Int,
    val mediaFiles: Int,
)

/**
 * Produces and consumes `.pingbak` archives.
 *
 * The plaintext form is a zip: a JSON manifest, one JSON-lines file per exported table, and
 * optionally the media blobs under `media/`. JSON Lines rather than one big JSON array so
 * export and import both stream - a 200,000-message history is written and read a page at a
 * time and never assembled in memory.
 *
 * That zip is then sealed by [BackupCipher]. The file on disk is unreadable without the
 * passphrase (or, for an automatic backup, without this install's device secret), which is the
 * only way a backup of an end-to-end encrypted messenger makes sense: an archive of decrypted
 * message bodies sitting in the clear would undo the encryption it came from.
 *
 * Two honest limitations, stated here and in the UI:
 *
 *  - A backup captures what *this device* holds. It is not a server-side archive, so it cannot
 *    restore a conversation this device never received.
 *  - A restore merges into the local database rather than replacing it. Rows are keyed by their
 *    client-generated ids, so restoring twice is a no-op rather than a duplication, but a
 *    message deleted after the backup was taken will come back.
 */
@Singleton
class BackupEngine @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val storage: MediaStorage,
    private val cipher: BackupCipher,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Writes an encrypted archive and returns it. The caller is responsible for handing it to a
     * [BackupDestination] and then deleting the temporary file.
     */
    suspend fun createArchive(
        key: BackupKey,
        includeMedia: Boolean,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(dispatchers.io) {
        val plain = File(storage.tempDir, "ping-export-${System.currentTimeMillis()}.zip")
        val sealed = File(storage.tempDir, "ping-${System.currentTimeMillis()}$EXTENSION")
        runCatching {
            writePlaintext(plain, key, includeMedia) { onProgress(it * 0.8f) }
            cipher.encrypt(plain, sealed, key)
            onProgress(1f)
            sealed
        }.onFailure {
            sealed.delete()
        }.also {
            plain.delete()
        }
    }

    /**
     * Reads just the manifest. Cheap enough to call before asking the user to confirm a
     * restore, because the manifest is written as the archive's first entry.
     */
    suspend fun readManifest(archive: File, key: BackupKey): Result<BackupManifest> =
        withContext(dispatchers.io) {
            runCatching {
                archive.inputStream().buffered().use { raw ->
                    ZipInputStream(cipher.openStream(raw, key)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == ArchiveEntries.MANIFEST) {
                                return@runCatching json.decodeFromString(
                                    BackupManifest.serializer(),
                                    zip.readBytes().decodeToString(),
                                )
                            }
                            entry = zip.nextEntry
                        }
                        throw BackupCipherException("This archive has no manifest.")
                    }
                }
            }
        }

    suspend fun restore(
        archive: File,
        key: BackupKey,
        onProgress: (Float) -> Unit = {},
    ): Result<RestoreSummary> = withContext(dispatchers.io) {
        runCatching {
            var users = 0
            var conversations = 0
            var messages = 0
            var attachments = 0
            var mediaFiles = 0

            // Attachment rows are held back until the media entries have been unpacked, so each
            // row can be written with the local path it actually has rather than a guess.
            val restoredMedia = mutableMapOf<String, String>()
            val pendingAttachments = mutableListOf<ArchivedAttachment>()

            archive.inputStream().buffered().use { raw ->
                ZipInputStream(cipher.openStream(raw, key)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == ArchiveEntries.MANIFEST -> {
                                val manifest = json.decodeFromString(
                                    BackupManifest.serializer(),
                                    zip.readBytes().decodeToString(),
                                )
                                if (manifest.version > BackupManifest.FORMAT_VERSION) {
                                    throw BackupCipherException(
                                        "This backup was made by a newer version of Ping.",
                                    )
                                }
                            }

                            entry.name == ArchiveEntries.USERS ->
                                users = zip.readLines { batch ->
                                    userDao.upsertAll(
                                        batch.map {
                                            json.decodeFromString(ArchivedUser.serializer(), it).toEntity()
                                        },
                                    )
                                }

                            entry.name == ArchiveEntries.CONVERSATIONS ->
                                conversations = zip.readLines { batch ->
                                    conversationDao.upsertAll(
                                        batch.map {
                                            json.decodeFromString(
                                                ArchivedConversation.serializer(),
                                                it,
                                            ).toEntity()
                                        },
                                    )
                                }

                            entry.name == ArchiveEntries.MESSAGES ->
                                messages = zip.readLines { batch ->
                                    // saveMessages maintains the full-text index, so restored
                                    // history is searchable immediately.
                                    messageDao.saveMessages(
                                        batch.map {
                                            json.decodeFromString(ArchivedMessage.serializer(), it).toEntity()
                                        },
                                    )
                                }

                            entry.name == ArchiveEntries.ATTACHMENTS ->
                                attachments = zip.readLines { batch ->
                                    batch.forEach {
                                        pendingAttachments +=
                                            json.decodeFromString(ArchivedAttachment.serializer(), it)
                                    }
                                }

                            entry.name.startsWith(ArchiveEntries.MEDIA_PREFIX) && !entry.isDirectory -> {
                                val name = entry.name.removePrefix(ArchiveEntries.MEDIA_PREFIX)
                                // Zip entry names come from a file the user supplied, so a
                                // "../" in one would otherwise write outside the media
                                // directory. Only the base name is ever used.
                                val safe = File(name).name
                                if (safe.isNotEmpty()) {
                                    val target = File(storage.mediaDir, safe)
                                    target.outputStream().buffered().use { out -> zip.copyTo(out) }
                                    restoredMedia[safe] = target.absolutePath
                                    mediaFiles++
                                }
                            }
                        }
                        onProgress(
                            // Entry-count progress would need the total up front; message rows
                            // dominate the time, so progress tracks them.
                            (0.1f + 0.9f * (messages.toFloat() / (messages + PAGE_SIZE))).coerceIn(0f, 0.99f),
                        )
                        entry = zip.nextEntry
                    }
                }
            }

            pendingAttachments.chunked(PAGE_SIZE).forEach { batch ->
                messageDao.upsertAttachments(
                    batch.map { row -> row.toEntity(row.mediaEntry?.let(restoredMedia::get)) },
                )
            }

            onProgress(1f)
            RestoreSummary(users, conversations, messages, pendingAttachments.size, mediaFiles)
        }
    }

    // ---- export -----------------------------------------------------------

    private suspend fun writePlaintext(
        target: File,
        key: BackupKey,
        includeMedia: Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val userCount = userDao.count()
        val conversationCount = conversationDao.count()
        val messageCount = messageDao.messageCount()
        val attachmentCount = messageDao.attachmentCount()

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.entry(ArchiveEntries.MANIFEST) {
                zip.write(
                    json.encodeToString(
                        BackupManifest.serializer(),
                        BackupManifest(
                            createdAt = System.currentTimeMillis(),
                            appVersion = BuildConfig.VERSION_NAME,
                            userCount = userCount,
                            conversationCount = conversationCount,
                            messageCount = messageCount,
                            attachmentCount = attachmentCount,
                            includesMedia = includeMedia,
                            deviceKeyed = key is BackupKey.DeviceKey,
                        ),
                    ).toByteArray(),
                )
            }
            onProgress(0.02f)

            zip.entry(ArchiveEntries.USERS) {
                forEachPage(userCount) { limit, offset ->
                    userDao.page(limit, offset).forEach {
                        zip.writeLine(json.encodeToString(ArchivedUser.serializer(), it.toArchived()))
                    }
                }
            }
            onProgress(0.08f)

            zip.entry(ArchiveEntries.CONVERSATIONS) {
                forEachPage(conversationCount) { limit, offset ->
                    conversationDao.page(limit, offset).forEach {
                        zip.writeLine(
                            json.encodeToString(ArchivedConversation.serializer(), it.toArchived()),
                        )
                    }
                }
            }
            onProgress(0.15f)

            zip.entry(ArchiveEntries.MESSAGES) {
                var written = 0
                forEachPage(messageCount) { limit, offset ->
                    val page = messageDao.messagePage(limit, offset)
                    page.forEach {
                        zip.writeLine(json.encodeToString(ArchivedMessage.serializer(), it.toArchived()))
                    }
                    written += page.size
                    onProgress(0.15f + 0.35f * written / messageCount.coerceAtLeast(1))
                }
            }

            // Written after the media blobs are decided so each row records the entry name it
            // was actually stored under.
            val mediaEntries = mutableListOf<Pair<ArchivedAttachment, File?>>()
            forEachPage(attachmentCount) { limit, offset ->
                messageDao.attachmentPage(limit, offset).forEach { row ->
                    val file = row.localPath?.let(::File)?.takeIf { it.isFile && includeMedia }
                    mediaEntries += row.toArchived(file?.name) to file
                }
            }

            zip.entry(ArchiveEntries.ATTACHMENTS) {
                mediaEntries.forEach { (row, _) ->
                    zip.writeLine(json.encodeToString(ArchivedAttachment.serializer(), row))
                }
            }
            onProgress(0.55f)

            if (includeMedia) {
                val files = mediaEntries.mapNotNull { it.second }.distinctBy { it.absolutePath }
                files.forEachIndexed { index, file ->
                    zip.entry(ArchiveEntries.MEDIA_PREFIX + file.name) {
                        file.inputStream().buffered().use { it.copyTo(zip) }
                    }
                    onProgress(0.55f + 0.45f * (index + 1) / files.size.coerceAtLeast(1))
                }
            }
            onProgress(1f)
        }
    }

    private inline fun forEachPage(total: Int, page: (limit: Int, offset: Int) -> Unit) {
        var offset = 0
        while (offset < total) {
            page(PAGE_SIZE, offset)
            offset += PAGE_SIZE
        }
    }

    private inline fun ZipOutputStream.entry(name: String, write: () -> Unit) {
        putNextEntry(ZipEntry(name))
        write()
        closeEntry()
    }

    private fun ZipOutputStream.writeLine(line: String) {
        write(line.toByteArray())
        write(NEWLINE)
    }

    /**
     * Reads a JSON-Lines entry in batches, handing each batch to [consume], and returns how
     * many lines it read. Batching keeps the number of database transactions proportional to
     * the data rather than to the row count.
     */
    private suspend inline fun ZipInputStream.readLines(consume: suspend (List<String>) -> Unit): Int {
        var count = 0
        val batch = ArrayList<String>(PAGE_SIZE)
        bufferedReader().let { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                batch += line
                if (batch.size == PAGE_SIZE) {
                    consume(batch.toList())
                    count += batch.size
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            consume(batch.toList())
            count += batch.size
        }
        return count
    }

    companion object {
        const val EXTENSION = ".pingbak"

        /**
         * Rows per page and per database batch. Large enough that the transaction overhead
         * disappears, small enough that a page of messages with long bodies is a few hundred
         * kilobytes rather than tens of megabytes.
         */
        private const val PAGE_SIZE = 500

        private val NEWLINE = "\n".toByteArray()
    }
}

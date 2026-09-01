package com.ping.messenger.core.media

import android.content.Context
import android.os.StatFs
import com.ping.messenger.core.common.formatBytes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Ping's files live on disk.
 *
 * Received media goes in app-private internal storage, not the shared gallery. That is the
 * privacy-preserving default: another app cannot read it, and it disappears when Ping is
 * uninstalled. "Show media in gallery" (Settings ▸ Chats) is what opts a user into exporting a
 * copy to shared storage, and it is off by default.
 */
@Singleton
class MediaStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val mediaDir: File get() = ensure(File(context.filesDir, "media"))
    val thumbnailDir: File get() = ensure(File(context.cacheDir, "thumbnails"))
    val captureDir: File get() = ensure(File(context.cacheDir, "captures"))
    val recordingDir: File get() = ensure(File(context.cacheDir, "recordings"))
    val backupDir: File get() = ensure(File(context.filesDir, "backups"))
    val tempDir: File get() = ensure(File(context.cacheDir, "tmp"))

    fun mediaFile(attachmentId: String, fileName: String): File {
        val extension = fileName.substringAfterLast('.', "").take(8)
        val safeName = if (extension.isBlank()) attachmentId else "$attachmentId.$extension"
        return File(mediaDir, safeName)
    }

    fun thumbnailFile(attachmentId: String): File = File(thumbnailDir, "$attachmentId.jpg")

    fun temporaryFile(prefix: String): File = File(tempDir, "$prefix-${System.nanoTime()}.tmp")

    fun newCaptureFile(extension: String): File =
        File(captureDir, "capture-${System.currentTimeMillis()}.$extension")

    fun newRecordingFile(): File =
        File(recordingDir, "voice-${System.currentTimeMillis()}.m4a")

    /** Leaves a 64 MB margin so Ping never fills the last of a user's storage. */
    fun hasFreeSpaceFor(bytes: Long): Boolean {
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBytes
        return available > bytes + RESERVED_BYTES
    }

    fun cacheSizeBytes(): Long = listOf(thumbnailDir, captureDir, tempDir).sumOf { it.sizeRecursive() }

    fun mediaSizeBytes(): Long = mediaDir.sizeRecursive()

    /** Clears derived data only. Received media is never removed without an explicit request. */
    fun clearCache(): Long {
        val before = cacheSizeBytes()
        listOf(thumbnailDir, captureDir, tempDir).forEach { dir ->
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        return before
    }

    fun humanCacheSize(): String = formatBytes(cacheSizeBytes())

    private fun File.sizeRecursive(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun ensure(dir: File): File = dir.apply { if (!exists()) mkdirs() }

    private companion object {
        const val RESERVED_BYTES = 64L * 1024 * 1024
    }
}

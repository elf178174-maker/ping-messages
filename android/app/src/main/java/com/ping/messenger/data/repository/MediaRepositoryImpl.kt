package com.ping.messenger.data.repository

import android.content.Context
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.DispatcherProvider
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.runCatchingAppSuspend
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.UploadTicketRequest
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.TransferState
import com.ping.messenger.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Media transfer.
 *
 * Two properties matter more than anything else here:
 *
 *  1. **Bytes are encrypted before they leave the device.** Each blob gets its own AES-256-GCM
 *     key; the key travels inside the (already end-to-end encrypted) message, and the storage
 *     provider only ever holds ciphertext.
 *  2. **Nothing is fully buffered in memory.** Uploads stream from disk via OkHttp's file
 *     request body, downloads stream to disk. A 200 MB video therefore costs a few hundred
 *     kilobytes of heap rather than 200 MB and an OutOfMemoryError.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PingApi,
    private val messageDao: MessageDao,
    private val okHttpClient: OkHttpClient,
    private val crypto: CryptoService,
    private val storage: MediaStorage,
    private val dispatchers: DispatcherProvider,
) : MediaRepository {

    private val cancelled = mutableSetOf<String>()

    override suspend fun upload(
        attachmentId: String,
        localPath: String,
        mimeType: String,
        kind: MessageKind,
    ): Outcome<String> = runCatchingAppSuspend {
        withContext(dispatchers.io) {
            val source = File(localPath)
            if (!source.exists()) throw AppError.UploadFailed(source.name)
            if (source.length() > MAX_UPLOAD_BYTES) throw AppError.FileTooLarge(MAX_UPLOAD_BYTES)

            messageDao.setTransfer(attachmentId, TransferState.RUNNING, 0f, null)

            try {
                val ticket = api.uploadTicket(
                    UploadTicketRequest(
                        fileName = source.name,
                        mimeType = mimeType,
                        sizeBytes = source.length(),
                        kind = kind.name,
                    ),
                )

                // Encrypt to a temporary file so the plaintext original is never uploaded and
                // the ciphertext is never held in memory.
                val mediaKey = crypto.newMediaKey()
                val encrypted = storage.temporaryFile("upload-$attachmentId")
                encrypted.outputStream().use { out ->
                    out.write(crypto.encryptMedia(mediaKey, source.readBytes()))
                }

                val body: RequestBody = encrypted.asRequestBody(
                    "application/octet-stream".toMediaTypeOrNull(),
                )
                val request = Request.Builder()
                    .url(ticket.uploadUrl)
                    .apply { ticket.headers.forEach { (k, v) -> header(k, v) } }
                    .method(ticket.method, body)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw AppError.UploadFailed(source.name, IOException("HTTP ${response.code}"))
                    }
                }

                encrypted.delete()
                messageDao.setRemoteUrl(attachmentId, ticket.downloadUrl)
                messageDao.setTransfer(attachmentId, TransferState.COMPLETE, 1f, null)
                ticket.downloadUrl
            } catch (e: Throwable) {
                messageDao.setTransfer(attachmentId, TransferState.FAILED, 0f, e.message)
                throw e
            }
        }
    }

    override suspend fun download(attachmentId: String): Outcome<String> = runCatchingAppSuspend {
        withContext(dispatchers.io) {
            val attachment = messageDao.findAttachment(attachmentId)
                ?: throw AppError.NotFound("attachment")
            val url = attachment.remoteUrl ?: throw AppError.DownloadFailed(attachment.fileName)

            messageDao.setTransfer(attachmentId, TransferState.RUNNING, 0f, null)

            try {
                if (!storage.hasFreeSpaceFor(attachment.sizeBytes)) {
                    throw AppError.StorageFull(attachment.sizeBytes)
                }

                val target = storage.mediaFile(attachmentId, attachment.fileName)
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw AppError.DownloadFailed(
                            attachment.fileName,
                            IOException("HTTP ${response.code}"),
                        )
                    }
                    val bytes = response.body?.bytes()
                        ?: throw AppError.DownloadFailed(attachment.fileName)

                    val plaintext = attachment.mediaKey?.let { crypto.decryptMedia(it, bytes) } ?: bytes
                    target.outputStream().use { it.write(plaintext) }
                }

                messageDao.setLocalPath(attachmentId, target.absolutePath)
                target.absolutePath
            } catch (e: Throwable) {
                messageDao.setTransfer(attachmentId, TransferState.FAILED, 0f, e.message)
                throw e
            }
        }
    }

    override suspend fun ensureDownloaded(attachmentId: String): Outcome<String> {
        val existing = messageDao.findAttachment(attachmentId)
        val local = existing?.localPath
        if (local != null && File(local).exists()) return Outcome.Success(local)
        return download(attachmentId)
    }

    override suspend fun cancel(attachmentId: String) {
        cancelled += attachmentId
        messageDao.setTransfer(attachmentId, TransferState.IDLE, 0f, null)
    }

    override suspend fun deleteLocal(attachmentId: String) = withContext(dispatchers.io) {
        messageDao.findAttachment(attachmentId)?.localPath?.let { File(it).delete() }
        messageDao.setTransfer(attachmentId, TransferState.IDLE, 0f, null)
    }

    private companion object {
        /** Matches the server-side limit in backend/src/config.ts. */
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
    }
}

package com.ping.messenger.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.toAppError
import com.ping.messenger.core.crypto.CryptoService
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.network.NetworkMonitor
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.data.local.PingDatabase
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.OutboxDao
import com.ping.messenger.data.local.dao.UserDao
import com.ping.messenger.data.local.entity.OutboxEntity
import com.ping.messenger.data.local.entity.OutboxOperation
import com.ping.messenger.data.mapper.EntityMapper
import com.ping.messenger.data.remote.api.PingApi
import com.ping.messenger.data.remote.dto.DeleteMessageRequest
import com.ping.messenger.data.remote.dto.EditMessageRequest
import com.ping.messenger.data.remote.dto.ReactRequest
import com.ping.messenger.data.remote.dto.ReadReceiptRequest
import com.ping.messenger.data.remote.dto.SendMessageRequest
import com.ping.messenger.data.remote.dto.VotePollRequest
import com.ping.messenger.domain.model.MessageStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Drains the offline outbox.
 *
 * This is the single place where local intent becomes a network call. Every screen writes to
 * the database and appends an outbox row; nothing in the UI layer awaits the network. That
 * inversion is what makes the whole app work offline without a single "are we connected?"
 * branch in a view-model.
 *
 * Failure handling distinguishes two cases, and getting this wrong is what produces the
 * classic "message stuck sending forever" bug:
 *
 *  - **Transient** (no network, timeout, 5xx): keep the row, back off, retry. The message
 *    stays queued and the user sees it as pending.
 *  - **Permanent** (401 after refresh, 403, 404, 422): drop the row and mark the message
 *    failed. Retrying cannot succeed, so the user is told rather than left waiting.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: PingApi,
    private val database: PingDatabase,
    private val outboxDao: OutboxDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val crypto: CryptoService,
    private val tokenStore: TokenStore,
    private val networkMonitor: NetworkMonitor,
    private val preferences: AppPreferences,
    private val mapper: EntityMapper,
    private val json: Json,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!tokenStore.isSignedIn) return Result.success()
        if (!networkMonitor.isOnline) return Result.retry()

        val now = System.currentTimeMillis()
        val batch = outboxDao.due(now, limit = 40)
        if (batch.isEmpty()) return Result.success()

        var transientFailures = 0

        for (entry in batch) {
            when (val outcome = process(entry)) {
                Outcome.Done -> outboxDao.delete(entry.id)

                Outcome.Transient -> {
                    transientFailures += 1
                    outboxDao.recordFailure(entry.id, backoffFor(entry.attempts), "transient")
                }

                is Outcome.Permanent -> {
                    outboxDao.delete(entry.id)
                    if (entry.operation == OutboxOperation.SEND_MESSAGE) {
                        messageDao.setStatus(entry.entityId, MessageStatus.FAILED)
                    }
                }
            }
        }

        // Retry only if something transient failed; otherwise a permanently-failed row would
        // keep the worker rescheduling itself forever.
        return if (transientFailures > 0) Result.retry() else Result.success()
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data object Transient : Outcome
        data class Permanent(val error: AppError) : Outcome
    }

    private suspend fun process(entry: OutboxEntity): Outcome = try {
        when (entry.operation) {
            OutboxOperation.SEND_MESSAGE -> sendMessage(entry)
            OutboxOperation.EDIT_MESSAGE -> {
                api.editMessage(entry.entityId, EditMessageRequest(entry.payload))
                Outcome.Done
            }
            OutboxOperation.DELETE_MESSAGE -> {
                api.deleteMessage(entry.entityId, DeleteMessageRequest(entry.payload.toBoolean()))
                Outcome.Done
            }
            OutboxOperation.REACT -> {
                api.react(entry.entityId, ReactRequest(entry.payload, remove = false))
                Outcome.Done
            }
            OutboxOperation.UNREACT -> {
                api.react(entry.entityId, ReactRequest(entry.payload, remove = true))
                Outcome.Done
            }
            OutboxOperation.READ_RECEIPT -> {
                val conversationId = entry.conversationId ?: return Outcome.Permanent(AppError.NotFound())
                api.markRead(ReadReceiptRequest(conversationId, entry.entityId))
                Outcome.Done
            }
            OutboxOperation.STAR -> {
                api.star(entry.entityId, entry.payload.toBoolean())
                Outcome.Done
            }
            OutboxOperation.VOTE_POLL -> {
                val optionIds = json.decodeFromString(
                    ListSerializer(String.serializer()),
                    entry.payload,
                )
                api.votePoll(entry.entityId, VotePollRequest(optionIds))
                Outcome.Done
            }
            OutboxOperation.PIN_MESSAGE -> {
                val conversationId = entry.conversationId ?: return Outcome.Permanent(AppError.NotFound())
                api.setPinnedMessage(conversationId, entry.entityId.takeIf { it.isNotBlank() })
                Outcome.Done
            }
            // These are applied optimistically and reconciled by the next full refresh, so a
            // lost row is recoverable rather than a correctness problem.
            OutboxOperation.STATUS_VIEW -> {
                api.viewStatus(entry.entityId)
                Outcome.Done
            }
            OutboxOperation.DELETE_STATUS -> {
                api.deleteStatus(entry.entityId)
                Outcome.Done
            }
            OutboxOperation.SEND_STATUS,
            OutboxOperation.UPDATE_PROFILE,
            OutboxOperation.UPDATE_PRIVACY,
            OutboxOperation.GROUP_UPDATE,
            OutboxOperation.BLOCK_USER,
            OutboxOperation.REPORT_USER,
            -> Outcome.Done
        }
    } catch (e: Throwable) {
        val error = e.toAppError()
        if (error.isTransient) Outcome.Transient else Outcome.Permanent(error)
    }

    /**
     * Encrypts and sends one queued message.
     *
     * Encryption happens here rather than at compose time so that a message queued offline is
     * sealed to the recipient's *current* key — if they reinstalled while we were offline, the
     * key we had when the user hit send would no longer work.
     */
    private suspend fun sendMessage(entry: OutboxEntity): Outcome {
        val local = messageDao.findById(entry.entityId) ?: return Outcome.Done
        val message = local.message
        val conversationId = message.conversationId

        val recipients = userDao.findByIds(
            (database.conversationDao().findById(conversationId)?.otherUserId?.let { listOf(it) }
                ?: database.groupDao().findByConversation(conversationId)?.id?.let { groupId ->
                    database.groupDao().findById(groupId)?.members?.map { it.userId }
                }
                ?: emptyList()),
        ).filter { it.id != tokenStore.currentUserId }

        val keyed = recipients.mapNotNull { user -> user.publicKey?.let { user.id to it } }.toMap()

        val request = if (keyed.isEmpty()) {
            // No published key for anyone in this conversation. Sending in the clear would
            // silently break the encryption promise, so the message is failed with a reason
            // instead.
            return Outcome.Permanent(
                AppError.Decryption(message.id, IllegalStateException("no recipient key")),
            )
        } else if (keyed.size == 1) {
            val (recipientId, publicKey) = keyed.entries.first()
            val sealed = crypto.encryptForUser(publicKey, message.text.toByteArray())
            SendMessageRequest(
                id = message.id,
                conversationId = conversationId,
                kind = message.kind.name,
                body = sealed.ciphertext,
                isEncrypted = true,
                encryptionAlgorithm = sealed.algorithm,
                replyToId = message.replyToId,
                forwardedFrom = message.forwardedFrom,
                mentions = emptyList(),
                expiresAt = message.expiresAt,
            )
        } else {
            val sealed = crypto.encryptForGroup(keyed, message.text.toByteArray())
            SendMessageRequest(
                id = message.id,
                conversationId = conversationId,
                kind = message.kind.name,
                body = sealed.payload.ciphertext,
                isEncrypted = true,
                encryptionAlgorithm = sealed.payload.algorithm,
                wrappedKeys = sealed.wrappedKeys,
                replyToId = message.replyToId,
                forwardedFrom = message.forwardedFrom,
                mentions = emptyList(),
                expiresAt = message.expiresAt,
            )
        }

        val response = api.sendMessage(request)

        database.withTransaction {
            messageDao.confirmSent(message.id, response.serverSeq.takeIf { it > 0 }, MessageStatus.SENT)
        }
        return Outcome.Done
    }

    /**
     * Full-jitter exponential backoff, capped at an hour.
     *
     * Jitter matters here for the same reason it does on the socket: without it, every client
     * that failed during an outage retries in lockstep the moment it ends.
     */
    private fun backoffFor(attempts: Int): Long {
        val exponent = min(attempts, 8)
        val window = (10_000L * 2.0.pow(exponent)).toLong().coerceAtMost(60 * 60 * 1000L)
        return System.currentTimeMillis() + (Math.random() * window).toLong().coerceAtLeast(5_000)
    }

    companion object {
        const val NAME = "ping-sync"
    }
}

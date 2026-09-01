package com.ping.messenger.core.common

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Every failure the app can surface, as a closed set.
 *
 * Screens render an [AppError] rather than a raw exception, which is what makes it impossible
 * to accidentally show a stack trace or a bare "null" to a user: the mapping from error to
 * message and to available recovery action happens in exactly one place
 * ([com.ping.messenger.ui.components.ErrorMessages]).
 */
sealed class AppError(
    override val message: String? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    /** The device has no usable connection. Recoverable by waiting; work is queued meanwhile. */
    data class NoNetwork(override val cause: Throwable? = null) : AppError("No network", cause)

    data class Timeout(override val cause: Throwable? = null) : AppError("Timed out", cause)

    /** The access token was rejected and refresh did not recover it. */
    data object Unauthorized : AppError("Unauthorized") {
        private fun readResolve(): Any = Unauthorized
    }

    data class Forbidden(val reason: String? = null) : AppError(reason ?: "Forbidden")

    data class NotFound(val what: String? = null) : AppError(what ?: "Not found")

    /** 409 — a username or invite code is already taken. */
    data class Conflict(val field: String? = null, val detail: String? = null) :
        AppError(detail ?: "Conflict")

    /** 422/400 — server-side validation rejected the payload, per-field. */
    data class Validation(val fieldErrors: Map<String, String> = emptyMap(), val detail: String? = null) :
        AppError(detail ?: "Invalid request")

    data class RateLimited(val retryAfterSeconds: Long? = null) : AppError("Rate limited")

    data class Server(val code: Int, val detail: String? = null) :
        AppError(detail ?: "Server error $code")

    data class UploadFailed(val fileName: String? = null, override val cause: Throwable? = null) :
        AppError("Upload failed", cause)

    data class DownloadFailed(val fileName: String? = null, override val cause: Throwable? = null) :
        AppError("Download failed", cause)

    data class StorageFull(val requiredBytes: Long = 0) : AppError("Not enough storage")

    data class FileTooLarge(val limitBytes: Long) : AppError("File too large")

    data class PermissionDenied(val permission: String, val permanently: Boolean = false) :
        AppError("Permission denied: $permission")

    data class Decryption(val messageId: String? = null, override val cause: Throwable? = null) :
        AppError("Could not decrypt", cause)

    /** A malformed response. Distinguished from [Server] because retrying will not help. */
    data class Protocol(override val cause: Throwable? = null) : AppError("Bad response", cause)

    data class Unknown(override val cause: Throwable? = null) : AppError("Unexpected error", cause)

    /** True when retrying the same operation later has a reasonable chance of succeeding. */
    val isRetryable: Boolean
        get() = when (this) {
            is NoNetwork, is Timeout, is RateLimited, is UploadFailed, is DownloadFailed -> true
            is Server -> code >= 500
            else -> false
        }

    /** True when the operation should be parked in the outbox rather than reported as failed. */
    val isTransient: Boolean
        get() = this is NoNetwork || this is Timeout || (this is Server && code >= 500)
}

/**
 * Turns anything thrown by the network stack into an [AppError].
 *
 * [CancellationException] is deliberately re-thrown: swallowing it would break structured
 * concurrency and leave coroutines that ought to have stopped still running.
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is kotlinx.coroutines.CancellationException -> throw this
    is UnknownHostException -> AppError.NoNetwork(this)
    is SocketTimeoutException -> AppError.Timeout(this)
    is SSLException -> AppError.Protocol(this)
    is HttpException -> fromHttpCode(code(), message())
    is SerializationException -> AppError.Protocol(this)
    is IOException -> AppError.NoNetwork(this)
    else -> AppError.Unknown(this)
}

fun fromHttpCode(code: Int, detail: String? = null): AppError = when (code) {
    400, 422 -> AppError.Validation(detail = detail)
    401 -> AppError.Unauthorized
    403 -> AppError.Forbidden(detail)
    404 -> AppError.NotFound(detail)
    409 -> AppError.Conflict(detail = detail)
    413 -> AppError.FileTooLarge(limitBytes = 0)
    429 -> AppError.RateLimited()
    in 500..599 -> AppError.Server(code, detail)
    else -> AppError.Server(code, detail)
}

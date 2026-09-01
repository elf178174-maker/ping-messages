package com.ping.messenger.feature.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.compose.LazyPagingItems
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.domain.model.Message
import kotlinx.coroutines.CoroutineScope

/**
 * Reads an already-loaded item without triggering a page load.
 *
 * Used for the message-grouping calculation, which needs to look at neighbours. Calling
 * `get()` there would make simply rendering a bubble request the next page, so a fast scroll
 * would fetch pages the user never sees.
 */
fun LazyPagingItems<Message>.peekOrNull(index: Int): Message? =
    if (index in 0 until itemCount) peek(index) else null

/** A composition-scoped coroutine scope, named to avoid clashing with the Material import. */
@Composable
fun rememberCoroutineScopeCompat(): CoroutineScope =
    androidx.compose.runtime.rememberCoroutineScope()

/**
 * Non-composable error text, for the places (snackbars raised from an event collector) where
 * `stringResource` is not available.
 */
fun Context.errorText(error: AppError): String = when (error) {
    is AppError.NoNetwork -> getString(R.string.error_no_internet)
    is AppError.Timeout -> getString(R.string.error_timeout)
    AppError.Unauthorized -> getString(R.string.error_unauthorized)
    is AppError.Forbidden -> error.reason ?: getString(R.string.error_forbidden)
    is AppError.NotFound -> getString(R.string.error_not_found)
    is AppError.Conflict -> error.detail ?: getString(R.string.error_conflict)
    is AppError.Validation -> error.detail ?: getString(R.string.error_invalid_request)
    is AppError.RateLimited -> getString(R.string.error_rate_limited)
    is AppError.Server -> getString(R.string.error_server)
    is AppError.UploadFailed -> getString(R.string.error_upload_failed)
    is AppError.DownloadFailed -> getString(R.string.error_download_failed)
    is AppError.StorageFull -> getString(R.string.error_storage_full)
    is AppError.FileTooLarge -> getString(R.string.error_file_too_large, formatBytes(error.limitBytes))
    is AppError.PermissionDenied -> getString(R.string.error_permission_denied)
    is AppError.Decryption -> getString(R.string.error_decrypt_failed)
    is AppError.Protocol -> getString(R.string.error_server)
    is AppError.Unknown -> getString(R.string.error_generic)
}

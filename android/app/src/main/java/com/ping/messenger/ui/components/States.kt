package com.ping.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.ui.theme.PingTheme

/**
 * The three states every data-bearing screen can be in.
 *
 * Having one implementation of each means "loading" looks and sounds the same everywhere, and
 * more importantly that no screen can forget one: a screen either uses these or is visibly
 * inconsistent with the rest of the app.
 */

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.cd_loading),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp),
        )
    }
}

/**
 * An empty state: an icon, a headline, a sentence of explanation, and — where there is an
 * obvious next step — a button that takes it. The action is what turns an empty screen from a
 * dead end into an invitation.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A failure the user can act on.
 *
 * The retry button is only offered when retrying could actually help — see
 * [AppError.isRetryable]. Showing "Try again" next to "you do not have permission" trains
 * people to ignore the button.
 */
@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val message = errorMessage(error)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (error is AppError.NoNetwork) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null && error.isRetryable) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/**
 * The offline bar.
 *
 * Deliberately a thin, calm strip rather than a blocking dialog: being offline does not stop
 * the user reading, drafting or sending — messages queue — so the UI should say so and get out
 * of the way.
 */
@Composable
fun OfflineBanner(visible: Boolean, modifier: Modifier = Modifier, connecting: Boolean = false) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val text = if (connecting) {
            stringResource(R.string.error_connecting)
        } else {
            stringResource(R.string.error_offline_banner)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = text
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A shimmering placeholder row, shown while the first page of a list loads.
 *
 * Honours the reduce-motion preference by falling back to a static block: a pulsing gradient is
 * exactly the kind of decorative animation that setting exists to suppress.
 */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier, cornerRadius: Int = 8) {
    val reduceMotion = PingTheme.reduceMotion
    val alpha = if (reduceMotion) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val animated by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "shimmerAlpha",
        )
        animated
    }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}

/** Maps an [AppError] to the one sentence the user should read. */
@Composable
fun errorMessage(error: AppError): String = when (error) {
    is AppError.NoNetwork -> stringResource(R.string.error_no_internet)
    is AppError.Timeout -> stringResource(R.string.error_timeout)
    AppError.Unauthorized -> stringResource(R.string.error_unauthorized)
    is AppError.Forbidden -> error.reason ?: stringResource(R.string.error_forbidden)
    is AppError.NotFound -> stringResource(R.string.error_not_found)
    is AppError.Conflict -> error.detail ?: stringResource(R.string.error_conflict)
    is AppError.Validation -> error.detail ?: stringResource(R.string.error_invalid_request)
    is AppError.RateLimited -> stringResource(R.string.error_rate_limited)
    is AppError.Server -> stringResource(R.string.error_server)
    is AppError.UploadFailed -> stringResource(R.string.error_upload_failed)
    is AppError.DownloadFailed -> stringResource(R.string.error_download_failed)
    is AppError.StorageFull -> stringResource(R.string.error_storage_full)
    is AppError.FileTooLarge -> stringResource(
        R.string.error_file_too_large,
        com.ping.messenger.core.common.formatBytes(error.limitBytes),
    )
    is AppError.PermissionDenied -> stringResource(R.string.error_permission_denied)
    is AppError.Decryption -> stringResource(R.string.error_decrypt_failed)
    is AppError.Protocol -> stringResource(R.string.error_server)
    is AppError.Unknown -> stringResource(R.string.error_generic)
}

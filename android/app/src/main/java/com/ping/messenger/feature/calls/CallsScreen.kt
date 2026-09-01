package com.ping.messenger.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.PhoneDisabled
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.CallDirection
import com.ping.messenger.domain.model.CallOutcome
import com.ping.messenger.domain.model.CallRecord
import com.ping.messenger.domain.repository.CallAvailability
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.theme.PingTheme

/**
 * Call history.
 *
 * When no STUN/TURN configuration is available the screen says so explicitly, with the setting
 * to change, instead of offering call buttons that would fail silently. That honesty is the
 * point: WebRTC genuinely cannot connect without ICE servers, and pretending otherwise wastes
 * the user's time.
 */
@Composable
fun CallsScreen(
    onStartCall: (conversationId: String, video: Boolean) -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.calls_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(overflowOpen, { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calls_clear_history)) },
                                onClick = { overflowOpen = false; viewModel.clearHistory() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.availability is CallAvailability.NotConfigured) {
                CallsUnavailableNotice(onOpenAdvancedSettings)
            }

            when {
                state.isLoading -> LoadingState()

                state.calls.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.PhoneDisabled,
                    title = stringResource(R.string.calls_empty_title),
                    body = stringResource(R.string.calls_empty_body),
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.calls, key = { it.id }) { call ->
                        CallRow(
                            call = call,
                            timeFormatter = timeFormatter,
                            enabled = state.callsEnabled,
                            onCallBack = { video -> onStartCall(call.conversationId, video) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallsUnavailableNotice(onConfigure: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onConfigure)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = PingTheme.colors.warning,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.calls_not_configured_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.calls_not_configured_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CallRow(
    call: CallRecord,
    timeFormatter: TimeFormatter,
    enabled: Boolean,
    onCallBack: (video: Boolean) -> Unit,
) {
    val missed = call.outcome == CallOutcome.MISSED
    val icon = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed
        call.direction == CallDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val tint = if (missed) PingTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = call.peerName,
            photoUrl = call.peerAvatarUrl,
            seed = call.peerId,
            size = 48.dp,
            isGroup = call.isGroup,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = call.peerName,
                style = MaterialTheme.typography.titleSmall,
                color = if (missed) PingTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = buildString {
                        append(timeFormatter.relative(call.startedAt))
                        if (call.durationSeconds > 0) {
                            append(" · ")
                            append(timeFormatter.durationSeconds(call.durationSeconds))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onCallBack(call.isVideo) }, enabled = enabled) {
            Icon(
                imageVector = if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                contentDescription = stringResource(
                    if (call.isVideo) R.string.calls_video else R.string.calls_voice,
                ),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

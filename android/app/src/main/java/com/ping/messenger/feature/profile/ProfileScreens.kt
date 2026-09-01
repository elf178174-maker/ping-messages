package com.ping.messenger.feature.profile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Presence
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.components.SingleChoiceDialog

/**
 * Another person's profile.
 *
 * The security-code row is the notable one: it shows the safety number derived from both
 * parties' public keys so two people can verify out of band that nobody is in the middle.
 */
@Composable
fun ContactProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onCall: (String, Boolean) -> Unit,
    onOpenMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }
    var blockOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var securityCodeOpen by remember { mutableStateOf(false) }

    LaunchedEffect(userId) { viewModel.load(userId) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEvent.Message -> snackbar.showSnackbar(event.text)
                is ProfileEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                is ProfileEvent.OpenConversation -> onMessage(event.conversationId)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val user = state.user
        if (user == null) {
            Box(Modifier.padding(padding).fillMaxSize()) { LoadingState() }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(name = user.displayName, photoUrl = user.avatarUrl, seed = user.id, size = 112.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = user.handle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            when (val presence = user.presence) {
                is Presence.Online -> Text(
                    text = stringResource(R.string.chats_online),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.ping.messenger.ui.theme.PingTheme.colors.online,
                )
                is Presence.LastSeen -> Text(
                    text = stringResource(R.string.chats_last_seen, timeFormatter.relative(presence.at)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Presence.Unknown -> Unit
            }

            if (user.about.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = user.about,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                ProfileAction(Icons.Default.Chat, stringResource(R.string.chat_message_hint)) {
                    viewModel.openChat(user.id)
                }
                ProfileAction(Icons.Default.Call, stringResource(R.string.calls_voice)) {
                    onCall(user.id, false)
                }
                ProfileAction(Icons.Default.Videocam, stringResource(R.string.calls_video)) {
                    onCall(user.id, true)
                }
            }

            Spacer(Modifier.height(26.dp))
            SettingsDivider()

            SettingsRow(
                title = stringResource(R.string.profile_media_gallery),
                icon = Icons.Outlined.PhotoLibrary,
                onClick = { state.conversationId?.let(onOpenMedia) },
            )
            SettingsRow(
                title = stringResource(R.string.settings_about_encryption),
                summary = stringResource(R.string.chat_encrypted_notice),
                icon = Icons.Outlined.Lock,
                onClick = { securityCodeOpen = true },
            )

            if (state.groupsInCommon.isNotEmpty()) {
                SectionHeader(stringResource(R.string.profile_shared_groups))
                state.groupsInCommon.forEach { group ->
                    SettingsRow(
                        title = group.name,
                        summary = stringResource(R.string.group_member_count, group.memberCount),
                        icon = Icons.Default.Groups,
                    )
                }
            }

            SettingsDivider()
            SettingsRow(
                title = stringResource(
                    if (user.isBlocked) R.string.action_unblock else R.string.action_block,
                ),
                icon = Icons.Outlined.Block,
                destructive = !user.isBlocked,
                onClick = { if (user.isBlocked) viewModel.unblock(user.id) else blockOpen = true },
            )
            SettingsRow(
                title = stringResource(R.string.action_report),
                icon = Icons.Default.Report,
                destructive = true,
                onClick = { reportOpen = true },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (blockOpen) {
        ConfirmDialog(
            title = stringResource(R.string.action_block),
            body = stringResource(R.string.contacts_block_confirm, state.user?.displayName.orEmpty()),
            confirmLabel = stringResource(R.string.action_block),
            destructive = true,
            onConfirm = { blockOpen = false; viewModel.block(userId) },
            onDismiss = { blockOpen = false },
        )
    }

    if (reportOpen) {
        SingleChoiceDialog(
            title = stringResource(R.string.contacts_report_title, state.user?.displayName.orEmpty()),
            options = ReportReason.entries,
            selected = ReportReason.SPAM,
            labelFor = { it.label },
            onSelect = { reason ->
                reportOpen = false
                viewModel.report(userId, reason.name)
            },
            onDismiss = { reportOpen = false },
            footnote = stringResource(R.string.contacts_report_body),
        )
    }

    if (securityCodeOpen) {
        SecurityCodeDialog(
            code = state.securityCode,
            peerName = state.user?.displayName.orEmpty(),
            onDismiss = { securityCodeOpen = false },
        )
    }
}

enum class ReportReason(val label: String) {
    SPAM("Spam or scam"),
    HARASSMENT("Harassment or abuse"),
    IMPERSONATION("Impersonation"),
    OTHER("Something else"),
}

@Composable
private fun ProfileAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The safety number.
 *
 * Rendered in grouped digits so two people can read it aloud to each other. If it ever changes
 * for an existing contact, the transcript shows a security notice — that is the signal that
 * either the peer reinstalled or something is wrong.
 */
@Composable
private fun SecurityCodeDialog(code: String?, peerName: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_encryption)) },
        text = {
            Column {
                Text(
                    text = "Compare this code with $peerName in person or over another channel. " +
                        "If it matches, nobody is intercepting your messages.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = code ?: "Not available until you exchange a message.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

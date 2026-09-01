package com.ping.messenger.feature.settings

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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.BuildConfig
import com.ping.messenger.R
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.feature.chat.errorText
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow

/**
 * The settings root.
 *
 * Grouped the way Android users expect: identity at the top, then the categories in rough
 * order of how often they are touched, with destructive actions last and visually separated.
 */
@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStarred: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenQrCode: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var signOutOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Message -> snackbar.showSnackbar(event.text)
                is SettingsEvent.Failed -> snackbar.showSnackbar(context.errorText(event.error))
                SettingsEvent.SignedOut -> onSignedOut()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenQrCode) {
                        Icon(Icons.Default.QrCode2, stringResource(R.string.contacts_my_qr))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ProfileHeader(
                name = state.me?.displayName.orEmpty(),
                handle = state.me?.handle.orEmpty(),
                about = state.me?.about.orEmpty(),
                avatarUrl = state.me?.avatarUrl,
                onClick = onOpenProfile,
            )

            SettingsDivider()

            SettingsRow(
                title = stringResource(R.string.settings_account),
                summary = stringResource(R.string.settings_account_summary),
                icon = Icons.Outlined.Shield,
                onClick = onOpenPrivacy,
            )
            SettingsRow(
                title = stringResource(R.string.settings_security),
                summary = stringResource(R.string.settings_two_step_summary),
                icon = Icons.Default.Lock,
                onClick = onOpenSecurity,
            )
            SettingsRow(
                title = stringResource(R.string.settings_devices),
                summary = if (state.devices.size > 1) {
                    stringResource(R.string.settings_devices_other) + " · ${state.devices.size - 1}"
                } else {
                    stringResource(R.string.settings_devices_this)
                },
                icon = Icons.Default.Devices,
                onClick = onOpenDevices,
            )

            SectionHeader(stringResource(R.string.settings_chats))
            SettingsRow(
                title = stringResource(R.string.settings_chats),
                summary = stringResource(R.string.settings_chats_summary),
                icon = Icons.Default.Chat,
                onClick = onOpenChats,
            )
            SettingsRow(
                title = stringResource(R.string.settings_appearance),
                summary = stringResource(R.string.settings_appearance_summary),
                icon = Icons.Default.Palette,
                onClick = onOpenAppearance,
            )
            SettingsRow(
                title = stringResource(R.string.settings_notifications),
                summary = stringResource(R.string.settings_notifications_summary),
                icon = Icons.Default.Notifications,
                onClick = onOpenNotifications,
            )
            SettingsRow(
                title = stringResource(R.string.settings_storage),
                summary = formatBytes(state.totalMediaBytes + state.cacheBytes),
                icon = Icons.Default.Storage,
                onClick = onOpenStorage,
            )
            SettingsRow(
                title = stringResource(R.string.settings_backup),
                summary = state.backup.lastBackupAt
                    ?.takeIf { it > 0 }
                    ?.let { formatBytes(state.backup.lastBackupSizeBytes) }
                    ?: stringResource(R.string.settings_backup_never),
                icon = Icons.Default.Bookmark,
                onClick = onOpenBackup,
            )

            SectionHeader(stringResource(R.string.contacts_title))
            SettingsRow(
                title = stringResource(R.string.contacts_title),
                icon = Icons.Default.Contacts,
                onClick = onOpenContacts,
            )
            SettingsRow(
                title = stringResource(R.string.profile_starred),
                icon = Icons.Default.Bookmark,
                onClick = onOpenStarred,
            )

            SectionHeader(stringResource(R.string.settings_about))
            SettingsRow(
                title = stringResource(R.string.settings_advanced),
                icon = Icons.Default.Tune,
                onClick = onOpenAdvanced,
            )
            SettingsRow(
                title = stringResource(R.string.settings_about),
                summary = stringResource(
                    R.string.settings_about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                icon = Icons.Default.Info,
                onClick = onOpenAbout,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            SettingsRow(
                title = stringResource(R.string.auth_sign_out),
                icon = Icons.AutoMirrored.Filled.Logout,
                destructive = true,
                onClick = { signOutOpen = true },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (signOutOpen) {
        ConfirmDialog(
            title = stringResource(R.string.auth_sign_out),
            body = "Signing out removes this device's messages and encryption keys. " +
                "Your account and history on other devices are unaffected.",
            confirmLabel = stringResource(R.string.auth_sign_out),
            destructive = true,
            onConfirm = {
                signOutOpen = false
                viewModel.signOut()
            },
            onDismiss = { signOutOpen = false },
        )
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    handle: String,
    about: String,
    avatarUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, photoUrl = avatarUrl, seed = handle, size = 66.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name.ifBlank { "…" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (about.isNotBlank()) {
                Text(
                    text = about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.QrCode2,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

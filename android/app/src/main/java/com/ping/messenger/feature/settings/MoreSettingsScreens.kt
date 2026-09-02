package com.ping.messenger.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.BuildConfig
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.core.datastore.AutoDownloadPolicy
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.components.SettingsSwitchRow
import com.ping.messenger.ui.components.SingleChoiceDialog
import com.ping.messenger.ui.components.TextInputDialog

// ---------------------------------------------------------------------------
// Chats
// ---------------------------------------------------------------------------

@Composable
fun ChatsSettingsScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SubScreen(stringResource(R.string.settings_chats), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_chats))

        SettingsSwitchRow(
            title = stringResource(R.string.settings_enter_to_send),
            summary = stringResource(R.string.settings_enter_to_send_summary),
            icon = Icons.Default.Keyboard,
            checked = state.chat.enterToSend,
            onCheckedChange = viewModel::setEnterToSend,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_media_visibility),
            summary = stringResource(R.string.settings_media_visibility_summary),
            icon = Icons.Default.PhotoLibrary,
            checked = state.chat.mediaVisibleInGallery,
            onCheckedChange = viewModel::setMediaVisibility,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_link_previews),
            summary = stringResource(R.string.settings_link_previews_summary),
            icon = Icons.Default.Link,
            checked = state.chat.linkPreviews,
            onCheckedChange = viewModel::setLinkPreviews,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_translation),
            // Translation needs a provider, and shipping message text to a third party by
            // default would contradict the rest of the app. The switch says what it does.
            summary = stringResource(R.string.settings_translation_unconfigured),
            icon = Icons.Default.Translate,
            checked = state.chat.translationEnabled,
            onCheckedChange = viewModel::setTranslation,
        )

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_appearance),
            summary = stringResource(R.string.settings_appearance_summary),
            onClick = onOpenAppearance,
        )
        SettingsRow(
            title = stringResource(R.string.settings_backup),
            summary = state.backup.lastBackupAt
                .takeIf { it > 0 }
                ?.let { formatBytes(state.backup.lastBackupSizeBytes) }
                ?: stringResource(R.string.settings_backup_never),
            onClick = onOpenBackup,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read on every composition rather than cached: the user can revoke the permission in
    // system settings while this screen is open, and a stale "all good" banner would be a lie.
    val systemAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()

    SubScreen(stringResource(R.string.settings_notifications), onBack, modifier) {
        if (!systemAllowed) {
            NoticeCard(
                title = stringResource(R.string.settings_notif_permission_needed),
                actionLabel = stringResource(R.string.settings_notif_permission_action),
                onAction = { context.openNotificationSettings() },
            )
        }

        SectionHeader(stringResource(R.string.settings_notifications))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_messages),
            icon = Icons.Default.Notifications,
            checked = state.notifications.messagesEnabled,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setMessageNotifications,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_groups),
            checked = state.notifications.groupsEnabled,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setGroupNotifications,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_calls),
            icon = Icons.Default.VideoCall,
            checked = state.notifications.callsEnabled,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setCallNotifications,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_reactions),
            checked = state.notifications.reactionsEnabled,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setReactionNotifications,
        )

        SettingsDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_preview),
            summary = stringResource(R.string.settings_notif_preview_summary),
            checked = state.notifications.showPreview,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setNotificationPreview,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notif_vibrate),
            checked = state.notifications.vibrate,
            enabled = systemAllowed,
            onCheckedChange = viewModel::setVibrate,
        )

        SettingsDivider()
        // Per-channel sound and importance live in system settings from Android 8 onwards.
        // Duplicating them in-app would give two answers to the same question.
        SettingsRow(
            title = stringResource(R.string.settings_notif_system),
            summary = stringResource(R.string.settings_notif_channels_summary),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { context.openNotificationSettings() },
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Storage and data
// ---------------------------------------------------------------------------

private fun AutoDownloadPolicy.labelRes(): Int = when (this) {
    AutoDownloadPolicy.NEVER -> R.string.settings_autodownload_never
    AutoDownloadPolicy.IMAGES_ONLY -> R.string.settings_autodownload_images
    AutoDownloadPolicy.IMAGES_AND_AUDIO -> R.string.settings_autodownload_images_audio
    AutoDownloadPolicy.ALL -> R.string.settings_autodownload_all
}

/** Which connection an auto-download policy applies to, as a lens onto the settings object. */
private enum class DownloadNetwork(val labelRes: Int) {
    WIFI(R.string.settings_autodownload_wifi),
    MOBILE(R.string.settings_autodownload_mobile),
    ROAMING(R.string.settings_autodownload_roaming),
}

@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DownloadNetwork?>(null) }

    SubScreen(stringResource(R.string.settings_storage), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_storage_usage))
        Text(
            text = stringResource(
                R.string.settings_storage_total,
                formatBytes(state.totalMediaBytes + state.cacheBytes),
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        val rows = listOf(
            R.string.settings_storage_photos to state.storageBreakdown[MessageKind.IMAGE].orZero(),
            R.string.settings_storage_videos to state.storageBreakdown[MessageKind.VIDEO].orZero(),
            R.string.settings_storage_audio to
                (
                    state.storageBreakdown[MessageKind.AUDIO].orZero() +
                        state.storageBreakdown[MessageKind.VOICE].orZero()
                    ),
            R.string.settings_storage_documents to
                state.storageBreakdown[MessageKind.DOCUMENT].orZero(),
        )
        val total = rows.sumOf { it.second }.coerceAtLeast(1)
        rows.forEach { (labelRes, bytes) ->
            StorageBar(
                label = stringResource(labelRes),
                bytes = bytes,
                fraction = bytes.toFloat() / total,
            )
        }

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_storage_cache),
            summary = formatBytes(state.cacheBytes),
            trailing = {
                Button(onClick = viewModel::clearCache, enabled = state.cacheBytes > 0) {
                    Text(stringResource(R.string.settings_storage_clear_cache))
                }
            },
        )

        SettingsDivider()
        SectionHeader(stringResource(R.string.settings_autodownload))
        Text(
            text = stringResource(R.string.settings_autodownload_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        DownloadNetwork.entries.forEach { network ->
            SettingsRow(
                title = stringResource(network.labelRes),
                icon = Icons.Default.CloudDownload,
                value = stringResource(state.storage.policyFor(network).labelRes()),
                onClick = { editing = network },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { network ->
        SingleChoiceDialog(
            title = stringResource(network.labelRes),
            options = AutoDownloadPolicy.entries.toList(),
            selected = state.storage.policyFor(network),
            labelFor = { stringResource(it.labelRes()) },
            onSelect = { policy ->
                val next = state.storage.with(network, policy)
                viewModel.setAutoDownload(
                    next.autoDownloadWifi,
                    next.autoDownloadMobile,
                    next.autoDownloadRoaming,
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun com.ping.messenger.core.datastore.StorageSettings.policyFor(
    network: DownloadNetwork,
): AutoDownloadPolicy = when (network) {
    DownloadNetwork.WIFI -> autoDownloadWifi
    DownloadNetwork.MOBILE -> autoDownloadMobile
    DownloadNetwork.ROAMING -> autoDownloadRoaming
}

private fun com.ping.messenger.core.datastore.StorageSettings.with(
    network: DownloadNetwork,
    policy: AutoDownloadPolicy,
) = when (network) {
    DownloadNetwork.WIFI -> copy(autoDownloadWifi = policy)
    DownloadNetwork.MOBILE -> copy(autoDownloadMobile = policy)
    DownloadNetwork.ROAMING -> copy(autoDownloadRoaming = policy)
}

@Composable
private fun StorageBar(label: String, bytes: Long, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Advanced
// ---------------------------------------------------------------------------

@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var serverDraft by remember(state.advanced.serverUrlOverride) {
        mutableStateOf(state.advanced.serverUrlOverride)
    }
    var iceDraft by remember(state.advanced.iceServersOverride) {
        mutableStateOf(state.advanced.iceServersOverride)
    }
    var editingServer by remember { mutableStateOf(false) }
    var editingIce by remember { mutableStateOf(false) }

    SubScreen(stringResource(R.string.settings_advanced), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_advanced))

        SettingsRow(
            title = stringResource(R.string.settings_server_url),
            summary = stringResource(R.string.settings_server_url_summary),
            icon = Icons.Default.Dns,
            value = state.advanced.serverUrlOverride.ifBlank { BuildConfig.API_BASE_URL },
            onClick = { editingServer = true },
        )
        SettingsRow(
            title = stringResource(R.string.settings_ice_servers),
            summary = stringResource(R.string.settings_ice_servers_summary),
            icon = Icons.Default.Tune,
            value = state.advanced.iceServersOverride.ifBlank {
                BuildConfig.STUN_SERVERS.ifBlank { stringResource(R.string.calls_not_configured_title) }
            },
            onClick = { editingIce = true },
        )
        Text(
            text = stringResource(R.string.settings_advanced_restart_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        SettingsDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_contact_sync),
            summary = stringResource(R.string.contacts_sync_explainer),
            icon = Icons.Default.Contacts,
            checked = state.advanced.contactSyncEnabled,
            onCheckedChange = viewModel::setContactSync,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (editingServer) {
        TextInputDialog(
            title = stringResource(R.string.settings_server_url),
            value = serverDraft,
            onValueChange = { serverDraft = it },
            label = stringResource(R.string.settings_server_url),
            supportingText = stringResource(R.string.settings_server_url_default),
            onConfirm = {
                viewModel.setServerUrl(serverDraft.trim())
                editingServer = false
            },
            onDismiss = { editingServer = false },
        )
    }

    if (editingIce) {
        TextInputDialog(
            title = stringResource(R.string.settings_ice_servers),
            value = iceDraft,
            onValueChange = { iceDraft = it },
            label = stringResource(R.string.settings_ice_servers),
            supportingText = stringResource(R.string.settings_ice_servers_summary),
            onConfirm = {
                viewModel.setIceServers(iceDraft.trim())
                editingIce = false
            },
            onDismiss = { editingIce = false },
        )
    }
}

// ---------------------------------------------------------------------------
// About and licences
// ---------------------------------------------------------------------------

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repoUrl = stringResource(R.string.about_repo_url)

    SubScreen(stringResource(R.string.about_title), onBack, modifier) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                R.string.settings_about_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        SectionHeader(stringResource(R.string.settings_about_encryption))
        Text(
            text = stringResource(R.string.about_encryption_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_about_source),
            summary = stringResource(R.string.about_source_body),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { context.openUrl(repoUrl) },
        )
        SettingsRow(
            title = stringResource(R.string.settings_about_licenses),
            icon = Icons.Default.Gavel,
            onClick = onOpenLicenses,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The libraries Ping is built on.
 *
 * Written out rather than generated: a generated report needs a Gradle plugin whose output
 * would then have to be kept in the APK, and this list changes about twice a year.
 */
private val Libraries = listOf(
    "Jetpack Compose, Room, Paging, WorkManager, DataStore, CameraX, Media3" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache 2.0",
    "Dagger Hilt" to "Apache 2.0",
    "Google Tink" to "Apache 2.0",
    "OkHttp and Retrofit" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "stream-webrtc-android" to "BSD 3-Clause",
    "ZXing Core" to "Apache 2.0",
    "Material Components and Material Symbols" to "Apache 2.0",
)

@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    SubScreen(stringResource(R.string.licenses_title), onBack, modifier) {
        Text(
            text = stringResource(R.string.licenses_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Libraries.forEach { (name, license) ->
            SettingsRow(title = name, summary = license)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

/** A card that explains something the user has to act on outside the app. */
@Composable
internal fun NoticeCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun android.content.Context.openNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
    }
    // An OEM can ship a build without the settings activity this resolves to, and an
    // ActivityNotFoundException here would take the app down for a settings shortcut.
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun android.content.Context.openUrl(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Formats a timestamp the way the rest of the app does, for screens that show only one. */
@Composable
internal fun rememberTimeFormatter(): TimeFormatter {
    val context = LocalContext.current
    return remember(context) { TimeFormatter(context) }
}

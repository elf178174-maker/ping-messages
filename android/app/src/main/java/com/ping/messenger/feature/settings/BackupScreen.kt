package com.ping.messenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.backup.BackupCipher
import com.ping.messenger.core.backup.BackupHandle
import com.ping.messenger.core.backup.BackupManifest
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.domain.model.BackupState
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.PasswordField
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.components.SettingsSwitchRow
import com.ping.messenger.ui.components.errorMessage

/**
 * Chat backup.
 *
 * The screen is deliberately explicit about the two kinds of archive, because the difference
 * only matters at the moment it is too late to change: an automatic backup is sealed with a key
 * that lives on this device and dies with it, while a passphrase backup can be copied off the
 * phone and opened anywhere. Neither is presented as "backup" without qualification, and there
 * is no cloud switch, because there is no cloud provider in this build.
 *
 * A restore reads the archive's manifest first, so the confirmation says how many messages are
 * actually in the file - and so a wrong passphrase is caught before anything is written.
 */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val formatter = rememberTimeFormatter()

    var passphrasePrompt by remember { mutableStateOf<BackupHandle?>(null) }
    var creatingPassphrase by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupHandle?>(null) }
    var failure by remember { mutableStateOf<AppError?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val restoredTemplate = stringResource(R.string.backup_restore_done)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupEvent.Message -> notice = event.text
                is BackupEvent.Failed -> failure = event.error
                is BackupEvent.Inspected -> pending = pending?.copy(manifest = event.manifest)
                is BackupEvent.Restored -> notice =
                    String.format(restoredTemplate, event.messages, event.mediaFiles)
            }
        }
    }

    // errorMessage is composable, so the failure is turned into text here rather than inside
    // the collector above.
    failure?.let { error ->
        val text = errorMessage(error)
        LaunchedEffect(error) {
            snackbars.showSnackbar(text)
            failure = null
        }
    }
    notice?.let { text ->
        LaunchedEffect(text) {
            snackbars.showSnackbar(text)
            notice = null
        }
    }

    val running = state.status.state == BackupState.RUNNING

    SubScreen(
        title = stringResource(R.string.backup_title),
        onBack = onBack,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
    ) {
        if (running) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.backup_working),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.status.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_backup))
        SettingsRow(
            title = stringResource(R.string.settings_backup_last),
            summary = state.settings.lastBackupAt
                .takeIf { it > 0 }
                ?.let {
                    formatter.listTimestamp(it) + " - " + formatBytes(state.settings.lastBackupSizeBytes)
                }
                ?: stringResource(R.string.settings_backup_never),
            icon = Icons.Default.Schedule,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_backup_auto),
            summary = stringResource(R.string.backup_device_key_notice),
            icon = Icons.Default.Backup,
            checked = state.settings.automaticEnabled,
            onCheckedChange = viewModel::setAutomatic,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_backup_include_media),
            icon = Icons.Default.PermMedia,
            checked = state.settings.includeMedia,
            onCheckedChange = viewModel::setIncludeMedia,
        )

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_backup_now),
            summary = stringResource(R.string.backup_device_sealed),
            icon = Icons.Default.Backup,
            enabled = !running,
            onClick = { viewModel.backUpNow(passphrase = null) },
        )
        SettingsRow(
            title = stringResource(R.string.backup_now_passphrase),
            summary = stringResource(R.string.backup_passphrase_explainer),
            icon = Icons.Default.Key,
            enabled = !running,
            onClick = { creatingPassphrase = true },
        )

        // There is no cloud destination in this build, and this says so instead of offering a
        // switch that would silently do nothing.
        SettingsRow(
            title = stringResource(R.string.settings_backup_cloud_unconfigured),
            summary = stringResource(R.string.settings_backup_cloud_explainer),
            icon = Icons.Default.CloudOff,
            enabled = false,
        )

        SettingsDivider()
        SectionHeader(stringResource(R.string.backup_existing))
        if (state.archives.isEmpty()) {
            Text(
                text = stringResource(R.string.backup_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else {
            state.archives.forEach { handle ->
                SettingsRow(
                    title = formatter.listTimestamp(handle.createdAt),
                    summary = formatBytes(handle.sizeBytes),
                    icon = Icons.Default.Restore,
                    enabled = !running,
                    onClick = { passphrasePrompt = handle },
                    trailing = {
                        IconButton(onClick = { confirmDelete = handle }, enabled = !running) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (creatingPassphrase) {
        PassphraseDialog(
            creating = true,
            onConfirm = { passphrase ->
                viewModel.backUpNow(passphrase)
                creatingPassphrase = false
            },
            onUseDeviceKey = { creatingPassphrase = false },
            onDismiss = { creatingPassphrase = false },
        )
    }

    passphrasePrompt?.let { handle ->
        PassphraseDialog(
            creating = false,
            onConfirm = { passphrase ->
                pending = PendingRestore(handle, passphrase)
                viewModel.inspect(handle, passphrase)
                passphrasePrompt = null
            },
            onUseDeviceKey = {
                pending = PendingRestore(handle, passphrase = null)
                viewModel.inspect(handle, passphrase = null)
                passphrasePrompt = null
            },
            onDismiss = { passphrasePrompt = null },
        )
    }

    // Shown only once the manifest has come back, so the numbers in it are real.
    pending?.takeIf { it.manifest != null }?.let { ready ->
        val manifest = requireNotNull(ready.manifest)
        ConfirmDialog(
            title = stringResource(R.string.backup_restore_title),
            body = stringResource(
                R.string.backup_inspect_summary,
                manifest.messageCount,
                manifest.attachmentCount,
                formatter.listTimestamp(manifest.createdAt),
            ) + "\n\n" + stringResource(R.string.backup_restore_explainer),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = {
                viewModel.restore(ready.handle, ready.passphrase)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }

    confirmDelete?.let { handle ->
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            body = stringResource(R.string.backup_delete_confirm),
            destructive = true,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(handle)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/**
 * A restore the user has authenticated but not yet confirmed. The passphrase lives here, in
 * composition state that goes away with the screen, rather than on the view-model.
 */
private data class PendingRestore(
    val handle: BackupHandle,
    val passphrase: String?,
    val manifest: BackupManifest? = null,
)

/**
 * Asks for a passphrase.
 *
 * Creating asks twice, because a typo in a write-only secret is unrecoverable. Restoring asks
 * once and also offers "this was an automatic backup", since the user may not know which kind
 * of archive they are looking at.
 */
@Composable
private fun PassphraseDialog(
    creating: Boolean,
    onConfirm: (String) -> Unit,
    onUseDeviceKey: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }

    val tooShort = first.isNotEmpty() && first.length < BackupCipher.MIN_PASSPHRASE_LENGTH
    val mismatch = creating && second.isNotEmpty() && first != second
    val ready = first.length >= BackupCipher.MIN_PASSPHRASE_LENGTH && (!creating || first == second)

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (creating) R.string.backup_passphrase else R.string.backup_enter_passphrase,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creating) {
                    Text(
                        stringResource(R.string.backup_passphrase_explainer),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PasswordField(
                    value = first,
                    onValueChange = { first = it },
                    label = stringResource(R.string.backup_passphrase),
                    supportingText = stringResource(R.string.backup_passphrase_hint),
                    errorText = stringResource(R.string.backup_passphrase_short).takeIf { tooShort },
                )
                if (creating) {
                    PasswordField(
                        value = second,
                        onValueChange = { second = it },
                        label = stringResource(R.string.backup_passphrase_repeat),
                        errorText = stringResource(R.string.backup_passphrase_mismatch)
                            .takeIf { mismatch },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(first) }, enabled = ready) {
                Text(
                    stringResource(
                        if (creating) R.string.action_save else R.string.action_continue,
                    ),
                )
            }
        },
        dismissButton = {
            if (creating) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            } else {
                TextButton(onClick = onUseDeviceKey) {
                    Text(stringResource(R.string.backup_device_sealed))
                }
            }
        },
    )
}

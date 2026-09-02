package com.ping.messenger.feature.settings

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.PrivacyAudience
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.PasswordField
import com.ping.messenger.ui.components.PingTextField
import com.ping.messenger.ui.components.SectionHeader
import com.ping.messenger.ui.components.SettingsDivider
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.components.SettingsSwitchRow
import com.ping.messenger.ui.components.SingleChoiceDialog
import com.ping.messenger.ui.theme.ChatWallpaper
import com.ping.messenger.ui.theme.ThemeMode
import com.ping.messenger.ui.theme.wallpaperModifier

/** Shared chrome for every settings sub-screen. */
@Composable
internal fun SubScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Privacy
// ---------------------------------------------------------------------------

/**
 * Each privacy control expressed as a lens onto [PrivacySettings].
 *
 * Modelling them as data rather than seven near-identical blocks of UI means adding a control
 * is one line, and the dialog wiring cannot drift between them.
 */
enum class PrivacyField(
    val labelRes: Int,
    val get: (PrivacySettings) -> PrivacyAudience,
    val set: (PrivacySettings, PrivacyAudience) -> PrivacySettings,
) {
    LAST_SEEN(R.string.settings_privacy_last_seen, { it.lastSeen }, { s, v -> s.copy(lastSeen = v) }),
    ONLINE(R.string.settings_privacy_online, { it.onlineStatus }, { s, v -> s.copy(onlineStatus = v) }),
    PHOTO(R.string.settings_privacy_photo, { it.profilePhoto }, { s, v -> s.copy(profilePhoto = v) }),
    ABOUT(R.string.settings_privacy_about, { it.about }, { s, v -> s.copy(about = v) }),
    STATUS(R.string.settings_privacy_status, { it.status }, { s, v -> s.copy(status = v) }),
    GROUPS(R.string.settings_privacy_groups, { it.groups }, { s, v -> s.copy(groups = v) }),
    CALLS(R.string.settings_privacy_calls, { it.calls }, { s, v -> s.copy(calls = v) }),
}

private fun PrivacyAudience.labelRes(): Int = when (this) {
    PrivacyAudience.EVERYONE -> R.string.settings_privacy_everyone
    PrivacyAudience.CONTACTS -> R.string.settings_privacy_contacts
    PrivacyAudience.NOBODY -> R.string.settings_privacy_nobody
}

@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onOpenBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PrivacyField?>(null) }

    SubScreen(stringResource(R.string.settings_privacy), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_privacy))

        PrivacyField.entries.forEach { field ->
            SettingsRow(
                title = stringResource(field.labelRes),
                value = stringResource(field.get(state.privacy).labelRes()),
                onClick = { editing = field },
            )
        }

        SectionHeader(stringResource(R.string.settings_chats))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_read_receipts),
            summary = stringResource(R.string.settings_read_receipts_summary),
            checked = state.privacy.readReceipts,
            onCheckedChange = { on -> viewModel.updatePrivacy { it.copy(readReceipts = on) } },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_typing_indicators),
            checked = state.privacy.typingIndicators,
            onCheckedChange = { on -> viewModel.updatePrivacy { it.copy(typingIndicators = on) } },
        )

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.contacts_blocked),
            icon = Icons.Outlined.Block,
            onClick = onOpenBlocked,
        )
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { field ->
        SingleChoiceDialog(
            title = stringResource(field.labelRes),
            options = PrivacyAudience.entries,
            selected = field.get(state.privacy),
            labelFor = { stringResource(it.labelRes()) },
            onSelect = { audience ->
                viewModel.updatePrivacy { field.set(it, audience) }
                editing = null
            },
            onDismiss = { editing = null },
            footnote = if (field == PrivacyField.LAST_SEEN) {
                stringResource(R.string.settings_privacy_note_reciprocal)
            } else {
                null
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------

@Composable
fun SecuritySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var twoStepOpen by remember { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }

    SubScreen(stringResource(R.string.settings_security), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_security))

        SettingsSwitchRow(
            title = stringResource(R.string.settings_app_lock),
            summary = stringResource(R.string.settings_app_lock_summary),
            icon = Icons.Default.Lock,
            checked = state.security.appLockEnabled,
            onCheckedChange = viewModel::setAppLock,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_screen_security),
            summary = stringResource(R.string.settings_screen_security_summary),
            icon = Icons.Default.Screenshot,
            checked = state.security.blockScreenshots,
            onCheckedChange = viewModel::setBlockScreenshots,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_security_notifications),
            summary = stringResource(R.string.settings_security_notifications_summary),
            checked = state.security.securityNotificationsEnabled,
            onCheckedChange = viewModel::setSecurityNotifications,
        )

        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_two_step),
            summary = stringResource(R.string.settings_two_step_summary),
            value = stringResource(
                if (state.security.twoStepEnabled) {
                    R.string.settings_two_step_on
                } else {
                    R.string.settings_two_step_off
                },
            ),
            icon = Icons.Default.LockReset,
            onClick = { twoStepOpen = true },
        )
        SettingsRow(
            title = stringResource(R.string.settings_change_password),
            icon = Icons.Default.Password,
            onClick = { passwordOpen = true },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (twoStepOpen) {
        // Both the PIN and the account password are required: a PIN alone would let anyone
        // holding an unlocked phone turn the second factor off.
        CredentialDialog(
            title = stringResource(R.string.settings_two_step),
            firstLabel = stringResource(R.string.settings_two_step_pin),
            secondLabel = stringResource(R.string.auth_password),
            confirmLabel = stringResource(R.string.action_save),
            // Clearing the PIN field turns two-step off, which is why an empty first field is
            // allowed here but the password never is.
            firstOptional = state.security.twoStepEnabled,
            onConfirm = { pin, password ->
                viewModel.setTwoStep(pin.ifBlank { null }, password)
                twoStepOpen = false
            },
            onDismiss = { twoStepOpen = false },
        )
    }

    if (passwordOpen) {
        CredentialDialog(
            title = stringResource(R.string.settings_change_password),
            firstLabel = stringResource(R.string.auth_password),
            secondLabel = stringResource(R.string.auth_confirm_password),
            confirmLabel = stringResource(R.string.action_save),
            firstIsPassword = true,
            onConfirm = { current, next ->
                viewModel.changePassword(current, next)
                passwordOpen = false
            },
            onDismiss = { passwordOpen = false },
        )
    }
}

/**
 * Two secret fields and a confirm button.
 *
 * Shared by two-step verification and password change because both need exactly this and
 * nothing more, and having one implementation means the "confirm is disabled until the fields
 * are usable" rule cannot drift between them.
 */
@Composable
private fun CredentialDialog(
    title: String,
    firstLabel: String,
    secondLabel: String,
    confirmLabel: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    firstOptional: Boolean = false,
    firstIsPassword: Boolean = true,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ready = (firstOptional || first.isNotBlank()) && second.isNotBlank()

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (firstIsPassword) {
                    PasswordField(value = first, onValueChange = { first = it }, label = firstLabel)
                } else {
                    PingTextField(value = first, onValueChange = { first = it }, label = firstLabel)
                }
                PasswordField(value = second, onValueChange = { second = it }, label = secondLabel)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(first, second) }, enabled = ready) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Devices
// ---------------------------------------------------------------------------

@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }
    var revokeAllOpen by remember { mutableStateOf(false) }

    SubScreen(stringResource(R.string.settings_devices), onBack, modifier) {
        val current = state.devices.firstOrNull { it.isCurrent }
        val others = state.devices.filterNot { it.isCurrent }

        SectionHeader(stringResource(R.string.settings_devices_this))
        current?.let {
            SettingsRow(
                title = it.deviceName,
                summary = stringResource(
                    R.string.settings_devices_last_active,
                    timeFormatter.relative(it.lastActiveAt),
                ),
                icon = Icons.Default.Devices,
            )
        }

        SectionHeader(stringResource(R.string.settings_devices_other))
        if (others.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        } else {
            others.forEach { device ->
                SettingsRow(
                    title = device.deviceName,
                    summary = buildString {
                        append(device.platform)
                        append(" · ")
                        append(
                            stringResource(
                                R.string.settings_devices_last_active,
                                timeFormatter.relative(device.lastActiveAt),
                            ),
                        )
                        device.ipCountry?.let { append(" · $it") }
                    },
                    icon = Icons.Default.Devices,
                    value = stringResource(R.string.settings_devices_revoke),
                    onClick = { viewModel.revokeDevice(device.id) },
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_devices_revoke_all),
                destructive = true,
                onClick = { revokeAllOpen = true },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (revokeAllOpen) {
        ConfirmDialog(
            title = stringResource(R.string.settings_devices_revoke_all),
            body = "Every other signed-in device will be logged out immediately.",
            destructive = true,
            onConfirm = { revokeAllOpen = false; viewModel.revokeOtherDevices() },
            onDismiss = { revokeAllOpen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Appearance
// ---------------------------------------------------------------------------

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var themeDialogOpen by remember { mutableStateOf(false) }
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    SubScreen(stringResource(R.string.settings_appearance), onBack, modifier) {
        SectionHeader(stringResource(R.string.settings_theme))
        SettingsRow(
            title = stringResource(R.string.settings_theme),
            value = stringResource(state.appearance.themeMode.labelRes()),
            icon = Icons.Default.DarkMode,
            onClick = { themeDialogOpen = true },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            // Shown disabled with the reason on older devices rather than hidden, so the
            // setting stays discoverable instead of appearing to be missing.
            summary = if (supportsDynamicColor) {
                stringResource(R.string.settings_dynamic_color_summary)
            } else {
                "Requires Android 12 or newer"
            },
            enabled = supportsDynamicColor,
            checked = state.appearance.dynamicColor && supportsDynamicColor,
            onCheckedChange = viewModel::setDynamicColor,
        )

        SectionHeader(stringResource(R.string.settings_font_scale))
        FontScaleControl(state.appearance.fontScale, viewModel::setFontScale)

        SectionHeader("Accessibility")
        SettingsSwitchRow(
            title = stringResource(R.string.settings_high_contrast),
            summary = stringResource(R.string.settings_high_contrast_summary),
            checked = state.appearance.highContrast,
            onCheckedChange = viewModel::setHighContrast,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_reduce_motion),
            summary = stringResource(R.string.settings_reduce_motion_summary),
            checked = state.appearance.reduceMotion,
            onCheckedChange = viewModel::setReduceMotion,
        )

        SectionHeader(stringResource(R.string.chat_wallpaper))
        WallpaperPicker(state.appearance.wallpaperId, viewModel::setWallpaper)
        Spacer(Modifier.height(32.dp))
    }

    if (themeDialogOpen) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = state.appearance.themeMode,
            labelFor = { stringResource(it.labelRes()) },
            onSelect = { viewModel.setThemeMode(it); themeDialogOpen = false },
            onDismiss = { themeDialogOpen = false },
        )
    }
}

/**
 * Text-size control.
 *
 * Multiplies the system font scale rather than replacing it, so a user who has already
 * enlarged text device-wide keeps that as their baseline. The sample below updates live,
 * because a number alone tells nobody what 1.15 will look like.
 */
@Composable
private fun FontScaleControl(scale: Float, onScaleChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.FormatSize,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = scale,
                onValueChange = onScaleChange,
                valueRange = 0.85f..1.4f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                Icons.Default.FormatSize,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The quick brown fox jumps over the lazy dog.",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WallpaperPicker(selectedId: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ChatWallpaper.entries.size) { index ->
            val wallpaper = ChatWallpaper.entries[index]
            val selected = wallpaper.id == selectedId
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(width = 62.dp, height = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .wallpaperModifier(wallpaper.id)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(wallpaper.id) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = wallpaper.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

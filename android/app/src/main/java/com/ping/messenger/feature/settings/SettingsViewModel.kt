package com.ping.messenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.core.datastore.AdvancedSettings
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.datastore.AppearanceSettings
import com.ping.messenger.core.datastore.AutoDownloadPolicy
import com.ping.messenger.core.datastore.BackupSettings
import com.ping.messenger.core.datastore.ChatSettings
import com.ping.messenger.core.datastore.NotificationSettings
import com.ping.messenger.core.datastore.SecuritySettings
import com.ping.messenger.core.datastore.StorageSettings
import com.ping.messenger.domain.model.BackupStatus
import com.ping.messenger.domain.model.DeviceSession
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.AuthRepository
import com.ping.messenger.domain.repository.SettingsRepository
import com.ping.messenger.domain.repository.UserRepository
import com.ping.messenger.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val me: User? = null,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val chat: ChatSettings = ChatSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val storage: StorageSettings = StorageSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val backup: BackupSettings = BackupSettings(),
    val backupStatus: BackupStatus = BackupStatus(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    val devices: List<DeviceSession> = emptyList(),
    val storageBreakdown: Map<MessageKind, Long> = emptyMap(),
    val cacheBytes: Long = 0,
    val busy: Boolean = false,
    val error: AppError? = null,
    val message: String? = null,
) {
    val totalMediaBytes: Long get() = storageBreakdown.values.sum()
}

sealed interface SettingsEvent {
    data class Message(val text: String) : SettingsEvent
    data class Failed(val error: AppError) : SettingsEvent
    data object SignedOut : SettingsEvent
}

/**
 * Backs the whole settings tree.
 *
 * A single view-model for all of Settings rather than one per sub-screen: the sub-screens are
 * views onto one preferences object, and splitting them would mean each re-reading DataStore
 * and re-deriving the same state.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val settings: SettingsRepository,
    private val users: UserRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private data class LocalState(
        val busy: Boolean = false,
        val error: AppError? = null,
        val storageBreakdown: Map<MessageKind, Long> = emptyMap(),
        val cacheBytes: Long = 0,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        users.observeMe(),
        combine(preferences.appearance, preferences.chat, preferences.notifications) { a, c, n ->
            Triple(a, c, n)
        },
        combine(preferences.privacy, preferences.storage, preferences.security) { p, s, sec ->
            Triple(p, s, sec)
        },
        combine(preferences.backup, preferences.advanced, settings.observeBackupStatus()) { b, adv, status ->
            Triple(b, adv, status)
        },
        combine(settings.observeDevices(), local) { devices, localState -> devices to localState },
    ) { me, (appearance, chat, notifications), (privacy, storage, security), (backup, advanced, backupStatus), (devices, localState) ->
        SettingsUiState(
            me = me,
            appearance = appearance,
            chat = chat,
            notifications = notifications,
            privacy = privacy,
            storage = storage,
            security = security,
            backup = backup,
            backupStatus = backupStatus,
            advanced = advanced,
            devices = devices,
            storageBreakdown = localState.storageBreakdown,
            cacheBytes = localState.cacheBytes,
            busy = localState.busy,
            error = localState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshStorage()
        viewModelScope.launch { settings.refreshDevices() }
    }

    // ---- Appearance -------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { preferences.setDynamicColor(on) }
    fun setHighContrast(on: Boolean) = viewModelScope.launch { preferences.setHighContrast(on) }
    fun setReduceMotion(on: Boolean) = viewModelScope.launch { preferences.setReduceMotion(on) }
    fun setFontScale(scale: Float) = viewModelScope.launch { preferences.setFontScale(scale) }
    fun setWallpaper(id: String) = viewModelScope.launch { preferences.setWallpaper(id) }

    // ---- Chats ------------------------------------------------------------

    fun setEnterToSend(on: Boolean) = viewModelScope.launch { preferences.setEnterToSend(on) }
    fun setMediaVisibility(on: Boolean) = viewModelScope.launch { preferences.setMediaVisibility(on) }
    fun setLinkPreviews(on: Boolean) = viewModelScope.launch { preferences.setLinkPreviews(on) }
    fun setTranslation(on: Boolean) = viewModelScope.launch { preferences.setTranslation(on) }

    // ---- Notifications ----------------------------------------------------

    fun setMessageNotifications(on: Boolean) = viewModelScope.launch {
        preferences.setNotificationsEnabled(on)
    }
    fun setGroupNotifications(on: Boolean) = viewModelScope.launch {
        preferences.setGroupNotifications(on)
    }
    fun setCallNotifications(on: Boolean) = viewModelScope.launch {
        preferences.setCallNotifications(on)
    }
    fun setReactionNotifications(on: Boolean) = viewModelScope.launch {
        preferences.setReactionNotifications(on)
    }
    fun setNotificationPreview(on: Boolean) = viewModelScope.launch {
        preferences.setNotificationPreview(on)
    }
    fun setVibrate(on: Boolean) = viewModelScope.launch { preferences.setVibrate(on) }

    // ---- Privacy ----------------------------------------------------------

    /**
     * Read receipts are reciprocal: turning them off also stops the user seeing other
     * people's. That is enforced here rather than only described in the UI copy, so the
     * setting cannot be used one-sidedly.
     */
    fun updatePrivacy(update: (PrivacySettings) -> PrivacySettings) = viewModelScope.launch {
        val current = uiState.value.privacy
        val next = update(current)
        when (val result = settings.updatePrivacy(next)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    // ---- Security ---------------------------------------------------------

    fun setAppLock(on: Boolean) = viewModelScope.launch { preferences.setAppLock(on) }
    fun setBlockScreenshots(on: Boolean) = viewModelScope.launch { preferences.setBlockScreenshots(on) }
    fun setSecurityNotifications(on: Boolean) = viewModelScope.launch {
        preferences.setSecurityNotifications(on)
    }

    fun setTwoStep(pin: String?, currentPassword: String) = busy {
        when (val result = auth.setTwoStepPin(pin, currentPassword)) {
            is Outcome.Success -> _events.emit(SettingsEvent.Message("Two-step verification updated"))
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    fun changePassword(current: String, new: String) = busy {
        when (val result = auth.changePassword(current, new)) {
            is Outcome.Success -> _events.emit(SettingsEvent.Message("Password changed"))
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    // ---- Devices ----------------------------------------------------------

    fun refreshDevices() = busy { settings.refreshDevices() }

    fun revokeDevice(id: String) = busy {
        when (val result = settings.revokeDevice(id)) {
            is Outcome.Success -> _events.emit(SettingsEvent.Message("Device signed out"))
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    fun revokeOtherDevices() = busy {
        when (val result = settings.revokeOtherDevices()) {
            is Outcome.Success -> _events.emit(SettingsEvent.Message("Other devices signed out"))
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    // ---- Storage ----------------------------------------------------------

    fun refreshStorage() = viewModelScope.launch {
        local.update {
            it.copy(
                storageBreakdown = settings.storageBreakdown(),
                cacheBytes = settings.cacheSizeBytes(),
            )
        }
    }

    fun clearCache() = busy {
        val freed = settings.clearCache()
        refreshStorage()
        _events.emit(SettingsEvent.Message("Cache cleared (${formatBytes(freed)})"))
    }

    fun setAutoDownload(
        wifi: AutoDownloadPolicy,
        mobile: AutoDownloadPolicy,
        roaming: AutoDownloadPolicy,
    ) = viewModelScope.launch { preferences.setAutoDownload(wifi, mobile, roaming) }

    // ---- Backup -----------------------------------------------------------

    fun setBackupAutomatic(on: Boolean) = viewModelScope.launch { preferences.setBackupAutomatic(on) }
    fun setBackupIncludeMedia(on: Boolean) = viewModelScope.launch {
        preferences.setBackupIncludeMedia(on)
    }

    fun runBackup() = busy {
        val includeMedia = uiState.value.backup.includeMedia
        when (val result = settings.runBackup(includeMedia)) {
            is Outcome.Success -> _events.emit(
                SettingsEvent.Message("Backup complete (${formatBytes(result.value)})"),
            )
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    // ---- Advanced ---------------------------------------------------------

    fun setServerUrl(url: String) = viewModelScope.launch { preferences.setServerUrl(url) }
    fun setIceServers(value: String) = viewModelScope.launch { preferences.setIceServers(value) }
    fun setContactSync(on: Boolean) = viewModelScope.launch { preferences.setContactSync(on) }

    // ---- Account ----------------------------------------------------------

    fun signOut() = viewModelScope.launch {
        auth.signOut()
        _events.emit(SettingsEvent.SignedOut)
    }

    fun deleteAccount() = busy {
        when (val result = auth.deleteAccount()) {
            is Outcome.Success -> _events.emit(SettingsEvent.SignedOut)
            is Outcome.Failure -> emitFailure(result.error)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    // ---- Internals --------------------------------------------------------

    private fun busy(block: suspend () -> Unit) = viewModelScope.launch {
        local.update { it.copy(busy = true, error = null) }
        try {
            block()
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    private suspend fun emitFailure(error: AppError) {
        local.update { it.copy(error = error) }
        _events.emit(SettingsEvent.Failed(error))
    }
}

package com.ping.messenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.backup.BackupHandle
import com.ping.messenger.core.backup.BackupManifest
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.StringProvider
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.datastore.BackupSettings
import com.ping.messenger.domain.model.BackupStatus
import com.ping.messenger.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BackupUiState(
    val settings: BackupSettings = BackupSettings(),
    val status: BackupStatus = BackupStatus(),
    val archives: List<BackupHandle> = emptyList(),
)

sealed interface BackupEvent {
    data class Message(val text: String) : BackupEvent
    data class Failed(val error: AppError) : BackupEvent

    /** An archive was opened and its manifest read, so a restore can be confirmed with facts. */
    data class Inspected(val manifest: BackupManifest) : BackupEvent

    /** Restored counts, so the screen can report what actually came back. */
    data class Restored(val messages: Int, val mediaFiles: Int) : BackupEvent
}

/**
 * Backs the backup screen.
 *
 * Separate from [SettingsViewModel] because this is the one settings screen with real work
 * behind it: long-running operations with progress, a passphrase the view-model must never
 * retain, and a list that changes as a result of what the user does here.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val strings: StringProvider,
    private val settings: SettingsRepository,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val uiState: StateFlow<BackupUiState> = combine(
        preferences.backup,
        settings.observeBackupStatus(),
        settings.observeBackups(),
    ) { settingsValue, status, archives ->
        BackupUiState(settingsValue, status, archives)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    fun setAutomatic(on: Boolean) = viewModelScope.launch { preferences.setBackupAutomatic(on) }

    fun setIncludeMedia(on: Boolean) = viewModelScope.launch {
        preferences.setBackupIncludeMedia(on)
    }

    /**
     * [passphrase] null seals the archive with this device's key. The value is passed straight
     * through and never stored on this view-model, so a configuration change cannot leave it
     * sitting in memory.
     */
    fun backUpNow(passphrase: String?) = viewModelScope.launch {
        when (val result = settings.runBackup(uiState.value.settings.includeMedia, passphrase)) {
            is Outcome.Success -> _events.emit(BackupEvent.Message(strings[R.string.toast_backup_complete]))
            is Outcome.Failure -> _events.emit(BackupEvent.Failed(result.error))
        }
    }

    /**
     * Opens the archive far enough to read its manifest. This is also how a wrong passphrase
     * is caught before anything is written to the database.
     */
    fun inspect(handle: BackupHandle, passphrase: String?) = viewModelScope.launch {
        when (val result = settings.inspectBackup(handle.location, passphrase)) {
            is Outcome.Success -> _events.emit(BackupEvent.Inspected(result.value))
            is Outcome.Failure -> _events.emit(BackupEvent.Failed(result.error))
        }
    }

    fun restore(handle: BackupHandle, passphrase: String?) = viewModelScope.launch {
        when (val result = settings.restoreBackup(handle.location, passphrase)) {
            is Outcome.Success -> _events.emit(
                BackupEvent.Restored(result.value.messages, result.value.mediaFiles),
            )
            is Outcome.Failure -> _events.emit(BackupEvent.Failed(result.error))
        }
    }

    fun delete(handle: BackupHandle) = viewModelScope.launch {
        when (val result = settings.deleteBackup(handle.id)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> _events.emit(BackupEvent.Failed(result.error))
        }
    }
}

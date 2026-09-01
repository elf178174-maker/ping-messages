package com.ping.messenger.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.repository.UserRepository
import com.ping.messenger.feature.auth.AuthValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditProfileUiState(
    val displayName: String = "",
    val username: String = "",
    val about: String = "",
    val phoneNumber: String = "",
    val avatarUrl: String? = null,
    val avatarPath: String? = null,
    val displayNameError: String? = null,
    val usernameError: String? = null,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
) {
    val canSave: Boolean
        get() = isDirty && !isSaving &&
            AuthValidation.isValidDisplayName(displayName) &&
            AuthValidation.isValidUsername(username)
}

sealed interface EditProfileEvent {
    data object Saved : EditProfileEvent
    data class Failed(val error: AppError) : EditProfileEvent
}

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    private val users: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditProfileEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val me = users.observeMe().filterNotNull().first()
            _uiState.update {
                it.copy(
                    displayName = me.displayName,
                    username = me.username,
                    about = me.about,
                    phoneNumber = me.phoneNumber.orEmpty(),
                    avatarUrl = me.avatarUrl,
                )
            }
        }
    }

    fun onDisplayNameChange(value: String) = _uiState.update {
        it.copy(
            displayName = value.take(AuthValidation.MAX_DISPLAY_NAME_LENGTH),
            displayNameError = null,
            isDirty = true,
        )
    }

    fun onUsernameChange(value: String) = _uiState.update {
        it.copy(
            username = AuthValidation.normaliseUsername(value).take(24),
            usernameError = null,
            isDirty = true,
        )
    }

    fun onAboutChange(value: String) = _uiState.update {
        it.copy(about = value.take(AuthValidation.MAX_ABOUT_LENGTH), isDirty = true)
    }

    fun pickAvatar(path: String) = _uiState.update { it.copy(avatarPath = path, isDirty = true) }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            // The avatar is uploaded first: if that fails there is no point writing the rest,
            // and if the profile write fails afterwards the upload is simply unreferenced.
            state.avatarPath?.let { path ->
                when (val upload = users.updateAvatar(path)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> {
                        _uiState.update { it.copy(isSaving = false) }
                        _events.emit(EditProfileEvent.Failed(upload.error))
                        return@launch
                    }
                }
            }

            val result = users.updateProfile(
                displayName = state.displayName,
                about = state.about,
                username = state.username,
            )
            _uiState.update { it.copy(isSaving = false) }

            when (result) {
                is Outcome.Success -> {
                    _uiState.update { it.copy(isDirty = false, avatarPath = null) }
                    _events.emit(EditProfileEvent.Saved)
                }
                is Outcome.Failure -> {
                    if (result.error is AppError.Conflict) {
                        _uiState.update {
                            it.copy(usernameError = "That username is already taken")
                        }
                    }
                    _events.emit(EditProfileEvent.Failed(result.error))
                }
            }
        }
    }
}

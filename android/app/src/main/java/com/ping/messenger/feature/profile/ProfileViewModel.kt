package com.ping.messenger.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val groupsInCommon: List<Group> = emptyList(),
    val securityCode: String? = null,
    val conversationId: String? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
)

sealed interface ProfileEvent {
    data class OpenConversation(val conversationId: String) : ProfileEvent
    data class Message(val text: String) : ProfileEvent
    data class Failed(val error: AppError) : ProfileEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    private val userId = MutableStateFlow<String?>(null)
    private val extras = MutableStateFlow(Extras())

    private val _events = MutableSharedFlow<ProfileEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private data class Extras(
        val groups: List<Group> = emptyList(),
        val securityCode: String? = null,
        val conversationId: String? = null,
        val busy: Boolean = false,
        val error: AppError? = null,
    )

    val uiState: StateFlow<ProfileUiState> = combine(
        userId.flatMapLatest { id -> if (id == null) flowOf(null) else users.observeUser(id) },
        extras,
    ) { user, extra ->
        ProfileUiState(
            user = user,
            groupsInCommon = extra.groups,
            securityCode = extra.securityCode,
            conversationId = extra.conversationId,
            isBusy = extra.busy,
            error = extra.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun load(id: String) {
        if (userId.value == id) return
        userId.value = id
        viewModelScope.launch {
            extras.update {
                it.copy(
                    groups = users.groupsInCommon(id),
                    securityCode = users.securityCodeFor(id),
                )
            }
        }
    }

    fun openChat(id: String) = viewModelScope.launch {
        when (val result = conversations.openDirectChat(id)) {
            is Outcome.Success -> {
                extras.update { it.copy(conversationId = result.value) }
                _events.emit(ProfileEvent.OpenConversation(result.value))
            }
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun block(id: String) = viewModelScope.launch {
        when (val result = users.block(id)) {
            is Outcome.Success -> _events.emit(ProfileEvent.Message("Blocked"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun unblock(id: String) = viewModelScope.launch {
        when (val result = users.unblock(id)) {
            is Outcome.Success -> _events.emit(ProfileEvent.Message("Unblocked"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun report(id: String, reason: String) = viewModelScope.launch {
        when (val result = users.report(id, reason, emptyList(), null)) {
            is Outcome.Success -> _events.emit(ProfileEvent.Message("Report submitted"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    private suspend fun fail(error: AppError) {
        extras.update { it.copy(error = error) }
        _events.emit(ProfileEvent.Failed(error))
    }
}

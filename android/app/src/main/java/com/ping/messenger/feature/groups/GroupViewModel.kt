package com.ping.messenger.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.common.StringProvider
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.GroupPermission
import com.ping.messenger.domain.model.GroupRole
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.GroupRepository
import com.ping.messenger.domain.repository.UserRepository
import com.ping.messenger.ui.navigation.Routes
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

data class GroupUiState(
    val group: Group? = null,
    val contacts: List<User> = emptyList(),
    val inviteLink: String? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
) {
    val isAdmin: Boolean get() = group?.isAdmin == true
}

sealed interface GroupEvent {
    data class Created(val conversationId: String) : GroupEvent
    data class Message(val text: String) : GroupEvent
    data class Failed(val error: AppError) : GroupEvent
    data object Left : GroupEvent
}

/**
 * Group creation and administration.
 *
 * Every mutating call goes through the repository, which round-trips to the server and writes
 * the authoritative response back. Group membership is one of the few places where optimistic
 * local updates are the wrong choice: the server is the arbiter of who is actually in a group,
 * and showing a member who was rejected is worse than a moment's latency.
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val strings: StringProvider,
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
    private val users: UserRepository,
) : ViewModel() {

    private val conversationId: String? = savedStateHandle[Routes.ARG_CONVERSATION_ID]
    private val local = MutableStateFlow(LocalState())

    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private data class LocalState(
        val inviteLink: String? = null,
        val busy: Boolean = false,
        val error: AppError? = null,
    )

    val uiState: StateFlow<GroupUiState> = combine(
        if (conversationId != null) groups.observeGroup(conversationId) else kotlinx.coroutines.flow.flowOf(null),
        users.observeContacts(),
        local,
    ) { group, contacts, localState ->
        GroupUiState(
            group = group,
            contacts = contacts,
            inviteLink = localState.inviteLink,
            isBusy = localState.busy,
            error = localState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUiState())

    fun create(name: String, description: String, memberIds: List<String>) = busy {
        when (val result = groups.create(name, description, memberIds)) {
            is Outcome.Success -> _events.emit(GroupEvent.Created(result.value))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun updateInfo(name: String?, description: String?) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.updateInfo(id, name, description)) {
            is Outcome.Success -> _events.emit(GroupEvent.Message(strings[R.string.toast_group_updated]))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun updateAvatar(localPath: String) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.updateAvatar(id, localPath)) {
            is Outcome.Success -> _events.emit(GroupEvent.Message(strings[R.string.toast_group_icon_updated]))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun addMembers(userIds: List<String>) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.addMembers(id, userIds)) {
            is Outcome.Success -> _events.emit(GroupEvent.Message(strings[R.string.toast_members_added]))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun removeMember(userId: String) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.removeMember(id, userId)) {
            is Outcome.Success -> _events.emit(GroupEvent.Message(strings[R.string.toast_member_removed]))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun setRole(userId: String, role: GroupRole) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.setRole(id, userId, role)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun setPermissions(
        send: GroupPermission,
        editInfo: GroupPermission,
        addMembers: GroupPermission,
    ) = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.setPermissions(id, send, editInfo, addMembers)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun leave() = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.leave(id)) {
            is Outcome.Success -> _events.emit(GroupEvent.Left)
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun loadInviteLink() = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.inviteLink(id)) {
            is Outcome.Success -> local.update { it.copy(inviteLink = result.value) }
            is Outcome.Failure -> fail(result.error)
        }
    }

    /** Rotating the code invalidates every previously shared link. */
    fun resetInviteLink() = busy {
        val id = uiState.value.group?.id ?: return@busy
        when (val result = groups.resetInviteLink(id)) {
            is Outcome.Success -> {
                local.update { it.copy(inviteLink = result.value) }
                _events.emit(GroupEvent.Message(strings[R.string.toast_invite_link_reset]))
            }
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun joinByCode(code: String) = busy {
        when (val result = groups.joinByCode(code)) {
            is Outcome.Success -> _events.emit(GroupEvent.Created(result.value))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun busy(block: suspend () -> Unit) = viewModelScope.launch {
        local.update { it.copy(busy = true, error = null) }
        try {
            block()
        } finally {
            local.update { it.copy(busy = false) }
        }
    }

    private suspend fun fail(error: AppError) {
        local.update { it.copy(error = error) }
        _events.emit(GroupEvent.Failed(error))
    }
}

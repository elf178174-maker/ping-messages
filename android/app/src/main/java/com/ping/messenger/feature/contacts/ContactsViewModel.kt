package com.ping.messenger.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.UserRepository
import com.ping.messenger.feature.auth.AuthValidation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactsUiState(
    val contacts: List<User> = emptyList(),
    val blocked: List<User> = emptyList(),
    val query: String = "",
    val remoteResults: List<User> = emptyList(),
    val isSearching: Boolean = false,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val error: AppError? = null,
) {
    val filteredContacts: List<User>
        get() = if (query.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.displayName.contains(query, true) || it.username.contains(query, true)
            }
        }

    /** Remote hits that are not already in the local contact list. */
    val discoveries: List<User>
        get() = remoteResults.filterNot { remote -> contacts.any { it.id == remote.id } }
}

sealed interface ContactsEvent {
    data class OpenConversation(val conversationId: String) : ContactsEvent
    data class Message(val text: String) : ContactsEvent
    data class Failed(val error: AppError) : ContactsEvent
}

/**
 * Contacts and people search.
 *
 * The list is local-first: typing filters what is already on the device instantly, and a
 * server lookup for unknown usernames runs alongside it after a debounce. That means the
 * common case (finding someone you already talk to) never waits on the network.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private var searchJob: Job? = null

    private val _events = MutableSharedFlow<ContactsEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private data class LocalState(
        val query: String = "",
        val remoteResults: List<User> = emptyList(),
        val isSearching: Boolean = false,
        val notFound: Boolean = false,
        val error: AppError? = null,
        val loaded: Boolean = false,
    )

    val uiState: StateFlow<ContactsUiState> = combine(
        users.observeContacts(),
        users.observeBlocked(),
        local,
    ) { contacts, blocked, localState ->
        ContactsUiState(
            contacts = contacts,
            blocked = blocked,
            query = localState.query,
            remoteResults = localState.remoteResults,
            isSearching = localState.isSearching,
            isLoading = !localState.loaded,
            notFound = localState.notFound,
            error = localState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    init {
        viewModelScope.launch {
            users.refreshContacts()
            local.update { it.copy(loaded = true) }
        }
    }

    fun onQueryChange(value: String) {
        local.update { it.copy(query = value, notFound = false) }
        searchJob?.cancel()

        if (value.isBlank()) {
            local.update { it.copy(remoteResults = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(400)
            local.update { it.copy(isSearching = true) }

            // An exact @handle gets a direct lookup, which is both faster and the only way to
            // find someone who has opted out of appearing in search.
            val trimmed = value.trim()
            val result = if (AuthValidation.isValidUsername(AuthValidation.normaliseUsername(trimmed))) {
                when (val exact = users.findByUsername(trimmed)) {
                    is Outcome.Success -> Outcome.Success(listOf(exact.value))
                    is Outcome.Failure ->
                        if (exact.error is AppError.NotFound) users.searchUsers(trimmed) else exact
                }
            } else {
                users.searchUsers(trimmed)
            }

            when (result) {
                is Outcome.Success -> local.update {
                    it.copy(
                        remoteResults = result.value,
                        isSearching = false,
                        notFound = result.value.isEmpty(),
                    )
                }
                is Outcome.Failure -> local.update {
                    it.copy(isSearching = false, error = result.error.takeIf { e -> e !is AppError.NotFound })
                }
            }
        }
    }

    fun clearQuery() = onQueryChange("")

    fun openChat(userId: String) = viewModelScope.launch {
        when (val result = conversations.openDirectChat(userId)) {
            is Outcome.Success -> _events.emit(ContactsEvent.OpenConversation(result.value))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun addContact(userId: String) = viewModelScope.launch {
        when (val result = users.addContact(userId)) {
            is Outcome.Success -> _events.emit(ContactsEvent.Message("Added to contacts"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun block(userId: String) = viewModelScope.launch {
        when (val result = users.block(userId)) {
            is Outcome.Success -> _events.emit(ContactsEvent.Message("Blocked"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun unblock(userId: String) = viewModelScope.launch {
        when (val result = users.unblock(userId)) {
            is Outcome.Success -> _events.emit(ContactsEvent.Message("Unblocked"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    fun report(userId: String, reason: String, note: String?) = viewModelScope.launch {
        when (val result = users.report(userId, reason, emptyList(), note)) {
            is Outcome.Success -> _events.emit(ContactsEvent.Message("Report submitted"))
            is Outcome.Failure -> fail(result.error)
        }
    }

    /**
     * Resolves a scanned QR payload.
     *
     * Accepts both the `ping://user/<username>` deep link and a bare @handle, so a code
     * produced by another build (or typed by hand) still works.
     */
    fun openScanned(payload: String) = viewModelScope.launch {
        val username = payload
            .removePrefix("ping://user/")
            .substringAfterLast('/')
            .removePrefix("@")
            .trim()

        if (username.isBlank()) {
            fail(AppError.NotFound("username"))
            return@launch
        }
        when (val user = users.findByUsername(username)) {
            is Outcome.Success -> openChat(user.value.id)
            is Outcome.Failure -> fail(user.error)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private suspend fun fail(error: AppError) {
        local.update { it.copy(error = error) }
        _events.emit(ContactsEvent.Failed(error))
    }
}

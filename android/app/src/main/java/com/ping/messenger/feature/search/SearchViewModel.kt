package com.ping.messenger.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.core.common.FtsQuery
import com.ping.messenger.data.local.dao.MessageDao
import com.ping.messenger.data.local.dao.MessageSearchHit
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.User
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchFilter(val labelRes: Int) {
    ALL(R.string.search_filter_all),
    CHATS(R.string.search_section_chats),
    CONTACTS(R.string.search_section_contacts),
    MESSAGES(R.string.search_section_messages),
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val contacts: List<User> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val messages: List<MessageSearchHit> = emptyList(),
    val isSearching: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !isSearching && contacts.isEmpty() && conversations.isEmpty() && messages.isEmpty()
}

/**
 * Global search.
 *
 * Runs entirely against the local database. That is a deliberate design choice, not a
 * limitation: messages are end-to-end encrypted, so the server physically cannot search their
 * contents — only the device that can decrypt them can. The FTS index is what makes that
 * practical.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val users: UserRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        runSearch(value, _uiState.value.filter)
    }

    fun onFilterChange(filter: SearchFilter) {
        _uiState.update { it.copy(filter = filter) }
        runSearch(_uiState.value.query, filter)
    }

    private fun runSearch(query: String, filter: SearchFilter) {
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    contacts = emptyList(),
                    conversations = emptyList(),
                    messages = emptyList(),
                    isSearching = false,
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(180)
            _uiState.update { it.copy(isSearching = true) }

            val wantsContacts = filter == SearchFilter.ALL || filter == SearchFilter.CONTACTS
            val wantsChats = filter == SearchFilter.ALL || filter == SearchFilter.CHATS
            val wantsMessages = filter == SearchFilter.ALL || filter == SearchFilter.MESSAGES

            val contactResults = if (wantsContacts) users.searchLocal(query) else emptyList()
            val chatResults = if (wantsChats) conversations.searchConversations(query) else emptyList()
            val messageResults = if (wantsMessages) {
                FtsQuery.sanitise(query)
                    ?.let { runCatching { messageDao.searchAll(it) }.getOrDefault(emptyList()) }
                    .orEmpty()
            } else {
                emptyList()
            }

            _uiState.update {
                it.copy(
                    contacts = contactResults,
                    conversations = chatResults,
                    messages = messageResults,
                    isSearching = false,
                )
            }
        }
    }
}

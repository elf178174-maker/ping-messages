package com.ping.messenger.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.network.NetworkMonitor
import com.ping.messenger.data.remote.ws.RealtimeStatus
import com.ping.messenger.data.remote.ws.RealtimeClient
import com.ping.messenger.domain.model.ChatFolder
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the chat list screen renders, as one immutable snapshot. */
data class ChatsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val folders: List<ChatFolder> = emptyList(),
    val selectedFolderId: String = ChatFolder.ALL_ID,
    val archivedCount: Int = 0,
    val query: String = "",
    val isSearching: Boolean = false,
    val isOffline: Boolean = false,
    val isConnecting: Boolean = false,
    val error: AppError? = null,
    val selectedIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
    val isEmpty: Boolean get() = !isLoading && conversations.isEmpty() && query.isBlank()
    val hasNoResults: Boolean get() = !isLoading && conversations.isEmpty() && query.isNotBlank()
}

/** One-shot events: things that happen rather than things that are. */
sealed interface ChatsEvent {
    data class ShowUndo(val message: String, val undo: () -> Unit) : ChatsEvent
    data class ShowMessage(val message: String) : ChatsEvent
    data class OpenConversation(val conversationId: String) : ChatsEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val networkMonitor: NetworkMonitor,
    private val realtimeClient: RealtimeClient,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow(ChatFolder.ALL_ID)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<AppError?>(null)

    private val _events = MutableSharedFlow<ChatsEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /**
     * The list, filtered by folder and search text.
     *
     * The search term is debounced by 200 ms: typing "hello" would otherwise run five filter
     * passes over the whole list, and the last one is the only one anybody sees.
     */
    val uiState: StateFlow<ChatsUiState> = combine(
        conversations.observeChats(archived = false),
        conversations.observeFolders(),
        conversations.observeArchivedCount(),
        combine(query.debounce(200), selectedFolder, selection) { q, folder, selected ->
            Triple(q, folder, selected)
        },
        combine(networkMonitor.state, realtimeClient.status, refreshing, error) { net, socket, isRefreshing, err ->
            NetworkSnapshot(net.isOnline, socket == RealtimeStatus.CONNECTING, isRefreshing, err)
        },
    ) { all, folders, archived, (q, folderId, selected), network ->
        ChatsUiState(
            isLoading = false,
            isRefreshing = network.refreshing,
            conversations = all.filterBy(folderId, folders).searchedBy(q),
            folders = folders,
            selectedFolderId = folderId,
            archivedCount = archived,
            query = q,
            isSearching = q.isNotBlank(),
            isOffline = !network.online,
            isConnecting = network.connecting,
            error = network.error,
            selectedIds = selected,
        )
    }.stateIn(
        scope = viewModelScope,
        // 5 s rather than Eagerly: the list keeps flowing across a configuration change, but
        // stops doing database work once the user genuinely leaves the screen.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatsUiState(),
    )

    private data class NetworkSnapshot(
        val online: Boolean,
        val connecting: Boolean,
        val refreshing: Boolean,
        val error: AppError?,
    )

    init {
        refresh()
        viewModelScope.launch {
            // Subscribing tells the server which conversations this device wants live events
            // for, so a user in 400 groups is not pushed all of them at once.
            conversations.observeChats(archived = false)
                .map { list -> list.map { it.id } }
                .collect { realtimeClient.subscribe(it) }
        }
    }

    private fun List<Conversation>.filterBy(
        folderId: String,
        folders: List<ChatFolder>,
    ): List<Conversation> = when (folderId) {
        ChatFolder.ALL_ID -> this
        ChatFolder.UNREAD_ID -> filter { it.hasUnread }
        ChatFolder.GROUPS_ID -> filter { it.isGroup }
        else -> {
            val folder = folders.firstOrNull { it.id == folderId }
            when {
                folder == null -> this
                folder.includeUnreadOnly -> filter { it.id in folder.conversationIds && it.hasUnread }
                folder.includeGroups -> filter { it.id in folder.conversationIds || it.isGroup }
                else -> filter { it.id in folder.conversationIds }
            }
        }
    }

    private fun List<Conversation>.searchedBy(q: String): List<Conversation> {
        if (q.isBlank()) return this
        val needle = q.trim()
        return filter { conversation ->
            conversation.title.contains(needle, ignoreCase = true) ||
                conversation.lastMessage?.text?.contains(needle, ignoreCase = true) == true
        }
    }

    fun onQueryChange(value: String) { query.value = value }

    fun clearQuery() { query.value = "" }

    fun onFolderSelected(id: String) { selectedFolder.value = id }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            when (val result = conversations.refresh()) {
                is Outcome.Success -> error.value = null
                is Outcome.Failure -> {
                    // A refresh failure is not a blocking error: the cached list is still
                    // shown. It only becomes visible if there is nothing cached at all.
                    if (uiState.value.conversations.isEmpty()) error.value = result.error
                }
            }
            refreshing.value = false
        }
    }

    fun dismissError() { error.value = null }

    // ---- Selection --------------------------------------------------------

    fun toggleSelection(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { selection.value = emptySet() }

    fun selectAll() {
        selection.value = uiState.value.conversations.map { it.id }.toSet()
    }

    // ---- Actions ----------------------------------------------------------

    fun togglePin(id: String) = viewModelScope.launch {
        val pinned = uiState.value.conversations.firstOrNull { it.id == id }?.isPinned ?: false
        conversations.setPinned(id, !pinned)
        clearSelection()
    }

    fun toggleMute(id: String) = viewModelScope.launch {
        val muted = uiState.value.conversations.firstOrNull { it.id == id }?.isMuted ?: false
        conversations.setMuted(id, !muted)
        clearSelection()
    }

    /**
     * Archiving is offered with an undo rather than a confirmation dialog: it is reversible,
     * and a dialog on a frequent, low-risk action is friction for no safety gain.
     */
    fun archive(id: String, archived: Boolean = true, undoLabel: String) = viewModelScope.launch {
        conversations.setArchived(id, archived)
        clearSelection()
        _events.emit(
            ChatsEvent.ShowUndo(undoLabel) {
                viewModelScope.launch { conversations.setArchived(id, !archived) }
            },
        )
    }

    fun archiveSelected(undoLabel: String) = viewModelScope.launch {
        val ids = selection.value.toList()
        ids.forEach { conversations.setArchived(it, true) }
        clearSelection()
        _events.emit(
            ChatsEvent.ShowUndo(undoLabel) {
                viewModelScope.launch { ids.forEach { conversations.setArchived(it, false) } }
            },
        )
    }

    fun toggleRead(id: String) = viewModelScope.launch {
        val conversation = uiState.value.conversations.firstOrNull { it.id == id }
        if (conversation?.hasUnread == true) conversations.markRead(id) else conversations.markUnread(id)
        clearSelection()
    }

    /**
     * Deleting is destructive and not undoable, so the screen shows a confirmation first;
     * this is only called once the user has confirmed.
     */
    fun delete(id: String) = viewModelScope.launch {
        when (val result = conversations.delete(id)) {
            is Outcome.Success -> clearSelection()
            is Outcome.Failure -> error.value = result.error
        }
    }

    fun deleteSelected() = viewModelScope.launch {
        selection.value.forEach { conversations.delete(it) }
        clearSelection()
    }
}

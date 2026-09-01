package com.ping.messenger.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.core.media.AudioRecorder
import com.ping.messenger.core.network.NetworkMonitor
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.GeoPoint
import com.ping.messenger.domain.model.Group
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.GroupRepository
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.domain.repository.OutgoingMessage
import com.ping.messenger.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConversationUiState(
    val conversation: Conversation? = null,
    val group: Group? = null,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val enterToSend: Boolean = false,
    val composerMode: ComposerMode = ComposerMode.Idle,
    val selectedMessageIds: Set<String> = emptySet(),
    val searchTerm: String? = null,
    val searchResults: List<String> = emptyList(),
    val searchIndex: Int = 0,
    val highlightedMessageId: String? = null,
    val pinnedMessage: Message? = null,
    val error: AppError? = null,
    val wallpaperId: String? = null,
) {
    val isSelectionMode: Boolean get() = selectedMessageIds.isNotEmpty()
    val isGroup: Boolean get() = conversation?.isGroup == true
    val canSend: Boolean get() = group?.canSend ?: true
    val title: String get() = conversation?.title.orEmpty()
}

sealed interface ConversationEvent {
    data class ScrollTo(val messageId: String) : ConversationEvent
    data class ShowMessage(val text: String) : ConversationEvent
    data class ShowError(val error: AppError) : ConversationEvent
    data class CopyToClipboard(val text: String) : ConversationEvent
    data object ScrollToBottom : ConversationEvent
}

/**
 * The conversation screen's state holder.
 *
 * The screen is stateless with respect to messages: it renders a [PagingData] stream straight
 * from Room, which means an incoming message, a delivery receipt, or a reaction applied by the
 * sync engine all reach the UI without this class knowing about them. What it owns is the
 * *interaction* state — what is selected, what is being replied to, what the search is showing.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val groups: GroupRepository,
    private val media: MediaRepository,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val audioRecorder: AudioRecorder,
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle[Routes.ARG_CONVERSATION_ID])
    private val focusMessageId: String? =
        savedStateHandle.get<String>(Routes.ARG_MESSAGE_ID)?.takeIf { it.isNotBlank() }

    private val composerMode = MutableStateFlow<ComposerMode>(ComposerMode.Idle)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val search = MutableStateFlow<SearchState>(SearchState())
    private val highlighted = MutableStateFlow<String?>(focusMessageId)
    private val error = MutableStateFlow<AppError?>(null)
    private val draftText = MutableStateFlow("")

    private var typingJob: Job? = null
    private var recordingJob: Job? = null

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private data class SearchState(
        val term: String? = null,
        val results: List<String> = emptyList(),
        val index: Int = 0,
    )

    /**
     * `cachedIn` is essential here: without it, every recomposition would resubscribe to the
     * pager and reload page one, resetting the user's scroll position.
     */
    val messagePages: Flow<PagingData<Message>> =
        messages.pagedMessages(conversationId).cachedIn(viewModelScope)

    val uiState: StateFlow<ConversationUiState> = combine(
        conversations.observeConversation(conversationId),
        groups.observeGroup(conversationId),
        combine(composerMode, selection, search, highlighted) { mode, selected, searchState, highlight ->
            Interaction(mode, selected, searchState, highlight)
        },
        combine(preferences.chat, networkMonitor.state, error) { chat, network, err ->
            Environment(chat.enterToSend, !network.isOnline, err)
        },
    ) { conversation, group, interaction, environment ->
        ConversationUiState(
            conversation = conversation,
            group = group,
            isLoading = conversation == null,
            isOffline = environment.offline,
            enterToSend = environment.enterToSend,
            composerMode = interaction.mode,
            selectedMessageIds = interaction.selection,
            searchTerm = interaction.search.term,
            searchResults = interaction.search.results,
            searchIndex = interaction.search.index,
            highlightedMessageId = interaction.highlighted,
            error = environment.error,
            wallpaperId = conversation?.wallpaperId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    private data class Interaction(
        val mode: ComposerMode,
        val selection: Set<String>,
        val search: SearchState,
        val highlighted: String?,
    )

    private data class Environment(
        val enterToSend: Boolean,
        val offline: Boolean,
        val error: AppError?,
    )

    init {
        viewModelScope.launch {
            // Opening a conversation is what marks it read; doing it here rather than in the
            // composable means it happens once per open, not once per recomposition.
            conversations.markRead(conversationId)
            conversations.observeConversation(conversationId).first()?.draft?.let {
                draftText.value = it
            }
        }
        focusMessageId?.let { id ->
            viewModelScope.launch { _events.emit(ConversationEvent.ScrollTo(id)) }
        }
    }

    // ---- Composing --------------------------------------------------------

    /**
     * Called on every keystroke. Two side effects are deliberately debounced rather than
     * immediate: the typing indicator (so we do not emit one socket frame per character) and
     * the draft write (so we do not hit the database per character either).
     */
    fun onTextChanged(text: String) {
        draftText.value = text
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            messages.setTyping(conversationId, text.isNotBlank())
            delay(1_200)
            conversations.saveDraft(conversationId, text)
            // Typing indicators auto-expire so a user who stops mid-sentence does not appear
            // to type forever.
            delay(2_500)
            messages.setTyping(conversationId, false)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val mode = composerMode.value

        viewModelScope.launch {
            when (mode) {
                is ComposerMode.Editing -> {
                    if (trimmed.isNotEmpty() && trimmed != mode.message.text) {
                        messages.edit(mode.message.id, trimmed).onFailureEmit()
                    }
                }
                else -> {
                    if (trimmed.isEmpty()) return@launch
                    val result = messages.send(
                        OutgoingMessage(
                            conversationId = conversationId,
                            text = trimmed,
                            replyToId = (mode as? ComposerMode.Replying)?.message?.id,
                            mentions = com.ping.messenger.core.common.TextUtils.extractMentions(trimmed),
                        ),
                    )
                    result.onFailureEmit()
                    if (result is Outcome.Success) _events.emit(ConversationEvent.ScrollToBottom)
                }
            }
            composerMode.value = ComposerMode.Idle
            draftText.value = ""
            conversations.saveDraft(conversationId, null)
            messages.setTyping(conversationId, false)
        }
    }

    fun sendAttachments(paths: List<String>, kind: MessageKind, caption: String = "") {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            messages.send(
                OutgoingMessage(
                    conversationId = conversationId,
                    text = caption,
                    kind = kind,
                    attachmentPaths = paths,
                    replyToId = (composerMode.value as? ComposerMode.Replying)?.message?.id,
                ),
            ).onFailureEmit()
            composerMode.value = ComposerMode.Idle
            _events.emit(ConversationEvent.ScrollToBottom)
        }
    }

    fun sendLocation(point: GeoPoint) = viewModelScope.launch {
        messages.send(
            OutgoingMessage(
                conversationId = conversationId,
                kind = MessageKind.LOCATION,
                location = point,
            ),
        ).onFailureEmit()
    }

    fun sendContact(userId: String) = viewModelScope.launch {
        messages.send(
            OutgoingMessage(
                conversationId = conversationId,
                kind = MessageKind.CONTACT,
                contactUserId = userId,
            ),
        ).onFailureEmit()
    }

    fun sendPoll(question: String, options: List<String>, allowsMultiple: Boolean) =
        viewModelScope.launch {
            messages.send(
                OutgoingMessage(
                    conversationId = conversationId,
                    kind = MessageKind.POLL,
                    pollQuestion = question,
                    pollOptions = options.filter { it.isNotBlank() },
                    pollAllowsMultiple = allowsMultiple,
                ),
            ).onFailureEmit()
        }

    fun scheduleMessage(text: String, atMillis: Long) = viewModelScope.launch {
        messages.send(
            OutgoingMessage(
                conversationId = conversationId,
                text = text.trim(),
                scheduledFor = atMillis,
            ),
        ).onFailureEmit()
        composerMode.value = ComposerMode.Idle
    }

    // ---- Voice recording --------------------------------------------------

    fun startRecording() {
        val started = audioRecorder.start()
        if (!started) {
            error.value = AppError.PermissionDenied(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        recordingJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                composerMode.value = ComposerMode.Recording(
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    cancelProgress = 0f,
                )
                delay(100)
            }
        }
    }

    fun stopRecording(cancelled: Boolean) {
        recordingJob?.cancel()
        recordingJob = null
        composerMode.value = ComposerMode.Idle

        val result = audioRecorder.stop(discard = cancelled)
        if (cancelled || result == null) return

        // A recording shorter than a second is almost always an accidental tap on the mic.
        if (result.durationMs < 900) {
            viewModelScope.launch { _events.emit(ConversationEvent.ShowMessage("Hold to record")) }
            return
        }

        viewModelScope.launch {
            messages.send(
                OutgoingMessage(
                    conversationId = conversationId,
                    kind = MessageKind.VOICE,
                    attachmentPaths = listOf(result.path),
                ),
            ).onFailureEmit()
            _events.emit(ConversationEvent.ScrollToBottom)
        }
    }

    // ---- Message actions --------------------------------------------------

    fun reply(message: Message) { composerMode.value = ComposerMode.Replying(message) }

    fun edit(message: Message) { composerMode.value = ComposerMode.Editing(message) }

    fun cancelComposerMode() { composerMode.value = ComposerMode.Idle }

    fun react(messageId: String, emoji: String) = viewModelScope.launch {
        messages.toggleReaction(messageId, emoji).onFailureEmit()
    }

    fun toggleStar(message: Message) = viewModelScope.launch {
        messages.setStarred(message.id, !message.isStarred).onFailureEmit()
        clearSelection()
    }

    fun deleteMessages(ids: Set<String>, forEveryone: Boolean) = viewModelScope.launch {
        ids.forEach { messages.delete(it, forEveryone).onFailureEmit() }
        clearSelection()
    }

    fun retry(messageId: String) = viewModelScope.launch {
        messages.retry(messageId).onFailureEmit()
    }

    fun copyText(text: String) = viewModelScope.launch {
        _events.emit(ConversationEvent.CopyToClipboard(text))
        clearSelection()
    }

    fun votePoll(messageId: String, optionIds: List<String>) = viewModelScope.launch {
        messages.votePoll(messageId, optionIds).onFailureEmit()
    }

    fun pinMessage(messageId: String?) = viewModelScope.launch {
        messages.setPinnedMessage(conversationId, messageId).onFailureEmit()
        clearSelection()
    }

    fun downloadAttachment(attachmentId: String) = viewModelScope.launch {
        media.ensureDownloaded(attachmentId).onFailureEmit()
    }

    // ---- Selection --------------------------------------------------------

    fun toggleSelection(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { selection.value = emptySet() }

    // ---- Search within the conversation ----------------------------------

    fun searchInChat(term: String) = viewModelScope.launch {
        if (term.isBlank()) {
            search.value = SearchState()
            return@launch
        }
        val hits = messages.searchInConversation(conversationId, term).map { it.id }
        search.value = SearchState(term = term, results = hits, index = 0)
        hits.firstOrNull()?.let {
            highlighted.value = it
            _events.emit(ConversationEvent.ScrollTo(it))
        }
    }

    fun nextSearchResult() = stepSearch(1)

    fun previousSearchResult() = stepSearch(-1)

    private fun stepSearch(delta: Int) = viewModelScope.launch {
        val current = search.value
        if (current.results.isEmpty()) return@launch
        val next = (current.index + delta).mod(current.results.size)
        search.value = current.copy(index = next)
        val id = current.results[next]
        highlighted.value = id
        _events.emit(ConversationEvent.ScrollTo(id))
    }

    fun closeSearch() {
        search.value = SearchState()
        highlighted.value = null
    }

    // ---- Conversation settings -------------------------------------------

    fun setDisappearing(duration: Duration?) = viewModelScope.launch {
        conversations.setDisappearing(conversationId, duration).onFailureEmit()
    }

    fun setWallpaper(id: String?) = viewModelScope.launch {
        conversations.setWallpaper(conversationId, id)
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch {
        conversations.setMuted(conversationId, muted)
    }

    fun jumpTo(messageId: String) = viewModelScope.launch {
        highlighted.value = messageId
        _events.emit(ConversationEvent.ScrollTo(messageId))
    }

    fun clearHighlight() { highlighted.value = null }

    fun dismissError() { error.value = null }

    override fun onCleared() {
        super.onCleared()
        // Leaving the screen must not leave a stale "typing…" showing for the other party.
        typingJob?.cancel()
        recordingJob?.cancel()
        audioRecorder.stop(discard = true)
    }

    private suspend fun <T> Outcome<T>.onFailureEmit() {
        if (this is Outcome.Failure) {
            this@ConversationViewModel.error.value = this.error
            _events.emit(ConversationEvent.ShowError(this.error))
        }
    }
}

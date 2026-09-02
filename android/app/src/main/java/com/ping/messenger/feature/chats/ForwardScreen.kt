package com.ping.messenger.feature.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.SearchField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ForwardEvent {
    data class Sent(val chats: Int) : ForwardEvent
    data class Failed(val error: AppError) : ForwardEvent
}

@HiltViewModel
class ForwardViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val _events = MutableSharedFlow<ForwardEvent>(extraBufferCapacity = 2)
    val events = _events.asSharedFlow()

    val chats: StateFlow<List<Conversation>> = combine(
        conversations.observeChats(archived = false),
        conversations.observeChats(archived = true),
        query,
    ) { active, archived, q ->
        // Archived chats are included: forwarding to one is a perfectly ordinary thing to want,
        // and leaving them out would look like the chat had disappeared.
        (active + archived)
            .filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
            .sortedByDescending { it.lastMessage?.timestamp ?: it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchText: StateFlow<String> = query

    fun onQueryChange(value: String) { query.value = value }

    fun forward(messageIds: List<String>, targets: Set<String>) = viewModelScope.launch {
        when (val result = messages.forward(messageIds, targets.toList())) {
            is Outcome.Success -> _events.emit(ForwardEvent.Sent(targets.size))
            is Outcome.Failure -> _events.emit(ForwardEvent.Failed(result.error))
        }
    }
}

/**
 * Pick the chats to forward the selected messages to.
 *
 * Multi-select with an explicit send, rather than forwarding on tap: forwarding to the wrong
 * conversation is not undoable, so it takes two deliberate actions.
 */
@Composable
fun ForwardScreen(
    messageIds: List<String>,
    onBack: () -> Unit,
    onForwarded: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForwardViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val query by viewModel.searchText.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val doneTemplate = stringResource(R.string.forward_done)
    val genericError = stringResource(R.string.error_generic)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ForwardEvent.Sent -> onForwarded(String.format(doneTemplate, event.chats))
                is ForwardEvent.Failed -> onForwarded(genericError)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.forward_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.forward(messageIds, selected) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    text = { Text(stringResource(R.string.forward_count, selected.size)) },
                )
            }
        },
    ) { padding ->
        // The search field stays outside the list so the empty state can take the remaining
        // height instead of collapsing inside a lazy item.
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                placeholder = stringResource(R.string.chats_search_hint),
            )
            if (chats.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(R.string.search_no_results),
                    body = stringResource(R.string.search_empty_body),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(chats, key = { it.id }) { chat ->
                        PersonRow(
                            name = chat.title,
                            avatarUrl = chat.avatarUrl,
                            seed = chat.id,
                            selected = chat.id in selected,
                            onClick = {
                                selected = if (chat.id in selected) {
                                    selected - chat.id
                                } else {
                                    selected + chat.id
                                }
                            },
                            trailing = {
                                Checkbox(checked = chat.id in selected, onCheckedChange = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

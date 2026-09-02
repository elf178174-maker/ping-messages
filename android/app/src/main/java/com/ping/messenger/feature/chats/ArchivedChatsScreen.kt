package com.ping.messenger.feature.chats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.feature.settings.rememberTimeFormatter
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ArchivedChatsViewModel @Inject constructor(
    private val conversations: ConversationRepository,
) : ViewModel() {

    val chats: StateFlow<List<Conversation>> = conversations
        .observeChats(archived = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unarchive(id: String) = viewModelScope.launch {
        conversations.setArchived(id, archived = false)
    }
}

/**
 * The archive.
 *
 * Archived chats keep receiving messages; they are simply out of the way. So this is the chat
 * list with one difference - the only action offered on a row is putting it back - rather than
 * a second, subtly different list implementation.
 */
@Composable
fun ArchivedChatsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchivedChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val formatter = rememberTimeFormatter()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.archived_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (chats.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inbox,
                title = stringResource(R.string.archived_empty_title),
                body = stringResource(R.string.archived_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(chats, key = { it.id }) { conversation ->
                    ChatListItem(
                        conversation = conversation,
                        timeFormatter = formatter,
                        onClick = { onOpenConversation(conversation.id) },
                        // Long-press selection belongs to the main list; here the one useful
                        // action gets a visible button instead of a hidden gesture.
                        onLongClick = { viewModel.unarchive(conversation.id) },
                        trailing = {
                            IconButton(onClick = { viewModel.unarchive(conversation.id) }) {
                                Icon(
                                    Icons.Default.Unarchive,
                                    contentDescription = stringResource(R.string.chats_unarchive),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

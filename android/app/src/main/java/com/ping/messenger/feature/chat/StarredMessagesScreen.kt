package com.ping.messenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.repository.MessageRepository
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
class StarredMessagesViewModel @Inject constructor(
    private val messages: MessageRepository,
) : ViewModel() {

    val starred: StateFlow<List<Message>> = messages
        .observeStarred()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unstar(messageId: String) = viewModelScope.launch {
        messages.setStarred(messageId, starred = false)
    }
}

/**
 * Every starred message, newest first.
 *
 * Tapping a row opens the conversation at that message rather than showing the message in
 * isolation - a saved message is almost always wanted in context.
 */
@Composable
fun StarredMessagesScreen(
    onBack: () -> Unit,
    onOpenMessage: (conversationId: String, messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StarredMessagesViewModel = hiltViewModel(),
) {
    val starred by viewModel.starred.collectAsStateWithLifecycle()
    val formatter = rememberTimeFormatter()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.profile_starred),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (starred.isEmpty()) {
            EmptyState(
                icon = Icons.Default.StarBorder,
                title = stringResource(R.string.starred_empty_title),
                body = stringResource(R.string.starred_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(starred, key = { it.id }) { message ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMessage(message.conversationId, message.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = message.senderName.ifBlank {
                                        stringResource(R.string.chat_you)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = formatter.listTimestamp(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = message.previewText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.unstar(message.id) }) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = stringResource(R.string.chat_unstar),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

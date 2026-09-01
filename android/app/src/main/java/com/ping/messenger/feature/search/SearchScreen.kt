package com.ping.messenger.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.PingChip
import com.ping.messenger.ui.components.SearchField
import com.ping.messenger.ui.components.SectionHeader

/**
 * Global search across chats, contacts, groups, messages and files.
 *
 * Message hits come from the SQLite FTS index rather than a LIKE scan, which is what keeps
 * search responsive once a user has tens of thousands of messages. The matched term is
 * highlighted inline so a hit is scannable without opening it.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenConversation: (String, String?) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    SearchField(
                        query = state.query,
                        onQueryChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.chats_search_hint),
                        autoFocus = true,
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchFilter.entries) { filter ->
                    PingChip(
                        label = stringResource(filter.labelRes),
                        selected = state.filter == filter,
                        onClick = { viewModel.onFilterChange(filter) },
                    )
                }
            }

            when {
                state.query.isBlank() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.search_empty_title),
                    body = stringResource(R.string.search_empty_body),
                )

                state.isEmpty -> EmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.search_no_results, state.query),
                    body = stringResource(R.string.search_empty_body),
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.contacts.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_section_contacts)) }
                        items(state.contacts, key = { "u-${it.id}" }) { user ->
                            PersonRow(
                                name = user.displayName,
                                subtitle = user.handle,
                                avatarUrl = user.avatarUrl,
                                seed = user.id,
                                onClick = { onOpenProfile(user.id) },
                            )
                        }
                    }

                    if (state.conversations.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_section_chats)) }
                        items(state.conversations, key = { "c-${it.id}" }) { conversation ->
                            PersonRow(
                                name = conversation.title,
                                subtitle = conversation.lastMessage?.text.orEmpty(),
                                avatarUrl = conversation.avatarUrl,
                                seed = conversation.id,
                                onClick = { onOpenConversation(conversation.id, null) },
                            )
                        }
                    }

                    if (state.messages.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.search_section_messages)) }
                        items(state.messages, key = { "m-${it.messageId}" }) { hit ->
                            PersonRow(
                                name = hit.conversationTitle,
                                subtitle = null,
                                avatarUrl = hit.avatarUrl,
                                seed = hit.conversationId,
                                onClick = { onOpenConversation(hit.conversationId, hit.messageId) },
                                trailing = {
                                    Text(
                                        text = timeFormatter.listTimestamp(hit.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                            Text(
                                text = highlight(hit.body, state.query),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                modifier = Modifier.padding(start = 78.dp, end = 16.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun highlight(body: String, term: String) = buildAnnotatedString {
    append(body)
    if (term.isBlank()) return@buildAnnotatedString
    val colour = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    var index = body.indexOf(term, ignoreCase = true)
    while (index >= 0) {
        addStyle(SpanStyle(background = colour), index, index + term.length)
        index = body.indexOf(term, index + term.length, ignoreCase = true)
    }
}

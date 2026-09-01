package com.ping.messenger.feature.chats

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.ChatFolder
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.ErrorState
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.OfflineBanner
import com.ping.messenger.ui.components.PingChip
import com.ping.messenger.ui.components.SearchField
import kotlinx.coroutines.launch

/**
 * The chat list — the screen the app opens on and the one people spend the most time scanning.
 *
 * Three interaction modes share it: browsing, searching, and multi-select. Rather than pushing
 * new screens, each swaps the top bar in place, which keeps the list scroll position and makes
 * the back button mean the obvious thing at every step.
 */
@Composable
fun ChatsScreen(
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var searchActive by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // Back closes selection first, then search, before leaving the screen. Getting this order
    // wrong is the classic way an Android app feels wrong under the system back gesture.
    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = searchActive && !state.isSelectionMode) {
        searchActive = false
        viewModel.clearQuery()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatsEvent.ShowUndo -> {
                    val result = snackbarHost.showSnackbar(
                        message = event.message,
                        actionLabel = context.getString(R.string.action_retry),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo()
                }
                is ChatsEvent.ShowMessage -> snackbarHost.showSnackbar(event.message)
                is ChatsEvent.OpenConversation -> onOpenConversation(event.conversationId)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            when {
                state.isSelectionMode -> SelectionTopBar(
                    count = state.selectedIds.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onArchive = {
                        viewModel.archiveSelected(context.getString(R.string.chats_archived))
                    },
                    onDelete = viewModel::deleteSelected,
                )

                searchActive -> SearchTopBar(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onClose = {
                        searchActive = false
                        viewModel.clearQuery()
                    },
                )

                else -> CenterAlignedTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.chats_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, stringResource(R.string.action_search))
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.action_more_options))
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chats_new_group)) },
                                    onClick = { overflowOpen = false; onNewGroup() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chats_manage_folders)) },
                                    onClick = { overflowOpen = false; onOpenFolders() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chats_archived)) },
                                    onClick = { overflowOpen = false; onOpenArchive() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.search_title)) },
                                    onClick = { overflowOpen = false; onOpenSearch() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !state.isSelectionMode && !searchActive) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.chats_new_chat)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineBanner(visible = state.isOffline, connecting = state.isConnecting)

            if (state.folders.isNotEmpty() || state.conversations.any { it.hasUnread }) {
                FolderRow(
                    folders = state.folders,
                    selectedId = state.selectedFolderId,
                    unreadCount = state.conversations.count { it.hasUnread },
                    onSelect = viewModel::onFolderSelected,
                )
            }

            when {
                state.isLoading -> LoadingState()

                state.error != null && state.conversations.isEmpty() ->
                    ErrorState(error = state.error!!, onRetry = viewModel::refresh)

                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = stringResource(R.string.chats_empty_title),
                    body = stringResource(R.string.chats_empty_body),
                    actionLabel = stringResource(R.string.chats_empty_action),
                    onAction = onNewChat,
                )

                state.hasNoResults -> EmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.search_no_results, state.query),
                    body = stringResource(R.string.search_empty_body),
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    if (state.archivedCount > 0 && !state.isSearching) {
                        item(key = "archived") {
                            ArchivedRow(count = state.archivedCount, onClick = onOpenArchive)
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }

                    items(
                        items = state.conversations,
                        // A stable key is what lets Compose keep each row's state (and its
                        // animation) as the list reorders on every incoming message.
                        key = { it.id },
                    ) { conversation ->
                        ChatListItem(
                            conversation = conversation,
                            timeFormatter = timeFormatter,
                            selected = conversation.id in state.selectedIds,
                            onClick = {
                                if (state.isSelectionMode) {
                                    viewModel.toggleSelection(conversation.id)
                                } else {
                                    onOpenConversation(conversation.id)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(conversation.id) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        com.ping.messenger.ui.components.ConfirmDialog(
            title = stringResource(R.string.chats_delete_chat),
            body = stringResource(R.string.chats_delete_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun FolderRow(
    folders: List<ChatFolder>,
    selectedId: String,
    unreadCount: Int,
    onSelect: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PingChip(
                label = stringResource(R.string.chats_folder_all),
                selected = selectedId == ChatFolder.ALL_ID,
                onClick = { onSelect(ChatFolder.ALL_ID) },
            )
        }
        if (unreadCount > 0) {
            item {
                PingChip(
                    label = stringResource(R.string.chats_folder_unread),
                    selected = selectedId == ChatFolder.UNREAD_ID,
                    count = unreadCount,
                    onClick = { onSelect(ChatFolder.UNREAD_ID) },
                )
            }
        }
        item {
            PingChip(
                label = stringResource(R.string.chats_folder_groups),
                selected = selectedId == ChatFolder.GROUPS_ID,
                onClick = { onSelect(ChatFolder.GROUPS_ID) },
            )
        }
        items(folders, key = { it.id }) { folder ->
            PingChip(
                label = folder.emoji?.let { "$it ${folder.name}" } ?: folder.name,
                selected = selectedId == folder.id,
                onClick = { onSelect(folder.id) },
            )
        }
    }
}

@Composable
private fun ArchivedRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Archive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = stringResource(R.string.chats_archived),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.chat_selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close))
            }
        },
        actions = {
            IconButton(onClick = onArchive) {
                Icon(Icons.Default.Archive, stringResource(R.string.chats_archive))
            }
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.MarkChatRead, stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.chats_search_hint),
                autoFocus = true,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

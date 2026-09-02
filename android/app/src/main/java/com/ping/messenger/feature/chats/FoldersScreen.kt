package com.ping.messenger.feature.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ping.messenger.R
import com.ping.messenger.domain.model.ChatFolder
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.repository.ConversationRepository
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.PersonRow
import com.ping.messenger.ui.components.PingTextField
import com.ping.messenger.ui.components.SettingsRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersUiState(
    val folders: List<ChatFolder> = emptyList(),
    val chats: List<Conversation> = emptyList(),
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val conversations: ConversationRepository,
) : ViewModel() {

    val uiState: StateFlow<FoldersUiState> = combine(
        conversations.observeFolders(),
        conversations.observeChats(archived = false),
    ) { folders, chats ->
        // The built-in filters (All, Unread, Groups) are computed, not stored, so they are not
        // editable here - showing them with a delete button would be a lie.
        FoldersUiState(folders.filterNot { it.id in BuiltIn }, chats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())

    fun create(name: String, emoji: String?, conversationIds: Set<String>) = viewModelScope.launch {
        conversations.createFolder(name, emoji, conversationIds)
    }

    fun update(folder: ChatFolder) = viewModelScope.launch { conversations.updateFolder(folder) }

    fun delete(id: String) = viewModelScope.launch { conversations.deleteFolder(id) }

    private companion object {
        val BuiltIn = setOf(ChatFolder.ALL_ID, ChatFolder.UNREAD_ID, ChatFolder.GROUPS_ID)
    }
}

/**
 * Chat folders.
 *
 * A folder is a saved set of conversations, not a move: a chat in a folder is still in the main
 * list. That is why deleting a folder says the chats are unaffected, and why the editor is a
 * checklist of existing chats rather than a drag-and-drop.
 */
@Composable
fun FoldersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatFolder?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.folders_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.folders_new)) },
            )
        },
    ) { padding ->
        if (state.folders.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.folders_empty_title),
                body = stringResource(R.string.folders_empty_body),
                actionLabel = stringResource(R.string.folders_new),
                onAction = { creating = true },
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.folders, key = { it.id }) { folder ->
                    SettingsRow(
                        title = listOfNotNull(folder.emoji, folder.name).joinToString(" "),
                        summary = stringResource(
                            R.string.folders_chat_count,
                            folder.conversationIds.size,
                        ),
                        onClick = { editing = folder },
                        trailing = {
                            IconButton(onClick = { deleting = folder }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        FolderEditor(
            folder = null,
            chats = state.chats,
            onSave = { name, emoji, ids ->
                viewModel.create(name, emoji, ids)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { folder ->
        FolderEditor(
            folder = folder,
            chats = state.chats,
            onSave = { name, emoji, ids ->
                viewModel.update(folder.copy(name = name, emoji = emoji, conversationIds = ids))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { folder ->
        ConfirmDialog(
            title = folder.name,
            body = stringResource(R.string.folders_delete_confirm),
            destructive = true,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(folder.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun FolderEditor(
    folder: ChatFolder?,
    chats: List<Conversation>,
    onSave: (String, String?, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(folder?.name.orEmpty()) }
    var emoji by remember { mutableStateOf(folder?.emoji.orEmpty()) }
    var selected by remember { mutableStateOf(folder?.conversationIds ?: emptySet()) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (folder == null) R.string.folders_new else R.string.action_edit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PingTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.folders_name),
                )
                PingTextField(
                    value = emoji,
                    // One grapheme is all that fits in the folder chip, so extra characters are
                    // dropped here rather than silently truncated at render time.
                    onValueChange = { emoji = it.take(2) },
                    label = stringResource(R.string.folders_emoji),
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.folders_pick_chats),
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
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
                                Checkbox(
                                    checked = chat.id in selected,
                                    // The whole row is the target; the box only reflects state,
                                    // so it must not also be independently clickable.
                                    onCheckedChange = null,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), emoji.trim().ifBlank { null }, selected) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

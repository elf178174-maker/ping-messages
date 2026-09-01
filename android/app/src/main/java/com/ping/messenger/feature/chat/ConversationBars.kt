package com.ping.messenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.Presence
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.PingBottomSheet
import com.ping.messenger.ui.components.SearchField
import com.ping.messenger.ui.components.SheetAction
import com.ping.messenger.ui.theme.PingTheme

/**
 * The conversation header.
 *
 * The subtitle carries the single most useful piece of live context, in priority order:
 * someone typing, then online, then last seen, then the member count for a group. Showing all
 * of them at once would make the one that matters harder to find.
 */
@Composable
fun ConversationTopBar(
    state: ConversationUiState,
    timeFormatter: TimeFormatter,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    overflowOpen: Boolean,
    onOverflowChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onMute: () -> Unit,
) {
    val conversation = state.conversation

    TopAppBar(
        navigationIcon = { BackButton(onBack) },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpenInfo),
            ) {
                Avatar(
                    name = state.title,
                    photoUrl = conversation?.avatarUrl,
                    seed = conversation?.id ?: state.title,
                    size = 38.dp,
                    isGroup = state.isGroup,
                    isOnline = conversation?.presence is Presence.Online,
                    showPresence = !state.isGroup,
                )
                Spacer(Modifier.width(11.dp))
                Column {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitleFor(state, timeFormatter)?.let { (text, highlight) ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (highlight) {
                                PingTheme.colors.online
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onVideoCall) {
                Icon(Icons.Default.Videocam, stringResource(R.string.calls_video))
            }
            IconButton(onClick = onVoiceCall) {
                Icon(Icons.Default.Call, stringResource(R.string.calls_voice))
            }
            Box {
                IconButton(onClick = { onOverflowChange(true) }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { onOverflowChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_search_in_chat)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = onSearch,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (conversation?.isMuted == true) {
                                        R.string.chats_unmute
                                    } else {
                                        R.string.chats_mute
                                    },
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.NotificationsOff, null) },
                        onClick = onMute,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_media_gallery)) },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = { onOverflowChange(false); onOpenInfo() },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** Returns the subtitle plus whether it should be tinted as a live signal. */
@Composable
private fun subtitleFor(
    state: ConversationUiState,
    timeFormatter: TimeFormatter,
): Pair<String, Boolean>? {
    val conversation = state.conversation ?: return null
    val typing = conversation.typingUserNames

    return when {
        typing.isNotEmpty() && state.isGroup && typing.size == 1 ->
            stringResource(R.string.chats_typing_named, typing.first()) to true
        typing.isNotEmpty() ->
            stringResource(R.string.chats_typing) to true
        state.isGroup ->
            state.group?.let { stringResource(R.string.group_member_count, it.memberCount) }
                ?.let { it to false }
        conversation.presence is Presence.Online ->
            stringResource(R.string.chats_online) to true
        conversation.presence is Presence.LastSeen ->
            stringResource(
                R.string.chats_last_seen,
                timeFormatter.relative((conversation.presence as Presence.LastSeen).at),
            ) to false
        else -> null
    }
}

/** The contextual bar shown when messages are selected. */
@Composable
fun MessageSelectionBar(
    state: ConversationUiState,
    pagedMessages: List<Message?>,
    onClose: () -> Unit,
    onReply: (Message) -> Unit,
    onCopy: (String) -> Unit,
    onForward: () -> Unit,
    onStar: (Message) -> Unit,
    onDelete: () -> Unit,
    onInfo: (String) -> Unit,
) {
    val selected = pagedMessages.filterNotNull().filter { it.id in state.selectedMessageIds }
    val single = selected.singleOrNull()

    TopAppBar(
        title = { Text(stringResource(R.string.chat_selected_count, state.selectedMessageIds.size)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close))
            }
        },
        actions = {
            if (single != null) {
                IconButton(onClick = { onReply(single) }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.chat_reply))
                }
                IconButton(onClick = { onStar(single) }) {
                    Icon(Icons.Default.Star, stringResource(R.string.chat_star))
                }
            }
            if (selected.any { it.text.isNotBlank() }) {
                IconButton(onClick = { onCopy(selected.joinToString("\n") { it.text }) }) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                }
            }
            IconButton(onClick = onForward) {
                Icon(Icons.Default.Forward, stringResource(R.string.chat_forward))
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

/** The in-chat find bar, with previous/next stepping through hits. */
@Composable
fun InChatSearchBar(
    resultCount: Int,
    index: Int,
    onSearch: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close))
            }
        },
        title = {
            SearchField(
                query = text,
                onQueryChange = {
                    text = it
                    onSearch(it)
                },
                placeholder = stringResource(R.string.chat_search_in_chat),
                autoFocus = true,
            )
        },
        actions = {
            if (resultCount > 0) {
                Text(
                    text = stringResource(R.string.search_result_count, index + 1, resultCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.KeyboardArrowUp, "Previous result")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.KeyboardArrowDown, "Next result")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** Quick reactions offered above the long-press menu. */
private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@Composable
fun MessageActionSheet(
    message: Message,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val actions = buildList {
        add(SheetAction(stringResource(R.string.chat_reply), Icons.AutoMirrored.Filled.Reply, onClick = onReply))
        if (message.text.isNotBlank()) {
            add(SheetAction(stringResource(R.string.chat_copy_text), Icons.Default.ContentCopy, onClick = onCopy))
        }
        add(SheetAction(stringResource(R.string.chat_forward), Icons.Default.Forward, onClick = onForward))
        add(
            SheetAction(
                label = stringResource(if (message.isStarred) R.string.chat_unstar else R.string.chat_star),
                icon = Icons.Default.Star,
                onClick = onStar,
            ),
        )
        if (canEdit) {
            add(SheetAction(stringResource(R.string.chat_edit_message), Icons.Default.Edit, onClick = onEdit))
        }
        add(SheetAction(stringResource(R.string.chat_pin_message), Icons.Default.PushPin, onClick = onPin))
        add(SheetAction(stringResource(R.string.chat_select_messages), Icons.Default.SelectAll, onClick = onSelect))
        if (message.isOutgoing) {
            add(SheetAction(stringResource(R.string.chat_message_info), Icons.Default.Info, onClick = onInfo))
        }
        add(
            SheetAction(
                label = stringResource(R.string.action_delete),
                icon = Icons.Default.Delete,
                destructive = true,
                onClick = onDelete,
            ),
        )
    }

    PingBottomSheet(
        title = null,
        actions = actions,
        onDismiss = onDismiss,
    )
}

/**
 * Deleting messages.
 *
 * "Delete for everyone" is only offered for the user's own messages, because that is the only
 * case where it can actually work — and offering a button that silently does nothing is worse
 * than not offering it.
 */
@Composable
fun DeleteMessagesDialog(
    count: Int,
    allowDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onDelete: (forEveryone: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.chat_selected_count, count),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                TextButton(onClick = { onDelete(false) }) {
                    Text(stringResource(R.string.chat_delete_for_me))
                }
                if (allowDeleteForEveryone) {
                    TextButton(onClick = { onDelete(true) }) {
                        Text(
                            text = stringResource(R.string.chat_delete_for_everyone),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

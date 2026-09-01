package com.ping.messenger.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.Presence
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.ConfirmDialog
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.OfflineBanner
import com.ping.messenger.ui.components.PingBottomSheet
import com.ping.messenger.ui.components.SearchField
import com.ping.messenger.ui.components.SheetAction
import com.ping.messenger.ui.theme.PingTheme
import com.ping.messenger.ui.theme.wallpaperModifier
import kotlinx.coroutines.launch

/**
 * The conversation transcript.
 *
 * The list is `reverseLayout = true`, which is the detail that makes a chat feel right: index 0
 * is the newest message and sits at the bottom, new messages push upward without a scroll
 * animation, and loading older history prepends without moving what the user is reading.
 */
@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onStartCall: (String, Boolean) -> Unit,
    onForward: (List<String>) -> Unit,
    onOpenMedia: (String) -> Unit,
    onMessageInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagedMessages = viewModel.messagePages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val timeFormatter = remember(context) { TimeFormatter(context) }
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScopeCompat()

    var composerValue by remember { mutableStateOf(TextFieldValue()) }
    var sheetTarget by remember { mutableStateOf<Message?>(null) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Set<String>?>(null) }

    // "Jump to latest" only appears once the user has actually scrolled away, so it does not
    // sit over the newest message during normal reading.
    val showJumpToBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 4 }
    }

    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = searchActive && !state.isSelectionMode) {
        searchActive = false
        viewModel.closeSearch()
    }
    BackHandler(enabled = state.composerMode !is ComposerMode.Idle && !searchActive && !state.isSelectionMode) {
        viewModel.cancelComposerMode()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationEvent.ScrollToBottom -> listState.animateScrollToItem(0)
                is ConversationEvent.ScrollTo -> {
                    val index = pagedMessages.itemSnapshotList.items
                        .indexOfFirst { it.id == event.messageId }
                    if (index >= 0) listState.animateScrollToItem(index)
                }
                is ConversationEvent.ShowMessage -> snackbarHost.showSnackbar(event.text)
                is ConversationEvent.ShowError ->
                    snackbarHost.showSnackbar(context.errorText(event.error))
                is ConversationEvent.CopyToClipboard -> {
                    clipboard.setText(AnnotatedString(event.text))
                    snackbarHost.showSnackbar(context.getString(R.string.action_copy))
                }
            }
        }
    }

    // Restore the draft the user left behind, with the cursor at the end.
    LaunchedEffect(state.conversation?.draft) {
        val draft = state.conversation?.draft
        if (!draft.isNullOrBlank() && composerValue.text.isBlank()) {
            composerValue = TextFieldValue(draft, androidx.compose.ui.text.TextRange(draft.length))
        }
    }

    LaunchedEffect(state.composerMode) {
        val mode = state.composerMode
        if (mode is ComposerMode.Editing) {
            composerValue = TextFieldValue(
                mode.message.text,
                androidx.compose.ui.text.TextRange(mode.message.text.length),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            when {
                state.isSelectionMode -> MessageSelectionBar(
                    state = state,
                    pagedMessages = pagedMessages.itemSnapshotList.items,
                    onClose = viewModel::clearSelection,
                    onReply = { message -> viewModel.reply(message); viewModel.clearSelection() },
                    onCopy = viewModel::copyText,
                    onForward = { onForward(state.selectedMessageIds.toList()) },
                    onStar = viewModel::toggleStar,
                    onDelete = { deleteTarget = state.selectedMessageIds },
                    onInfo = onMessageInfo,
                )

                searchActive -> InChatSearchBar(
                    resultCount = state.searchResults.size,
                    index = state.searchIndex,
                    onSearch = viewModel::searchInChat,
                    onNext = viewModel::nextSearchResult,
                    onPrevious = viewModel::previousSearchResult,
                    onClose = {
                        searchActive = false
                        viewModel.closeSearch()
                    },
                )

                else -> ConversationTopBar(
                    state = state,
                    timeFormatter = timeFormatter,
                    onBack = onBack,
                    onOpenInfo = { onOpenInfo(viewModel.conversationId) },
                    onVoiceCall = { onStartCall(viewModel.conversationId, false) },
                    onVideoCall = { onStartCall(viewModel.conversationId, true) },
                    overflowOpen = overflowOpen,
                    onOverflowChange = { overflowOpen = it },
                    onSearch = { searchActive = true; overflowOpen = false },
                    onMute = { viewModel.setMuted(state.conversation?.isMuted != true); overflowOpen = false },
                )
            }
        },
        bottomBar = {
            MessageComposer(
                value = composerValue,
                onValueChange = {
                    composerValue = it
                    viewModel.onTextChanged(it.text)
                },
                mode = state.composerMode,
                enterToSend = state.enterToSend,
                canSend = composerValue.text.isNotBlank(),
                timeFormatter = timeFormatter,
                onSend = {
                    viewModel.send(composerValue.text)
                    composerValue = TextFieldValue()
                },
                onAttach = { attachmentSheetOpen = true },
                onCamera = { attachmentSheetOpen = true },
                onEmoji = { attachmentSheetOpen = true },
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onCancelMode = {
                    viewModel.cancelComposerMode()
                    composerValue = TextFieldValue()
                },
                readOnlyReason = if (!state.canSend) {
                    stringResource(R.string.group_perm_admins)
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .wallpaperModifier(state.wallpaperId),
        ) {
            Column(Modifier.fillMaxSize()) {
                OfflineBanner(visible = state.isOffline)

                state.pinnedMessage?.let { pinned ->
                    PinnedMessageBar(
                        message = pinned,
                        onClick = { viewModel.jumpTo(pinned.id) },
                        onUnpin = { viewModel.pinMessage(null) },
                    )
                }

                when {
                    state.isLoading && pagedMessages.itemCount == 0 -> LoadingState()

                    pagedMessages.itemCount == 0 -> EmptyState(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.chat_empty_title),
                        body = stringResource(R.string.chat_empty_body),
                    )

                    else -> LazyColumn(
                        state = listState,
                        // The transcript grows upward: newest at index 0, pinned to the bottom.
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        items(
                            count = pagedMessages.itemCount,
                            key = pagedMessages.itemKey { it.id },
                        ) { index ->
                            val message = pagedMessages[index] ?: return@items
                            // In reverse layout, "previous in time" is the next index.
                            val older = pagedMessages.peekOrNull(index + 1)
                            val newer = pagedMessages.peekOrNull(index - 1)

                            val isFirstInGroup = older == null ||
                                older.senderId != message.senderId ||
                                message.createdAt - older.createdAt > GROUPING_WINDOW_MS
                            val isLastInGroup = newer == null ||
                                newer.senderId != message.senderId ||
                                newer.createdAt - message.createdAt > GROUPING_WINDOW_MS

                            MessageBubble(
                                message = message,
                                timeFormatter = timeFormatter,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                showSenderName = state.isGroup && !message.isOutgoing,
                                isSelected = message.id in state.selectedMessageIds,
                                isHighlighted = message.id == state.highlightedMessageId,
                                searchTerm = state.searchTerm,
                                onClick = {
                                    if (state.isSelectionMode) viewModel.toggleSelection(message.id)
                                },
                                onLongClick = { sheetTarget = message },
                                onQuoteClick = viewModel::jumpTo,
                                onAttachmentClick = { attachment ->
                                    if (attachment.localPath != null) {
                                        onOpenMedia(attachment.id)
                                    } else {
                                        viewModel.downloadAttachment(attachment.id)
                                    }
                                },
                                onRetry = { viewModel.retry(message.id) },
                                onReactionClick = { viewModel.react(message.id, it) },
                                onPollVote = { viewModel.votePoll(message.id, it) },
                            )

                            if (timeFormatter.needsDaySeparator(older?.createdAt, message.createdAt)) {
                                DaySeparator(timeFormatter.dayLabel(message.createdAt))
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showJumpToBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                    )
                }
            }
        }
    }

    sheetTarget?.let { message ->
        MessageActionSheet(
            message = message,
            canEdit = message.isOutgoing && !message.isDeleted,
            onDismiss = { sheetTarget = null },
            onReply = { viewModel.reply(message); sheetTarget = null },
            onReact = { emoji -> viewModel.react(message.id, emoji); sheetTarget = null },
            onCopy = { viewModel.copyText(message.text); sheetTarget = null },
            onForward = { onForward(listOf(message.id)); sheetTarget = null },
            onStar = { viewModel.toggleStar(message); sheetTarget = null },
            onEdit = { viewModel.edit(message); sheetTarget = null },
            onPin = { viewModel.pinMessage(message.id); sheetTarget = null },
            onInfo = { onMessageInfo(message.id); sheetTarget = null },
            onDelete = { deleteTarget = setOf(message.id); sheetTarget = null },
            onSelect = { viewModel.toggleSelection(message.id); sheetTarget = null },
        )
    }

    if (attachmentSheetOpen) {
        AttachmentSheet(
            onDismiss = { attachmentSheetOpen = false },
            onPicked = { paths, kind ->
                viewModel.sendAttachments(paths, kind)
                attachmentSheetOpen = false
            },
            onPollRequested = { attachmentSheetOpen = false },
            onLocationPicked = { point ->
                viewModel.sendLocation(point)
                attachmentSheetOpen = false
            },
        )
    }

    deleteTarget?.let { ids ->
        val anyOutgoing = pagedMessages.itemSnapshotList.items
            .any { it != null && it.id in ids && it.isOutgoing }
        DeleteMessagesDialog(
            count = ids.size,
            allowDeleteForEveryone = anyOutgoing,
            onDismiss = { deleteTarget = null },
            onDelete = { forEveryone ->
                viewModel.deleteMessages(ids, forEveryone)
                deleteTarget = null
            },
        )
    }
}

/** Messages within two minutes of each other from the same sender render as one group. */
private const val GROUPING_WINDOW_MS = 2 * 60 * 1000L

@Composable
private fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = PingTheme.colors.onSystemBubble,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(PingTheme.colors.systemBubble)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PinnedMessageBar(message: Message, onClick: () -> Unit, onUnpin: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PushPin,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_pinned_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message.previewText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onUnpin, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_unpin_message),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

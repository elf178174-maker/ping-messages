package com.ping.messenger.feature.status

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.StatusKind
import com.ping.messenger.domain.model.StatusThread
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.SectionHeader
import kotlinx.coroutines.delay

/** The status tab: my update at the top, then unseen, then already-viewed. */
@Composable
fun StatusScreen(
    onOpenViewer: (String) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.status_title), fontWeight = FontWeight.SemiBold)
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Default.Add, stringResource(R.string.status_add))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> LoadingState()

                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.status_empty_title),
                    body = stringResource(R.string.status_empty_body),
                    actionLabel = stringResource(R.string.status_add),
                    onAction = onCompose,
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    item {
                        MyStatusRow(
                            thread = state.myThread,
                            timeFormatter = timeFormatter,
                            onClick = {
                                if (state.myThread != null) {
                                    onOpenViewer(state.myThread!!.authorId)
                                } else {
                                    onCompose()
                                }
                            },
                            onAdd = onCompose,
                        )
                    }

                    if (state.recent.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.status_recent)) }
                        items(state.recent, key = { it.authorId }) { thread ->
                            StatusRow(thread, timeFormatter) { onOpenViewer(thread.authorId) }
                        }
                    }

                    if (state.viewed.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.status_viewed)) }
                        items(state.viewed, key = { it.authorId }) { thread ->
                            StatusRow(thread, timeFormatter) { onOpenViewer(thread.authorId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyStatusRow(
    thread: StatusThread?,
    timeFormatter: TimeFormatter,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(
                name = thread?.authorName.orEmpty(),
                photoUrl = thread?.authorAvatarUrl,
                seed = thread?.authorId ?: "me",
                size = 54.dp,
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.status_add),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.status_my_status),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = thread?.let { timeFormatter.relative(it.latestAt) }
                    ?: stringResource(R.string.status_tap_to_add),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A story row. The ring around the avatar is the standard unseen affordance; a seen thread
 * gets a muted outline instead of losing the ring entirely, so the row still reads as a story.
 */
@Composable
private fun StatusRow(thread: StatusThread, timeFormatter: TimeFormatter, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .border(
                    width = if (thread.hasUnseen) 2.5.dp else 1.dp,
                    color = if (thread.hasUnseen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(
                name = thread.authorName,
                photoUrl = thread.authorAvatarUrl,
                seed = thread.authorId,
                size = 50.dp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = thread.authorName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (thread.hasUnseen) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeFormatter.relative(thread.latestAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thread.unseenCount > 0) {
            Text(
                text = thread.unseenCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The full-screen story viewer.
 *
 * Tap right advances, tap left goes back, and the progress bars along the top show position in
 * the thread. Auto-advance uses each post's own duration so a video is not cut off at the
 * default five seconds.
 */
@Composable
fun StatusViewerScreen(
    thread: StatusThread,
    onClose: () -> Unit,
    onSeen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var index by remember(thread.authorId) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }

    val post = thread.posts.getOrNull(index) ?: return
    LaunchedEffect(post.id) { onSeen(post.id) }

    // Auto-advance. Cancelling on pause is what makes press-and-hold work.
    LaunchedEffect(post.id, paused) {
        if (paused) return@LaunchedEffect
        delay(post.durationMs)
        if (index < thread.posts.lastIndex) index++ else onClose()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(thread.authorId, index) {
                detectTapGestures(
                    onPress = {
                        paused = true
                        tryAwaitRelease()
                        paused = false
                    },
                    onTap = { offset ->
                        if (offset.x < size.width / 3f) {
                            if (index > 0) index-- else onClose()
                        } else {
                            if (index < thread.posts.lastIndex) index++ else onClose()
                        }
                    },
                )
            },
    ) {
        when (post.kind) {
            StatusKind.TEXT -> Box(
                Modifier
                    .fillMaxSize()
                    .background(post.backgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = post.text,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }

            StatusKind.IMAGE, StatusKind.VIDEO -> AsyncImage(
                model = post.localPath ?: post.mediaUrl,
                contentDescription = post.text.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                thread.posts.forEachIndexed { i, _ ->
                    val progress by animateFloatAsState(
                        targetValue = when {
                            i < index -> 1f
                            i > index -> 0f
                            paused -> 0.5f
                            else -> 1f
                        },
                        animationSpec = if (i == index && !paused) {
                            tween(post.durationMs.toInt(), easing = LinearEasing)
                        } else {
                            tween(0)
                        },
                        label = "storyProgress",
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    name = thread.authorName,
                    photoUrl = thread.authorAvatarUrl,
                    seed = thread.authorId,
                    size = 34.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = thread.authorName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = timeFormatter.relative(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                if (post.isMine) {
                    IconButton(onClick = { onDelete(post.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            stringResource(R.string.action_delete),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = Color.White)
                }
            }
        }

        if (post.isMine) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (post.viewers.isEmpty()) {
                        stringResource(R.string.status_no_viewers)
                    } else {
                        stringResource(R.string.status_viewers, post.viewers.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

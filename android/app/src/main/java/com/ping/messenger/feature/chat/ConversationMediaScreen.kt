package com.ping.messenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ping.messenger.R
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.repository.MessageRepository
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.ping.messenger.ui.components.SettingsRow
import com.ping.messenger.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ConversationMediaUiState(
    val media: List<Attachment> = emptyList(),
    val documents: List<Attachment> = emptyList(),
)

@HiltViewModel
class ConversationMediaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    messages: MessageRepository,
) : ViewModel() {

    private val conversationId: String = savedStateHandle[Routes.ARG_CONVERSATION_ID] ?: ""

    val uiState: StateFlow<ConversationMediaUiState> = combine(
        messages.observeGalleryMedia(conversationId),
        messages.observeGalleryDocuments(conversationId),
    ) { media, documents ->
        ConversationMediaUiState(media, documents)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConversationMediaUiState(),
    )
}

/**
 * Everything shared in one conversation, as a grid of media and a list of documents.
 *
 * The thumbnail shown is whatever the device already has - a generated thumbnail, the full file,
 * or the remote URL - so opening this screen never triggers a burst of downloads on a metered
 * connection.
 */
@Composable
fun ConversationMediaScreen(
    onBack: () -> Unit,
    onOpenAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.gallery_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.gallery_tab_media)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.gallery_tab_docs)) },
                )
            }

            val items = if (tab == 0) state.media else state.documents
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.PhotoLibrary,
                    title = stringResource(R.string.gallery_empty_title),
                    body = stringResource(R.string.gallery_empty_body),
                    modifier = Modifier.weight(1f),
                )
            } else if (tab == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(items, key = { it.id }) { attachment ->
                        MediaThumbnail(
                            attachment = attachment,
                            onClick = { onOpenAttachment(attachment.id) },
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(items, key = { it.id }) { attachment ->
                        SettingsRow(
                            title = attachment.fileName.ifBlank { attachment.mimeType },
                            summary = formatBytes(attachment.sizeBytes),
                            onClick = { onOpenAttachment(attachment.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    attachment: Attachment,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = attachment.thumbnailPath ?: attachment.localPath ?: attachment.remoteUrl,
            contentDescription = attachment.fileName.ifBlank {
                stringResource(R.string.cd_image_attachment)
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (attachment.kind == MessageKind.VIDEO) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

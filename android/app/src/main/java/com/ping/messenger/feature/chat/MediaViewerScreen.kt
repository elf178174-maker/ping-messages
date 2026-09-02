package com.ping.messenger.feature.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ping.messenger.R
import com.ping.messenger.core.common.AppError
import com.ping.messenger.core.common.Outcome
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.TransferState
import com.ping.messenger.domain.repository.MediaRepository
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.LoadingState
import com.ping.messenger.ui.components.errorMessage
import com.ping.messenger.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val media: MediaRepository,
) : ViewModel() {

    private val attachmentId: String = savedStateHandle[Routes.ARG_ATTACHMENT_ID] ?: ""

    private val _failures = MutableSharedFlow<AppError>(extraBufferCapacity = 2)
    val failures = _failures.asSharedFlow()

    val attachment: StateFlow<Attachment?> = media
        .observeAttachment(attachmentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun download() = viewModelScope.launch {
        when (val result = media.ensureDownloaded(attachmentId)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> _failures.emit(result.error)
        }
    }
}

/**
 * Full-screen viewer for one attachment.
 *
 * Pinch-to-zoom is implemented directly rather than pulled in as a dependency: it is one
 * transform gesture and a graphicsLayer, and the scale is clamped so a photo cannot be zoomed
 * into nothing.
 *
 * Video and audio are handed to whatever app the user already uses for them, through a
 * FileProvider URI with a read grant. Embedding a player would mean shipping a second video
 * pipeline to duplicate what the system does well.
 */
@Composable
fun MediaViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val attachment by viewModel.attachment.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    var failure by remember { mutableStateOf<AppError?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.failures.collect { failure = it }
    }
    failure?.let { error ->
        val text = errorMessage(error)
        LaunchedEffect(error) {
            snackbars.showSnackbar(text)
            failure = null
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = attachment?.fileName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                actions = {
                    val current = attachment
                    if (current != null && !current.isDownloaded) {
                        IconButton(onClick = viewModel::download) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.media_download),
                            )
                        }
                    }
                    if (current?.isDownloaded == true) {
                        IconButton(onClick = { context.openExternally(current) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.media_open_with),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = attachment
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                current == null -> LoadingState()

                current.isDownloaded && current.kind.isImageLike() -> ZoomableImage(current)

                current.isDownloaded -> {
                    // Not an image: the system's own player or reader is the right home for it.
                    LaunchedEffect(current.id) { context.openExternally(current) }
                    Text(
                        text = stringResource(R.string.media_open_with),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                current.transferState == TransferState.RUNNING -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingState()
                    Text(
                        text = stringResource(
                            R.string.media_download_progress,
                            (current.transferProgress * 100).toInt(),
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.viewer_unavailable),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = viewModel::download) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.media_download),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(attachment: Attachment, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    AsyncImage(
        model = attachment.localPath,
        contentDescription = attachment.fileName.ifBlank {
            stringResource(R.string.cd_image_attachment)
        },
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(attachment.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        // Panning at 1x would leave the image stranded off-centre.
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            ),
    )
}

private fun com.ping.messenger.domain.model.MessageKind.isImageLike(): Boolean =
    this == com.ping.messenger.domain.model.MessageKind.IMAGE ||
        this == com.ping.messenger.domain.model.MessageKind.GIF

/**
 * Hands the file to another app.
 *
 * A FileProvider URI plus FLAG_GRANT_READ_URI_PERMISSION, because app-private storage is not
 * readable by the receiving app and a `file://` URI has been illegal since Android 7.
 */
private fun android.content.Context.openExternally(attachment: Attachment) {
    val path = attachment.localPath ?: return
    runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, attachment.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

package com.ping.messenger.feature.status

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ping.messenger.R
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.feature.chat.copyToCache
import com.ping.messenger.feature.chat.isVideo
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.PingTextField

/**
 * The colours a text status can use.
 *
 * A short list of deliberately chosen, readable backgrounds rather than a colour wheel: every
 * one of these carries white text at an accessible contrast ratio, which a free picker cannot
 * promise.
 */
private val StatusBackgrounds = listOf(
    0xFF00695CL, // teal, matching the app's own accent
    0xFF283593L, // indigo
    0xFF4A148CL, // purple
    0xFFB71C1CL, // red
    0xFFE65100L, // orange
    0xFF1B5E20L, // green
    0xFF37474FL, // slate
)

/**
 * Post a status.
 *
 * One screen for both kinds of post: type something and it becomes a text status on a colour,
 * or pick a photo or video and the same text becomes its caption. Statuses expire after 24
 * hours and the screen says so, because "post" is otherwise indistinguishable from a message.
 */
@Composable
fun StatusComposerScreen(
    onBack: () -> Unit,
    onPosted: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val storage = remember(context) { MediaStorage(context) }

    var text by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(StatusBackgrounds.first()) }
    var pickedPath by remember { mutableStateOf<String?>(null) }
    var pickedIsVideo by remember { mutableStateOf(false) }

    // The view-model reports outcomes as text; the composer's job is done as soon as one
    // arrives, so it hands the message to the caller and lets it pop the screen.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> onPosted(message) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            pickedIsVideo = context.isVideo(uri)
            pickedPath = context.copyToCache(uri, storage)
        }
    }

    val canPost = pickedPath != null || text.isNotBlank()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.status_composer_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(
                        enabled = canPost && !state.isPosting,
                        onClick = {
                            val path = pickedPath
                            if (path != null) {
                                viewModel.postMedia(path, text.trim(), pickedIsVideo)
                            } else {
                                viewModel.postText(text.trim(), background)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.status_post))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val path = pickedPath
            if (path != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = path,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                OutlinedButton(onClick = { pickedPath = null }) {
                    Text(stringResource(R.string.action_remove))
                }
                PingTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.status_caption_hint),
                    singleLine = false,
                    maxLines = 3,
                )
            } else {
                // Live preview: the text renders on the chosen colour exactly as it will post.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text.ifBlank { stringResource(R.string.status_text_hint) },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusBackgrounds.forEach { colour ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(colour))
                                .border(
                                    width = if (colour == background) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { background = colour },
                        )
                    }
                }

                PingTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.status_text_hint),
                    singleLine = false,
                    maxLines = 4,
                )

                Button(
                    onClick = {
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.status_pick_photo))
                }
            }

            Text(
                text = stringResource(R.string.status_expires_in, "24h"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

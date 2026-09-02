package com.ping.messenger.feature.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ping.messenger.R
import com.ping.messenger.core.media.MediaStorage
import com.ping.messenger.domain.model.GeoPoint
import com.ping.messenger.domain.model.MessageKind
import java.io.File

/**
 * The attachment picker.
 *
 * Uses Android's **photo picker** (`PickVisualMedia`) rather than a storage permission plus a
 * custom gallery. The photo picker needs no runtime permission at all, is the system-consistent
 * experience, and gives the user per-item control rather than all-or-nothing access to their
 * library — which is exactly the trade a privacy-focused messenger should make.
 *
 * Everything a picker returns is a content URI, which is copied into app-private storage before
 * being attached, because the temporary read grant on that URI does not survive the upload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPicked: (paths: List<String>, kind: MessageKind) -> Unit,
    onPollRequested: () -> Unit,
    onLocationPicked: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storage = remember { MediaStorage(context) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val paths = uris.mapNotNull { context.copyToCache(it, storage) }
            val kind = if (uris.size == 1 && context.isVideo(uris.first())) {
                MessageKind.VIDEO
            } else {
                MessageKind.IMAGE
            }
            onPicked(paths, kind)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onPicked(uris.mapNotNull { context.copyToCache(it, storage) }, MessageKind.DOCUMENT)
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onPicked(uris.mapNotNull { context.copyToCache(it, storage) }, MessageKind.AUDIO)
        }
    }

    // The camera writes straight into app-private cache via FileProvider, so no media
    // permission is involved and the capture never lands in the shared gallery.
    val captureFile = remember { storage.newCaptureFile("jpg") }
    val captureUri = remember(captureFile) {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            captureFile,
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success && captureFile.length() > 0) {
            onPicked(listOf(captureFile.absolutePath), MessageKind.IMAGE)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) cameraLauncher.launch(captureUri) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                listOf(
                    AttachOption(R.string.attach_gallery, Icons.Default.Photo, Color(0xFF7B4FD1)) {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    AttachOption(R.string.attach_camera, Icons.Default.PhotoCamera, Color(0xFFD1554F)) {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    AttachOption(R.string.attach_document, Icons.Default.Description, Color(0xFF4F7BD1)) {
                        documentPicker.launch(arrayOf("*/*"))
                    },
                    AttachOption(R.string.attach_audio, Icons.Default.Headphones, Color(0xFFD18A4F)) {
                        audioPicker.launch(arrayOf("audio/*"))
                    },
                    AttachOption(R.string.attach_poll, Icons.Default.Poll, Color(0xFF4FA36B)) {
                        onPollRequested()
                    },
                    AttachOption(R.string.attach_location, Icons.Default.LocationOn, Color(0xFF3FA9A0)) {
                        onLocationPicked(GeoPoint(0.0, 0.0))
                    },
                    AttachOption(R.string.attach_contact, Icons.Default.Person, Color(0xFF8A6FD1)) {
                        onPollRequested()
                    },
                ),
            ) { option ->
                AttachButton(option)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private data class AttachOption(
    val labelRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun AttachButton(option: AttachOption) {
    Column(
        modifier = Modifier
            .clickable(onClick = option.onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(option.tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = option.tint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Copies a picked content URI into app-private cache.
 *
 * A picker's URI grant is scoped to the launching activity and can be revoked as soon as it
 * finishes, so the upload worker would find it dead. Streaming into a local file also means
 * a 500 MB video never has to fit in memory.
 */
internal fun Context.copyToCache(uri: Uri, storage: MediaStorage): String? = runCatching {
    val name = displayNameOf(uri) ?: "attachment-${System.currentTimeMillis()}"
    val target = File(storage.tempDir, "${System.nanoTime()}-$name")
    contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
    } ?: return null
    target.absolutePath
}.getOrNull()

private fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()

internal fun Context.isVideo(uri: Uri): Boolean =
    contentResolver.getType(uri)?.startsWith("video/") == true

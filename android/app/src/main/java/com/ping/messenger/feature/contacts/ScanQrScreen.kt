package com.ping.messenger.feature.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import com.ping.messenger.R
import com.ping.messenger.core.qr.QrCodes
import com.ping.messenger.ui.components.BackButton
import com.ping.messenger.ui.components.EmptyState
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Scans a Ping contact code.
 *
 * CameraX analyses frames continuously and each frame's luminance plane goes straight to ZXing,
 * so nothing is recorded, saved, or uploaded - the camera is used as a sensor and the frames
 * are discarded. The first code that parses as a Ping contact link wins, and scanning stops
 * immediately so a shaky hand cannot fire the same scan twice.
 */
@Composable
fun ScanQrScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbars = remember { SnackbarHostState() }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var handled by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ContactsEvent.OpenConversation) onOpenConversation(event.conversationId)
        }
    }

    notice?.let { text ->
        LaunchedEffect(text) {
            snackbars.showSnackbar(text)
            notice = null
            // Re-arm after a rejected code, so pointing at a valid one still works.
            handled = false
        }
    }

    val invalidMessage = stringResource(R.string.scan_qr_invalid)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.scan_qr_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (!granted) {
            EmptyState(
                icon = Icons.Default.PhotoCamera,
                title = stringResource(R.string.scan_qr_permission_title),
                body = stringResource(R.string.error_permission_rationale_camera),
                actionLabel = stringResource(R.string.action_continue),
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                CameraScanner(
                    onScanned = { payload ->
                        if (handled) return@CameraScanner
                        handled = true
                        if (QrCodes.isPingContactLink(payload)) {
                            viewModel.openScanned(payload)
                        } else {
                            notice = invalidMessage
                        }
                    },
                    lifecycleOwner = lifecycleOwner,
                    modifier = Modifier.fillMaxSize(),
                )
                // A viewfinder frame, so people know where to aim.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .border(
                            width = 3.dp,
                            color = Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(24.dp),
                        ),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.scan_qr_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}

/**
 * The camera preview plus the frame analyser.
 *
 * The analyser runs on its own single-thread executor with STRATEGY_KEEP_ONLY_LATEST: decoding
 * is slower than the frame rate, and queuing frames would make the scanner feel laggy while
 * decoding stale images.
 */
@Composable
private fun CameraScanner(
    onScanned: (String) -> Unit,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    LaunchedEffect(previewView) {
        val provider = ProcessCameraProvider.getInstance(context).awaitProvider(context)
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor) { image -> image.scanAndClose(onScanned) } }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Decodes one frame's luminance plane, then always closes the proxy.
 *
 * The Y plane's rows are padded to `rowStride`, which is usually wider than the image, so the
 * rows are repacked to exactly `width` bytes. Feeding the padded buffer to ZXing instead would
 * hand it a column of junk down the right-hand side of every frame.
 */
private fun ImageProxy.scanAndClose(onScanned: (String) -> Unit) {
    try {
        val plane = planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val stride = plane.rowStride
        val packed = ByteArray(width * height)
        val row = ByteArray(stride)
        for (y in 0 until height) {
            buffer.position(y * stride)
            val available = minOf(stride, buffer.remaining())
            buffer.get(row, 0, available)
            row.copyInto(packed, y * width, 0, width)
        }
        QrCodes.decodeLuminance(
            data = packed,
            width = width,
            height = height,
            rotationDegrees = imageInfo.rotationDegrees,
        )?.let(onScanned)
    } finally {
        // Not closing the proxy stalls the pipeline after a handful of frames.
        close()
    }
}

/**
 * Awaits the camera provider.
 *
 * CameraX only exposes a ListenableFuture, and the ktx adapter that would bridge it is a
 * separate artifact; this is the same handful of lines without the extra dependency. The
 * listener runs on the main executor, which is where binding to a lifecycle has to happen
 * anyway.
 */
private suspend fun ListenableFuture<ProcessCameraProvider>.awaitProvider(
    context: android.content.Context,
): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

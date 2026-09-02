package com.ping.messenger.feature.calls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ping.messenger.R
import com.ping.messenger.core.call.CallControls
import com.ping.messenger.core.call.CallState
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.ui.components.Avatar
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The in-call screen.
 *
 * Video renders through [SurfaceViewRenderer] inside an [AndroidView] — WebRTC has no Compose
 * renderer, and wrapping the real SurfaceView is the correct answer rather than trying to
 * shuttle frames into a Compose Canvas.
 *
 * The layout follows the convention every calling app shares, because it is what people
 * already know: remote video fills the screen, local video is a small draggable inset, and
 * the controls sit within thumb reach at the bottom.
 */
@Composable
fun CallScreen(
    state: CallState,
    controls: CallControls,
    eglContext: EglBase.Context?,
    localTrack: VideoTrack?,
    remoteTrack: VideoTrack?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeFormatter = remember(context) { TimeFormatter(context) }

    // The screen closes itself a moment after the call ends, so the user sees the outcome
    // rather than the activity vanishing mid-word.
    LaunchedEffect(state) {
        if (state is CallState.Ended || state is CallState.Failed) {
            delay(1_400)
            onClose()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (remoteTrack != null && eglContext != null) {
            VideoRenderer(
                track = remoteTrack,
                eglContext = eglContext,
                mirror = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CallBackdrop(state)
        }

        if (controls.cameraEnabled && localTrack != null && eglContext != null) {
            VideoRenderer(
                track = localTrack,
                eglContext = eglContext,
                // The local preview is mirrored so it behaves like a mirror, which is what
                // people expect when looking at themselves.
                mirror = controls.usingFrontCamera,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 108.dp, height = 168.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }

        CallHeader(
            state = state,
            timeFormatter = timeFormatter,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 32.dp),
        )

        CallControlBar(
            state = state,
            controls = controls,
            onAnswer = onAnswer,
            onDecline = onDecline,
            onHangUp = onHangUp,
            onToggleMic = onToggleMic,
            onToggleCamera = onToggleCamera,
            onSwitchCamera = onSwitchCamera,
            onToggleSpeaker = onToggleSpeaker,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp),
        )
    }
}

@Composable
private fun VideoRenderer(
    track: VideoTrack,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                track.addSink(this)
            }
        },
        update = { renderer -> renderer.setMirror(mirror) },
        // Detaching the sink and releasing the renderer is essential: without it the surface
        // leaks and the next call renders to a dead view.
        onRelease = { renderer ->
            runCatching { track.removeSink(renderer) }
            runCatching { renderer.release() }
        },
    )
}

/** What is shown when there is no remote video: the peer's avatar and the call state. */
@Composable
private fun CallBackdrop(state: CallState) {
    val (name, avatarUrl) = when (state) {
        is CallState.Incoming -> state.peerName to state.peerAvatarUrl
        else -> "" to null
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Avatar(
            name = name,
            photoUrl = avatarUrl,
            seed = name.ifBlank { "call" },
            size = 132.dp,
        )
    }
}

@Composable
private fun CallHeader(
    state: CallState,
    timeFormatter: TimeFormatter,
    modifier: Modifier = Modifier,
) {
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state) {
        if (state is CallState.Connected) {
            while (true) {
                elapsed = System.currentTimeMillis() - state.startedAt
                delay(1_000)
            }
        }
    }

    val title = when (state) {
        is CallState.Incoming -> state.peerName
        else -> ""
    }

    val subtitle = when (state) {
        is CallState.Idle -> ""
        is CallState.Incoming -> stringResource(R.string.calls_incoming)
        is CallState.Outgoing -> stringResource(R.string.calls_ringing)
        is CallState.Connecting -> stringResource(R.string.calls_connecting)
        is CallState.Connected -> timeFormatter.duration(elapsed)
        is CallState.Reconnecting -> stringResource(R.string.calls_reconnecting)
        is CallState.Ended -> stringResource(R.string.calls_ended)
        is CallState.Failed -> state.reason
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CallControlBar(
    state: CallState,
    controls: CallControls,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Secondary controls are hidden while ringing: the only two useful actions then are
        // answer and decline, and extra buttons invite a mis-tap.
        AnimatedVisibility(visible = state !is CallState.Incoming) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(bottom = 28.dp),
            ) {
                ControlButton(
                    icon = if (controls.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = stringResource(
                        if (controls.micMuted) R.string.calls_unmute_mic else R.string.calls_mute_mic,
                    ),
                    active = controls.micMuted,
                    onClick = onToggleMic,
                )
                ControlButton(
                    icon = if (controls.cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = stringResource(
                        if (controls.cameraEnabled) R.string.calls_camera_off else R.string.calls_camera_on,
                    ),
                    active = !controls.cameraEnabled,
                    onClick = onToggleCamera,
                )
                if (controls.cameraEnabled) {
                    ControlButton(
                        icon = Icons.Default.Cameraswitch,
                        label = stringResource(R.string.calls_switch_camera),
                        active = false,
                        onClick = onSwitchCamera,
                    )
                }
                ControlButton(
                    icon = if (controls.speakerOn) Icons.Default.VolumeUp else Icons.Outlined.VolumeDown,
                    label = stringResource(
                        if (controls.speakerOn) R.string.calls_earpiece else R.string.calls_speaker,
                    ),
                    active = controls.speakerOn,
                    onClick = onToggleSpeaker,
                )
            }
        }

        if (state is CallState.Incoming) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EndCallButton(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(R.string.calls_decline),
                    background = Color(0xFFD1554F),
                    onClick = onDecline,
                )
                EndCallButton(
                    icon = Icons.Default.Call,
                    label = stringResource(R.string.calls_answer),
                    background = Color(0xFF1EA97C),
                    onClick = onAnswer,
                )
            }
        } else {
            EndCallButton(
                icon = Icons.Default.CallEnd,
                label = stringResource(R.string.calls_hang_up),
                background = Color(0xFFD1554F),
                onClick = onHangUp,
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                // 58 dp: comfortably past the 48 dp minimum, because these are pressed
                // one-handed and often without looking.
                .size(58.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color.White.copy(alpha = 0.18f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Black else Color.White,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun EndCallButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

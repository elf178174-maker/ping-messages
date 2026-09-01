package com.ping.messenger.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Message
import com.ping.messenger.ui.theme.PingTheme

/** What the composer is currently doing. Only one of these is ever true. */
sealed interface ComposerMode {
    data object Idle : ComposerMode
    data class Replying(val message: Message) : ComposerMode
    data class Editing(val message: Message) : ComposerMode
    data class Recording(val elapsedMs: Long, val cancelProgress: Float) : ComposerMode
}

/**
 * The message input bar.
 *
 * Layout rules that keep it feeling right:
 *
 *  - The field grows with the text up to six lines, then scrolls. An unbounded field can push
 *    the whole transcript off screen.
 *  - The send/microphone button swaps in place rather than appearing beside the other, so the
 *    thumb target never moves.
 *  - "Enter sends" is a user preference; when it is off, Enter inserts a newline and only the
 *    button sends. Both paths are handled here rather than in the ViewModel.
 */
@Composable
fun MessageComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    mode: ComposerMode,
    enterToSend: Boolean,
    canSend: Boolean,
    timeFormatter: TimeFormatter,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onEmoji: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (cancelled: Boolean) -> Unit,
    onCancelMode: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.chat_message_hint),
    readOnlyReason: String? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // A group whose permissions forbid this user from posting gets an explanation
            // instead of an input they cannot use.
            if (readOnlyReason != null) {
                Text(
                    text = readOnlyReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
                return@Column
            }

            AnimatedVisibility(visible = mode is ComposerMode.Replying || mode is ComposerMode.Editing) {
                ComposerContextBar(mode = mode, onCancel = onCancelMode)
            }

            if (mode is ComposerMode.Recording) {
                RecordingBar(
                    elapsedMs = mode.elapsedMs,
                    cancelProgress = mode.cancelProgress,
                    timeFormatter = timeFormatter,
                    onCancel = { onStopRecording(true) },
                    onSend = { onStopRecording(false) },
                )
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onEmoji, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Outlined.EmojiEmotions,
                        contentDescription = stringResource(R.string.chat_emoji),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                // Shift+Enter always inserts a newline, even with
                                // "Enter sends" on — the convention every chat app follows.
                                val isEnter = event.key == Key.Enter && event.type == KeyEventType.KeyDown
                                if (enterToSend && isEnter && !event.isShiftPressed && canSend) {
                                    onSend()
                                    true
                                } else {
                                    false
                                }
                            },
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                        ),
                        maxLines = 6,
                    )
                }

                Spacer(Modifier.width(4.dp))

                if (value.text.isBlank() && mode !is ComposerMode.Editing) {
                    IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.chat_attach),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCamera, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = stringResource(R.string.attach_camera),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SendButton(
                    isVoice = value.text.isBlank() && mode !is ComposerMode.Editing,
                    enabled = canSend || value.text.isBlank(),
                    onSend = onSend,
                    onStartRecording = onStartRecording,
                )
            }
        }
    }
}

@Composable
private fun SendButton(
    isVoice: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStartRecording: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.9f,
        label = "sendScale",
    )
    Box(
        Modifier
            .size(46.dp)
            .scale(if (PingTheme.reduceMotion) 1f else scale)
            .clip(CircleShape)
            .background(
                if (enabled || isVoice) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            )
            .clickable(enabled = enabled || isVoice) {
                if (isVoice) onStartRecording() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isVoice) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(
                if (isVoice) R.string.chat_record_voice else R.string.chat_send,
            ),
            tint = if (enabled || isVoice) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(21.dp),
        )
    }
}

/** The strip above the input showing what is being replied to or edited. */
@Composable
private fun ComposerContextBar(mode: ComposerMode, onCancel: () -> Unit) {
    val (label, body) = when (mode) {
        is ComposerMode.Replying -> stringResource(
            R.string.chat_reply_to,
            mode.message.senderName.ifBlank { stringResource(R.string.chat_you) },
        ) to mode.message.previewText
        is ComposerMode.Editing -> stringResource(R.string.chat_edit_message) to mode.message.text
        else -> return
    }

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * The voice-recording bar.
 *
 * Recording replaces the whole composer rather than overlaying it, because a half-visible text
 * field during recording invites taps that do nothing.
 */
@Composable
private fun RecordingBar(
    elapsedMs: Long,
    cancelProgress: Float,
    timeFormatter: TimeFormatter,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.action_cancel),
                tint = PingTheme.colors.danger,
            )
        }

        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(PingTheme.colors.danger),
        )
        Spacer(Modifier.width(10.dp))

        Text(
            text = timeFormatter.duration(elapsedMs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.chat_slide_to_cancel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = (1f - cancelProgress).coerceIn(0.3f, 1f),
            ),
            modifier = Modifier.weight(1f),
        )

        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.chat_send),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

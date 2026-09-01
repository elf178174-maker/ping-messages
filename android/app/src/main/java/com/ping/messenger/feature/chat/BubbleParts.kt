package com.ping.messenger.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ping.messenger.R
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.core.common.formatBytes
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.ContactCard
import com.ping.messenger.domain.model.GeoPoint
import com.ping.messenger.domain.model.LinkPreview
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageQuote
import com.ping.messenger.domain.model.Poll
import com.ping.messenger.domain.model.Reaction
import com.ping.messenger.domain.model.TransferState
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.theme.BubbleShapes
import com.ping.messenger.ui.theme.MessageBodyStyle
import com.ping.messenger.ui.theme.MessageMetaStyle
import com.ping.messenger.ui.theme.PingTheme
import kotlin.math.roundToInt

/** The quoted block shown above a reply. Tapping it jumps to the original. */
@Composable
fun QuotedMessage(
    quote: MessageQuote,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The accent bar is what makes a quote scannable at a glance without reading it.
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 40.dp)
                .background(accent),
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                text = quote.senderName,
                style = MessageMetaStyle,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = quote.text.ifBlank { quote.kind.previewLabel() },
                style = MessageMetaStyle,
                color = accent.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (quote.thumbnailUrl != null) {
            AsyncImage(
                model = quote.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(4.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }
    }
}

private fun MessageKind.previewLabel(): String = when (this) {
    MessageKind.IMAGE -> "📷 Photo"
    MessageKind.VIDEO -> "🎥 Video"
    MessageKind.VOICE -> "🎤 Voice message"
    MessageKind.AUDIO -> "🎵 Audio"
    MessageKind.DOCUMENT -> "📄 Document"
    MessageKind.LOCATION -> "📍 Location"
    MessageKind.CONTACT -> "👤 Contact"
    MessageKind.POLL -> "📊 Poll"
    MessageKind.GIF -> "GIF"
    else -> ""
}

/** Dispatches to the right renderer for whatever is attached. */
@Composable
fun AttachmentContent(
    attachments: List<Attachment>,
    kind: MessageKind,
    timeFormatter: TimeFormatter,
    tint: Color,
    metaColor: Color,
    onClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = attachments.first()
    when (kind) {
        MessageKind.IMAGE, MessageKind.GIF, MessageKind.VIDEO ->
            VisualAttachment(first, kind, timeFormatter, metaColor, onClick, modifier)
        MessageKind.VOICE ->
            VoiceAttachment(first, timeFormatter, tint, metaColor, onClick, modifier)
        else ->
            FileAttachment(first, tint, metaColor, onClick, modifier)
    }

    // Anything beyond the first attachment is listed compactly rather than stacked full-size,
    // which keeps a five-file share from filling the whole transcript.
    attachments.drop(1).forEach { extra ->
        Spacer(Modifier.height(4.dp))
        FileAttachment(extra, tint, metaColor, onClick)
    }
}

@Composable
private fun VisualAttachment(
    attachment: Attachment,
    kind: MessageKind,
    timeFormatter: TimeFormatter,
    metaColor: Color,
    onClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = attachment.aspectRatio.coerceIn(0.6f, 1.8f)
    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(BubbleShapes.attachment)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick(attachment) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                // Prefers the local copy; falls back to the remote URL. Coil handles the
                // downsampling to the composable's measured size, which is what keeps a
                // 12-megapixel photo from becoming a 48 MB bitmap.
                .data(attachment.localPath ?: attachment.thumbnailPath ?: attachment.remoteUrl)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(R.string.cd_image_attachment),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(),
        )

        if (kind == MessageKind.VIDEO) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (attachment.durationMs > 0) {
                Text(
                    text = timeFormatter.duration(attachment.durationMs),
                    style = MessageMetaStyle,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        TransferOverlay(attachment, Modifier.align(Alignment.Center))
    }
}

/**
 * Upload/download state drawn over a thumbnail.
 *
 * Progress is shown as a determinate ring when the size is known, because an indeterminate
 * spinner on a 90-second video upload tells the user nothing about whether to wait.
 */
@Composable
private fun TransferOverlay(attachment: Attachment, modifier: Modifier = Modifier) {
    when (attachment.transferState) {
        TransferState.QUEUED, TransferState.RUNNING -> Box(
            modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.transferProgress > 0f) {
                CircularProgressIndicator(
                    progress = { attachment.transferProgress },
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        TransferState.FAILED -> Box(
            modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.media_retry_upload),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        TransferState.IDLE -> if (attachment.localPath == null && attachment.remoteUrl != null) {
            Box(
                modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.media_download),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        TransferState.COMPLETE -> Unit
    }
}

/** A voice message: play button, waveform, duration. */
@Composable
private fun VoiceAttachment(
    attachment: Attachment,
    timeFormatter: TimeFormatter,
    tint: Color,
    metaColor: Color,
    onClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playbackProgress: Float = 0f,
) {
    Row(
        modifier = modifier
            .widthIn(min = 180.dp, max = 260.dp)
            .clickable { onClick(attachment) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.cd_pause else R.string.cd_play,
                ),
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Waveform(
                amplitudes = attachment.waveform,
                progress = playbackProgress,
                playedColor = tint,
                unplayedColor = tint.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = timeFormatter.duration(attachment.durationMs),
                style = MessageMetaStyle,
                color = metaColor,
            )
        }
    }
}

/**
 * The voice-message waveform.
 *
 * Drawn on a Canvas rather than composed from Boxes: a 48-bar waveform as 48 composables would
 * cost 48 layout nodes per bubble, and a transcript can hold dozens of them.
 */
@Composable
fun Waveform(
    amplitudes: List<Float>,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 40,
) {
    Canvas(modifier) {
        val bars = if (amplitudes.isEmpty()) {
            // A gentle synthetic shape, so a message whose waveform never arrived still looks
            // like a voice note rather than an empty box.
            List(barCount) { i -> 0.25f + 0.35f * kotlin.math.abs(kotlin.math.sin(i * 0.7f)) }
        } else {
            resample(amplitudes, barCount)
        }

        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        val playedBars = (bars.size * progress).roundToInt()

        bars.forEachIndexed { index, amplitude ->
            val barHeight = (size.height * amplitude.coerceIn(0.08f, 1f))
            val x = index * (barWidth + gap) + barWidth / 2
            drawLine(
                color = if (index < playedBars) playedColor else unplayedColor,
                start = Offset(x, (size.height - barHeight) / 2),
                end = Offset(x, (size.height + barHeight) / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Averages [source] down (or repeats it up) to exactly [target] bars. */
private fun resample(source: List<Float>, target: Int): List<Float> {
    if (source.size == target) return source
    if (source.size < target) return List(target) { source[it * source.size / target] }
    val bucket = source.size.toFloat() / target
    return List(target) { i ->
        val from = (i * bucket).toInt()
        val to = ((i + 1) * bucket).toInt().coerceAtMost(source.size)
        if (to <= from) source[from] else source.subList(from, to).average().toFloat()
    }
}

/** Documents and non-voice audio. */
@Composable
private fun FileAttachment(
    attachment: Attachment,
    tint: Color,
    metaColor: Color,
    onClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.07f))
            .clickable { onClick(attachment) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.transferState == TransferState.RUNNING) {
                CircularProgressIndicator(
                    progress = { attachment.transferProgress },
                    strokeWidth = 2.dp,
                    color = tint,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.fileName.ifBlank { "File" },
                style = MessageBodyStyle.copy(fontSize = MessageBodyStyle.fontSize * 0.9f),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(formatBytes(attachment.sizeBytes))
                    attachment.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
                        ?.let { append(" · ${it.uppercase()}") }
                },
                style = MessageMetaStyle,
                color = metaColor,
            )
        }
        if (attachment.localPath == null && attachment.transferState != TransferState.RUNNING) {
            Icon(
                Icons.Default.Download,
                contentDescription = stringResource(R.string.media_download),
                tint = metaColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** An in-bubble poll with live results. */
@Composable
fun PollContent(
    poll: Poll,
    textColor: Color,
    metaColor: Color,
    onVote: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.widthIn(min = 220.dp, max = 300.dp)) {
        Text(
            text = poll.question,
            style = MessageBodyStyle,
            color = textColor,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        poll.options.forEach { option ->
            val share = if (poll.totalVotes == 0) 0f else option.voteCount.toFloat() / poll.totalVotes
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !poll.isClosed) {
                        val next = if (poll.allowsMultipleAnswers) {
                            if (option.votedByMe) poll.myVotes - option.id else poll.myVotes + option.id
                        } else {
                            listOf(option.id)
                        }
                        onVote(next)
                    }
                    .padding(vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(17.dp)
                            .clip(if (poll.allowsMultipleAnswers) RoundedCornerShape(4.dp) else CircleShape)
                            .background(
                                if (option.votedByMe) textColor else Color.Transparent,
                            )
                            .border(
                                width = 1.5.dp,
                                color = textColor.copy(alpha = 0.5f),
                                shape = if (poll.allowsMultipleAnswers) {
                                    RoundedCornerShape(4.dp)
                                } else {
                                    CircleShape
                                },
                            ),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = option.text,
                        style = MessageBodyStyle.copy(fontSize = MessageBodyStyle.fontSize * 0.92f),
                        color = textColor,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(share * 100).roundToInt()}%",
                        style = MessageMetaStyle,
                        color = metaColor,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { share },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = textColor.copy(alpha = 0.75f),
                    trackColor = textColor.copy(alpha = 0.12f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (poll.totalVotes == 0) {
                stringResource(R.string.poll_no_votes)
            } else {
                stringResource(R.string.poll_votes, poll.totalVotes)
            },
            style = MessageMetaStyle,
            color = metaColor,
        )
    }
}

@Composable
fun LocationContent(
    point: GeoPoint,
    textColor: Color,
    metaColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.07f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = point.label ?: stringResource(R.string.attach_location),
                style = MessageBodyStyle.copy(fontSize = MessageBodyStyle.fontSize * 0.92f),
                color = textColor,
            )
            Text(
                text = "%.5f, %.5f".format(point.latitude, point.longitude),
                style = MessageMetaStyle,
                color = metaColor,
            )
        }
    }
}

@Composable
fun ContactCardContent(
    card: ContactCard,
    textColor: Color,
    metaColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(textColor.copy(alpha = 0.07f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = card.displayName, seed = card.userId ?: card.displayName, size = 38.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = card.displayName,
                style = MessageBodyStyle.copy(fontSize = MessageBodyStyle.fontSize * 0.92f),
                color = textColor,
            )
            card.username?.let {
                Text(text = "@$it", style = MessageMetaStyle, color = metaColor)
            }
        }
    }
}

@Composable
fun LinkPreviewCard(
    preview: LinkPreview,
    textColor: Color,
    metaColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = 0.06f)),
    ) {
        preview.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }
        Column(Modifier.padding(9.dp)) {
            preview.siteName?.let {
                Text(it, style = MessageMetaStyle, color = metaColor)
            }
            preview.title?.let {
                Text(
                    text = it,
                    style = MessageBodyStyle.copy(fontSize = MessageBodyStyle.fontSize * 0.9f),
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            preview.description?.let {
                Text(
                    text = it,
                    style = MessageMetaStyle,
                    color = metaColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The reaction pills under a bubble. */
@Composable
fun ReactionRow(
    reactions: List<Reaction>,
    onReactionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.take(6).forEach { reaction ->
            val description = stringResource(R.string.cd_reaction, reaction.emoji, reaction.count)
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (reaction.reactedByMe) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onReactionClick(reaction.emoji) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reaction.emoji, style = MessageMetaStyle.copy(fontSize = MessageMetaStyle.fontSize * 1.2f))
                if (reaction.count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = reaction.count.toString(),
                        style = MessageMetaStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

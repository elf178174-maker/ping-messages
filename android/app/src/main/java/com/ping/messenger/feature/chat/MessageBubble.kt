package com.ping.messenger.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.core.common.TextUtils
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Attachment
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.theme.BubbleShapes
import com.ping.messenger.ui.theme.MessageBodyStyle
import com.ping.messenger.ui.theme.MessageMetaStyle
import com.ping.messenger.ui.theme.PingTheme
import com.ping.messenger.ui.theme.emojiOnlyStyle

/**
 * One message bubble.
 *
 * A few decisions worth naming:
 *
 *  - **Grouping.** Consecutive messages from the same person collapse: only the last of a run
 *    gets the tightened "tail" corner, and only the first gets an avatar and sender name. This
 *    is what stops a rapid-fire exchange from looking like a wall of identical cards.
 *  - **Max width 78%.** Wide enough for a paragraph, narrow enough that the reader can always
 *    see which side a message is on without reading it.
 *  - **Emoji-only messages render large**, matching every other messenger, because a single 👍
 *    at body size reads as a mistake.
 *  - **The whole bubble is one accessibility node** with a sentence describing sender, content
 *    and time, rather than a dozen fragments a screen reader has to stitch together.
 */
@Composable
fun MessageBubble(
    message: Message,
    timeFormatter: TimeFormatter,
    modifier: Modifier = Modifier,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    showSenderName: Boolean = false,
    isSelected: Boolean = false,
    isHighlighted: Boolean = false,
    searchTerm: String? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    onAttachmentClick: (Attachment) -> Unit = {},
    onRetry: () -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onPollVote: (List<String>) -> Unit = {},
) {
    val colors = PingTheme.colors
    val outgoing = message.isOutgoing

    val bubbleColor = when {
        isHighlighted -> MaterialTheme.colorScheme.tertiaryContainer
        outgoing -> colors.outgoingBubble
        else -> colors.incomingBubble
    }
    val textColor = if (outgoing) colors.onOutgoingBubble else colors.onIncomingBubble
    val metaColor = if (outgoing) colors.outgoingBubbleMeta else colors.bubbleMeta
    val shape = if (outgoing) {
        BubbleShapes.outgoing(isLastInGroup)
    } else {
        BubbleShapes.incoming(isLastInGroup)
    }

    val description = bubbleDescription(message, timeFormatter)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirstInGroup) 6.dp else 1.dp,
                bottom = if (isLastInGroup) 4.dp else 1.dp,
            ),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // In a group chat, the avatar column is always reserved so that consecutive bubbles
        // stay aligned with the first one instead of stepping left.
        if (!outgoing && showSenderName) {
            if (isLastInGroup) {
                Avatar(
                    name = message.senderName,
                    photoUrl = message.senderAvatarUrl,
                    seed = message.senderId,
                    size = 28.dp,
                )
            } else {
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .wrapContentWidth(if (outgoing) Alignment.End else Alignment.Start),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(bubbleColor)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .semantics(mergeDescendants = true) { contentDescription = description }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (showSenderName && !outgoing && isFirstInGroup) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = remember(message.senderId) {
                            com.ping.messenger.ui.components.avatarColorFor(message.senderId)
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }

                message.replyTo?.let { quote ->
                    QuotedMessage(
                        quote = quote,
                        accent = if (outgoing) colors.onOutgoingBubble else MaterialTheme.colorScheme.primary,
                        onClick = { onQuoteClick(quote.messageId) },
                    )
                    Spacer(Modifier.height(5.dp))
                }

                if (message.forwardedFrom != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 3.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = metaColor,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.chat_forward),
                            style = MessageMetaStyle,
                            color = metaColor,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }

                MessageContent(
                    message = message,
                    textColor = textColor,
                    metaColor = metaColor,
                    searchTerm = searchTerm,
                    timeFormatter = timeFormatter,
                    onAttachmentClick = onAttachmentClick,
                    onPollVote = onPollVote,
                )

                Spacer(Modifier.height(2.dp))
                BubbleFooter(
                    message = message,
                    metaColor = metaColor,
                    timeFormatter = timeFormatter,
                    onRetry = onRetry,
                )
            }

            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                ReactionRow(
                    reactions = message.reactions,
                    onReactionClick = onReactionClick,
                )
            }
        }
    }
}

@Composable
private fun MessageContent(
    message: Message,
    textColor: Color,
    metaColor: Color,
    searchTerm: String?,
    timeFormatter: TimeFormatter,
    onAttachmentClick: (Attachment) -> Unit,
    onPollVote: (List<String>) -> Unit,
) {
    if (message.isDeleted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = metaColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_deleted_placeholder),
                style = MessageBodyStyle,
                color = metaColor,
                fontStyle = FontStyle.Italic,
            )
        }
        return
    }

    if (message.decryptionFailed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = PingTheme.colors.warning,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.error_decrypt_failed),
                style = MessageBodyStyle,
                color = metaColor,
                fontStyle = FontStyle.Italic,
            )
        }
        return
    }

    // Attachments render above any caption, which is the order every gallery-style messenger
    // uses and what makes a captioned photo read as one unit.
    if (message.attachments.isNotEmpty()) {
        AttachmentContent(
            attachments = message.attachments,
            kind = message.kind,
            timeFormatter = timeFormatter,
            tint = textColor,
            metaColor = metaColor,
            onClick = onAttachmentClick,
        )
        if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
    }

    message.poll?.let { poll ->
        PollContent(poll = poll, textColor = textColor, metaColor = metaColor, onVote = onPollVote)
        return
    }

    message.location?.let { point ->
        LocationContent(point = point, textColor = textColor, metaColor = metaColor)
        return
    }

    message.contactCard?.let { card ->
        ContactCardContent(card = card, textColor = textColor, metaColor = metaColor)
        return
    }

    val body = message.translatedText ?: message.text
    if (body.isBlank()) return

    val emojiCount = remember(body) { TextUtils.emojiOnlyCount(body) }
    if (emojiCount > 0) {
        Text(text = body, style = emojiOnlyStyle(emojiCount), color = textColor)
        return
    }

    Text(
        text = highlightedBody(body, searchTerm, message.mentions),
        style = MessageBodyStyle,
        color = textColor,
    )

    if (message.translatedText != null) {
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.chat_translated_from, "auto"),
            style = MessageMetaStyle,
            color = metaColor,
            fontStyle = FontStyle.Italic,
        )
    }

    message.linkPreview?.let { preview ->
        Spacer(Modifier.height(6.dp))
        LinkPreviewCard(preview = preview, textColor = textColor, metaColor = metaColor)
    }
}

/**
 * Styles @mentions and, during an in-chat search, the matched term.
 *
 * Built with an AnnotatedString rather than several Text composables so the highlighted run
 * still wraps and reflows as part of one paragraph.
 */
@Composable
private fun highlightedBody(
    body: String,
    searchTerm: String?,
    mentions: List<String>,
) = buildAnnotatedString {
    val mentionColor = PingTheme.colors.mention
    val highlightColor = MaterialTheme.colorScheme.tertiary

    append(body)

    if (mentions.isNotEmpty()) {
        TextUtils.mentionRanges(body).forEach { range ->
            addStyle(
                SpanStyle(color = mentionColor, fontWeight = FontWeight.Medium),
                range.first,
                range.last + 1,
            )
        }
    }

    TextUtils.urlRanges(body).forEach { range ->
        addStyle(
            SpanStyle(color = mentionColor, textDecoration = TextDecoration.Underline),
            range.first,
            range.last + 1,
        )
    }

    if (!searchTerm.isNullOrBlank()) {
        var index = body.indexOf(searchTerm, ignoreCase = true)
        while (index >= 0) {
            addStyle(
                SpanStyle(background = highlightColor.copy(alpha = 0.35f)),
                index,
                index + searchTerm.length,
            )
            index = body.indexOf(searchTerm, index + searchTerm.length, ignoreCase = true)
        }
    }
}

/** Time, edited marker, star and delivery state, right-aligned inside the bubble. */
@Composable
private fun BubbleFooter(
    message: Message,
    metaColor: Color,
    timeFormatter: TimeFormatter,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.isStarred) {
            Icon(
                Icons.Default.Star,
                contentDescription = stringResource(R.string.chat_star),
                modifier = Modifier.size(11.dp),
                tint = metaColor,
            )
            Spacer(Modifier.width(4.dp))
        }

        if (message.isEdited) {
            Text(
                text = stringResource(R.string.chat_edited),
                style = MessageMetaStyle,
                color = metaColor,
                fontStyle = FontStyle.Italic,
            )
            Spacer(Modifier.width(4.dp))
        }

        if (message.expiresAt != null) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = metaColor,
            )
            Spacer(Modifier.width(4.dp))
        }

        Text(
            text = message.scheduledFor
                ?.let { timeFormatter.until(it) }
                ?: timeFormatter.timeOfDay(message.createdAt),
            style = MessageMetaStyle,
            color = metaColor,
        )

        if (message.isOutgoing) {
            Spacer(Modifier.width(4.dp))
            DeliveryTick(status = message.status, metaColor = metaColor, onRetry = onRetry)
        }
    }
}

@Composable
private fun DeliveryTick(status: MessageStatus, metaColor: Color, onRetry: () -> Unit) {
    val colors = PingTheme.colors
    when (status) {
        MessageStatus.PENDING, MessageStatus.SENDING -> Icon(
            Icons.Outlined.Schedule,
            contentDescription = stringResource(R.string.cd_message_status_sending),
            modifier = Modifier.size(13.dp),
            tint = metaColor,
        )
        MessageStatus.SENT -> Icon(
            Icons.Default.Check,
            contentDescription = stringResource(R.string.cd_message_status_sent),
            modifier = Modifier.size(14.dp),
            tint = metaColor,
        )
        MessageStatus.DELIVERED -> Icon(
            Icons.Default.DoneAll,
            contentDescription = stringResource(R.string.cd_message_status_delivered),
            modifier = Modifier.size(14.dp),
            tint = metaColor,
        )
        MessageStatus.READ -> Icon(
            Icons.Default.DoneAll,
            contentDescription = stringResource(R.string.cd_message_status_read),
            modifier = Modifier.size(14.dp),
            tint = colors.readTick,
        )
        MessageStatus.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.combinedClickable(onClick = onRetry),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.cd_message_status_failed),
                modifier = Modifier.size(13.dp),
                tint = colors.danger,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.state_failed_action),
                style = MessageMetaStyle,
                color = colors.danger,
            )
        }
    }
}

/** A screen reader hears one sentence per bubble, not a dozen fragments. */
@Composable
private fun bubbleDescription(message: Message, timeFormatter: TimeFormatter): String {
    val time = timeFormatter.timeOfDay(message.createdAt)
    val prefix = if (message.isOutgoing) {
        stringResource(R.string.cd_message_from_you, time)
    } else {
        stringResource(R.string.cd_message_from, message.senderName, time)
    }
    val body = when {
        message.isDeleted -> stringResource(R.string.chat_deleted_placeholder)
        message.decryptionFailed -> stringResource(R.string.error_decrypt_failed)
        message.text.isNotBlank() -> message.text
        message.kind == MessageKind.IMAGE -> stringResource(R.string.cd_image_attachment)
        message.kind == MessageKind.VOICE -> stringResource(
            R.string.cd_voice_message_duration,
            timeFormatter.duration(message.attachments.firstOrNull()?.durationMs ?: 0),
        )
        else -> message.attachments.firstOrNull()?.fileName.orEmpty()
    }
    val status = if (message.isOutgoing) {
        ", " + when (message.status) {
            MessageStatus.PENDING, MessageStatus.SENDING -> stringResource(R.string.state_sending)
            MessageStatus.SENT -> stringResource(R.string.state_sent)
            MessageStatus.DELIVERED -> stringResource(R.string.state_delivered)
            MessageStatus.READ -> stringResource(R.string.state_read)
            MessageStatus.FAILED -> stringResource(R.string.state_failed)
        }
    } else {
        ""
    }
    return "$prefix. $body$status"
}

package com.ping.messenger.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PushPin
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ping.messenger.R
import com.ping.messenger.core.common.TextUtils
import com.ping.messenger.core.common.TimeFormatter
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.MessageKind
import com.ping.messenger.domain.model.MessageStatus
import com.ping.messenger.ui.components.Avatar
import com.ping.messenger.ui.components.UnreadBadge
import com.ping.messenger.ui.theme.PingTheme

/**
 * One row of the chat list.
 *
 * The information hierarchy is deliberate and matches what people actually scan for: name and
 * time on the first line, then a single line of preview whose left edge carries whichever
 * status marker is most urgent — a draft, someone typing, or the delivery state of the last
 * message the user sent.
 */
@Composable
fun ChatListItem(
    conversation: Conversation,
    timeFormatter: TimeFormatter,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = PingTheme.colors
    val description = buildRowDescription(conversation)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button,
            )
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .defaultMinSize(minHeight = 76.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(
                name = conversation.title,
                photoUrl = conversation.avatarUrl,
                seed = conversation.id,
                size = 54.dp,
                isGroup = conversation.isGroup,
                isOnline = conversation.presence is com.ping.messenger.domain.model.Presence.Online,
                showPresence = !conversation.isGroup,
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.isGroup) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conversation.hasUnread) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = conversation.lastMessage
                        ?.let { timeFormatter.listTimestamp(it.timestamp) }
                        .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.hasUnread) {
                        colors.unreadBadge
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewLine(
                    conversation = conversation,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TrailingMarkers(conversation)
            }
        }
    }
}

/**
 * The second line. Priority order — typing beats draft beats last message — because the most
 * recent, most actionable signal is what the user wants at a glance.
 */
@Composable
private fun PreviewLine(conversation: Conversation, modifier: Modifier = Modifier) {
    val colors = PingTheme.colors
    val typing = conversation.typingUserNames

    when {
        typing.isNotEmpty() -> {
            Text(
                text = if (conversation.isGroup && typing.size == 1) {
                    stringResource(R.string.chats_typing_named, typing.first())
                } else {
                    stringResource(R.string.chats_typing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.online,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }

        !conversation.draft.isNullOrBlank() -> {
            val label = stringResource(R.string.chats_draft_label)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.danger, fontWeight = FontWeight.Medium)) {
                        append("$label ")
                    }
                    append(TextUtils.singleLine(conversation.draft))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }

        else -> {
            val last = conversation.lastMessage
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                if (last != null && last.isOutgoing) {
                    StatusTick(last.status)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = last?.let { previewFor(it.kind, it.text, it.senderName, conversation.isGroup) }
                        .orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (conversation.hasUnread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun previewFor(
    kind: MessageKind,
    text: String,
    senderName: String?,
    isGroup: Boolean,
): String {
    val body = when (kind) {
        MessageKind.TEXT, MessageKind.SYSTEM -> TextUtils.singleLine(text)
        MessageKind.IMAGE -> "📷 " + text.ifBlank { stringResource(R.string.attach_photo) }
        MessageKind.VIDEO -> "🎥 " + text.ifBlank { stringResource(R.string.attach_video) }
        MessageKind.GIF -> "GIF"
        MessageKind.VOICE -> "🎤 " + stringResource(R.string.attach_voice_message)
        MessageKind.AUDIO -> "🎵 " + text.ifBlank { stringResource(R.string.attach_audio) }
        MessageKind.DOCUMENT -> "📄 " + text.ifBlank { stringResource(R.string.attach_file) }
        MessageKind.LOCATION -> "📍 " + stringResource(R.string.attach_location)
        MessageKind.CONTACT -> "👤 " + stringResource(R.string.attach_contact)
        MessageKind.POLL -> "📊 " + text.ifBlank { stringResource(R.string.attach_poll) }
        MessageKind.CALL_EVENT -> "📞 " + text
    }
    return if (isGroup && !senderName.isNullOrBlank()) "$senderName: $body" else body
}

/** The delivery tick shown next to the user's own last message. */
@Composable
private fun StatusTick(status: MessageStatus, modifier: Modifier = Modifier) {
    val colors = PingTheme.colors
    val (icon, tint, description) = when (status) {
        MessageStatus.PENDING, MessageStatus.SENDING ->
            Triple(Icons.Outlined.Schedule, colors.bubbleMeta, R.string.cd_message_status_sending)
        MessageStatus.SENT ->
            Triple(Icons.Default.Check, colors.bubbleMeta, R.string.cd_message_status_sent)
        MessageStatus.DELIVERED ->
            Triple(Icons.Default.DoneAll, colors.bubbleMeta, R.string.cd_message_status_delivered)
        MessageStatus.READ ->
            Triple(Icons.Default.DoneAll, colors.readTick, R.string.cd_message_status_read)
        MessageStatus.FAILED ->
            Triple(Icons.Outlined.ErrorOutline, colors.danger, R.string.cd_message_status_failed)
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(description),
        tint = tint,
        modifier = modifier.size(15.dp),
    )
}

@Composable
private fun TrailingMarkers(conversation: Conversation) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (conversation.isMuted) {
            Icon(
                Icons.Outlined.NotificationsOff,
                contentDescription = stringResource(R.string.cd_muted),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (conversation.isPinned && !conversation.hasUnread) {
            Icon(
                Icons.AutoMirrored.Filled.PushPin,
                contentDescription = stringResource(R.string.cd_pinned),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (conversation.mentionCount > 0) {
            Text(
                text = "@",
                style = MaterialTheme.typography.labelMedium,
                color = PingTheme.colors.mention,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (conversation.unreadCount > 0) {
            UnreadBadge(conversation.unreadCount, muted = conversation.isMuted)
        } else if (conversation.markedUnread) {
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(PingTheme.colors.unreadBadge),
            )
        }
    }
}

/** A single spoken sentence for the whole row, instead of six disconnected fragments. */
@Composable
private fun buildRowDescription(conversation: Conversation): String = buildString {
    append(conversation.title)
    if (conversation.unreadCount > 0) {
        append(", ")
        append(stringResource(R.string.cd_unread_count, conversation.unreadCount))
    }
    conversation.lastMessage?.let {
        append(", ")
        append(TextUtils.singleLine(it.text, 80))
    }
    if (conversation.isMuted) {
        append(", ")
        append(stringResource(R.string.cd_muted))
    }
    if (conversation.isPinned) {
        append(", ")
        append(stringResource(R.string.cd_pinned))
    }
}

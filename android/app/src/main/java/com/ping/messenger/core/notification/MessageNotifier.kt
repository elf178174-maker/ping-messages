package com.ping.messenger.core.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.ping.messenger.MainActivity
import com.ping.messenger.R
import com.ping.messenger.core.datastore.AppPreferences
import com.ping.messenger.domain.model.Conversation
import com.ping.messenger.domain.model.Message
import com.ping.messenger.domain.model.MessageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Message notifications.
 *
 * Built on [NotificationCompat.MessagingStyle] rather than a plain notification, which is what
 * gets Ping the platform's conversation treatment: a per-conversation bubble, correct grouping,
 * inline reply, and correct rendering on Wear and Auto — none of which a hand-rolled
 * notification gets.
 *
 * One notification per conversation, updated in place. Posting one per message is the classic
 * way to bury a phone under fifty notifications from one group.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: NotificationChannels,
    private val preferences: AppPreferences,
) {
    private val manager = NotificationManagerCompat.from(context)

    /**
     * Shows or updates the notification for one conversation.
     *
     * [history] should be the last few messages, oldest first: MessagingStyle renders them as a
     * transcript, so the recipient sees context rather than just the newest line.
     */
    suspend fun notifyConversation(
        conversation: Conversation,
        history: List<Message>,
        selfName: String,
    ) {
        if (!channels.areNotificationsEnabled()) return

        val settings = preferences.notifications.first()
        if (!settings.messagesEnabled) return
        if (conversation.isGroup && !settings.groupsEnabled) return

        // A muted conversation stays silent but still updates the badge, which is what "mute"
        // means to a user: stop interrupting me, do not hide it from me.
        val muted = conversation.isMuted ||
            (conversation.mutedUntil?.let { it > System.currentTimeMillis() } == true)

        val mentionsMe = history.any { it.mentions.isNotEmpty() }
        val channelId = when {
            mentionsMe -> NotificationChannels.MENTIONS
            conversation.isGroup -> NotificationChannels.GROUP_CHATS
            else -> NotificationChannels.MESSAGES
        }

        val self = Person.Builder().setName(selfName).setKey("self").build()
        val style = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(conversation.isGroup)
            .setConversationTitle(conversation.title.takeIf { conversation.isGroup })

        for (message in history.takeLast(MAX_HISTORY)) {
            val sender = if (message.isOutgoing) {
                null
            } else {
                Person.Builder()
                    .setName(message.senderName.ifBlank { conversation.title })
                    .setKey(message.senderId)
                    .build()
            }
            style.addMessage(
                // With previews off, the notification says a message arrived without saying
                // what it was — the point of the setting.
                if (settings.showPreview) previewFor(message) else context.getString(R.string.app_name),
                message.createdAt,
                sender,
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            conversation.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse("ping://conversation/${conversation.id}")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(muted)
            .setSilent(muted)
            .setGroup(NOTIFICATION_GROUP)
            .setShortcutId(conversation.id)
            .setWhen(history.lastOrNull()?.createdAt ?: System.currentTimeMillis())
            .setNumber(conversation.unreadCount)
            .addAction(replyAction(conversation.id))
            .addAction(markReadAction(conversation.id))

        if (!muted) {
            builder.setDefaults(
                if (settings.vibrate) {
                    NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE
                } else {
                    NotificationCompat.DEFAULT_SOUND
                },
            )
        }

        // Lock-screen visibility follows the preview setting: hiding the body in the shade but
        // showing it on the lock screen would defeat the purpose.
        builder.setVisibility(
            if (settings.showPreview) {
                NotificationCompat.VISIBILITY_PRIVATE
            } else {
                NotificationCompat.VISIBILITY_SECRET
            },
        )

        runCatching { manager.notify(conversation.id.hashCode(), builder.build()) }
        postSummary()
    }

    /**
     * Inline reply.
     *
     * `RemoteInput` lets the user answer from the shade without opening the app, which is the
     * single most useful notification affordance a messenger has.
     */
    private fun replyAction(conversationId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.chat_reply))
            .build()

        val intent = PendingIntent.getBroadcast(
            context,
            ("reply-$conversationId").hashCode(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY
                putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            },
            // Mutable is required: the system writes the typed text into the intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_notification),
            context.getString(R.string.chat_reply),
            intent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun markReadAction(conversationId: String): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context,
            ("read-$conversationId").hashCode(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_READ
                putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, R.drawable.ic_notification),
            context.getString(R.string.chats_mark_read),
            intent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * The group summary.
     *
     * Required on API 24+ for grouped notifications to collapse: without it, several
     * conversations show as separate top-level entries rather than one expandable group.
     */
    private fun postSummary() {
        val summary = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.InboxStyle())
            .build()

        runCatching { manager.notify(SUMMARY_ID, summary) }
    }

    fun cancelConversation(conversationId: String) {
        manager.cancel(conversationId.hashCode())
        // Cancel the summary too once nothing is left under it, or an empty group header
        // lingers in the shade.
        if (manager.activeNotifications.none { it.id != SUMMARY_ID }) {
            manager.cancel(SUMMARY_ID)
        }
    }

    fun cancelAll() = manager.cancelAll()

    private fun previewFor(message: Message): String = when (message.kind) {
        MessageKind.TEXT, MessageKind.SYSTEM -> message.text
        MessageKind.IMAGE -> "📷 " + context.getString(R.string.attach_photo)
        MessageKind.VIDEO -> "🎥 " + context.getString(R.string.attach_video)
        MessageKind.VOICE -> "🎤 " + context.getString(R.string.attach_voice_message)
        MessageKind.AUDIO -> "🎵 " + context.getString(R.string.attach_audio)
        MessageKind.DOCUMENT -> "📄 " + message.attachments.firstOrNull()?.fileName.orEmpty()
        MessageKind.LOCATION -> "📍 " + context.getString(R.string.attach_location)
        MessageKind.CONTACT -> "👤 " + context.getString(R.string.attach_contact)
        MessageKind.POLL -> "📊 " + (message.poll?.question ?: context.getString(R.string.attach_poll))
        MessageKind.GIF -> "GIF"
        MessageKind.CALL_EVENT -> "📞 " + context.getString(R.string.calls_title)
    }

    companion object {
        const val KEY_REPLY_TEXT = "ping_reply_text"
        private const val NOTIFICATION_GROUP = "ping_messages"
        private const val SUMMARY_ID = 1
        private const val MAX_HISTORY = 6
    }
}

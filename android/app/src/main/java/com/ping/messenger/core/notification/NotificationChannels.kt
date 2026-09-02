package com.ping.messenger.core.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels.
 *
 * Split by purpose rather than lumped into one, because channels are the only mechanism
 * Android gives the user for fine-grained control: someone who wants call notifications to
 * ring through Do Not Disturb but group chatter to stay silent can only do that if those are
 * separate channels. Getting this wrong forces an all-or-nothing choice.
 *
 * Channel importance cannot be changed after creation — the system deliberately ignores it, so
 * the user's own adjustment sticks. That is why raising a channel's importance requires a new
 * id, and why the ids here carry a version suffix.
 */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun ensureChannels() {
        val manager = manager ?: return

        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_MESSAGES, "Messages"),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_CALLS, "Calls"),
        )

        val messages = NotificationChannel(
            MESSAGES,
            "Direct messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = GROUP_MESSAGES
            description = "One-to-one messages"
            enableVibration(true)
            setShowBadge(true)
        }

        val groups = NotificationChannel(
            GROUP_CHATS,
            "Group messages",
            // A step below direct messages: groups are noisier, so they notify without a
            // heads-up banner by default.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            group = GROUP_MESSAGES
            description = "Messages in groups"
            enableVibration(true)
            setShowBadge(true)
        }

        val mentions = NotificationChannel(
            MENTIONS,
            "Mentions and replies",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = GROUP_MESSAGES
            description = "When someone @mentions you or replies to your message"
            enableVibration(true)
            setShowBadge(true)
        }

        val reactions = NotificationChannel(
            REACTIONS,
            "Reactions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            group = GROUP_MESSAGES
            description = "When someone reacts to your message"
            setShowBadge(false)
        }

        val calls = NotificationChannel(
            CALLS,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            group = GROUP_CALLS
            description = "Ringing for incoming voice and video calls"
            // A ringtone, not a notification tone, and looping: an incoming call has to be
            // noticeable across a room.
            setSound(
                Uri.parse("android.resource://${context.packageName}/raw/ping_ringtone")
                    .takeIf { hasRingtoneResource() }
                    ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 800, 1000, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
            // Lets the user allow calls through Do Not Disturb without also allowing chat.
            setBypassDnd(false)
        }

        val ongoing = NotificationChannel(
            ONGOING,
            "Ongoing",
            // Silent: the foreground-service notification for an active call or a sync is
            // information, not an interruption.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            group = GROUP_CALLS
            description = "Active calls and background sync"
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(messages, groups, mentions, reactions, calls, ongoing),
        )
    }

    private fun hasRingtoneResource(): Boolean =
        context.resources.getIdentifier("ping_ringtone", "raw", context.packageName) != 0

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True when the user has specifically muted one channel. */
    fun isChannelEnabled(channelId: String): Boolean {
        val channel = manager?.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    companion object {
        const val GROUP_MESSAGES = "group_messages"
        const val GROUP_CALLS = "group_calls"

        const val MESSAGES = "messages_v1"
        const val GROUP_CHATS = "group_chats_v1"
        const val MENTIONS = "mentions_v1"
        const val REACTIONS = "reactions_v1"
        const val CALLS = "calls_v1"
        const val ONGOING = "ongoing_v1"
    }
}

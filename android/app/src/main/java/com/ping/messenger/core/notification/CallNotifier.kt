package com.ping.messenger.core.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.ping.messenger.R
import com.ping.messenger.feature.calls.CallActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incoming-call and in-call notifications.
 *
 * Uses [NotificationCompat.CallStyle] on API 31+, which is what makes an incoming call render
 * as a call — full-width answer and decline buttons, correct priority, and correct behaviour on
 * the lock screen. Below 31 it falls back to a full-screen intent with the same two actions.
 */
@Singleton
class CallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: NotificationChannels,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun incomingCall(
        callId: String,
        callerName: String,
        callerAvatarUrl: String?,
        isVideo: Boolean,
    ): Notification {
        val caller = Person.Builder()
            .setName(callerName)
            .setKey(callId)
            .setImportant(true)
            .build()

        val answer = callIntent(callId, CallActivity.ACTION_ANSWER, isVideo)
        val decline = callIntent(callId, CallActivity.ACTION_DECLINE, isVideo)
        // The full-screen intent is what lets a call show over the lock screen. It requires
        // USE_FULL_SCREEN_INTENT, and on API 34+ the system only honours it for calls and
        // alarms — which is exactly what this is.
        val fullScreen = callIntent(callId, CallActivity.ACTION_INCOMING, isVideo)

        val builder = NotificationCompat.Builder(context, NotificationChannels.CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // A ringing call must not be dismissible by a swipe.
            .setDeleteIntent(decline)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer),
            )
        } else {
            builder
                .setContentTitle(callerName)
                .setContentText(
                    context.getString(
                        if (isVideo) R.string.calls_video else R.string.calls_voice,
                    ),
                )
                .addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.calls_decline),
                    decline,
                )
                .addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.calls_answer),
                    answer,
                )
        }

        return builder.build()
    }

    /** The persistent notification shown while a call is connected. */
    fun ongoingCall(callId: String, peerName: String, isVideo: Boolean): Notification {
        val peer = Person.Builder().setName(peerName).setKey(callId).build()
        val hangUp = callIntent(callId, CallActivity.ACTION_HANG_UP, isVideo)
        val open = callIntent(callId, CallActivity.ACTION_OPEN, isVideo)

        val builder = NotificationCompat.Builder(context, NotificationChannels.ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(peer, hangUp))
        } else {
            builder
                .setContentTitle(peerName)
                .setContentText(context.getString(R.string.calls_title))
                .addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.calls_hang_up),
                    hangUp,
                )
        }

        return builder.build()
    }

    fun showIncoming(callId: String, callerName: String, avatarUrl: String?, isVideo: Boolean) {
        if (!channels.areNotificationsEnabled()) return
        runCatching {
            manager.notify(callId.hashCode(), incomingCall(callId, callerName, avatarUrl, isVideo))
        }
    }

    fun cancel(callId: String) = manager.cancel(callId.hashCode())

    private fun callIntent(callId: String, action: String, isVideo: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            "$action-$callId".hashCode(),
            Intent(context, CallActivity::class.java).apply {
                this.action = action
                putExtra(CallActivity.EXTRA_CALL_ID, callId)
                putExtra(CallActivity.EXTRA_IS_VIDEO, isVideo)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

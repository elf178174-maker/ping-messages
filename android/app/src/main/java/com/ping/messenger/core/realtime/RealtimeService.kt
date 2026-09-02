package com.ping.messenger.core.realtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ping.messenger.R
import com.ping.messenger.core.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * An optional foreground service that keeps the realtime socket open.
 *
 * **Deliberately not started by default.** A persistent foreground service is the single
 * biggest battery cost an app can impose, and Ping does not need one for normal operation: the
 * socket runs while the app is open, and messages missed while it was closed are fetched by
 * `/v1/sync` on next launch.
 *
 * It exists for the case where a user genuinely wants instant delivery without a push provider
 * — a self-hosted deployment with no FCM. Enabling it is an explicit choice, and the
 * notification says what it is doing and why.
 */
@AndroidEntryPoint
class RealtimeService : Service() {

    @Inject lateinit var channels: NotificationChannels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels.ensureChannels()

        val notification = NotificationCompat.Builder(this, NotificationChannels.ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.error_connecting))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        // STICKY: unlike a call, a dropped connection should be re-established, and the
        // RealtimeClient's own reconnect loop handles it once the process is back.
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4343

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RealtimeService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RealtimeService::class.java)) }
        }
    }
}

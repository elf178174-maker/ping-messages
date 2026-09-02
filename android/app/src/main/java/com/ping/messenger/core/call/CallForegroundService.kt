package com.ping.messenger.core.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ping.messenger.core.notification.CallNotifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps an active call alive when the app is not in the foreground.
 *
 * Android will freeze or kill a backgrounded process holding the microphone unless it runs a
 * foreground service, so without this a call drops the moment the user switches apps. The
 * `microphone|camera` service types are what grant continued access on API 30+.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var callNotifier: CallNotifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) == true

        if (callId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = callNotifier.ongoingCall(callId, peerName, isVideo)

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }

        // ServiceCompat handles the API-level differences in startForeground's signature;
        // calling the raw method breaks on either older or newer platforms.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

        // NOT_STICKY: if the process dies mid-call the call is over. Restarting the service
        // without a peer connection would show a phantom ongoing call.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4242
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_IS_VIDEO = "isVideo"

        fun start(context: Context, callId: String, peerName: String, isVideo: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        }
    }
}

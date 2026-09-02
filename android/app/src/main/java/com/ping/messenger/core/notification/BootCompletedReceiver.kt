package com.ping.messenger.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.core.work.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms background work after a reboot or an app update.
 *
 * WorkManager persists its queue across reboots by itself, but the periodic jobs still need
 * re-enqueuing after `MY_PACKAGE_REPLACED` because an update clears them. Without this, a user
 * who updates the app quietly stops getting scheduled messages and cleanup.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var channels: NotificationChannels

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        channels.ensureChannels()
        if (!tokenStore.isSignedIn) return

        syncScheduler.schedulePeriodicWork()
        syncScheduler.requestSync()
    }
}

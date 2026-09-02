package com.ping.messenger.core.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ping.messenger.data.repository.SyncTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the background work the app depends on.
 *
 * WorkManager rather than a plain coroutine for one reason that matters: these
 * jobs have to survive the process being killed. A message written to the outbox while the
 * user was offline must still be sent after they force-stop the app, reboot, or leave it
 * backgrounded for a day — and only the system job scheduler can promise that.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncTrigger {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Drains the outbox now, if there is a connection.
     *
     * `KEEP` rather than `REPLACE`: several messages sent in quick succession should share one
     * drain pass, not cancel and restart it each time.
     */
    override fun requestSync() {
        workManager.enqueueUniqueWork(
            SyncWorker.NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                // Exponential from 10 s: a server having a bad minute should not be hammered,
                // and the outbox is not time-critical once the first attempt has failed.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun requestMediaTransfer() {
        workManager.enqueueUniqueWork(
            MediaTransferWorker.NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MediaTransferWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * Registers the recurring jobs. Called once on app start and after boot.
     *
     * Periods are deliberately long. A 15-minute floor is WorkManager's minimum anyway, and
     * these are housekeeping tasks: nothing here needs to be prompt, and waking the device
     * more often than necessary is the main way a messaging app ruins battery life.
     */
    fun schedulePeriodicWork() {
        workManager.enqueueUniquePeriodicWork(
            CleanupWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            ScheduledMessageWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScheduledMessageWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }

    /** Automatic backup, only on an unmetered connection and while charging. */
    fun scheduleAutomaticBackup(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(BackupWorker.NAME)
            return
        }

        workManager.enqueueUniquePeriodicWork(
            BackupWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // A backup can be hundreds of megabytes; doing it on mobile data
                        // without asking would be a genuine cost to the user.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build(),
        )
    }

    fun cancelAll() {
        workManager.cancelAllWork()
    }
}

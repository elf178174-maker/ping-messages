package com.ping.messenger

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.ping.messenger.core.network.TokenStore
import com.ping.messenger.core.notification.NotificationChannels
import com.ping.messenger.core.realtime.RealtimeEventApplier
import com.ping.messenger.core.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Application entry point.
 *
 * Process-level setup only: notification channels, the image loader's cache policy, the
 * realtime event applier, and the background work schedule. Everything else is injected where
 * it is used rather than initialised here — a hundred-line Application class is how startup
 * time quietly becomes a second.
 */
@HiltAndroidApp
class PingApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationChannels: NotificationChannels
    @Inject lateinit var realtimeEventApplier: RealtimeEventApplier
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var okHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()

        notificationChannels.ensureChannels()
        realtimeEventApplier.start()

        if (tokenStore.isSignedIn) {
            syncScheduler.schedulePeriodicWork()
            // Anything queued while the app was closed goes out now.
            syncScheduler.requestSync()
        }
    }

    /**
     * The image loader.
     *
     * Two caches, deliberately sized:
     *
     *  - **Memory: 20% of available heap.** Enough for a screenful of avatars and thumbnails
     *    without competing with the message list for heap. Coil's default is 25%, which on a
     *    low-end device leaves too little for the rest of the app.
     *  - **Disk: 256 MB.** Chat media is re-viewed often, and re-downloading it costs the user
     *    data. The cache lives in the app's own cache directory so the system can reclaim it
     *    under pressure.
     *
     * The loader shares the app's OkHttp client, so image requests reuse the same connection
     * pool and carry the same TLS configuration as the API.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { okHttpClient }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .components {
            // Animated GIFs and stickers.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            // Lets a video attachment render its first frame as a thumbnail without a
            // separate thumbnailing pass.
            add(VideoFrameDecoder.Factory())
        }
        .respectCacheHeaders(false)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .build()
}

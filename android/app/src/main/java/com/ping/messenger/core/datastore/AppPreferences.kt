package com.ping.messenger.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ping.messenger.domain.model.PrivacyAudience
import com.ping.messenger.domain.model.PrivacySettings
import com.ping.messenger.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ping_settings")

/** Auto-download policy for one network class. */
enum class AutoDownloadPolicy { NEVER, IMAGES_ONLY, IMAGES_AND_AUDIO, ALL }

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    /** Multiplier applied on top of the system font scale, 0.85..1.4. */
    val fontScale: Float = 1.0f,
    val wallpaperId: String = "default",
)

data class ChatSettings(
    val enterToSend: Boolean = false,
    val mediaVisibleInGallery: Boolean = false,
    val linkPreviews: Boolean = true,
    val translationEnabled: Boolean = false,
)

data class NotificationSettings(
    val messagesEnabled: Boolean = true,
    val groupsEnabled: Boolean = true,
    val callsEnabled: Boolean = true,
    val reactionsEnabled: Boolean = true,
    val showPreview: Boolean = true,
    val vibrate: Boolean = true,
    val soundUri: String? = null,
)

data class StorageSettings(
    val autoDownloadWifi: AutoDownloadPolicy = AutoDownloadPolicy.ALL,
    val autoDownloadMobile: AutoDownloadPolicy = AutoDownloadPolicy.IMAGES_ONLY,
    val autoDownloadRoaming: AutoDownloadPolicy = AutoDownloadPolicy.NEVER,
)

data class SecuritySettings(
    val appLockEnabled: Boolean = false,
    val blockScreenshots: Boolean = false,
    val twoStepEnabled: Boolean = false,
    val securityNotificationsEnabled: Boolean = true,
)

data class BackupSettings(
    val automaticEnabled: Boolean = false,
    val includeMedia: Boolean = false,
    val destination: String = "local",
    val lastBackupAt: Long = 0,
    val lastBackupSizeBytes: Long = 0,
)

data class AdvancedSettings(
    /** Empty means "use the compiled-in BuildConfig.API_BASE_URL". */
    val serverUrlOverride: String = "",
    val iceServersOverride: String = "",
    val contactSyncEnabled: Boolean = false,
)

/**
 * Every user-adjustable setting, persisted with Jetpack DataStore.
 *
 * DataStore rather than SharedPreferences because the whole settings tree is consumed as
 * [Flow]s that drive recomposition: flipping the theme in Settings repaints the app without
 * any observer plumbing, and reads never block the main thread.
 *
 * An [IOException] while reading a corrupted file degrades to defaults rather than crashing —
 * losing a preference is recoverable, a launch crash is not.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    private val safeData: Flow<Preferences> = store.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    // ---- Appearance -------------------------------------------------------

    val appearance: Flow<AppearanceSettings> = safeData.map { prefs ->
        AppearanceSettings(
            themeMode = prefs[Keys.THEME_MODE]?.toThemeMode() ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            highContrast = prefs[Keys.HIGH_CONTRAST] ?: false,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            wallpaperId = prefs[Keys.WALLPAPER] ?: "default",
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setHighContrast(enabled: Boolean) = edit { it[Keys.HIGH_CONTRAST] = enabled }
    suspend fun setReduceMotion(enabled: Boolean) = edit { it[Keys.REDUCE_MOTION] = enabled }
    suspend fun setFontScale(scale: Float) = edit {
        it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.4f)
    }
    suspend fun setWallpaper(id: String) = edit { it[Keys.WALLPAPER] = id }

    // ---- Chats ------------------------------------------------------------

    val chat: Flow<ChatSettings> = safeData.map { prefs ->
        ChatSettings(
            enterToSend = prefs[Keys.ENTER_TO_SEND] ?: false,
            mediaVisibleInGallery = prefs[Keys.MEDIA_VISIBILITY] ?: false,
            linkPreviews = prefs[Keys.LINK_PREVIEWS] ?: true,
            translationEnabled = prefs[Keys.TRANSLATION] ?: false,
        )
    }

    suspend fun setEnterToSend(enabled: Boolean) = edit { it[Keys.ENTER_TO_SEND] = enabled }
    suspend fun setMediaVisibility(enabled: Boolean) = edit { it[Keys.MEDIA_VISIBILITY] = enabled }
    suspend fun setLinkPreviews(enabled: Boolean) = edit { it[Keys.LINK_PREVIEWS] = enabled }
    suspend fun setTranslation(enabled: Boolean) = edit { it[Keys.TRANSLATION] = enabled }

    // ---- Notifications ----------------------------------------------------

    val notifications: Flow<NotificationSettings> = safeData.map { prefs ->
        NotificationSettings(
            messagesEnabled = prefs[Keys.NOTIF_MESSAGES] ?: true,
            groupsEnabled = prefs[Keys.NOTIF_GROUPS] ?: true,
            callsEnabled = prefs[Keys.NOTIF_CALLS] ?: true,
            reactionsEnabled = prefs[Keys.NOTIF_REACTIONS] ?: true,
            showPreview = prefs[Keys.NOTIF_PREVIEW] ?: true,
            vibrate = prefs[Keys.NOTIF_VIBRATE] ?: true,
            soundUri = prefs[Keys.NOTIF_SOUND],
        )
    }

    suspend fun setNotificationsEnabled(messages: Boolean) = edit { it[Keys.NOTIF_MESSAGES] = messages }
    suspend fun setGroupNotifications(enabled: Boolean) = edit { it[Keys.NOTIF_GROUPS] = enabled }
    suspend fun setCallNotifications(enabled: Boolean) = edit { it[Keys.NOTIF_CALLS] = enabled }
    suspend fun setReactionNotifications(enabled: Boolean) = edit { it[Keys.NOTIF_REACTIONS] = enabled }
    suspend fun setNotificationPreview(enabled: Boolean) = edit { it[Keys.NOTIF_PREVIEW] = enabled }
    suspend fun setVibrate(enabled: Boolean) = edit { it[Keys.NOTIF_VIBRATE] = enabled }
    suspend fun setNotificationSound(uri: String?) = edit {
        if (uri == null) it.remove(Keys.NOTIF_SOUND) else it[Keys.NOTIF_SOUND] = uri
    }

    // ---- Privacy ----------------------------------------------------------

    val privacy: Flow<PrivacySettings> = safeData.map { prefs ->
        PrivacySettings(
            lastSeen = prefs[Keys.PRIVACY_LAST_SEEN].toAudience(PrivacyAudience.CONTACTS),
            onlineStatus = prefs[Keys.PRIVACY_ONLINE].toAudience(PrivacyAudience.EVERYONE),
            profilePhoto = prefs[Keys.PRIVACY_PHOTO].toAudience(PrivacyAudience.CONTACTS),
            about = prefs[Keys.PRIVACY_ABOUT].toAudience(PrivacyAudience.CONTACTS),
            status = prefs[Keys.PRIVACY_STATUS].toAudience(PrivacyAudience.CONTACTS),
            groups = prefs[Keys.PRIVACY_GROUPS].toAudience(PrivacyAudience.EVERYONE),
            calls = prefs[Keys.PRIVACY_CALLS].toAudience(PrivacyAudience.EVERYONE),
            readReceipts = prefs[Keys.READ_RECEIPTS] ?: true,
            typingIndicators = prefs[Keys.TYPING_INDICATORS] ?: true,
        )
    }

    suspend fun setPrivacy(settings: PrivacySettings) = edit { prefs ->
        prefs[Keys.PRIVACY_LAST_SEEN] = settings.lastSeen.name
        prefs[Keys.PRIVACY_ONLINE] = settings.onlineStatus.name
        prefs[Keys.PRIVACY_PHOTO] = settings.profilePhoto.name
        prefs[Keys.PRIVACY_ABOUT] = settings.about.name
        prefs[Keys.PRIVACY_STATUS] = settings.status.name
        prefs[Keys.PRIVACY_GROUPS] = settings.groups.name
        prefs[Keys.PRIVACY_CALLS] = settings.calls.name
        prefs[Keys.READ_RECEIPTS] = settings.readReceipts
        prefs[Keys.TYPING_INDICATORS] = settings.typingIndicators
    }

    // ---- Storage ----------------------------------------------------------

    val storage: Flow<StorageSettings> = safeData.map { prefs ->
        StorageSettings(
            autoDownloadWifi = prefs[Keys.AUTO_DL_WIFI].toPolicy(AutoDownloadPolicy.ALL),
            autoDownloadMobile = prefs[Keys.AUTO_DL_MOBILE].toPolicy(AutoDownloadPolicy.IMAGES_ONLY),
            autoDownloadRoaming = prefs[Keys.AUTO_DL_ROAMING].toPolicy(AutoDownloadPolicy.NEVER),
        )
    }

    suspend fun setAutoDownload(
        wifi: AutoDownloadPolicy,
        mobile: AutoDownloadPolicy,
        roaming: AutoDownloadPolicy,
    ) = edit { prefs ->
        prefs[Keys.AUTO_DL_WIFI] = wifi.name
        prefs[Keys.AUTO_DL_MOBILE] = mobile.name
        prefs[Keys.AUTO_DL_ROAMING] = roaming.name
    }

    // ---- Security ---------------------------------------------------------

    val security: Flow<SecuritySettings> = safeData.map { prefs ->
        SecuritySettings(
            appLockEnabled = prefs[Keys.APP_LOCK] ?: false,
            blockScreenshots = prefs[Keys.BLOCK_SCREENSHOTS] ?: false,
            twoStepEnabled = prefs[Keys.TWO_STEP] ?: false,
            securityNotificationsEnabled = prefs[Keys.SECURITY_NOTIFICATIONS] ?: true,
        )
    }

    suspend fun setAppLock(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    suspend fun setBlockScreenshots(enabled: Boolean) = edit { it[Keys.BLOCK_SCREENSHOTS] = enabled }
    suspend fun setTwoStep(enabled: Boolean) = edit { it[Keys.TWO_STEP] = enabled }
    suspend fun setSecurityNotifications(enabled: Boolean) = edit {
        it[Keys.SECURITY_NOTIFICATIONS] = enabled
    }

    // ---- Backup -----------------------------------------------------------

    val backup: Flow<BackupSettings> = safeData.map { prefs ->
        BackupSettings(
            automaticEnabled = prefs[Keys.BACKUP_AUTO] ?: false,
            includeMedia = prefs[Keys.BACKUP_MEDIA] ?: false,
            destination = prefs[Keys.BACKUP_DESTINATION] ?: "local",
            lastBackupAt = prefs[Keys.BACKUP_LAST_AT] ?: 0,
            lastBackupSizeBytes = prefs[Keys.BACKUP_LAST_SIZE] ?: 0,
        )
    }

    suspend fun setBackupAutomatic(enabled: Boolean) = edit { it[Keys.BACKUP_AUTO] = enabled }
    suspend fun setBackupIncludeMedia(enabled: Boolean) = edit { it[Keys.BACKUP_MEDIA] = enabled }
    suspend fun setBackupDestination(destination: String) = edit {
        it[Keys.BACKUP_DESTINATION] = destination
    }
    suspend fun recordBackup(at: Long, sizeBytes: Long) = edit {
        it[Keys.BACKUP_LAST_AT] = at
        it[Keys.BACKUP_LAST_SIZE] = sizeBytes
    }

    // ---- Advanced ---------------------------------------------------------

    val advanced: Flow<AdvancedSettings> = safeData.map { prefs ->
        AdvancedSettings(
            serverUrlOverride = prefs[Keys.SERVER_URL] ?: "",
            iceServersOverride = prefs[Keys.ICE_SERVERS] ?: "",
            contactSyncEnabled = prefs[Keys.CONTACT_SYNC] ?: false,
        )
    }

    suspend fun setServerUrl(url: String) = edit { it[Keys.SERVER_URL] = url.trim() }
    suspend fun setIceServers(servers: String) = edit { it[Keys.ICE_SERVERS] = servers.trim() }
    suspend fun setContactSync(enabled: Boolean) = edit { it[Keys.CONTACT_SYNC] = enabled }

    /** Blocking-free synchronous read used by the OkHttp base-URL interceptor. */
    suspend fun currentServerUrl(): String = advanced.first().serverUrlOverride

    // ---- Onboarding -------------------------------------------------------

    val hasCompletedOnboarding: Flow<Boolean> = safeData.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded(done: Boolean) = edit { it[Keys.ONBOARDED] = done }

    suspend fun clearAll() = edit { it.clear() }

    // ---- internals --------------------------------------------------------

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun String?.toThemeMode(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

    private fun String?.toAudience(default: PrivacyAudience): PrivacyAudience =
        PrivacyAudience.entries.firstOrNull { it.name == this } ?: default

    private fun String?.toPolicy(default: AutoDownloadPolicy): AutoDownloadPolicy =
        AutoDownloadPolicy.entries.firstOrNull { it.name == this } ?: default

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val WALLPAPER = stringPreferencesKey("wallpaper")

        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val MEDIA_VISIBILITY = booleanPreferencesKey("media_visibility")
        val LINK_PREVIEWS = booleanPreferencesKey("link_previews")
        val TRANSLATION = booleanPreferencesKey("translation")

        val NOTIF_MESSAGES = booleanPreferencesKey("notif_messages")
        val NOTIF_GROUPS = booleanPreferencesKey("notif_groups")
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_REACTIONS = booleanPreferencesKey("notif_reactions")
        val NOTIF_PREVIEW = booleanPreferencesKey("notif_preview")
        val NOTIF_VIBRATE = booleanPreferencesKey("notif_vibrate")
        val NOTIF_SOUND = stringPreferencesKey("notif_sound")

        val PRIVACY_LAST_SEEN = stringPreferencesKey("privacy_last_seen")
        val PRIVACY_ONLINE = stringPreferencesKey("privacy_online")
        val PRIVACY_PHOTO = stringPreferencesKey("privacy_photo")
        val PRIVACY_ABOUT = stringPreferencesKey("privacy_about")
        val PRIVACY_STATUS = stringPreferencesKey("privacy_status")
        val PRIVACY_GROUPS = stringPreferencesKey("privacy_groups")
        val PRIVACY_CALLS = stringPreferencesKey("privacy_calls")
        val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        val TYPING_INDICATORS = booleanPreferencesKey("typing_indicators")

        val AUTO_DL_WIFI = stringPreferencesKey("auto_dl_wifi")
        val AUTO_DL_MOBILE = stringPreferencesKey("auto_dl_mobile")
        val AUTO_DL_ROAMING = stringPreferencesKey("auto_dl_roaming")

        val APP_LOCK = booleanPreferencesKey("app_lock")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val TWO_STEP = booleanPreferencesKey("two_step")
        val SECURITY_NOTIFICATIONS = booleanPreferencesKey("security_notifications")

        val BACKUP_AUTO = booleanPreferencesKey("backup_auto")
        val BACKUP_MEDIA = booleanPreferencesKey("backup_media")
        val BACKUP_DESTINATION = stringPreferencesKey("backup_destination")
        val BACKUP_LAST_AT = longPreferencesKey("backup_last_at")
        val BACKUP_LAST_SIZE = longPreferencesKey("backup_last_size")

        val SERVER_URL = stringPreferencesKey("server_url")
        val ICE_SERVERS = stringPreferencesKey("ice_servers")
        val CONTACT_SYNC = booleanPreferencesKey("contact_sync")

        val ONBOARDED = booleanPreferencesKey("onboarded")
    }
}

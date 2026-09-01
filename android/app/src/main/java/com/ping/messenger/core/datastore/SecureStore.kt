package com.ping.messenger.core.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for the handful of values that must never sit on disk in the clear: refresh tokens,
 * the device's private keyset, and the two-step verification PIN hash.
 *
 * Backed by EncryptedSharedPreferences with an AES-256-GCM master key in the Android Keystore.
 * On a device with a secure element (most phones since 2018) the master key is hardware-bound
 * and cannot be extracted even from a rooted device.
 *
 * If the keystore is unavailable or the encrypted store is corrupt — which does happen, e.g.
 * after a botched device-to-device restore — the store rebuilds itself once rather than
 * crashing the app on launch. The cost is that the user is signed out, which is the correct
 * outcome: unreadable credentials are not credentials.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private fun openOrRecreate(): SharedPreferences = try {
        open()
    } catch (e: Exception) {
        Log.w(TAG, "Encrypted store unreadable, recreating", e)
        context.deleteSharedPreferences(FILE_NAME)
        try {
            open()
        } catch (fatal: Exception) {
            // Last resort: an unencrypted store would be a silent security downgrade, so the
            // app instead runs with an in-memory store and the user signs in again each launch.
            Log.e(TAG, "Keystore unavailable; secrets will not persist", fatal)
            InMemoryPreferences()
        }
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0): Long = prefs.getLong(key, default)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val FILE_NAME = "ping_secure_prefs"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TWO_STEP_PIN_HASH = "two_step_pin_hash"
        const val KEY_CONTACT_HASH_SALT = "contact_hash_salt"
    }
}

/** Fallback used only when the Android Keystore is unusable. Never touches disk. */
private class InMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { staged[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { staged[key] = value }
        override fun putInt(key: String, value: Int) = apply { staged[key] = value }
        override fun putLong(key: String, value: Long) = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean { applyChanges(); return true }
        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(staged)
        }
    }
}

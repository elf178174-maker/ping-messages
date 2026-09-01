package com.ping.messenger.core.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.ping.messenger.core.common.DispatcherProvider
import com.ping.messenger.core.datastore.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [CryptoService] backed by Google Tink.
 *
 * The device's private keyset is serialised with Tink's JSON keyset format and stored in
 * [SecureStore], which is backed by EncryptedSharedPreferences with a master key held in the
 * Android Keystore. The private key material therefore never sits in plaintext on disk, and on
 * devices with a secure element the master key never leaves it.
 */
@Singleton
class TinkCryptoService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureStore,
    private val dispatchers: DispatcherProvider,
) : CryptoService {

    private val mutex = Mutex()

    @Volatile private var privateKeyset: KeysetHandle? = null

    private val initialised: Boolean by lazy {
        // Tink's config registration is idempotent but not thread-safe on first call, so it is
        // funnelled through a lazy.
        HybridConfig.register()
        AeadConfig.register()
        true
    }

    override suspend fun ensureIdentity(): String = withContext(dispatchers.default) {
        mutex.withLock {
            check(initialised)
            val existing = loadPrivateKeyset()
            if (existing != null) {
                return@withLock exportPublicKey(existing)
            }

            val handle = KeysetHandle.generateNew(HPKE_PARAMETERS)
            val serialized = TinkJsonProtoKeysetFormat.serializeKeyset(
                handle,
                InsecureSecretKeyAccess.get(),
            )
            secureStore.putString(KEY_PRIVATE_KEYSET, serialized)
            privateKeyset = handle
            exportPublicKey(handle)
        }
    }

    override suspend fun publicKey(): String? = withContext(dispatchers.default) {
        check(initialised)
        loadPrivateKeyset()?.let { exportPublicKey(it) }
    }

    override fun fingerprintOf(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray())
        // 60 hex digits in 12 groups of 5 — the same shape as Signal's safety number, which is
        // short enough to read aloud and long enough to be meaningful.
        return digest.take(30).joinToString("") { "%02x".format(it) }
            .chunked(5)
            .joinToString(" ")
    }

    override suspend fun encryptForUser(
        recipientPublicKey: String,
        plaintext: ByteArray,
    ): EncryptedPayload = withContext(dispatchers.default) {
        check(initialised)
        try {
            val encrypter = publicHandle(recipientPublicKey).getPrimitive(HybridEncrypt::class.java)
            val ciphertext = encrypter.encrypt(plaintext, CONTEXT_INFO)
            EncryptedPayload(
                ciphertext = ciphertext.base64(),
                senderPublicKey = publicKey(),
            )
        } catch (e: Exception) {
            throw CryptoException("Failed to encrypt for recipient", e)
        }
    }

    override suspend fun encryptForGroup(
        recipientPublicKeys: Map<String, String>,
        plaintext: ByteArray,
    ): EncryptedGroupPayload = withContext(dispatchers.default) {
        check(initialised)
        try {
            // One random content key per message (the "sender key"), sealed per recipient.
            val contentKeyHandle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            val aead = contentKeyHandle.getPrimitive(Aead::class.java)
            val ciphertext = aead.encrypt(plaintext, CONTEXT_INFO)

            val serialisedContentKey = TinkJsonProtoKeysetFormat.serializeKeyset(
                contentKeyHandle,
                InsecureSecretKeyAccess.get(),
            ).toByteArray()

            val wrapped = recipientPublicKeys.mapValues { (_, recipientKey) ->
                publicHandle(recipientKey)
                    .getPrimitive(HybridEncrypt::class.java)
                    .encrypt(serialisedContentKey, CONTEXT_INFO)
                    .base64()
            }

            EncryptedGroupPayload(
                payload = EncryptedPayload(
                    ciphertext = ciphertext.base64(),
                    senderPublicKey = publicKey(),
                    algorithm = EncryptedPayload.ALGORITHM_AES_GCM,
                ),
                wrappedKeys = wrapped,
            )
        } catch (e: Exception) {
            throw CryptoException("Failed to encrypt for group", e)
        }
    }

    override suspend fun decrypt(payload: EncryptedPayload): ByteArray =
        withContext(dispatchers.default) {
            check(initialised)
            val handle = loadPrivateKeyset()
                ?: throw CryptoException("This device has no identity key")
            try {
                handle.getPrimitive(HybridDecrypt::class.java)
                    .decrypt(payload.ciphertext.unbase64(), CONTEXT_INFO)
            } catch (e: Exception) {
                throw CryptoException("Could not decrypt message", e)
            }
        }

    override suspend fun decryptGroup(payload: EncryptedPayload, wrappedKey: String): ByteArray =
        withContext(dispatchers.default) {
            check(initialised)
            val handle = loadPrivateKeyset()
                ?: throw CryptoException("This device has no identity key")
            try {
                val contentKeyBytes = handle.getPrimitive(HybridDecrypt::class.java)
                    .decrypt(wrappedKey.unbase64(), CONTEXT_INFO)
                val contentKey = TinkJsonProtoKeysetFormat.parseKeyset(
                    String(contentKeyBytes),
                    InsecureSecretKeyAccess.get(),
                )
                contentKey.getPrimitive(Aead::class.java)
                    .decrypt(payload.ciphertext.unbase64(), CONTEXT_INFO)
            } catch (e: Exception) {
                throw CryptoException("Could not decrypt group message", e)
            }
        }

    override fun newMediaKey(): String {
        check(initialised)
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        return TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
    }

    override suspend fun encryptMedia(key: String, plaintext: ByteArray): ByteArray =
        withContext(dispatchers.default) {
            check(initialised)
            try {
                aeadFor(key).encrypt(plaintext, MEDIA_CONTEXT)
            } catch (e: Exception) {
                throw CryptoException("Failed to encrypt media", e)
            }
        }

    override suspend fun decryptMedia(key: String, ciphertext: ByteArray): ByteArray =
        withContext(dispatchers.default) {
            check(initialised)
            try {
                aeadFor(key).decrypt(ciphertext, MEDIA_CONTEXT)
            } catch (e: Exception) {
                throw CryptoException("Failed to decrypt media", e)
            }
        }

    override suspend fun wipe() = withContext(dispatchers.default) {
        mutex.withLock {
            secureStore.remove(KEY_PRIVATE_KEYSET)
            privateKeyset = null
        }
    }

    // ---- internals --------------------------------------------------------

    private fun aeadFor(serialisedKey: String): Aead =
        TinkJsonProtoKeysetFormat.parseKeyset(serialisedKey, InsecureSecretKeyAccess.get())
            .getPrimitive(Aead::class.java)

    private fun loadPrivateKeyset(): KeysetHandle? {
        privateKeyset?.let { return it }
        val serialised = secureStore.getString(KEY_PRIVATE_KEYSET) ?: return null
        return runCatching {
            TinkJsonProtoKeysetFormat.parseKeyset(serialised, InsecureSecretKeyAccess.get())
        }.getOrNull()?.also { privateKeyset = it }
    }

    private fun exportPublicKey(handle: KeysetHandle): String =
        TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)

    private fun publicHandle(serialised: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(serialised)

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_PRIVATE_KEYSET = "identity_keyset_v1"

        /**
         * HPKE with X25519 key agreement, HKDF-SHA256 and AES-256-GCM (RFC 9180).
         *
         * Built explicitly rather than taken from `PredefinedHybridParameters`, whose entries
         * are all NIST-P256 ECIES; X25519 is the faster, misuse-resistant curve and is what
         * every modern messaging protocol has settled on.
         */
        val HPKE_PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setVariant(HpkeParameters.Variant.TINK)
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .build()

        /**
         * Associated data bound into every ciphertext. It is not secret; its job is to make a
         * ciphertext produced for one purpose fail to decrypt when replayed into another.
         */
        val CONTEXT_INFO = "ping:message:v1".toByteArray()
        val MEDIA_CONTEXT = "ping:media:v1".toByteArray()
    }
}

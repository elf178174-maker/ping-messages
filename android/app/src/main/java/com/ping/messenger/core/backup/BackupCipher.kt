package com.ping.messenger.core.backup

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import com.ping.messenger.core.datastore.SecureStore
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a backup archive is unlocked.
 *
 * [Passphrase] archives are portable: the file plus the passphrase is enough to restore on any
 * device. [DeviceKey] archives are sealed with a random secret held in Keystore-backed storage,
 * which is what automatic background backups use - nothing has to prompt for a passphrase at
 * 4am - at the cost of only being restorable on this install of the app. Both facts are stated
 * in the backup screen rather than left for the user to discover at restore time.
 */
sealed interface BackupKey {
    data class Passphrase(val value: String) : BackupKey
    data object DeviceKey : BackupKey
}

class BackupCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals and opens `.pingbak` archives.
 *
 * Construction, in envelope form:
 *
 *  1. A key-encryption key (KEK) is obtained - PBKDF2-HMAC-SHA256 over the passphrase for a
 *     passphrase archive, or the raw 256-bit device secret for a device-keyed one (already
 *     full-entropy, so stretching it would only cost time).
 *  2. A fresh Tink `StreamingAead` keyset (AES-256-GCM-HKDF, 1 MiB segments) is generated as
 *     the data-encryption key and used to encrypt the archive as a *stream*, so a large backup
 *     never has to fit in memory and every segment is individually authenticated.
 *  3. That keyset is wrapped under the KEK using Tink's encrypted-keyset format and stored in
 *     the file header.
 *
 * The plaintext header is bound into both operations as associated data, so editing the
 * iteration count or the salt makes the file fail to open rather than open weakly.
 *
 * Every cryptographic operation here is a call into Tink or into the platform's JCA
 * implementation of PBKDF2. Nothing about the primitives, modes or the streaming segmentation
 * is invented in this file - the streaming construction in particular is Tink's, precisely
 * because rolling a chunked AEAD by hand is where file-encryption code usually goes wrong.
 */
@Singleton
class BackupCipher @Inject constructor(
    private val secureStore: SecureStore,
) {
    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
    }

    private val random = SecureRandom()

    fun encrypt(plaintext: File, target: File, key: BackupKey) {
        val passphrase = key as? BackupKey.Passphrase
        val kdf = if (passphrase != null) KDF_PBKDF2_SHA256 else KDF_DEVICE_SECRET
        val iterations = if (passphrase != null) PBKDF2_ITERATIONS else 0
        val salt = if (passphrase != null) ByteArray(SALT_BYTES).also(random::nextBytes) else ByteArray(0)

        val kek = kekAead(key, salt, iterations)
        val dataKeyset = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)

        val prefix = header(kdf, iterations, salt)
        val wrapped = TinkJsonProtoKeysetFormat
            .serializeEncryptedKeyset(dataKeyset, kek, prefix)
            .toByteArray()

        target.outputStream().buffered().use { raw ->
            val out = DataOutputStream(raw)
            out.write(prefix)
            out.writeShort(wrapped.size)
            out.write(wrapped)
            out.flush()

            val streaming = dataKeyset.getPrimitive(StreamingAead::class.java)
            streaming.newEncryptingStream(out, prefix + wrapped).use { sealed ->
                plaintext.inputStream().buffered().use { source -> source.copyTo(sealed) }
            }
        }
    }

    fun decrypt(archive: File, target: File, key: BackupKey) {
        archive.inputStream().buffered().use { raw ->
            openStream(raw, key).use { plain ->
                target.outputStream().buffered().use { out -> plain.copyTo(out) }
            }
        }
    }

    /**
     * Opens the archive far enough to read a single entry without decrypting the whole file to
     * disk first, which is what showing "3,412 messages, 18 April" before a restore needs.
     */
    fun openStream(source: InputStream, key: BackupKey): InputStream {
        val input = DataInputStream(source)

        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupCipherException("This is not a Ping backup file.")
        }
        val version = input.readByte().toInt()
        if (version != HEADER_VERSION) {
            throw BackupCipherException("This backup was made by a newer version of Ping.")
        }
        val kdf = input.readByte().toInt()
        val iterations = input.readInt()
        val saltLength = input.readByte().toInt()
        if (saltLength < 0 || saltLength > MAX_SALT_BYTES) {
            throw BackupCipherException("The backup header is damaged.")
        }
        val salt = ByteArray(saltLength).also { input.readFully(it) }

        if (kdf == KDF_PBKDF2_SHA256 && key !is BackupKey.Passphrase) {
            throw BackupCipherException("This backup needs its passphrase.")
        }
        if (kdf == KDF_DEVICE_SECRET && key is BackupKey.Passphrase) {
            throw BackupCipherException("This backup is sealed to a device, not to a passphrase.")
        }

        val prefix = header(kdf, iterations, salt)
        val wrappedLength = input.readUnsignedShort()
        val wrapped = ByteArray(wrappedLength).also { input.readFully(it) }

        val dataKeyset = try {
            TinkJsonProtoKeysetFormat.parseEncryptedKeyset(
                wrapped.decodeToString(),
                kekAead(key, salt, iterations),
                prefix,
            )
        } catch (e: Exception) {
            // A wrong passphrase and a corrupt header are indistinguishable here, and the
            // overwhelmingly likely cause is the passphrase.
            throw BackupCipherException("Wrong passphrase, or the backup file is damaged.", e)
        }

        return try {
            dataKeyset
                .getPrimitive(StreamingAead::class.java)
                .newDecryptingStream(input, prefix + wrapped)
        } catch (e: Exception) {
            throw BackupCipherException("The backup file could not be opened.", e)
        }
    }

    /** True when a device-keyed backup made earlier could still be restored on this install. */
    fun hasDeviceSecret(): Boolean = secureStore.getString(KEY_BACKUP_SECRET) != null

    // ---- internals --------------------------------------------------------

    private fun header(kdf: Int, iterations: Int, salt: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC)
            out.writeByte(HEADER_VERSION)
            out.writeByte(kdf)
            out.writeInt(iterations)
            out.writeByte(salt.size)
            out.write(salt)
        }
        return buffer.toByteArray()
    }

    private fun kekAead(key: BackupKey, salt: ByteArray, iterations: Int): Aead {
        val material = when (key) {
            is BackupKey.Passphrase -> derive(key.value, salt, iterations)
            BackupKey.DeviceKey -> deviceSecret()
        }
        val gcm = AesGcmParameters.builder()
            .setKeySizeBytes(KEY_BYTES)
            .setIvSizeBytes(12)
            .setTagSizeBytes(16)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val aesKey = AesGcmKey.builder()
            .setParameters(gcm)
            .setKeyBytes(SecretBytes.copyFrom(material, InsecureSecretKeyAccess.get()))
            .build()
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(aesKey).withRandomId().makePrimary())
            .build()
            .getPrimitive(Aead::class.java)
    }

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "A passphrase archive must record its iteration count" }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            // The spec holds its own copy of the passphrase; clear it rather than waiting for
            // the garbage collector to get around to it.
            spec.clearPassword()
        }
    }

    /**
     * The device backup secret, generated on first use and kept in Keystore-backed storage.
     * It never leaves the device, and it is wiped along with the rest of the secure store on
     * sign-out, which is why a device-keyed archive is explicitly labelled as device-only.
     */
    private fun deviceSecret(): ByteArray {
        secureStore.getString(KEY_BACKUP_SECRET)?.let {
            return Base64.getDecoder().decode(it)
        }
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        secureStore.putString(KEY_BACKUP_SECRET, Base64.getEncoder().encodeToString(fresh))
        return fresh
    }

    companion object {
        /** Magic bytes, so the wrong file is rejected before any key derivation happens. */
        private val MAGIC = "PINGBAK1".toByteArray()
        private const val HEADER_VERSION = 1
        private const val KDF_DEVICE_SECRET = 0
        private const val KDF_PBKDF2_SHA256 = 1
        private const val SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val KEY_BYTES = 32
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

        /**
         * OWASP's 2023 password-storage guidance for PBKDF2-HMAC-SHA256. This runs once per
         * backup or restore, so a second of work is a fair price for making an offline guess
         * at the passphrase expensive.
         */
        const val PBKDF2_ITERATIONS = 600_000

        private const val KEY_BACKUP_SECRET = "backup_device_secret"

        /** Shortest passphrase the UI accepts; NIST SP 800-63B's floor for a user secret. */
        const val MIN_PASSPHRASE_LENGTH = 8
    }
}

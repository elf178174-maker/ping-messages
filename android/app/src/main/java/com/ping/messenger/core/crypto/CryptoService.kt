package com.ping.messenger.core.crypto

/**
 * End-to-end encryption.
 *
 * ## What this is
 *
 * Ping seals every message body to the recipient's published public key using **Google Tink**'s
 * hybrid encryption: HPKE with X25519 key agreement, HKDF-SHA256 and AES-256-GCM. Group
 * messages use the standard sender-key construction: the body is encrypted once under a random
 * AES-256-GCM key, and that key is sealed individually to each member's public key.
 *
 * ## What this deliberately is not
 *
 * No cryptographic primitive, mode, or construction in this file is invented here. Every
 * operation is a call into Tink, an audited library maintained by Google's cryptography team.
 * The one thing this layer does itself is *sequencing* Tink calls, and even that follows the
 * shape Tink's own hybrid-encryption guide prescribes.
 *
 * ## Honest limitations
 *
 * This is sealed-sender-style encryption, not a full Double Ratchet. Concretely, Ping does not
 * yet provide forward secrecy or post-compromise security: an attacker who obtains a device's
 * private key can decrypt that device's past ciphertexts if they also captured them. Signal's
 * X3DH + Double Ratchet is the right long-term answer, and [CryptoService] is shaped so that a
 * libsignal-backed implementation can replace [TinkCryptoService] without touching a caller.
 * `docs/SECURITY.md` states this in the same terms rather than overclaiming.
 */
interface CryptoService {

    /**
     * Ensures this device has an identity key pair, generating one on first run.
     * Returns the base64 public key that should be published to the server.
     */
    suspend fun ensureIdentity(): String

    /** This device's public key, or null if [ensureIdentity] has never run. */
    suspend fun publicKey(): String?

    /**
     * A short, human-comparable fingerprint of [publicKey], shown in the security screen so two
     * people can verify out of band that no one is in the middle.
     */
    fun fingerprintOf(publicKey: String): String

    /** Seals [plaintext] to a single recipient. */
    suspend fun encryptForUser(recipientPublicKey: String, plaintext: ByteArray): EncryptedPayload

    /**
     * Seals [plaintext] once and wraps the content key for each recipient.
     * This is what keeps a 200-member group from re-encrypting the body 200 times.
     */
    suspend fun encryptForGroup(
        recipientPublicKeys: Map<String, String>,
        plaintext: ByteArray,
    ): EncryptedGroupPayload

    /** Opens a payload sealed to this device. */
    suspend fun decrypt(payload: EncryptedPayload): ByteArray

    /** Opens a group payload, given the key envelope addressed to this device. */
    suspend fun decryptGroup(payload: EncryptedPayload, wrappedKey: String): ByteArray

    /** A fresh random AES-256-GCM key, used to encrypt a media blob before upload. */
    fun newMediaKey(): String

    suspend fun encryptMedia(key: String, plaintext: ByteArray): ByteArray

    suspend fun decryptMedia(key: String, ciphertext: ByteArray): ByteArray

    /** Destroys this device's keys. Called on sign-out and account deletion. */
    suspend fun wipe()
}

/** A sealed message body. [ciphertext] and [senderPublicKey] are base64. */
data class EncryptedPayload(
    val ciphertext: String,
    val senderPublicKey: String? = null,
    val algorithm: String = ALGORITHM_HPKE_X25519,
) {
    companion object {
        const val ALGORITHM_HPKE_X25519 = "hpke-x25519-hkdf-sha256-aes256gcm"
        const val ALGORITHM_AES_GCM = "aes256-gcm"
    }
}

/**
 * A group message: one ciphertext, plus the content key sealed once per recipient device.
 * [wrappedKeys] maps user id to a base64 envelope only that user can open.
 */
data class EncryptedGroupPayload(
    val payload: EncryptedPayload,
    val wrappedKeys: Map<String, String>,
)

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

package com.ping.messenger.backup

import com.google.common.truth.Truth.assertThat
import com.ping.messenger.core.backup.BackupCipher
import com.ping.messenger.core.backup.BackupCipherException
import com.ping.messenger.core.backup.BackupKey
import com.ping.messenger.core.datastore.SecureStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-trip and tamper tests for the backup envelope.
 *
 * These exist because a backup that cannot be restored is worse than no backup: the failure
 * only shows up on the day it matters. So the assertions are the ones that would actually
 * fail if the format drifted - byte-for-byte recovery, a wrong passphrase being rejected
 * rather than producing garbage, and a modified header failing authentication instead of
 * being silently trusted.
 *
 * No Robolectric runner: everything under test is Tink and the platform's JCA, so this runs as
 * a plain JVM test and stays fast enough to be run on every push.
 */
class BackupCipherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var cipher: BackupCipher

    @Before
    fun setUp() {
        // Only the device-key path touches the secure store, and it is backed here by a plain
        // map so the test does not depend on a working Android Keystore.
        val stored = mutableMapOf<String, String>()
        val store = mockk<SecureStore>(relaxed = true)
        every { store.getString(any()) } answers { stored[firstArg()] }
        val key = slot<String>()
        val value = slot<String>()
        every { store.putString(capture(key), capture(value)) } answers {
            stored[key.captured] = value.captured
        }
        cipher = BackupCipher(store)
    }

    private fun plaintextOf(size: Int): File =
        folder.newFile("plain.bin").apply {
            // Compressible but not uniform, so a truncation or an off-by-one in the header
            // would change the recovered bytes rather than happening to match.
            writeBytes(ByteArray(size) { (it * 31 % 251).toByte() })
        }

    @Test
    fun `passphrase archive round-trips exactly`() {
        val plain = plaintextOf(300_000)
        val sealed = folder.newFile("sealed.pingbak")
        val restored = folder.newFile("restored.bin")

        cipher.encrypt(plain, sealed, BackupKey.Passphrase("correct horse battery staple"))
        cipher.decrypt(sealed, restored, BackupKey.Passphrase("correct horse battery staple"))

        assertThat(restored.readBytes()).isEqualTo(plain.readBytes())
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val plain = folder.newFile("secret.txt").apply { writeText("SENSITIVE-MESSAGE-BODY") }
        val sealed = folder.newFile("sealed.pingbak")

        cipher.encrypt(plain, sealed, BackupKey.Passphrase("a passphrase"))

        assertThat(sealed.readBytes().decodeToString()).doesNotContain("SENSITIVE-MESSAGE-BODY")
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val plain = plaintextOf(2_048)
        val sealed = folder.newFile("sealed.pingbak")
        cipher.encrypt(plain, sealed, BackupKey.Passphrase("the right one"))

        val failure = assertThrows(BackupCipherException::class.java) {
            cipher.decrypt(sealed, folder.newFile("out.bin"), BackupKey.Passphrase("the wrong one"))
        }
        assertThat(failure).hasMessageThat().contains("passphrase")
    }

    @Test
    fun `a device-keyed archive refuses a passphrase and vice versa`() {
        val plain = plaintextOf(1_024)

        val deviceSealed = folder.newFile("device.pingbak")
        cipher.encrypt(plain, deviceSealed, BackupKey.DeviceKey)
        assertThrows(BackupCipherException::class.java) {
            cipher.decrypt(deviceSealed, folder.newFile("a.bin"), BackupKey.Passphrase("x"))
        }

        val passSealed = folder.newFile("pass.pingbak")
        cipher.encrypt(plain, passSealed, BackupKey.Passphrase("x"))
        assertThrows(BackupCipherException::class.java) {
            cipher.decrypt(passSealed, folder.newFile("b.bin"), BackupKey.DeviceKey)
        }
    }

    @Test
    fun `device-keyed archive round-trips with the same store`() {
        val plain = plaintextOf(4_096)
        val sealed = folder.newFile("device.pingbak")
        val restored = folder.newFile("restored.bin")

        cipher.encrypt(plain, sealed, BackupKey.DeviceKey)
        cipher.decrypt(sealed, restored, BackupKey.DeviceKey)

        assertThat(restored.readBytes()).isEqualTo(plain.readBytes())
        assertThat(cipher.hasDeviceSecret()).isTrue()
    }

    @Test
    fun `editing the header breaks authentication`() {
        val plain = plaintextOf(2_048)
        val sealed = folder.newFile("sealed.pingbak")
        cipher.encrypt(plain, sealed, BackupKey.Passphrase("passphrase"))

        // Byte 10 onwards is the iteration count. Lowering it would be a downgrade attack if
        // the header were not bound in as associated data.
        val bytes = sealed.readBytes()
        bytes[12] = 0
        bytes[13] = 1
        sealed.writeBytes(bytes)

        assertThrows(BackupCipherException::class.java) {
            cipher.decrypt(sealed, folder.newFile("out.bin"), BackupKey.Passphrase("passphrase"))
        }
    }

    @Test
    fun `flipping a ciphertext byte is detected`() {
        val plain = plaintextOf(8_192)
        val sealed = folder.newFile("sealed.pingbak")
        cipher.encrypt(plain, sealed, BackupKey.Passphrase("passphrase"))

        val bytes = sealed.readBytes()
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 0x01).toByte()
        sealed.writeBytes(bytes)

        // Tink authenticates each segment, so the corruption surfaces as an exception rather
        // than as plausible-looking wrong bytes.
        assertThrows(Exception::class.java) {
            cipher.decrypt(sealed, folder.newFile("out.bin"), BackupKey.Passphrase("passphrase"))
        }
    }

    @Test
    fun `a file that is not an archive is rejected before any key work`() {
        val notAnArchive = folder.newFile("random.bin").apply { writeText("just some bytes here") }

        val failure = assertThrows(BackupCipherException::class.java) {
            cipher.decrypt(notAnArchive, folder.newFile("out.bin"), BackupKey.Passphrase("x"))
        }
        assertThat(failure).hasMessageThat().contains("not a Ping backup")
    }
}

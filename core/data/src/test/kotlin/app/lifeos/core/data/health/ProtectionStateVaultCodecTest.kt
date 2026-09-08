package app.lifeos.core.data.health

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class ProtectionStateVaultCodecTest {
    @Test
    fun encryptedEnvelopeRoundTrips() {
        val key = aesKey()
        val plaintext = "protected-state".toByteArray()
        val encrypted = ProtectionStateVaultCodec.encrypt(plaintext, key)

        assertContentEquals(plaintext, ProtectionStateVaultCodec.decrypt(encrypted, key))
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val encrypted = ProtectionStateVaultCodec.encrypt("secret".toByteArray(), aesKey())
        assertFails { ProtectionStateVaultCodec.decrypt(encrypted, aesKey()) }
    }

    @Test
    fun tamperingIsRejected() {
        val key = aesKey()
        val encrypted = ProtectionStateVaultCodec.encrypt("secret".toByteArray(), key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFails { ProtectionStateVaultCodec.decrypt(encrypted, key) }
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

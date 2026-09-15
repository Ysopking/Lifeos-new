package app.lifeos.core.data.module

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ModuleEpistemicVaultCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 7).toByte() }, "AES")

    @Test
    fun `vault round trip restores exact payload`() {
        val plaintext = "module-epistemic-state".toByteArray()

        val encrypted = ModuleEpistemicVaultCodec.encrypt(plaintext, key)
        val decrypted = ModuleEpistemicVaultCodec.decrypt(encrypted, key)

        assertFalse(encrypted.contentEquals(plaintext))
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val encrypted = ModuleEpistemicVaultCodec.encrypt("protected".toByteArray(), key)
        val tampered = encrypted.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        assertFailsWith<Exception> {
            ModuleEpistemicVaultCodec.decrypt(tampered, key)
        }
    }

    @Test
    fun `wrong key cannot decrypt module state`() {
        val encrypted = ModuleEpistemicVaultCodec.encrypt("protected".toByteArray(), key)
        val otherKey = SecretKeySpec(ByteArray(32) { 0x33.toByte() }, "AES")

        assertFailsWith<Exception> {
            ModuleEpistemicVaultCodec.decrypt(encrypted, otherKey)
        }
    }

    @Test
    fun `oversized plaintext is rejected`() {
        val oversized = ByteArray(ModuleEpistemicVaultCodec.MAX_PLAINTEXT_BYTES + 1)

        assertFailsWith<IllegalArgumentException> {
            ModuleEpistemicVaultCodec.encrypt(oversized, key)
        }
    }
}

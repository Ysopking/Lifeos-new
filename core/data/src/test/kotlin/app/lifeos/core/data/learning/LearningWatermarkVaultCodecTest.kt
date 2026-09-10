package app.lifeos.core.data.learning

import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class LearningWatermarkVaultCodecTest {
    @Test
    fun encryptedEnvelopeRoundTrips() {
        val key = aesKey()
        val plaintext = "LIFEOS_LEARNING_WATERMARK_V1\nM|1\n".toByteArray()
        val encrypted = LearningWatermarkVaultCodec.encrypt(plaintext, key)

        assertContentEquals(plaintext, LearningWatermarkVaultCodec.decrypt(encrypted, key))
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val encrypted = LearningWatermarkVaultCodec.encrypt("cursor".toByteArray(), aesKey())
        assertFails { LearningWatermarkVaultCodec.decrypt(encrypted, aesKey()) }
    }

    @Test
    fun tamperingIsRejected() {
        val key = aesKey()
        val encrypted = LearningWatermarkVaultCodec.encrypt("cursor".toByteArray(), key)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertFails { LearningWatermarkVaultCodec.decrypt(encrypted, key) }
    }

    @Test
    fun oversizedPlaintextIsRejected() {
        val oversized = ByteArray(LearningWatermarkVaultCodec.MAX_PLAINTEXT_BYTES + 1) { 1 }
        assertFails { LearningWatermarkVaultCodec.encrypt(oversized, aesKey()) }
    }

    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
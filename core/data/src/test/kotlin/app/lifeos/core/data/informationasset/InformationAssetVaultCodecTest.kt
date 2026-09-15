package app.lifeos.core.data.informationasset

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class InformationAssetVaultCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")

    @Test
    fun `vault round trip restores exact plaintext`() {
        val plaintext = "revision-safe-information-asset".toByteArray()

        val encrypted = InformationAssetVaultCodec.encrypt(plaintext, key)
        val decrypted = InformationAssetVaultCodec.decrypt(encrypted, key)

        assertFalse(encrypted.contentEquals(plaintext))
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `tampered ciphertext is rejected by AES GCM`() {
        val encrypted = InformationAssetVaultCodec.encrypt("protected".toByteArray(), key)
        val tampered = encrypted.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        assertFailsWith<Exception> {
            InformationAssetVaultCodec.decrypt(tampered, key)
        }
    }

    @Test
    fun `wrong key cannot decrypt container`() {
        val encrypted = InformationAssetVaultCodec.encrypt("protected".toByteArray(), key)
        val otherKey = SecretKeySpec(ByteArray(32) { 0x55.toByte() }, "AES")

        assertFailsWith<Exception> {
            InformationAssetVaultCodec.decrypt(encrypted, otherKey)
        }
    }

    @Test
    fun `oversized plaintext is rejected before encryption`() {
        val oversized = ByteArray(InformationAssetVaultCodec.MAX_PLAINTEXT_BYTES + 1)

        assertFailsWith<IllegalArgumentException> {
            InformationAssetVaultCodec.encrypt(oversized, key)
        }
    }
}

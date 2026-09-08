package app.lifeos.core.data.field

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class FieldSnapshotVaultCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index * 7 + 3).toByte() }, "AES")

    @Test
    fun `encrypted snapshot envelope round trips exactly`() {
        val plaintext = "LIFEOS_FIELD_SNAPSHOT_V1\nexample".encodeToByteArray()

        val encrypted = FieldSnapshotVaultCodec.encrypt(plaintext, key)
        val decoded = FieldSnapshotVaultCodec.decrypt(encrypted, key)

        assertContentEquals(plaintext, decoded)
    }

    @Test
    fun `wrong key cannot decrypt snapshot`() {
        val encrypted = FieldSnapshotVaultCodec.encrypt("snapshot".encodeToByteArray(), key)
        val wrongKey = SecretKeySpec(ByteArray(32) { index -> (index * 11 + 1).toByte() }, "AES")

        assertFails { FieldSnapshotVaultCodec.decrypt(encrypted, wrongKey) }
    }

    @Test
    fun `ciphertext tampering is rejected`() {
        val encrypted = FieldSnapshotVaultCodec.encrypt("snapshot".encodeToByteArray(), key).copyOf()
        encrypted[encrypted.lastIndex] = (encrypted.last() xor 0x01)

        assertFails { FieldSnapshotVaultCodec.decrypt(encrypted, key) }
    }

    @Test
    fun `oversized plaintext is rejected before encryption`() {
        val oversized = ByteArray(FieldSnapshotVaultCodec.MAX_PLAINTEXT_BYTES + 1)

        assertFails { FieldSnapshotVaultCodec.encrypt(oversized, key) }
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
}

package app.lifeos.core.data.world

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class WorldFormulaVaultCodecTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index * 13 + 5).toByte() }, "AES")

    @Test
    fun `encrypted world snapshot envelope round trips exactly`() {
        val plaintext = "WORLD_FORMULA_SNAPSHOT_V1\nexample".encodeToByteArray()

        val encrypted = WorldFormulaVaultCodec.encrypt(plaintext, key)
        val decoded = WorldFormulaVaultCodec.decrypt(encrypted, key)

        assertContentEquals(plaintext, decoded)
    }

    @Test
    fun `wrong key cannot decrypt world snapshot`() {
        val encrypted = WorldFormulaVaultCodec.encrypt("world-snapshot".encodeToByteArray(), key)
        val wrongKey = SecretKeySpec(ByteArray(32) { index -> (index * 17 + 1).toByte() }, "AES")

        assertFails { WorldFormulaVaultCodec.decrypt(encrypted, wrongKey) }
    }

    @Test
    fun `ciphertext tampering is rejected`() {
        val encrypted = WorldFormulaVaultCodec.encrypt("world-snapshot".encodeToByteArray(), key).copyOf()
        encrypted[encrypted.lastIndex] = (encrypted.last() xor 0x01)

        assertFails { WorldFormulaVaultCodec.decrypt(encrypted, key) }
    }

    @Test
    fun `oversized plaintext is rejected before encryption`() {
        val oversized = ByteArray(WorldFormulaVaultCodec.MAX_PLAINTEXT_BYTES + 1)

        assertFails { WorldFormulaVaultCodec.encrypt(oversized, key) }
    }

    @Test
    fun `truncated container is rejected by envelope length validation`() {
        val encrypted = WorldFormulaVaultCodec.encrypt("world-snapshot".encodeToByteArray(), key)
        val truncated = encrypted.copyOf(encrypted.size - 1)

        assertFails { WorldFormulaVaultCodec.decrypt(truncated, key) }
    }

    private infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
}

package app.lifeos.core.data.security

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class VersionedPathBoundVaultSupportTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")

    @Test
    fun `container preserves versioned world vault wire layout and round trips`() {
        val plaintext = "world-equation-pack-payload".toByteArray()
        val aad = "world-equation-pack-vault/example.weqpack".toByteArray()

        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            containerVersion = 1,
            codecVersion = 7,
            maxPlaintextBytes = 1024,
            associatedData = aad,
        )

        DataInputStream(ByteArrayInputStream(container)).use { data ->
            assertEquals(1, data.readInt())
            assertEquals(7, data.readInt())
            val ivLength = data.readInt()
            assert(ivLength in 12..32)
            data.skipBytes(ivLength)
            val ciphertextLength = data.readInt()
            assertEquals(ciphertextLength, data.available())
        }

        assertContentEquals(
            plaintext,
            VersionedPathBoundVaultSupport.decrypt(
                container = container,
                key = key,
                expectedContainerVersion = 1,
                expectedCodecVersion = 7,
                maxPlaintextBytes = 1024,
                associatedData = aad,
            ),
        )
    }

    @Test
    fun `path bound aad rejects relocated ciphertext`() {
        val plaintext = "payload".toByteArray()
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            containerVersion = 1,
            codecVersion = 1,
            maxPlaintextBytes = 1024,
            associatedData = "vault/a.entry".toByteArray(),
        )

        assertFails {
            VersionedPathBoundVaultSupport.decrypt(
                container = container,
                key = key,
                expectedContainerVersion = 1,
                expectedCodecVersion = 1,
                maxPlaintextBytes = 1024,
                associatedData = "vault/b.entry".toByteArray(),
            )
        }
    }

    @Test
    fun `codec version mismatch fails closed before decrypt`() {
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = "payload".toByteArray(),
            key = key,
            containerVersion = 1,
            codecVersion = 3,
            maxPlaintextBytes = 1024,
            associatedData = "vault/a.entry".toByteArray(),
        )

        assertFails {
            VersionedPathBoundVaultSupport.decrypt(
                container = container,
                key = key,
                expectedContainerVersion = 1,
                expectedCodecVersion = 4,
                maxPlaintextBytes = 1024,
                associatedData = "vault/a.entry".toByteArray(),
            )
        }
    }
}

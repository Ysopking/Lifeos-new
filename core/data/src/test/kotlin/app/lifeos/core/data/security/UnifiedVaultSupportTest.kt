package app.lifeos.core.data.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class UnifiedVaultSupportTest {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 11).toByte() }, "AES")

    @Test
    fun `legacy V1 adapter remains byte-layout compatible and round trips`() {
        val plaintext = "legacy-payload".toByteArray()
        val aad = "ledger:owner-policy".toByteArray()
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = plaintext,
            key = key,
            maxPlaintextBytes = 1024,
            associatedData = aad,
        )
        assertContentEquals(
            plaintext,
            EncryptedLedgerVaultSupport.decrypt(
                container = container,
                key = key,
                maxPlaintextBytes = 1024,
                associatedData = aad,
            ),
        )
    }

    @Test
    fun `legacy V1 aad rejects relocation`() {
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = "payload".toByteArray(),
            key = key,
            maxPlaintextBytes = 1024,
            associatedData = "ledger:a".toByteArray(),
        )
        assertFails {
            EncryptedLedgerVaultSupport.decrypt(
                container = container,
                key = key,
                maxPlaintextBytes = 1024,
                associatedData = "ledger:b".toByteArray(),
            )
        }
    }

    @Test
    fun `truncated legacy container fails closed`() {
        val container = EncryptedLedgerVaultSupport.encrypt(
            plaintext = "payload".toByteArray(),
            key = key,
            maxPlaintextBytes = 1024,
        )
        assertFails {
            EncryptedLedgerVaultSupport.decrypt(
                container = container.copyOf(container.size - 1),
                key = key,
                maxPlaintextBytes = 1024,
            )
        }
    }

    @Test
    fun `invalid legacy iv length fails before cipher use`() {
        val malformed = ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeInt(4)
                data.write(ByteArray(4))
                data.writeInt(1)
                data.writeByte(0)
            }
            output.toByteArray()
        }
        assertFails {
            EncryptedLedgerVaultSupport.decrypt(
                container = malformed,
                key = key,
                maxPlaintextBytes = 1024,
            )
        }
    }

    @Test
    fun `versioned facade rejects wrong container version`() {
        val aad = "vault/path".toByteArray()
        val container = VersionedPathBoundVaultSupport.encrypt(
            plaintext = "payload".toByteArray(),
            key = key,
            containerVersion = 2,
            codecVersion = 3,
            maxPlaintextBytes = 1024,
            associatedData = aad,
        )
        assertFails {
            VersionedPathBoundVaultSupport.decrypt(
                container = container,
                key = key,
                expectedContainerVersion = 1,
                expectedCodecVersion = 3,
                maxPlaintextBytes = 1024,
                associatedData = aad,
            )
        }
    }
}

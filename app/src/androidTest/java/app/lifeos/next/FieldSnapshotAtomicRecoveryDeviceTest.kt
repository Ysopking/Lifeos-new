package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.field.EncryptedFieldSnapshotRepository
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android Keystore + AtomicFile proof for B01 field snapshot crash safety.
 * The test uses the real encrypted repository and simulates failures at AtomicFile's write boundary.
 */
@RunWith(AndroidJUnit4::class)
class FieldSnapshotAtomicRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-09-13T14:45:00Z")

    @Test
    fun failWriteRestoresLastCommittedEncryptedSnapshot() = runBlocking {
        withIsolatedFiles("fail-write") { context, root ->
            val repository = EncryptedFieldSnapshotRepository(context)
            val snapshot = snapshot("field-atomic-fail-write")
            repository.save(snapshot)

            val vault = snapshotFile(root, snapshot.id.value)
            assertTrue("Committed field snapshot must exist", vault.isFile && vault.length() > 0L)
            val committedCiphertext = vault.readBytes()

            val atomic = AtomicFile(vault)
            val stream = atomic.startWrite()
            stream.write(byteArrayOf(0x13, 0x37, 0x00, 0x7f))
            atomic.failWrite(stream)

            assertEquals(snapshot, EncryptedFieldSnapshotRepository(context).load(snapshot.id))
            assertTrue("Rollback must restore the committed encrypted bytes", committedCiphertext.contentEquals(vault.readBytes()))
            assertFalse("Successful rollback must not leave a stale backup", File("${vault.path}.bak").exists())
        }
    }

    @Test
    fun interruptedWriteRecoversBackupAcrossFreshRepositoryInstance() = runBlocking {
        withIsolatedFiles("process-death") { context, root ->
            val repository = EncryptedFieldSnapshotRepository(context)
            val snapshot = snapshot("field-atomic-process-death")
            repository.save(snapshot)

            val vault = snapshotFile(root, snapshot.id.value)
            val committedCiphertext = vault.readBytes()
            val atomic = AtomicFile(vault)
            val stream = atomic.startWrite()
            stream.write(byteArrayOf(0x01, 0x02, 0x03))
            stream.flush()
            stream.close()

            val backup = File("${vault.path}.bak")
            assertTrue("Interrupted AtomicFile write must retain the previous committed backup", backup.isFile)
            assertFalse("Interrupted base must not equal committed ciphertext", committedCiphertext.contentEquals(vault.readBytes()))

            val recovered = EncryptedFieldSnapshotRepository(context).load(snapshot.id)
            assertEquals(snapshot, recovered)
            assertTrue("Fresh load must restore the previous committed encrypted bytes", committedCiphertext.contentEquals(vault.readBytes()))
            assertFalse("Recovery must consume the stale backup", backup.exists())
        }
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "v17-field-snapshot-atomic-$suffix-${System.nanoTime()}"
        )
        assertFalse(root.exists())
        assertTrue(root.mkdirs())
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        }
        try {
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun snapshot(photonId: String) = FieldConvergenceEngine().converge(
        DefaultPhotonFieldRequestFactory().create(
            Photon(
                id = PhotonId(photonId),
                content = "atomic encrypted field snapshot recovery proof",
                semanticMass = 1.0,
                energy = 0.8,
                confidence = 0.95,
                provenance = Provenance(
                    source = "instrumentation-test",
                    actor = "FieldSnapshotAtomicRecoveryDeviceTest",
                    createdAt = now,
                ),
            )
        )
    ).snapshot

    private fun snapshotFile(root: File, snapshotId: String): File {
        val digest = snapshotId.removePrefix("snapshot:")
        require(digest.matches(Regex("[0-9a-f]{64}")))
        return root.resolve("field-snapshot-vault/$digest.fsnapshot")
    }
}

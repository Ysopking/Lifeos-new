package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineCycleState
import app.lifeos.core.runtime.boot.BootEngineCycleStoreHealth
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootEngineCyclePointerRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun corruptActivePointerIsQuarantinedAndUniquelyReconstructed() = runBlocking {
        withIsolatedFiles("active-rebuild") { context, root ->
            val repository = EncryptedBootEngineCycleRepository(context)
            val cycle = preparedCycle("active-rebuild")
            assertTrue(repository.create(cycle))

            val vault = root.resolve("boot-engine-cycle-vault")
            val records = vault.resolve("records")
            val record = records.listFiles().orEmpty().single { it.name.endsWith(".bcycle") }
            val recordBefore = record.readBytes()

            vault.resolve("active.bcycle").writeBytes(byteArrayOf(1, 2, 3, 4))

            val report = repository.loadReport()

            assertEquals(BootEngineCycleStoreHealth.REPAIRED, report.health)
            assertEquals(cycle, report.activeCycle)
            assertTrue("active-pointer-quarantined" in report.repairActions)
            assertTrue("active-pointer-reconstructed" in report.repairActions)
            assertArrayEquals(recordBefore, record.readBytes())
            assertTrue(
                vault.resolve("quarantine").listFiles().orEmpty()
                    .any { it.name.startsWith("active.bcycle.") && it.name.endsWith(".corrupt") }
            )
            assertEquals(cycle, repository.loadActive())
        }
    }

    @Test
    fun corruptCommittedPointerIsReconstructedFromHighestCommittedCycle() = runBlocking {
        withIsolatedFiles("committed-rebuild") { context, root ->
            val repository = EncryptedBootEngineCycleRepository(context)
            val prepared = preparedCycle("committed-rebuild")
            assertTrue(repository.create(prepared))
            val evaluated = prepared.evaluated(
                requestId = "request-committed-rebuild",
                snapshotId = "snapshot-committed-rebuild",
            )
            assertTrue(repository.compareAndSet(prepared.fingerprint, evaluated))
            val committed = evaluated.committed(headRevision = 7L)
            assertTrue(repository.compareAndSet(evaluated.fingerprint, committed))
            assertEquals(BootEngineCycleState.COMMITTED, repository.loadLatestCommitted()?.state)

            val vault = root.resolve("boot-engine-cycle-vault")
            vault.resolve("latest-committed.bcycle").writeBytes(byteArrayOf(9, 8, 7))

            val report = repository.loadReport()

            assertEquals(BootEngineCycleStoreHealth.REPAIRED, report.health)
            assertNull(report.activeCycle)
            assertEquals(committed, report.latestCommitted)
            assertTrue("committed-pointer-quarantined" in report.repairActions)
            assertTrue("committed-pointer-reconstructed" in report.repairActions)
        }
    }

    @Test
    fun corruptPointerWithoutRecoverableRecordDegradesInsteadOfDeletingVault() = runBlocking {
        withIsolatedFiles("no-record") { context, root ->
            val repository = EncryptedBootEngineCycleRepository(context)
            val cycle = preparedCycle("no-record")
            assertTrue(repository.create(cycle))

            val vault = root.resolve("boot-engine-cycle-vault")
            val records = vault.resolve("records")
            records.listFiles().orEmpty().forEach { assertTrue(it.delete()) }
            vault.resolve("active.bcycle").writeBytes(byteArrayOf(4, 3, 2, 1))

            val report = repository.loadReport()

            assertEquals(BootEngineCycleStoreHealth.REPAIRED, report.health)
            assertNull(report.activeCycle)
            assertTrue("active-pointer-cleared-no-active-cycle" in report.repairActions)
            assertTrue(vault.isDirectory)
            assertTrue(records.isDirectory)
            assertFalse(vault.resolve("active.bcycle").exists())
        }
    }

    private fun preparedCycle(seed: String): BootEngineCycle {
        val cycleId = CognitiveCycleId("cycle-$seed")
        val frozen = BootEngineFrozenInputs(
            representationSnapshotId = "representation-$seed",
            strategySnapshotId = "strategy-$seed",
            equationVersion = "equation-$seed",
            resourceSnapshotId = "resource-$seed",
        )
        val context = WorldFormulaCycleContext(
            cycleId = cycleId,
            previousWorldSnapshotId = null,
            representationSnapshotId = frozen.representationSnapshotId,
            strategySnapshotId = frozen.strategySnapshotId,
            equationVersion = frozen.equationVersion,
            resourceSnapshotId = frozen.resourceSnapshotId,
        )
        return BootEngineCycle.prepared(
            cycleId = cycleId,
            context = context,
            frozenInputs = frozen,
        )
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "b502-boot-cycle-recovery-" + suffix + "-" + System.nanoTime()
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
}

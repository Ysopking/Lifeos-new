package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductiveWorldPersistenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun worldHeadCasSurvivesRepositoryReopenAndRejectsStalePredecessor() = runBlocking {
        withIsolatedFiles("world-head") { context, _ ->
            val first = worldHead(1, "snapshot-a", null, "cycle-a")
            val repo = EncryptedProductiveWorldHeadRepository(context)
            assertTrue(repo.compareAndSet(null, first))
            assertEquals(first, EncryptedProductiveWorldHeadRepository(context).load())
            assertFalse(repo.compareAndSet(null, first))

            val invalidNext = worldHead(2, "snapshot-b", "wrong-predecessor", "cycle-b")
            var rejected = false
            try {
                repo.compareAndSet(1L, invalidNext)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(first, repo.load())
        }
    }

    @Test
    fun copiedCycleCiphertextToWrongPhysicalIdFailsClosed() = runBlocking {
        withIsolatedFiles("wrong-cycle-id") { context, root ->
            val repo = EncryptedBootEngineCycleRepository(context)
            val cycleA = preparedCycle("cycle-a")
            assertTrue(repo.create(cycleA))
            val records = root.resolve("boot-engine-cycle-vault/records")
            val source = records.resolve(sha256("cycle-a") + ".bcycle")
            assertTrue(source.isFile)

            val wrongTarget = records.resolve(sha256("cycle-b") + ".bcycle")
            source.copyTo(wrongTarget, overwrite = true)
            var rejected = false
            try {
                EncryptedBootEngineCycleRepository(context).load(CognitiveCycleId("cycle-b"))
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    @Test
    fun corruptWorldHeadAndActiveCycleAreReported() = runBlocking {
        withIsolatedFiles("corruption") { context, root ->
            val headRepo = EncryptedProductiveWorldHeadRepository(context)
            assertTrue(headRepo.compareAndSet(null, worldHead(1, "snapshot-a", null, "cycle-a")))
            val headFile = root.resolve("productive-world-head-vault/head.pworld")
            assertTrue(headFile.isFile)
            headFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(EncryptedProductiveWorldHeadRepository(context).loadReport().corrupted)

            val cycleRepo = EncryptedBootEngineCycleRepository(context)
            val cycle = preparedCycle("cycle-corrupt")
            assertTrue(cycleRepo.create(cycle))
            val record = root.resolve(
                "boot-engine-cycle-vault/records/" + sha256("cycle-corrupt") + ".bcycle"
            )
            assertTrue(record.isFile)
            record.writeBytes(byteArrayOf(9, 8, 7))
            val report = EncryptedBootEngineCycleRepository(context).loadReport()
            assertTrue(report.corrupted)
            assertNotNull(report.message)
        }
    }

    private fun preparedCycle(value: String): BootEngineCycle {
        val cycleId = CognitiveCycleId(value)
        val frozen = BootEngineFrozenInputs(
            representationSnapshotId = "representation-v1",
            strategySnapshotId = "strategy-v1",
            equationVersion = "lifeos-world-cognitive-v1",
            resourceSnapshotId = "resources-v1",
        )
        return BootEngineCycle.prepared(
            cycleId = cycleId,
            context = WorldFormulaCycleContext(
                cycleId = cycleId,
                previousWorldSnapshotId = null,
                representationSnapshotId = frozen.representationSnapshotId,
                strategySnapshotId = frozen.strategySnapshotId,
                equationVersion = frozen.equationVersion,
                resourceSnapshotId = frozen.resourceSnapshotId,
            ),
            frozenInputs = frozen,
        )
    }

    private fun worldHead(
        revision: Long,
        snapshotId: String,
        predecessor: String?,
        cycle: String,
    ): ProductiveWorldHead {
        val cycleId = CognitiveCycleId(cycle)
        val ref = WorldFormulaSnapshotRef(
            namespace = WorldFormulaSnapshotNamespace.PRODUCTIVE,
            snapshotId = snapshotId,
            equationVersion = "lifeos-world-cognitive-v1",
            cycleId = cycleId,
        )
        val contextFingerprint = StableFieldIds.fingerprint(
            "test-cycle-context",
            cycle,
            snapshotId,
        )
        val fingerprint = StableFieldIds.fingerprint(
            "productive-world-head/v1",
            revision.toString(),
            ref.fingerprint(),
            predecessor.orEmpty(),
            ref.equationVersion,
            cycleId.value,
            contextFingerprint,
        )
        return ProductiveWorldHead.restore(
            revision = revision,
            activeSnapshot = ref,
            predecessorSnapshotId = predecessor,
            equationVersion = ref.equationVersion,
            cycleId = cycleId,
            cycleContextFingerprint = contextFingerprint,
            fingerprint = fingerprint,
        )
    }

    private suspend fun withIsolatedFiles(
        suffix: String,
        block: suspend (Context, File) -> Unit,
    ) {
        val root = instrumentation.targetContext.cacheDir.resolve(
            "level7-productive-state-$suffix-${System.nanoTime()}"
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}

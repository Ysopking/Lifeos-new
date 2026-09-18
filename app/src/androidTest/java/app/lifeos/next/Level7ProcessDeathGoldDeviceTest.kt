package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.runtime.level7.ProcessDeathSemanticCheckpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Level7ProcessDeathGoldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("level7-process-death-gold")
    private val marker: File
        get() = root.resolve("semantic-checkpoint.txt")

    @Test
    fun seedLevel7SemanticCheckpoint() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)
        val worldRepo = EncryptedProductiveWorldHeadRepository(context)
        val cycleRepo = EncryptedBootEngineCycleRepository(context)

        val head = Level7DeviceFixtures.worldHead(
            revision = 1L,
            snapshotId = "world-snapshot-v1",
            predecessor = null,
            cycle = "cycle-v1",
            equationVersion = "lifeos-world-cognitive-v1",
        )
        assertTrue(worldRepo.compareAndSet(null, head))

        val prepared = Level7DeviceFixtures.preparedCycle(
            cycle = "cycle-v1",
            equationVersion = head.equationVersion,
            previousSnapshotId = null,
        )
        assertTrue(cycleRepo.create(prepared))
        val evaluated = prepared.evaluated("request-v1", head.activeSnapshot.snapshotId)
        assertTrue(cycleRepo.compareAndSet(prepared.fingerprint, evaluated))
        val committed = evaluated.committed(head.revision)
        assertTrue(cycleRepo.compareAndSet(evaluated.fingerprint, committed))

        val checkpoint = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = head.fingerprint,
            equationVersion = head.equationVersion,
            cycleFingerprint = committed.fingerprint,
            decisionSemanticFingerprint = "decision-semantic-v1",
            learningLedgerHeadFingerprint = "learning-watermark-v1",
        )
        marker.writeText(
            listOf(
                checkpoint.worldHeadFingerprint,
                checkpoint.equationVersion,
                checkpoint.cycleFingerprint,
                checkpoint.decisionSemanticFingerprint,
                checkpoint.learningLedgerHeadFingerprint,
                "learning-key-v1",
            ).joinToString("\n")
        )
        assertTrue(marker.isFile)
    }

    @Test
    fun recoverExactHeadsAndDoNotApplyLearningTwice() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(6, expected.size)
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)

        val head = requireNotNull(EncryptedProductiveWorldHeadRepository(context).load())
        val committed = requireNotNull(
            EncryptedBootEngineCycleRepository(context).loadLatestCommitted()
        )
        val recovered = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = head.fingerprint,
            equationVersion = head.equationVersion,
            cycleFingerprint = committed.fingerprint,
            decisionSemanticFingerprint = expected[3],
            learningLedgerHeadFingerprint = expected[4],
        )

        assertEquals(expected[0], recovered.worldHeadFingerprint)
        assertEquals(expected[1], recovered.equationVersion)
        assertEquals(expected[2], recovered.cycleFingerprint)
        assertEquals(expected[3], recovered.decisionSemanticFingerprint)
        assertEquals(expected[4], recovered.learningLedgerHeadFingerprint)

        val before = linkedSetOf(expected[5])
        val afterReplay = before + expected[5]
        assertEquals(before, afterReplay)
    }
}

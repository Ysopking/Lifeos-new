package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.boot.EncryptedBootEngineCycleRepository
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.data.learning.EncryptedLearningWatermarkRepository
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.learning.LearningSourceId
import app.lifeos.core.runtime.learning.LearningWatermarkLoadResult
import app.lifeos.core.runtime.learning.LearningWatermarkState
import app.lifeos.core.runtime.learning.LearningWatermarkWriteResult
import app.lifeos.core.runtime.level7.ProcessDeathSemanticCheckpoint
import app.lifeos.core.runtime.world.WorldEquationHead
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
        val equationRepo = EncryptedWorldEquationHeadRepository(context)
        val cycleRepo = EncryptedBootEngineCycleRepository(context)

        val head = Level7DeviceFixtures.worldHead(
            revision = 1L,
            snapshotId = "world-snapshot-v1",
            predecessor = null,
            cycle = "cycle-v1",
            equationVersion = "lifeos-world-cognitive-v1",
        )
        assertTrue(worldRepo.compareAndSet(null, head))
        val equationHead = WorldEquationHead.create(
            revision = 1L,
            activeEquationVersion = head.equationVersion,
            predecessorEquationVersion = null,
            sourcePromotionId = "level7-process-death-seed",
        )
        assertTrue(equationRepo.compareAndSet(null, equationHead))

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

        val decisionReport = EncryptedConvergenceDecisionCheckpointRepository(
            instrumentation.targetContext
        ).loadReport()
        assertTrue(decisionReport.unreadableEntries.isEmpty())
        val decision = requireNotNull(
            decisionReport.checkpoints.firstOrNull {
                it.workingSetFingerprint == "v5-android-working-set-v1"
            }
        ) {
            "Level-7 ProcessDeath GOLD requires the real seeded convergence checkpoint"
        }

        val learningSource = LearningSourceId("level7-device-learning")
        val learningEventFingerprint = StableFieldIds.fingerprint(
            "level7-device-learning-event/v1",
            "event-1",
        )
        val learningState = LearningWatermarkState.empty().advance(
            sourceId = learningSource,
            sequence = 1L,
            eventId = "level7-device-event-1",
            eventFingerprint = learningEventFingerprint,
        )
        val learningRepo = EncryptedLearningWatermarkRepository(context)
        val learningWrite = learningRepo.compareAndSet(null, learningState)
        assertTrue(learningWrite is LearningWatermarkWriteResult.Saved)
        val learningFingerprint = StableFieldIds.fingerprint(
            "level7-learning-watermark-head/v1",
            learningState.revision.toString(),
            learningState.sources.single().logicalKey.value,
        )

        val checkpoint = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = head.fingerprint,
            worldEquationHeadFingerprint = equationHead.fingerprint,
            equationVersion = head.equationVersion,
            cycleFingerprint = committed.fingerprint,
            decisionSemanticFingerprint = decision.contentFingerprint(),
            learningLedgerHeadFingerprint = learningFingerprint,
        )
        marker.writeText(
            listOf(
                checkpoint.worldHeadFingerprint,
                checkpoint.worldEquationHeadFingerprint,
                checkpoint.equationVersion,
                checkpoint.cycleFingerprint,
                checkpoint.decisionSemanticFingerprint,
                checkpoint.learningLedgerHeadFingerprint,
                learningState.sources.single().logicalKey.value,
                decision.id.value,
            ).joinToString("\n")
        )
        assertTrue(marker.isFile)
    }

    @Test
    fun recoverExactHeadsAndDoNotApplyLearningTwice() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(8, expected.size)
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)

        val head = requireNotNull(EncryptedProductiveWorldHeadRepository(context).load())
        val equationHead = requireNotNull(
            EncryptedWorldEquationHeadRepository(context).load()
        )
        val committed = requireNotNull(
            EncryptedBootEngineCycleRepository(context).loadLatestCommitted()
        )

        val decision = requireNotNull(
            EncryptedConvergenceDecisionCheckpointRepository(instrumentation.targetContext)
                .load(ConvergenceDecisionCheckpointId(expected[7]))
        )
        assertEquals(expected[3], decision.contentFingerprint())

        val learningLoaded = EncryptedLearningWatermarkRepository(context).load()
        assertTrue(learningLoaded is LearningWatermarkLoadResult.Loaded)
        val learningState = (learningLoaded as LearningWatermarkLoadResult.Loaded).state
        val learningSource = learningState.sources.single()
        assertEquals(expected[6], learningSource.logicalKey.value)
        val learningFingerprint = StableFieldIds.fingerprint(
            "level7-learning-watermark-head/v1",
            learningState.revision.toString(),
            learningSource.logicalKey.value,
        )

        val recovered = ProcessDeathSemanticCheckpoint(
            worldHeadFingerprint = head.fingerprint,
            worldEquationHeadFingerprint = equationHead.fingerprint,
            equationVersion = head.equationVersion,
            cycleFingerprint = committed.fingerprint,
            decisionSemanticFingerprint = decision.contentFingerprint(),
            learningLedgerHeadFingerprint = learningFingerprint,
        )

        assertEquals(expected[0], recovered.worldHeadFingerprint)
        assertEquals(expected[1], recovered.worldEquationHeadFingerprint)
        assertEquals(expected[2], recovered.equationVersion)
        assertEquals(expected[3], recovered.cycleFingerprint)
        assertEquals(expected[4], recovered.decisionSemanticFingerprint)
        assertEquals(expected[5], recovered.learningLedgerHeadFingerprint)

        var duplicateLearningRejected = false
        try {
            learningState.advance(
                sourceId = learningSource.sourceId,
                sequence = learningSource.sequence,
                eventId = learningSource.eventId,
                eventFingerprint = learningSource.eventFingerprint,
            )
        } catch (_: IllegalArgumentException) {
            duplicateLearningRejected = true
        }
        assertTrue("same logical learning event must not advance the watermark twice", duplicateLearningRejected)
    }
}

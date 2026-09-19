package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.world.EncryptedProductiveWorldHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldEquationHead
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Level7WorldEquationRollbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("level7-equation-rollback")
    private val marker: File
        get() = root.resolve("rollback-expected.txt")

    @Test
    fun seedDegradedTrialHead() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)
        val repo = EncryptedProductiveWorldHeadRepository(context)
        val equationRepo = EncryptedWorldEquationHeadRepository(context)
        val baselineSpec = CognitiveWorldEquationProfile().spec.copy(version = "v17")
        val trialSpec = baselineSpec.copy(version = "v18")
        val equations = InMemoryWorldEquationRegistry(listOf(baselineSpec, trialSpec))
        val seededEquation = WorldEquationHead.create(
            revision = 1L,
            activeEquationVersion = "v17",
            predecessorEquationVersion = null,
            sourcePromotionId = "bootstrap:device-v17",
        )
        assertTrue(equationRepo.compareAndSet(null, seededEquation))
        val promotedEquation = WorldEquationHead.create(
            revision = 2L,
            activeEquationVersion = "v18",
            predecessorEquationVersion = "v17",
            sourcePromotionId = "trial:v18",
        )
        assertTrue(equationRepo.compareAndSet(1L, promotedEquation))
        assertEquals("v18", promotedEquation.activeEquationVersion)

        val v17 = Level7DeviceFixtures.worldHead(
            revision = 1L,
            snapshotId = "world-v17",
            predecessor = null,
            cycle = "cycle-v17",
            equationVersion = "v17",
        )
        assertTrue(repo.compareAndSet(null, v17))
        val v18 = Level7DeviceFixtures.worldHead(
            revision = 2L,
            snapshotId = "world-v18",
            predecessor = v17.activeSnapshot.snapshotId,
            cycle = "cycle-v18",
            equationVersion = "v18",
        )
        assertTrue(repo.compareAndSet(1L, v18))

        val rollback = Level7DeviceFixtures.worldHead(
            revision = 3L,
            snapshotId = v17.activeSnapshot.snapshotId,
            predecessor = v18.activeSnapshot.snapshotId,
            cycle = "cycle-rollback-v17",
            equationVersion = v17.equationVersion,
        )
        marker.writeText(
            listOf(
                rollback.activeSnapshot.snapshotId,
                rollback.equationVersion,
                rollback.fingerprint,
                promotedEquation.fingerprint,
                promotedEquation.predecessorEquationVersion.orEmpty(),
            ).joinToString("\n")
        )
    }

    @Test
    fun rollbackAfterProcessDeathRestoresExactV17WorldHead() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(5, expected.size)
        val context = Level7DeviceFixtures.context(instrumentation.targetContext, root)
        val repo = EncryptedProductiveWorldHeadRepository(context)
        val degraded = requireNotNull(repo.load())
        assertEquals("v18", degraded.equationVersion)

        val baselineSpec = CognitiveWorldEquationProfile().spec.copy(version = "v17")
        val trialSpec = baselineSpec.copy(version = "v18")
        val equationRepo = EncryptedWorldEquationHeadRepository(context)
        val degradedEquation = requireNotNull(equationRepo.load())
        assertEquals("v18", degradedEquation.activeEquationVersion)
        assertEquals(expected[3], degradedEquation.fingerprint)
        assertEquals(expected[4], degradedEquation.predecessorEquationVersion)

        val equationAuthority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baselineSpec, trialSpec)),
            heads = equationRepo,
            baseline = baselineSpec,
        )
        val restoredEquation = equationAuthority.rollbackToPredecessor(
            expectedCurrentVersion = "v18",
            rollbackDecisionId = "device:rollback-v17",
        )
        assertEquals("v17", restoredEquation.activeEquationVersion)
        assertEquals("v18", restoredEquation.predecessorEquationVersion)

        val rollback = Level7DeviceFixtures.worldHead(
            revision = degraded.revision + 1L,
            snapshotId = expected[0],
            predecessor = degraded.activeSnapshot.snapshotId,
            cycle = "cycle-rollback-v17",
            equationVersion = expected[1],
        )
        assertEquals(expected[2], rollback.fingerprint)
        assertTrue(repo.compareAndSet(degraded.revision, rollback))

        val restored = requireNotNull(EncryptedProductiveWorldHeadRepository(context).load())
        assertEquals(expected[0], restored.activeSnapshot.snapshotId)
        assertEquals(expected[1], restored.equationVersion)
        assertEquals(expected[2], restored.fingerprint)

        val durableEquation = requireNotNull(
            EncryptedWorldEquationHeadRepository(context).load()
        )
        assertEquals("v17", durableEquation.activeEquationVersion)
        assertEquals(restored.equationVersion, durableEquation.activeEquationVersion)
    }
}

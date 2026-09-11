package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointWriteResult
import app.lifeos.core.runtime.convergence.ConvergenceDecisionEngine
import app.lifeos.core.runtime.convergence.ConvergenceDecisionPolicy
import app.lifeos.core.runtime.convergence.ConvergenceDecisionRequest
import app.lifeos.core.runtime.convergence.ConvergenceDomainInput
import app.lifeos.core.runtime.convergence.ConvergenceCoordinator
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.convergence.DomainConvergenceRunner
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvergenceDecisionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun seedConvergenceDecisionCheckpoint() {
        runBlocking {
            val repository = EncryptedConvergenceDecisionCheckpointRepository(instrumentation.targetContext)
            val expected = fixture()
            val write = repository.save(expected)
            assertTrue(
                write is ConvergenceDecisionCheckpointWriteResult.Stored ||
                    write is ConvergenceDecisionCheckpointWriteResult.Duplicate
            )
            val report = repository.loadReport()
            assertTrue(report.unreadableEntries.isEmpty())
            assertEquals(expected, repository.load(expected.id))
            assertEquals(1, report.checkpoints.count { it.id == expected.id })
            assertEquals(1, checkpointFiles().count { it.name == fileName(expected) })
        }
    }

    @Test
    fun recoverConvergenceDecisionCheckpoint() {
        runBlocking {
            val repository = EncryptedConvergenceDecisionCheckpointRepository(instrumentation.targetContext)
            val expected = fixture()
            val loaded = repository.load(expected.id)
            assertEquals("V5 checkpoint must survive target-process kill", expected, loaded)

            val coordinator = DurableConvergenceDecisionCoordinator(
                repository = repository,
                policy = POLICY,
            )
            val verified = coordinator.loadVerified()
            assertTrue(verified.any { it == expected })
            val beforeCount = checkpointFiles().size
            val replay = repository.save(expected)
            assertTrue(replay is ConvergenceDecisionCheckpointWriteResult.Duplicate)
            assertEquals(beforeCount, checkpointFiles().size)
            assertEquals(1, repository.loadReport().checkpoints.count { it.id == expected.id })
        }
    }

    private fun fixture(): ConvergenceDecisionCheckpoint {
        val photon = Photon(
            id = PhotonId(PHOTON_ID),
            revision = 1,
            content = "V5 Android convergence checkpoint recovery",
            confidence = 0.95,
            semanticMass = 1.0,
            energy = 0.8,
            provenance = Provenance(
                source = "v5-convergence-device-test",
                actor = "ConvergenceDecisionDeviceTest",
                createdAt = AT,
            ),
            tags = setOf("v5-convergence-checkpoint"),
        )
        val fieldRequest = DefaultPhotonFieldRequestFactory().create(photon)
        val crossRequest = CrossDomainConvergenceRequest(
            domains = listOf(ConvergenceDomainInput(fieldRequest))
        )
        val convergence = ConvergenceCoordinator(
            DomainConvergenceRunner { FieldConvergenceEngine().converge(it) }
        ).coordinate(crossRequest)
        val request = ConvergenceDecisionRequest(
            source = crossRequest,
            convergence = convergence,
            workingSetFingerprint = WORKING_SET_FINGERPRINT,
        )
        val decision = ConvergenceDecisionEngine(POLICY).decide(request)
        return ConvergenceDecisionCheckpoint.create(request, decision, POLICY)
    }

    private fun checkpointFiles() = instrumentation.targetContext.filesDir
        .resolve("convergence-decision-checkpoints")
        .listFiles()
        ?.filter { it.name.endsWith(".cv5checkpoint") }
        .orEmpty()

    private fun fileName(checkpoint: ConvergenceDecisionCheckpoint): String =
        checkpoint.id.value.removePrefix("convergence-checkpoint:") + ".cv5checkpoint"

    private companion object {
        val AT: Instant = Instant.parse("2026-09-11T08:20:00Z")
        const val PHOTON_ID = "v5-convergence-device-photon"
        const val WORKING_SET_FINGERPRINT = "v5-android-working-set-v1"
        val POLICY = ConvergenceDecisionPolicy(
            minTotalScore = 0.0,
            minEvidenceScore = 0.0,
        )
    }
}

package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.evolution.EncryptedWorldEquationEvidenceRepository
import app.lifeos.core.data.world.EncryptedWorldEquationHeadRepository
import app.lifeos.core.data.world.EncryptedWorldEquationSpecRepository
import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationEvaluationProtocol
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationLifecycleState
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyMonitor
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyResult
import app.lifeos.core.runtime.evolution.WorldEquationPrimaryMetric
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationRunMetrics
import app.lifeos.core.runtime.evolution.WorldEquationShadowObservation
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.SelfStateWorldBand
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldEquationAutoEvolutionRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve(
            "world-equation-auto-evolution-recovery"
        )

    @Test
    fun promotedHeadRecoversEvidenceThenPersistentSafetyRegressionRollsBack() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = Level7DeviceFixtures.context(
            instrumentation.targetContext,
            root,
        )
        val baseline = CognitiveWorldEquationProfile().spec
        val coefficient = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = baseline.version + "-device-candidate",
            coefficients = baseline.coefficients.map {
                if (it.id == coefficient.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else {
                    it
                }
            },
        )

        val evidenceRepository = EncryptedWorldEquationEvidenceRepository(context)
        val evidenceCoordinator = WorldEquationEvidenceCoordinator(
            repository = evidenceRepository,
            evaluator = WorldEquationPromotionEvaluator(WorldEquationPromotionPolicy.V1),
        )
        val gate = WorldEquationEvolutionAdmissionGate(
            evidence = evidenceRepository,
            evaluator = WorldEquationPromotionEvaluator(WorldEquationPromotionPolicy.V1),
        )
        val heads = EncryptedWorldEquationHeadRepository(context)
        val specs = EncryptedWorldEquationSpecRepository(context)
        val authority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = heads,
            baseline = baseline,
            specs = specs,
            admissionVerifier = gate,
        )
        val baselineHead = authority.activeHead()
        authority.registerCandidate(candidate)
        val protocol = WorldEquationEvaluationProtocol(
            version = "device-recovery-protocol-v1",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )
        evidenceCoordinator.beginShadow(candidate, baseline, protocol)
        evidenceCoordinator.recordObservation(
            candidate,
            baseline,
            passingObservation(
                baseline = baseline.fingerprint(),
                candidate = candidate.fingerprint(),
                coefficientId = coefficient.id,
                runId = "device-run-1",
            ),
        )
        val promotable = evidenceCoordinator.recordObservation(
            candidate,
            baseline,
            passingObservation(
                baseline = baseline.fingerprint(),
                candidate = candidate.fingerprint(),
                coefficientId = coefficient.id,
                runId = "device-run-2",
            ),
        )
        assertEquals(WorldEquationLifecycleState.PROMOTABLE, promotable.state)

        val admission = gate.admit(candidate, baseline)
        val promotedHead = authority.promote(
            candidate = candidate,
            admission = admission,
            expectedHeadFingerprint = baselineHead.fingerprint,
        )
        assertEquals(candidate.version, promotedHead.activeEquationVersion)
        assertEquals(
            WorldEquationLifecycleState.PROMOTABLE,
            requireNotNull(evidenceRepository.load(candidate.fingerprint())).state,
        )

        // Fresh repository/registry/authority instances model a cold process reconstruction.
        val recoveredEvidence = EncryptedWorldEquationEvidenceRepository(context)
        val recoveredCoordinator = WorldEquationEvidenceCoordinator(
            repository = recoveredEvidence,
            evaluator = WorldEquationPromotionEvaluator(WorldEquationPromotionPolicy.V1),
        )
        val recoveredAuthority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = EncryptedWorldEquationHeadRepository(context),
            baseline = baseline,
            specs = EncryptedWorldEquationSpecRepository(context),
        )
        val safety = WorldEquationPostActivationSafetyMonitor(
            evidence = recoveredEvidence,
            evidenceCoordinator = recoveredCoordinator,
            authority = recoveredAuthority,
        )

        safety.reconcile()
        val recoveredActive = requireNotNull(
            recoveredEvidence.load(candidate.fingerprint())
        )
        assertEquals(WorldEquationLifecycleState.ACTIVE, recoveredActive.state)
        assertEquals(promotedHead.fingerprint, recoveredActive.activationHeadFingerprint)
        assertEquals(candidate.version, recoveredAuthority.activeVersion())

        val first = safety.observe(
            assessmentId = "device-stable",
            authorityFingerprint = fingerprint("device-authority-stable"),
            band = SelfStateWorldBand.STABLE,
            observedAt = Instant.parse("2026-09-19T08:00:00Z"),
        )
        assertTrue(first is WorldEquationPostActivationSafetyResult.Monitored)

        val second = safety.observe(
            assessmentId = "device-critical-1",
            authorityFingerprint = fingerprint("device-authority-critical-1"),
            band = SelfStateWorldBand.CRITICAL,
            observedAt = Instant.parse("2026-09-19T08:01:00Z"),
        )
        assertTrue(second is WorldEquationPostActivationSafetyResult.Monitored)

        val third = safety.observe(
            assessmentId = "device-critical-2",
            authorityFingerprint = fingerprint("device-authority-critical-2"),
            band = SelfStateWorldBand.CRITICAL,
            observedAt = Instant.parse("2026-09-19T08:02:00Z"),
        )
        assertTrue(third is WorldEquationPostActivationSafetyResult.RolledBack)

        val rolledBack = requireNotNull(recoveredEvidence.load(candidate.fingerprint()))
        assertEquals(WorldEquationLifecycleState.ROLLED_BACK, rolledBack.state)
        assertEquals(3, rolledBack.postActivationSafetyObservations.size)
        assertEquals(baseline.version, recoveredAuthority.activeVersion())

        // A second fresh authority proves both the rollback head and the evidence ledger survived.
        val finalAuthority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = EncryptedWorldEquationHeadRepository(context),
            baseline = baseline,
            specs = EncryptedWorldEquationSpecRepository(context),
        )
        assertEquals(baseline.version, finalAuthority.activeVersion())
        val finalRecord = requireNotNull(
            EncryptedWorldEquationEvidenceRepository(context).load(candidate.fingerprint())
        )
        assertEquals(WorldEquationLifecycleState.ROLLED_BACK, finalRecord.state)
        assertTrue(!finalRecord.rollbackDecisionId.isNullOrBlank())
    }

    private fun passingObservation(
        baseline: String,
        candidate: String,
        coefficientId: WorldCoefficientId,
        runId: String,
    ) = WorldEquationShadowObservation(
        caseFingerprint = fingerprint("case:" + runId),
        runId = runId,
        workloadId = "device-workload-a",
        baselineEquationFingerprint = baseline,
        candidateEquationFingerprint = candidate,
        baseline = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 5,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(coefficientId),
        ),
        candidate = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 3,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(coefficientId),
        ),
    )

    private fun fingerprint(value: String): String =
        app.lifeos.core.field.StableFieldIds.fingerprint(
            "world-equation-device-recovery-test/v1",
            value,
        )
}

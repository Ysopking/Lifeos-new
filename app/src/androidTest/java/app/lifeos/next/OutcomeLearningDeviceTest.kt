package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.learning.EncryptedLearningAdaptationRepository
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.learning.DurableLearningAdaptationLedger
import app.lifeos.core.runtime.learning.LearningAdaptation
import app.lifeos.core.runtime.learning.LearningAdaptationPlanner
import app.lifeos.core.runtime.learning.LearningAdaptationTarget
import app.lifeos.core.runtime.learning.LearningAdaptationTargetKind
import app.lifeos.core.runtime.learning.OutcomeEvidence
import app.lifeos.core.runtime.learning.OutcomeEvidenceSourceClass
import app.lifeos.core.runtime.learning.OutcomeExpectedHypothesis
import app.lifeos.core.runtime.learning.OutcomePrediction
import app.lifeos.core.runtime.learning.OutcomeScorer
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.next.kernel.KernelBootstrapStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real AndroidKeyStore/AtomicFile restart proof for the V6 adaptation ledger. */
@RunWith(AndroidJUnit4::class)
class OutcomeLearningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication
    private val marker
        get() = instrumentation.targetContext.filesDir.resolve(MARKER_FILE)

    @Test
    fun seedOutcomeLearningAdaptation() = runBlocking {
        val repository = EncryptedLearningAdaptationRepository(instrumentation.targetContext)
        val ledger = DurableLearningAdaptationLedger(repository)
        ledger.rehydrate()
        val target = LearningAdaptationTarget(
            kind = LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
            key = PROVIDER_ID,
        )
        val existing = ledger.state.value.events.firstOrNull { it.target == target }
        val adaptation = existing ?: createAdaptation(target).also { ledger.append(it) }
        val state = ledger.state.value
        val effective = ledger.effectiveValue(target, BASELINE)

        marker.writeText(
            listOf(
                adaptation.id.value,
                state.revision.toString(),
                state.fingerprint,
                java.lang.Double.toHexString(effective),
            ).joinToString("\n")
        )
        assertTrue(marker.isFile)
        assertEquals(adaptation.resultingEffectiveValue, effective, 1e-12)
    }

    @Test
    fun recoverOutcomeLearningAdaptationAfterColdStart() = runBlocking {
        val processStartup = withTimeout(30_000) {
            app.startupState.first { state ->
                state.phase == LifeOsProcessStartupPhase.READY ||
                    state.phase == LifeOsProcessStartupPhase.FAILED
            }
        }
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed during V6 recovery: ${processStartup.failure ?: "unknown"}")
        }
        val boot = withTimeout(30_000) {
            app.kernel.bootstrapState.first { state ->
                state.status == KernelBootstrapStatus.READY ||
                    state.status == KernelBootstrapStatus.DEGRADED ||
                    state.status == KernelBootstrapStatus.FAILED
            }
        }
        if (boot.status == KernelBootstrapStatus.FAILED) {
            error("Kernel boot failed during V6 recovery: ${boot.failureMessage ?: "unknown"}")
        }
        assertTrue("Kernel must boot with the persisted V6 learning ledger", boot.ready)
        assertTrue("V6 recovery marker must survive process restart", marker.isFile)
        val expected = marker.readLines()
        assertEquals(4, expected.size)

        val repository = EncryptedLearningAdaptationRepository(instrumentation.targetContext)
        val ledger = DurableLearningAdaptationLedger(repository)
        val report = ledger.rehydrate()
        val target = LearningAdaptationTarget(
            kind = LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
            key = PROVIDER_ID,
        )
        val state = ledger.state.value
        val effective = ledger.effectiveValue(target, BASELINE)

        assertTrue(report.restoredEvents >= 1)
        assertTrue(state.events.any { it.id.value == expected[0] })
        assertEquals(expected[1].toLong(), state.revision)
        assertEquals(expected[2], state.fingerprint)
        assertEquals(expected[3], java.lang.Double.toHexString(effective))
    }

    private fun createAdaptation(target: LearningAdaptationTarget): LearningAdaptation {
        val at = Instant.parse("2026-09-11T12:30:00Z")
        val prediction = OutcomePrediction.create(
            actionId = "android-recovery-action",
            decisionId = ConvergenceDecisionId("android-recovery-decision"),
            expectedHypotheses = listOf(
                OutcomeExpectedHypothesis(
                    hypothesisId = HypothesisId("android-recovery-hypothesis"),
                    confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
                )
            ),
            providerIds = listOf(PROVIDER_ID),
            fieldSnapshotFingerprints = listOf("android-field-snapshot"),
            thoughtGraphWorkingSetFingerprint = "android-working-set",
            decisionPolicyFingerprint = "android-decision-policy",
            createdAt = at,
        )
        val evidence = OutcomeEvidence.create(
            predictionId = prediction.id,
            sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
            sourceId = "android-durable-observer",
            sourceFingerprint = "android-durable-result",
            signal = OutcomeSignal(
                completion = 1.0,
                correctness = 1.0,
                usefulness = 1.0,
                policyCompliance = 1.0,
            ),
            confidence = 1.0,
            independentOfProviderIds = setOf(PROVIDER_ID),
            observedAt = at.plusSeconds(1),
            reason = "android-process-restart-proof",
        )
        val score = OutcomeScorer().score(prediction, listOf(evidence))
        return requireNotNull(
            LearningAdaptationPlanner().plan(
                target = target,
                baselineValue = BASELINE,
                currentEffectiveValue = BASELINE,
                score = score,
                createdAt = at.plusSeconds(2),
            )
        )
    }

    private companion object {
        const val MARKER_FILE = "v6-outcome-learning-recovery.marker"
        const val PROVIDER_ID = "android-recovery-provider"
        const val BASELINE = 0.60
    }
}

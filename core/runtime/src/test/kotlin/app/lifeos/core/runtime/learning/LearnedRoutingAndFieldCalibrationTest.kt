package app.lifeos.core.runtime.learning

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityRouter
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LearnedRoutingAndFieldCalibrationTest {
    private val at = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun `verified outcome changes later provider selection without mutating descriptor trust roots`() = runBlocking {
        val high = provider("provider-high", 0.70)
        val low = provider("provider-low", 0.69)
        val registry = CapabilityRegistry(listOf(high, low))
        val repository = MemoryAdaptationRepository()
        val ledger = DurableLearningAdaptationLedger(repository)
        val resolver = LearnedProviderReliabilityResolver(ledger)
        val router = LanguageGoalCapabilityRouter(registry = registry, reliability = resolver)
        val goal = queryGoal()

        assertEquals("provider-high", router.route(goal).selectedProviders.getValue(CapabilityId("knowledge.resolve")).providerId)

        val prediction = prediction("route-negative", providerIds = listOf("provider-high"))
        val score = verifiedScore(prediction, 0.0)
        val target = LearningAdaptationTarget(
            LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
            "provider-high",
        )
        val adaptation = requireNotNull(
            LearningAdaptationPlanner().plan(
                target = target,
                baselineValue = high.reliability,
                currentEffectiveValue = high.reliability,
                score = score,
                createdAt = at.plusSeconds(2),
            )
        )
        ledger.append(adaptation)

        val after = router.route(goal).selectedProviders.getValue(CapabilityId("knowledge.resolve"))
        assertEquals("provider-low", after.providerId)
        assertEquals(0.70, high.reliability)
        assertEquals(TrustLevel.HIGH, high.trustLevel)
        assertEquals(0.65, resolver.resolve(high), absoluteTolerance = 1e-12)
    }

    @Test
    fun `field calibration is an overlay and durable replay restores the exact profile`() = runBlocking {
        val repository = MemoryAdaptationRepository()
        val ledger = DurableLearningAdaptationLedger(repository)
        val calibration = LearnedFieldCalibration(ledger)
        val base = calibration.profile()
        val target = calibration.target(LearnedFieldWeightDimension.RELIABILITY)
        val prediction = prediction("field-positive")
        val score = verifiedScore(prediction, 1.0)
        val adaptation = requireNotNull(
            LearningAdaptationPlanner().plan(
                target = target,
                baselineValue = base.base.reliability,
                currentEffectiveValue = base.base.reliability,
                score = score,
                createdAt = at.plusSeconds(2),
            )
        )
        ledger.append(adaptation)
        val learned = calibration.profile(base.base)

        assertEquals(0.20, base.base.reliability, absoluteTolerance = 1e-12)
        assertEquals(0.20, learned.base.reliability, absoluteTolerance = 1e-12)
        assertEquals(0.22, learned.effective.reliability, absoluteTolerance = 1e-12)
        assertNotEquals(base.fingerprint, learned.fingerprint)

        val restarted = DurableLearningAdaptationLedger(repository)
        restarted.rehydrate()
        val recovered = LearnedFieldCalibration(restarted).profile(base.base)
        assertEquals(learned.effective, recovered.effective)
        assertEquals(learned.ledgerRevision, recovered.ledgerRevision)
        assertEquals(learned.ledgerFingerprint, recovered.ledgerFingerprint)
        assertEquals(learned.fingerprint, recovered.fingerprint)
    }

    private fun provider(id: String, reliability: Double) = CapabilityDescriptor(
        capabilityId = CapabilityId("knowledge.resolve"),
        providerId = id,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(
            requiredInputs = setOf("goal-photon"),
            outputs = setOf("answer-photon"),
        ),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.HIGH,
        reliability = reliability,
        cost = 0.0,
    )

    private fun queryGoal() = GoalFrame(
        intent = IntentType.QUERY,
        objective = "answer query",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 1.0,
        language = LanguageCode.EN,
    )

    private fun prediction(actionId: String, providerIds: List<String> = listOf("provider-field")) =
        OutcomePrediction.create(
            actionId = actionId,
            decisionId = ConvergenceDecisionId("decision-$actionId"),
            expectedHypotheses = listOf(
                OutcomeExpectedHypothesis(
                    hypothesisId = HypothesisId("hypothesis-$actionId"),
                    confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
                )
            ),
            providerIds = providerIds,
            fieldSnapshotFingerprints = listOf("field-$actionId"),
            thoughtGraphWorkingSetFingerprint = "working-set-$actionId",
            decisionPolicyFingerprint = "decision-policy-v1",
            createdAt = at,
        )

    private fun verifiedScore(prediction: OutcomePrediction, value: Double): OutcomeScore =
        OutcomeScorer().score(
            prediction,
            listOf(
                OutcomeEvidence.create(
                    predictionId = prediction.id,
                    sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
                    sourceId = "runtime-task-store",
                    sourceFingerprint = "runtime-observation-${prediction.actionId}-$value",
                    signal = OutcomeSignal(
                        completion = value,
                        correctness = value,
                        usefulness = value,
                        policyCompliance = value,
                    ),
                    confidence = 1.0,
                    independentOfProviderIds = prediction.providerIds.toSet(),
                    observedAt = at.plusSeconds(1),
                    reason = "durable-runtime-result",
                )
            ),
        )

    private class MemoryAdaptationRepository : LearningAdaptationRepository {
        private val events = linkedMapOf<LearningAdaptationId, LearningAdaptation>()

        override suspend fun save(event: LearningAdaptation): LearningAdaptationWriteResult {
            val existing = events[event.id]
            if (existing != null) {
                require(existing == event)
                return LearningAdaptationWriteResult.Duplicate(existing)
            }
            events[event.id] = event
            return LearningAdaptationWriteResult.Stored(event)
        }

        override suspend fun load(id: LearningAdaptationId): LearningAdaptation? = events[id]

        override suspend fun loadReport(): LearningAdaptationLoadReport =
            LearningAdaptationLoadReport(events.values.toList(), emptyList())
    }
}

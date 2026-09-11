package app.lifeos.core.runtime.learning

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeLearningCoreTest {
    private val at = Instant.parse("2026-09-11T09:00:00Z")

    @Test
    fun `prediction identity is canonical across unordered inputs`() {
        val first = prediction(
            actionId = "action-canonical",
            providerIds = listOf("provider-b", "provider-a"),
            fieldFingerprints = listOf("field-z", "field-a"),
            hypothesisIds = listOf("hypothesis-b", "hypothesis-a"),
        )
        val second = prediction(
            actionId = "action-canonical",
            providerIds = listOf("provider-a", "provider-b"),
            fieldFingerprints = listOf("field-a", "field-z"),
            hypothesisIds = listOf("hypothesis-a", "hypothesis-b"),
        )

        assertEquals(first.id, second.id)
        assertEquals(listOf("provider-a", "provider-b"), first.providerIds)
        assertEquals(listOf("field-a", "field-z"), first.fieldSnapshotFingerprints)
        assertEquals(
            listOf("hypothesis-a", "hypothesis-b"),
            first.expectedHypotheses.map { it.hypothesisId.value },
        )
    }

    @Test
    fun `self reported success alone cannot drive learning`() {
        val prediction = prediction("action-self")
        val selfReport = evidence(
            prediction = prediction,
            sourceClass = OutcomeEvidenceSourceClass.ACTION_SELF_REPORT,
            sourceId = "provider-a",
            signal = all(1.0),
            independentOf = emptySet(),
        )

        val score = OutcomeScorer().score(prediction, listOf(selfReport))

        assertEquals(OutcomeScoreState.INSUFFICIENT_EVIDENCE, score.state)
        assertTrue(score.independentEvidenceIds.isEmpty())
        assertEquals(0.0, score.signedScore)
        assertFalse(score.adaptationAllowed)
        assertNull(
            LearningAdaptationPlanner().plan(
                target = providerTarget(),
                baselineValue = 0.6,
                currentEffectiveValue = 0.6,
                score = score,
                createdAt = at.plusSeconds(2),
            )
        )
    }

    @Test
    fun `independent user correction overrides positive self report without self confirmation`() {
        val prediction = prediction("action-correction")
        val selfReport = evidence(
            prediction = prediction,
            sourceClass = OutcomeEvidenceSourceClass.ACTION_SELF_REPORT,
            sourceId = "provider-a",
            signal = all(1.0),
            independentOf = emptySet(),
        )
        val correction = evidence(
            prediction = prediction,
            sourceClass = OutcomeEvidenceSourceClass.USER_CORRECTION,
            sourceId = "owner-correction",
            signal = all(0.0),
            independentOf = setOf("provider-a"),
            observedAt = at.plusSeconds(2),
        )

        val score = OutcomeScorer().score(prediction, listOf(selfReport, correction))

        assertEquals(OutcomeScoreState.VERIFIED, score.state)
        assertEquals(listOf(correction.id), score.independentEvidenceIds)
        assertEquals(0.0, score.quality)
        assertEquals(-1.0, score.signedScore)
        assertTrue(score.adaptationAllowed)
    }

    @Test
    fun `contradictory independent evidence remains conflicted`() {
        val prediction = prediction("action-conflict")
        val positive = evidence(
            prediction = prediction,
            sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
            sourceId = "system-observer",
            signal = all(1.0),
            independentOf = setOf("provider-a"),
        )
        val negative = evidence(
            prediction = prediction,
            sourceClass = OutcomeEvidenceSourceClass.EXTERNAL_VERIFICATION,
            sourceId = "external-verifier",
            signal = all(0.0),
            independentOf = setOf("provider-a"),
            observedAt = at.plusSeconds(2),
        )

        val score = OutcomeScorer().score(prediction, listOf(negative, positive))

        assertEquals(OutcomeScoreState.CONFLICTED, score.state)
        assertEquals(0.0, score.signedScore)
        assertFalse(score.adaptationAllowed)
    }

    @Test
    fun `positive and negative adaptation are bounded and one prediction cannot update target twice`() {
        val prediction = prediction("action-positive")
        val positiveScore = OutcomeScorer().score(
            prediction,
            listOf(
                evidence(
                    prediction = prediction,
                    sourceClass = OutcomeEvidenceSourceClass.EXTERNAL_VERIFICATION,
                    sourceId = "external-positive",
                    signal = all(1.0),
                    independentOf = setOf("provider-a"),
                )
            ),
        )
        val planner = LearningAdaptationPlanner()
        val reducer = LearningAdaptationReducer()
        val first = requireNotNull(
            planner.plan(
                target = providerTarget(),
                baselineValue = 0.6,
                currentEffectiveValue = 0.6,
                score = positiveScore,
                createdAt = at.plusSeconds(3),
            )
        )

        assertEquals(0.02, first.delta, absoluteTolerance = 1e-12)
        assertEquals(0.62, first.resultingEffectiveValue, absoluteTolerance = 1e-12)
        val firstApply = reducer.apply(LearningAdaptationState(), first)
        assertEquals(1L, firstApply.state.revision)
        assertTrue(reducer.apply(firstApply.state, first).replayed)

        val rescoredSamePrediction = OutcomeScorer().score(
            prediction,
            listOf(
                evidence(
                    prediction = prediction,
                    sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
                    sourceId = "second-observer-same-action",
                    signal = all(1.0),
                    independentOf = setOf("provider-a"),
                    observedAt = at.plusSeconds(4),
                )
            ),
        )
        assertNotEquals(positiveScore.id, rescoredSamePrediction.id)
        val duplicateActionAdaptation = requireNotNull(
            planner.plan(
                target = providerTarget(),
                baselineValue = 0.6,
                currentEffectiveValue = first.resultingEffectiveValue,
                score = rescoredSamePrediction,
                createdAt = at.plusSeconds(5),
                previousAdaptation = first,
            )
        )
        assertFailsWith<IllegalArgumentException> {
            reducer.apply(firstApply.state, duplicateActionAdaptation)
        }
    }

    @Test
    fun `rollback is append only and restores exact prior effective value even from reversed replay input`() {
        val positivePrediction = prediction("action-before-drift")
        val positiveScore = verifiedScore(positivePrediction, 1.0, "positive-verifier")
        val planner = LearningAdaptationPlanner()
        val reducer = LearningAdaptationReducer()
        val first = requireNotNull(
            planner.plan(
                target = providerTarget(),
                baselineValue = 0.6,
                currentEffectiveValue = 0.6,
                score = positiveScore,
                createdAt = at.plusSeconds(3),
            )
        )
        val afterFirst = reducer.apply(LearningAdaptationState(), first).state

        val rollbackPrediction = prediction("action-drift-verification", createdAt = at.plusSeconds(10))
        val rollbackScore = verifiedScore(
            rollbackPrediction,
            value = 0.0,
            sourceId = "negative-independent-verifier",
            observedAt = at.plusSeconds(11),
        )
        val rollback = planner.rollbackLatest(
            adaptation = first,
            currentEffectiveValue = first.resultingEffectiveValue,
            rollbackScore = rollbackScore,
            createdAt = at.plusSeconds(12),
        )
        val sequential = reducer.apply(afterFirst, rollback).state
        val recovered = reducer.replay(listOf(rollback, first))

        assertEquals(0.6, sequential.effectiveValues.getValue(providerTarget()), absoluteTolerance = 1e-12)
        assertEquals(2L, sequential.revision)
        assertEquals(sequential.fingerprint, recovered.fingerprint)
        assertEquals(first.id, rollback.rollbackOf)
    }

    @Test
    fun `rolling positive learning cannot exceed baseline drift budget`() {
        val planner = LearningAdaptationPlanner()
        val reducer = LearningAdaptationReducer()
        val target = providerTarget()
        var state = LearningAdaptationState()
        var current = 0.6
        var previous: LearningAdaptation? = null

        repeat(20) { index ->
            val prediction = prediction(
                actionId = "action-drift-$index",
                createdAt = at.plusSeconds(index.toLong() * 3L),
            )
            val score = verifiedScore(
                prediction = prediction,
                value = 1.0,
                sourceId = "verifier-$index",
                observedAt = prediction.createdAt.plusSeconds(1),
            )
            val next = planner.plan(
                target = target,
                baselineValue = 0.6,
                currentEffectiveValue = current,
                score = score,
                createdAt = prediction.createdAt.plusSeconds(2),
                previousAdaptation = previous,
            ) ?: return@repeat
            state = reducer.apply(state, next).state
            current = next.resultingEffectiveValue
            previous = next
        }

        assertTrue(current <= 0.8 + 1e-12)
        assertEquals(0.8, current, absoluteTolerance = 1e-12)
        assertEquals(current, state.effectiveValues.getValue(target), absoluteTolerance = 1e-12)
    }

    private fun prediction(
        actionId: String,
        providerIds: List<String> = listOf("provider-a"),
        fieldFingerprints: List<String> = listOf("field-a"),
        hypothesisIds: List<String> = listOf("hypothesis-a"),
        createdAt: Instant = at,
    ): OutcomePrediction = OutcomePrediction.create(
        actionId = actionId,
        decisionId = ConvergenceDecisionId("decision-$actionId"),
        expectedHypotheses = hypothesisIds.map { id ->
            OutcomeExpectedHypothesis(
                hypothesisId = HypothesisId(id),
                confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
            )
        },
        providerIds = providerIds,
        fieldSnapshotFingerprints = fieldFingerprints,
        thoughtGraphWorkingSetFingerprint = "working-set-$actionId",
        decisionPolicyFingerprint = "decision-policy-v1",
        createdAt = createdAt,
    )

    private fun evidence(
        prediction: OutcomePrediction,
        sourceClass: OutcomeEvidenceSourceClass,
        sourceId: String,
        signal: OutcomeSignal,
        independentOf: Set<String>,
        observedAt: Instant = at.plusSeconds(1),
    ): OutcomeEvidence = OutcomeEvidence.create(
        predictionId = prediction.id,
        sourceClass = sourceClass,
        sourceId = sourceId,
        sourceFingerprint = "fingerprint-$sourceId",
        signal = signal,
        confidence = 1.0,
        independentOfProviderIds = independentOf,
        observedAt = observedAt,
        reason = "test-$sourceId",
    )

    private fun verifiedScore(
        prediction: OutcomePrediction,
        value: Double,
        sourceId: String,
        observedAt: Instant = prediction.createdAt.plusSeconds(1),
    ): OutcomeScore = OutcomeScorer().score(
        prediction,
        listOf(
            evidence(
                prediction = prediction,
                sourceClass = OutcomeEvidenceSourceClass.EXTERNAL_VERIFICATION,
                sourceId = sourceId,
                signal = all(value),
                independentOf = prediction.providerIds.toSet(),
                observedAt = observedAt,
            )
        ),
    )

    private fun all(value: Double): OutcomeSignal = OutcomeSignal(
        completion = value,
        correctness = value,
        usefulness = value,
        policyCompliance = value,
    )

    private fun providerTarget(): LearningAdaptationTarget = LearningAdaptationTarget(
        kind = LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
        key = "provider-a",
    )
}

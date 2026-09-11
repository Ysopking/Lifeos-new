package app.lifeos.core.runtime.cognition

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.learning.OutcomeEvidence
import app.lifeos.core.runtime.learning.OutcomeEvidenceSourceClass
import app.lifeos.core.runtime.learning.OutcomeExpectedHypothesis
import app.lifeos.core.runtime.learning.OutcomePrediction
import app.lifeos.core.runtime.learning.OutcomeScorer
import app.lifeos.core.runtime.learning.OutcomeSignal
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomePhotonFactoryTest {
    private val at = Instant.parse("2026-09-11T10:30:00Z")

    @Test
    fun `outcome photon is deterministic and preserves task prediction evidence provenance`() {
        val sourcePhotonId = PhotonId("source-photon")
        val prediction = OutcomePrediction.create(
            actionId = "action-1",
            decisionId = ConvergenceDecisionId("decision-1"),
            expectedHypotheses = listOf(
                OutcomeExpectedHypothesis(
                    hypothesisId = HypothesisId("hypothesis-1"),
                    confidenceBand = ConvergenceConfidenceBand(0.75, 0.85, 0.95),
                )
            ),
            providerIds = listOf("provider-1"),
            fieldSnapshotFingerprints = listOf("field-snapshot-fingerprint-1"),
            thoughtGraphWorkingSetFingerprint = "working-set-1",
            decisionPolicyFingerprint = "decision-policy-1",
            createdAt = at,
        )
        val evidence = OutcomeEvidence.create(
            predictionId = prediction.id,
            sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
            sourceId = "durable-task-store",
            sourceFingerprint = "task-result-fingerprint",
            signal = OutcomeSignal(
                completion = 1.0,
                correctness = 1.0,
                usefulness = 0.8,
                policyCompliance = 1.0,
            ),
            confidence = 1.0,
            independentOfProviderIds = setOf("provider-1"),
            observedAt = at.plusSeconds(1),
            reason = "authoritative-durable-task-result",
        )
        val score = OutcomeScorer().score(prediction, listOf(evidence))
        val result = CognitiveTaskExecutionResult(
            taskId = TaskId("task-1"),
            photonId = sourcePhotonId,
            finalState = TaskState.COMPLETED,
            influences = emptyList(),
            failures = emptyList(),
            fieldShadow = null,
        )
        val factory = OutcomePhotonFactory()

        val first = factory.create(prediction, result, listOf(evidence), score, at.plusSeconds(2))
        val second = factory.create(prediction, result, listOf(evidence), score, at.plusSeconds(2))
        val predictionPhotonId = PhotonId(prediction.id.value)

        assertEquals(first, second)
        assertEquals(OutcomePhotonContract.MIME_TYPE, first.mimeType)
        assertEquals(PhotonPhase.CONVERGED, first.phase)
        assertEquals(setOf(sourcePhotonId, predictionPhotonId), first.provenance.parentIds)
        assertEquals(OutcomePhotonContract.PROVENANCE_SOURCE, first.provenance.source)
        assertEquals(OutcomePhotonContract.PROVENANCE_ACTOR, first.provenance.actor)
        assertEquals(setOf(sourcePhotonId, predictionPhotonId), first.relations.map { it.target }.toSet())
        assertTrue(first.relations.all { it.type == RelationType.DERIVED_FROM })
        assertTrue("verified-outcome" in first.tags)
        assertTrue(first.content.contains(prediction.id.value))
        assertTrue(first.content.contains(prediction.thoughtGraphWorkingSetFingerprint))
        assertTrue(first.content.contains("provider-1"))
        assertTrue(first.content.contains(evidence.sourceFingerprint))
        assertTrue(first.content.contains(score.id.value))
        assertTrue(first.content.contains(evidence.id.value))
        assertTrue(first.content.contains(result.taskId.value))
    }

    @Test
    fun `failed task is recorded as finalized outcome without inventing verified success`() {
        val prediction = OutcomePrediction.create(
            actionId = "action-failed",
            decisionId = ConvergenceDecisionId("decision-failed"),
            expectedHypotheses = listOf(
                OutcomeExpectedHypothesis(
                    hypothesisId = HypothesisId("hypothesis-failed"),
                    confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
                )
            ),
            providerIds = listOf("provider-failed"),
            fieldSnapshotFingerprints = listOf("field-failed"),
            thoughtGraphWorkingSetFingerprint = "working-set-failed",
            decisionPolicyFingerprint = "decision-policy-1",
            createdAt = at,
        )
        val evidence = OutcomeEvidence.create(
            predictionId = prediction.id,
            sourceClass = OutcomeEvidenceSourceClass.ACTION_SELF_REPORT,
            sourceId = "provider-failed",
            sourceFingerprint = "self-report-failed",
            signal = OutcomeSignal(completion = 0.0),
            confidence = 1.0,
            independentOfProviderIds = emptySet(),
            observedAt = at.plusSeconds(1),
            reason = "self-reported-failure",
        )
        val score = OutcomeScorer().score(prediction, listOf(evidence))
        val result = CognitiveTaskExecutionResult(
            taskId = TaskId("task-failed"),
            photonId = null,
            finalState = TaskState.FAILED,
            influences = emptyList(),
            failures = emptyList(),
            fieldShadow = null,
        )

        val photon = OutcomePhotonFactory().create(prediction, result, listOf(evidence), score, at.plusSeconds(2))

        assertEquals(PhotonPhase.CONVERGED, photon.phase)
        assertTrue("task:failed" in photon.tags)
        assertTrue("verified-outcome" !in photon.tags)
        assertEquals(setOf(PhotonId(prediction.id.value)), photon.provenance.parentIds)
        assertTrue(photon.content.contains("\"finalState\":\"FAILED\""))
        assertTrue(photon.content.contains("\"scoreState\":\"INSUFFICIENT_EVIDENCE\""))
    }
}

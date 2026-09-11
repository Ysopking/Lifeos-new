package app.lifeos.core.runtime.learning

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutcomeLearningCoordinatorTest {
    private val at = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun `restart replay preserves one outcome photon and one adaptation`() = runBlocking {
        val photons = MemoryPhotonRepository()
        val adaptations = MemoryAdaptationRepository()
        val firstLedger = DurableLearningAdaptationLedger(adaptations)
        val firstCoordinator = OutcomeLearningCoordinator(photons, firstLedger)
        val prediction = prediction("restart")
        val evidence = verifiedEvidence(prediction, 1.0)
        val result = result("restart")
        val target = LearningTargetBaseline(
            LearningAdaptationTarget(LearningAdaptationTargetKind.PROVIDER_RELIABILITY, "provider-a"),
            0.60,
        )

        firstCoordinator.recordPrediction(prediction)
        val first = firstCoordinator.recordOutcome(prediction, result, listOf(evidence), listOf(target))
        assertEquals(1, first.adaptations.size)
        assertEquals(2, photons.loadAll().size)

        val restartedLedger = DurableLearningAdaptationLedger(adaptations)
        val rehydrated = restartedLedger.rehydrate()
        assertEquals(1, rehydrated.restoredEvents)
        val restartedCoordinator = OutcomeLearningCoordinator(photons, restartedLedger)
        val replay = restartedCoordinator.recordOutcome(prediction, result, listOf(evidence), listOf(target))

        assertEquals(first.outcomePhoton, replay.outcomePhoton)
        assertEquals(first.score, replay.score)
        assertEquals(first.adaptations.single().id, replay.adaptations.single().id)
        assertEquals(1L, restartedLedger.state.value.revision)
        assertEquals(2, photons.loadAll().size)
    }

    @Test
    fun `self report is durably recorded but cannot adapt reliability`() = runBlocking {
        val photons = MemoryPhotonRepository()
        val adaptations = MemoryAdaptationRepository()
        val ledger = DurableLearningAdaptationLedger(adaptations)
        val coordinator = OutcomeLearningCoordinator(photons, ledger)
        val prediction = prediction("self-report")
        val evidence = OutcomeEvidence.create(
            predictionId = prediction.id,
            sourceClass = OutcomeEvidenceSourceClass.ACTION_SELF_REPORT,
            sourceId = "provider-a",
            sourceFingerprint = "provider-self-report",
            signal = OutcomeSignal(completion = 1.0, correctness = 1.0),
            confidence = 1.0,
            independentOfProviderIds = emptySet(),
            observedAt = at.plusSeconds(1),
            reason = "provider-claims-success",
        )

        coordinator.recordPrediction(prediction)
        val record = coordinator.recordOutcome(
            prediction = prediction,
            result = result("self-report"),
            evidence = listOf(evidence),
            targets = listOf(
                LearningTargetBaseline(
                    LearningAdaptationTarget(LearningAdaptationTargetKind.PROVIDER_RELIABILITY, "provider-a"),
                    0.60,
                )
            ),
        )

        assertEquals(OutcomeScoreState.INSUFFICIENT_EVIDENCE, record.score.state)
        assertTrue(record.adaptations.isEmpty())
        assertEquals(0L, ledger.state.value.revision)
        assertTrue("verified-outcome" !in record.outcomePhoton.tags)
        assertEquals(2, photons.loadAll().size)
    }

    private fun prediction(action: String) = OutcomePrediction.create(
        actionId = "action-$action",
        decisionId = ConvergenceDecisionId("decision-$action"),
        expectedHypotheses = listOf(
            OutcomeExpectedHypothesis(
                hypothesisId = HypothesisId("hypothesis-$action"),
                confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
            )
        ),
        providerIds = listOf("provider-a"),
        fieldSnapshotFingerprints = listOf("field-$action"),
        thoughtGraphWorkingSetFingerprint = "working-set-$action",
        decisionPolicyFingerprint = "policy-v1",
        createdAt = at,
    )

    private fun verifiedEvidence(prediction: OutcomePrediction, value: Double) = OutcomeEvidence.create(
        predictionId = prediction.id,
        sourceClass = OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION,
        sourceId = "durable-task-store",
        sourceFingerprint = "task-result-${prediction.actionId}",
        signal = OutcomeSignal(
            completion = value,
            correctness = value,
            usefulness = value,
            policyCompliance = value,
        ),
        confidence = 1.0,
        independentOfProviderIds = prediction.providerIds.toSet(),
        observedAt = at.plusSeconds(1),
        reason = "authoritative-task-result",
    )

    private fun result(suffix: String) = CognitiveTaskExecutionResult(
        taskId = TaskId("task-$suffix"),
        photonId = PhotonId("source-$suffix"),
        finalState = TaskState.COMPLETED,
        influences = emptyList(),
        failures = emptyList(),
        fieldShadow = null,
    )

    private class MemoryPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id]?.let { require(it == photon) }
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(loadAll(), emptyList())

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }

    private class MemoryAdaptationRepository : LearningAdaptationRepository {
        private val values = linkedMapOf<LearningAdaptationId, LearningAdaptation>()

        override suspend fun save(event: LearningAdaptation): LearningAdaptationWriteResult {
            values[event.id]?.let { existing ->
                require(existing == event)
                return LearningAdaptationWriteResult.Duplicate(existing)
            }
            values[event.id] = event
            return LearningAdaptationWriteResult.Stored(event)
        }

        override suspend fun load(id: LearningAdaptationId): LearningAdaptation? = values[id]

        override suspend fun loadReport(): LearningAdaptationLoadReport =
            LearningAdaptationLoadReport(values.values.toList(), emptyList())
    }
}

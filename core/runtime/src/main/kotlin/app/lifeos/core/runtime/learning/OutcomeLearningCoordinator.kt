package app.lifeos.core.runtime.learning

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.cognition.OutcomePhotonFactory
import app.lifeos.core.runtime.cognition.OutcomePredictionPhotonFactory
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult

data class LearningTargetBaseline(
    val target: LearningAdaptationTarget,
    val baselineValue: Double,
) {
    init { require(baselineValue.isFinite() && baselineValue in 0.0..1.0) }
}

data class OutcomeLearningRecord(
    val predictionPhoton: Photon,
    val outcomePhoton: Photon,
    val score: OutcomeScore,
    val adaptations: List<LearningAdaptation>,
)

/**
 * Crash-safe V6 write coordinator.
 *
 * Order is deliberate:
 * 1) pre-action prediction Photon is durable before productive execution;
 * 2) full outcome/evidence/score Photon is durable after the authoritative result;
 * 3) append-only adaptations are persisted before their RAM projection by the durable ledger.
 *
 * Retrying after a kill is idempotent: Photon identities are content-derived and one prediction may
 * adapt each target at most once.
 */
class OutcomeLearningCoordinator(
    private val photons: PhotonRepository,
    private val ledger: DurableLearningAdaptationLedger,
    private val predictionPhotons: OutcomePredictionPhotonFactory = OutcomePredictionPhotonFactory(),
    private val outcomePhotons: OutcomePhotonFactory = OutcomePhotonFactory(),
    private val scorer: OutcomeScorer = OutcomeScorer(),
    private val planner: LearningAdaptationPlanner = LearningAdaptationPlanner(),
) {
    suspend fun recordPrediction(prediction: OutcomePrediction): Photon {
        val photon = predictionPhotons.create(prediction)
        saveExact(photon)
        return photon
    }

    suspend fun recordOutcome(
        prediction: OutcomePrediction,
        result: CognitiveTaskExecutionResult,
        evidence: List<OutcomeEvidence>,
        targets: List<LearningTargetBaseline>,
    ): OutcomeLearningRecord {
        require(evidence.isNotEmpty()) { "Outcome learning requires explicit outcome evidence" }
        require(targets.map { it.target }.distinct().size == targets.size) {
            "Outcome learning targets must be unique"
        }
        val expectedPredictionPhoton = predictionPhotons.create(prediction)
        val persistedPrediction = photons.load(expectedPredictionPhoton.id)
            ?: error("Outcome prediction was not durably recorded before action execution")
        require(persistedPrediction == expectedPredictionPhoton) {
            "Durable outcome prediction identity/content mismatch"
        }

        val score = scorer.score(prediction, evidence)
        val recordedAt = evidence.maxOf { it.observedAt }
        val outcomePhoton = outcomePhotons.create(
            prediction = prediction,
            result = result,
            evidence = evidence,
            score = score,
            recordedAt = recordedAt,
        )
        saveExact(outcomePhoton)

        val adaptations = mutableListOf<LearningAdaptation>()
        targets.sortedBy { it.target.stableKey }.forEach { targetBaseline ->
            val existing = ledger.state.value.events.firstOrNull { event ->
                event.target == targetBaseline.target && event.outcomePredictionId == prediction.id
            }
            if (existing != null) {
                adaptations += existing
                return@forEach
            }
            val previous = ledger.latestFor(targetBaseline.target)
            val current = ledger.effectiveValue(targetBaseline.target, targetBaseline.baselineValue)
            val planned = planner.plan(
                target = targetBaseline.target,
                baselineValue = targetBaseline.baselineValue,
                currentEffectiveValue = current,
                score = score,
                createdAt = recordedAt,
                previousAdaptation = previous,
            ) ?: return@forEach
            adaptations += ledger.append(planned).state.events.last { it.id == planned.id }
        }

        return OutcomeLearningRecord(
            predictionPhoton = persistedPrediction,
            outcomePhoton = outcomePhoton,
            score = score,
            adaptations = adaptations.sortedBy { it.target.stableKey },
        )
    }

    private suspend fun saveExact(photon: Photon) {
        val existing = photons.load(photon.id)
        if (existing != null) {
            require(existing == photon) { "Photon identity collision: ${photon.id.value}" }
            return
        }
        photons.save(photon)
        require(photons.load(photon.id) == photon) {
            "Photon persistence verification failed: ${photon.id.value}"
        }
    }
}

package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.learning.LearningEvent
import app.lifeos.core.runtime.learning.LearningEventKind
import app.lifeos.core.runtime.learning.LearningProvenance
import app.lifeos.core.runtime.learning.LearningSourceId
import java.time.Instant

/**
 * B497 adapters for the productive ContinuousLearningCoordinator.
 *
 * Context changes stay INFERENCE. Only B494 verified terminal outcomes are tagged VERIFIED_OUTCOME.
 */
object PersonalContextLearningEventFactory {
    val contextSourceId: LearningSourceId =
        LearningSourceId("personal-context.delta")
    val verifiedOutcomeSourceId: LearningSourceId =
        LearningSourceId("verified-outcome.signal")

    fun contextDelta(
        delta: PersonalContextDelta,
        sequence: Long,
        occurredAt: Instant,
    ): LearningEvent =
        LearningEvent(
            sourceId = contextSourceId,
            sequence = sequence,
            eventId = "personal-context-delta:" + delta.fingerprint,
            kind = LearningEventKind.CONTEXT_CHANGE,
            provenance = LearningProvenance.INFERENCE,
            occurredAt = occurredAt,
            attributes = mapOf(
                "delta_kind" to delta.kind.name,
                "context_fingerprint" to delta.contextFingerprint,
                "current_fingerprint" to delta.currentFingerprint,
                "previous_fingerprint" to delta.previousFingerprint.orEmpty(),
            ),
        )

    fun verifiedOutcome(
        signal: VerifiedOutcomeLearningSignal,
        sequence: Long,
        occurredAt: Instant,
    ): LearningEvent =
        LearningEvent(
            sourceId = verifiedOutcomeSourceId,
            sequence = sequence,
            eventId = "verified-outcome:" + signal.fingerprint,
            kind = LearningEventKind.COGNITIVE_OUTCOME,
            provenance = LearningProvenance.VERIFIED_OUTCOME,
            occurredAt = occurredAt,
            attributes = mapOf(
                "graph_id" to signal.graphId,
                "expectation_fingerprint" to signal.expectationFingerprint,
                "source_observation_id" to signal.sourceObservationId,
                "outcome_state" to signal.outcomeState.name,
                "reason_code" to signal.reasonCode,
                "learning_signal_fingerprint" to signal.fingerprint,
            ),
        )
}

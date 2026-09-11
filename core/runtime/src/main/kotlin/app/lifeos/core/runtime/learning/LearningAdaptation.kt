package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import kotlin.math.abs

enum class LearningAdaptationTargetKind {
    PROVIDER_RELIABILITY,
    FIELD_WEIGHT,
}

data class LearningAdaptationTarget(
    val kind: LearningAdaptationTargetKind,
    val key: String,
) : Comparable<LearningAdaptationTarget> {
    init { require(key.isNotBlank()) { "Learning adaptation target key must not be blank" } }

    val stableKey: String = "${kind.name}:$key"

    override fun compareTo(other: LearningAdaptationTarget): Int = stableKey.compareTo(other.stableKey)
}

data class LearningAdaptationPolicy(
    val maxPositiveDeltaPerEvent: Double = 0.02,
    val maxNegativeDeltaPerEvent: Double = 0.05,
    val maxAbsoluteDriftFromBaseline: Double = 0.20,
    val absoluteFloor: Double = 0.0,
    val absoluteCeiling: Double = 1.0,
) {
    init {
        require(maxPositiveDeltaPerEvent.isFinite() && maxPositiveDeltaPerEvent in 0.0..1.0)
        require(maxNegativeDeltaPerEvent.isFinite() && maxNegativeDeltaPerEvent in 0.0..1.0)
        require(maxAbsoluteDriftFromBaseline.isFinite() && maxAbsoluteDriftFromBaseline in 0.0..1.0)
        require(absoluteFloor.isFinite() && absoluteFloor in 0.0..1.0)
        require(absoluteCeiling.isFinite() && absoluteCeiling in 0.0..1.0)
        require(absoluteFloor <= absoluteCeiling)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "learning-adaptation-policy/v1",
        java.lang.Double.toHexString(maxPositiveDeltaPerEvent),
        java.lang.Double.toHexString(maxNegativeDeltaPerEvent),
        java.lang.Double.toHexString(maxAbsoluteDriftFromBaseline),
        java.lang.Double.toHexString(absoluteFloor),
        java.lang.Double.toHexString(absoluteCeiling),
    )
}

@JvmInline
value class LearningAdaptationId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid learning adaptation id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid learning adaptation digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "learning-adaptation:"
    }
}

data class LearningAdaptation(
    val id: LearningAdaptationId,
    val target: LearningAdaptationTarget,
    val predecessorId: LearningAdaptationId?,
    val outcomePredictionId: OutcomePredictionId,
    val outcomeScoreId: OutcomeScoreId,
    val outcomeScoreFingerprint: String,
    val baselineValue: Double,
    val priorEffectiveValue: Double,
    val delta: Double,
    val resultingEffectiveValue: Double,
    val policyFingerprint: String,
    val createdAt: Instant,
    val rollbackOf: LearningAdaptationId? = null,
) {
    init {
        require(outcomeScoreFingerprint.isNotBlank())
        require(listOf(baselineValue, priorEffectiveValue, delta, resultingEffectiveValue).all { it.isFinite() })
        require(baselineValue in 0.0..1.0)
        require(priorEffectiveValue in 0.0..1.0)
        require(resultingEffectiveValue in 0.0..1.0)
        require(abs(resultingEffectiveValue - (priorEffectiveValue + delta).coerceIn(0.0, 1.0)) <= EPSILON) {
            "Learning adaptation result does not match prior + delta"
        }
        require(policyFingerprint.isNotBlank())
        require(predecessorId != id) { "Learning adaptation cannot be its own predecessor" }
        require(rollbackOf != id) { "Learning adaptation cannot roll back itself" }
        require(id == expectedId()) { "Learning adaptation id/content mismatch" }
    }

    fun contentFingerprint(): String = fingerprint(
        target = target,
        predecessorId = predecessorId,
        outcomePredictionId = outcomePredictionId,
        outcomeScoreId = outcomeScoreId,
        outcomeScoreFingerprint = outcomeScoreFingerprint,
        baselineValue = baselineValue,
        priorEffectiveValue = priorEffectiveValue,
        delta = delta,
        resultingEffectiveValue = resultingEffectiveValue,
        policyFingerprint = policyFingerprint,
        createdAt = createdAt,
        rollbackOf = rollbackOf,
    )

    private fun expectedId(): LearningAdaptationId =
        LearningAdaptationId("${LearningAdaptationId.PREFIX}${contentFingerprint()}")

    companion object {
        private const val EPSILON = 1e-12

        fun create(
            target: LearningAdaptationTarget,
            predecessorId: LearningAdaptationId?,
            outcomePredictionId: OutcomePredictionId,
            outcomeScoreId: OutcomeScoreId,
            outcomeScoreFingerprint: String,
            baselineValue: Double,
            priorEffectiveValue: Double,
            delta: Double,
            policyFingerprint: String,
            createdAt: Instant,
            rollbackOf: LearningAdaptationId? = null,
        ): LearningAdaptation {
            require(delta.isFinite())
            val result = (priorEffectiveValue + delta).coerceIn(0.0, 1.0)
            val actualDelta = result - priorEffectiveValue
            val fingerprint = fingerprint(
                target = target,
                predecessorId = predecessorId,
                outcomePredictionId = outcomePredictionId,
                outcomeScoreId = outcomeScoreId,
                outcomeScoreFingerprint = outcomeScoreFingerprint,
                baselineValue = baselineValue,
                priorEffectiveValue = priorEffectiveValue,
                delta = actualDelta,
                resultingEffectiveValue = result,
                policyFingerprint = policyFingerprint,
                createdAt = createdAt,
                rollbackOf = rollbackOf,
            )
            return LearningAdaptation(
                id = LearningAdaptationId("${LearningAdaptationId.PREFIX}$fingerprint"),
                target = target,
                predecessorId = predecessorId,
                outcomePredictionId = outcomePredictionId,
                outcomeScoreId = outcomeScoreId,
                outcomeScoreFingerprint = outcomeScoreFingerprint,
                baselineValue = baselineValue,
                priorEffectiveValue = priorEffectiveValue,
                delta = actualDelta,
                resultingEffectiveValue = result,
                policyFingerprint = policyFingerprint,
                createdAt = createdAt,
                rollbackOf = rollbackOf,
            )
        }

        private fun fingerprint(
            target: LearningAdaptationTarget,
            predecessorId: LearningAdaptationId?,
            outcomePredictionId: OutcomePredictionId,
            outcomeScoreId: OutcomeScoreId,
            outcomeScoreFingerprint: String,
            baselineValue: Double,
            priorEffectiveValue: Double,
            delta: Double,
            resultingEffectiveValue: Double,
            policyFingerprint: String,
            createdAt: Instant,
            rollbackOf: LearningAdaptationId?,
        ): String = StableFieldIds.fingerprint(
            "learning-adaptation/v3",
            target.kind.name,
            target.key,
            predecessorId?.value.orEmpty(),
            outcomePredictionId.value,
            outcomeScoreId.value,
            outcomeScoreFingerprint,
            java.lang.Double.toHexString(baselineValue),
            java.lang.Double.toHexString(priorEffectiveValue),
            java.lang.Double.toHexString(delta),
            java.lang.Double.toHexString(resultingEffectiveValue),
            policyFingerprint,
            createdAt.toString(),
            rollbackOf?.value.orEmpty(),
        )
    }
}

class LearningAdaptationPlanner(
    private val policy: LearningAdaptationPolicy = LearningAdaptationPolicy(),
) {
    fun plan(
        target: LearningAdaptationTarget,
        baselineValue: Double,
        currentEffectiveValue: Double,
        score: OutcomeScore,
        createdAt: Instant,
        previousAdaptation: LearningAdaptation? = null,
    ): LearningAdaptation? {
        require(baselineValue.isFinite() && baselineValue in 0.0..1.0)
        require(currentEffectiveValue.isFinite() && currentEffectiveValue in 0.0..1.0)
        previousAdaptation?.let { previous ->
            require(previous.target == target) { "Previous learning adaptation target mismatch" }
            require(abs(previous.baselineValue - baselineValue) <= EPSILON) {
                "Previous learning adaptation baseline mismatch"
            }
            require(abs(previous.resultingEffectiveValue - currentEffectiveValue) <= EPSILON) {
                "Current effective value does not match previous adaptation"
            }
        } ?: require(abs(currentEffectiveValue - baselineValue) <= EPSILON) {
            "First learning adaptation must start from its immutable baseline"
        }
        if (!score.adaptationAllowed || score.signedScore == 0.0) return null

        val requestedDelta = if (score.signedScore > 0.0) {
            score.signedScore * policy.maxPositiveDeltaPerEvent
        } else {
            score.signedScore * policy.maxNegativeDeltaPerEvent
        }
        val lowerDriftBound = maxOf(policy.absoluteFloor, baselineValue - policy.maxAbsoluteDriftFromBaseline)
        val upperDriftBound = minOf(policy.absoluteCeiling, baselineValue + policy.maxAbsoluteDriftFromBaseline)
        val desired = (currentEffectiveValue + requestedDelta).coerceIn(lowerDriftBound, upperDriftBound)
        val actualDelta = desired - currentEffectiveValue
        if (abs(actualDelta) <= EPSILON) return null

        return LearningAdaptation.create(
            target = target,
            predecessorId = previousAdaptation?.id,
            outcomePredictionId = score.predictionId,
            outcomeScoreId = score.id,
            outcomeScoreFingerprint = score.contentFingerprint(),
            baselineValue = baselineValue,
            priorEffectiveValue = currentEffectiveValue,
            delta = actualDelta,
            policyFingerprint = policy.fingerprint(),
            createdAt = createdAt,
        )
    }

    fun rollbackLatest(
        adaptation: LearningAdaptation,
        currentEffectiveValue: Double,
        rollbackScore: OutcomeScore,
        createdAt: Instant,
    ): LearningAdaptation {
        require(rollbackScore.adaptationAllowed) { "Rollback requires independently verified outcome evidence" }
        require(abs(currentEffectiveValue - adaptation.resultingEffectiveValue) <= EPSILON) {
            "Only the current latest adaptation can be rolled back exactly"
        }
        require(rollbackScore.predictionId != adaptation.outcomePredictionId) {
            "Rollback requires a distinct independently verified prediction/outcome"
        }
        return LearningAdaptation.create(
            target = adaptation.target,
            predecessorId = adaptation.id,
            outcomePredictionId = rollbackScore.predictionId,
            outcomeScoreId = rollbackScore.id,
            outcomeScoreFingerprint = rollbackScore.contentFingerprint(),
            baselineValue = adaptation.baselineValue,
            priorEffectiveValue = currentEffectiveValue,
            delta = adaptation.priorEffectiveValue - currentEffectiveValue,
            policyFingerprint = policy.fingerprint(),
            createdAt = createdAt,
            rollbackOf = adaptation.id,
        )
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}

data class LearningAdaptationState(
    val revision: Long = 0L,
    val events: List<LearningAdaptation> = emptyList(),
    val effectiveValues: Map<LearningAdaptationTarget, Double> = emptyMap(),
) {
    init {
        require(revision >= 0L)
        require(revision == events.size.toLong()) { "Adaptation revision must equal unique event count" }
        require(events.map { it.id }.distinct().size == events.size)
        require(effectiveValues.values.all { it.isFinite() && it in 0.0..1.0 })
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "learning-adaptation-state/v3",
        revision.toString(),
        *events.map { "event:${it.id.value}" }.sorted().toTypedArray(),
        *effectiveValues.entries.sortedBy { it.key.stableKey }.map { (target, value) ->
            "effective:${target.stableKey}:${java.lang.Double.toHexString(value)}"
        }.toTypedArray(),
    )

    fun latestFor(target: LearningAdaptationTarget): LearningAdaptation? =
        events.lastOrNull { it.target == target }
}

data class LearningAdaptationApplyResult(
    val state: LearningAdaptationState,
    val replayed: Boolean,
)

/** Pure append-only reducer. Historical adaptations are never edited or removed. */
class LearningAdaptationReducer {
    fun apply(
        state: LearningAdaptationState,
        event: LearningAdaptation,
    ): LearningAdaptationApplyResult {
        state.events.firstOrNull { it.id == event.id }?.let { existing ->
            require(existing == event) { "Learning adaptation identity collision" }
            return LearningAdaptationApplyResult(state, replayed = true)
        }
        require(state.events.none {
            it.target == event.target && it.outcomePredictionId == event.outcomePredictionId
        }) {
            "Outcome prediction has already adapted this target"
        }

        val previousForTarget = state.latestFor(event.target)
        require(event.predecessorId == previousForTarget?.id) {
            "Learning adaptation predecessor does not match target head"
        }
        val expectedCurrent = previousForTarget?.resultingEffectiveValue ?: event.baselineValue
        require(abs(event.priorEffectiveValue - expectedCurrent) <= EPSILON) {
            "Learning adaptation prior value does not match replay state"
        }
        previousForTarget?.let { previous ->
            require(abs(previous.baselineValue - event.baselineValue) <= EPSILON) {
                "Learning adaptation baseline changed for existing target"
            }
        }
        event.rollbackOf?.let { rollbackId ->
            val rolledBack = state.events.firstOrNull { it.id == rollbackId }
                ?: error("Learning rollback references unknown adaptation")
            require(rolledBack.target == event.target) { "Learning rollback target mismatch" }
            require(previousForTarget?.id == rollbackId) {
                "Learning rollback must target the latest adaptation for exact restoration"
            }
            require(abs(event.resultingEffectiveValue - rolledBack.priorEffectiveValue) <= EPSILON) {
                "Learning rollback must restore the exact previous effective value"
            }
        }

        val nextEvents = state.events + event
        val nextValues = state.effectiveValues.toMutableMap().apply {
            put(event.target, event.resultingEffectiveValue)
        }.toSortedMap()
        return LearningAdaptationApplyResult(
            state = LearningAdaptationState(
                revision = nextEvents.size.toLong(),
                events = nextEvents,
                effectiveValues = nextValues,
            ),
            replayed = false,
        )
    }

    fun replay(events: List<LearningAdaptation>): LearningAdaptationState {
        require(events.map { it.id }.distinct().size == events.size) {
            "Learning adaptation replay cannot contain duplicate physical events"
        }
        var state = LearningAdaptationState()
        val remaining = events.toMutableList()
        while (remaining.isNotEmpty()) {
            val knownIds = state.events.mapTo(mutableSetOf()) { it.id }
            val ready = remaining
                .filter { it.predecessorId == null || it.predecessorId in knownIds }
                .sortedWith(
                    compareBy<LearningAdaptation> { it.target.stableKey }
                        .thenBy { it.createdAt }
                        .thenBy { it.id.value }
                )
            require(ready.isNotEmpty()) { "Learning adaptation history has a missing/cyclic predecessor" }
            var progressed = false
            for (event in ready) {
                val previous = state.latestFor(event.target)
                if (event.predecessorId != previous?.id) continue
                state = apply(state, event).state
                remaining.remove(event)
                progressed = true
            }
            require(progressed) { "Learning adaptation history forks or cannot be replayed" }
        }
        return state
    }

    private companion object {
        const val EPSILON = 1e-12
    }
}

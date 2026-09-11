package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

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
        require(resultingEffectiveValue == (priorEffectiveValue + delta).coerceIn(0.0, 1.0)) {
            "Learning adaptation result does not match prior + delta"
        }
        require(policyFingerprint.isNotBlank())
        require(rollbackOf != id) { "Learning adaptation cannot roll back itself" }
        require(id == expectedId()) { "Learning adaptation id/content mismatch" }
    }

    fun contentFingerprint(): String = fingerprint(
        target = target,
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
        fun create(
            target: LearningAdaptationTarget,
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
            "learning-adaptation/v1",
            target.kind.name,
            target.key,
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
    ): LearningAdaptation? {
        require(baselineValue.isFinite() && baselineValue in 0.0..1.0)
        require(currentEffectiveValue.isFinite() && currentEffectiveValue in 0.0..1.0)
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
        if (actualDelta == 0.0) return null

        return LearningAdaptation.create(
            target = target,
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
        require(currentEffectiveValue == adaptation.resultingEffectiveValue) {
            "Only the current latest adaptation can be rolled back exactly"
        }
        return LearningAdaptation.create(
            target = adaptation.target,
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
        "learning-adaptation-state/v1",
        revision.toString(),
        *events.map { "event:${it.id.value}" }.toTypedArray(),
        *effectiveValues.entries.sortedBy { it.key.stableKey }.map { (target, value) ->
            "effective:${target.stableKey}:${java.lang.Double.toHexString(value)}"
        }.toTypedArray(),
    )
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

        val previousForTarget = state.events.lastOrNull { it.target == event.target }
        val expectedCurrent = previousForTarget?.resultingEffectiveValue ?: event.baselineValue
        require(event.priorEffectiveValue == expectedCurrent) {
            "Learning adaptation prior value does not match replay state"
        }
        previousForTarget?.let { previous ->
            require(previous.baselineValue == event.baselineValue) {
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
            require(event.resultingEffectiveValue == rolledBack.priorEffectiveValue) {
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

    fun replay(events: List<LearningAdaptation>): LearningAdaptationState =
        events.fold(LearningAdaptationState()) { state, event -> apply(state, event).state }
}

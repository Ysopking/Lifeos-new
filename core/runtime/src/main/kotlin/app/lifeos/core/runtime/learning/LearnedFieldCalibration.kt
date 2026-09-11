package app.lifeos.core.runtime.learning

import app.lifeos.core.field.ConvergenceConfig
import app.lifeos.core.field.DomainFieldRegistry
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldForceCalculator
import app.lifeos.core.field.FieldWeights
import app.lifeos.core.field.StableFieldIds

enum class LearnedFieldWeightDimension(val key: String) {
    CONFIDENCE("confidence"),
    RELIABILITY("reliability"),
    AUTHORITY("authority"),
    TEMPORAL_VALIDITY("temporal-validity"),
    CONTEXT_COHERENCE("context-coherence"),
    SEMANTIC_MASS("semantic-mass"),
}

data class LearnedFieldCalibrationProfile(
    val base: FieldWeights,
    val effective: FieldWeights,
    val ledgerRevision: Long,
    val ledgerFingerprint: String,
) {
    init {
        require(ledgerRevision >= 0L)
        require(ledgerFingerprint.isNotBlank())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "learned-field-calibration-profile/v1",
        base.fingerprint(),
        effective.fingerprint(),
        ledgerRevision.toString(),
        ledgerFingerprint,
    )
}

/**
 * Read-only projection of the append-only learning ledger into field physics.
 * Base weights are never mutated. Every convergence run can capture a fresh profile,
 * so later independently verified outcomes can influence later decisions without rewriting history.
 */
class LearnedFieldCalibration(
    private val ledger: DurableLearningAdaptationLedger,
) {
    fun target(dimension: LearnedFieldWeightDimension): LearningAdaptationTarget =
        LearningAdaptationTarget(
            kind = LearningAdaptationTargetKind.FIELD_WEIGHT,
            key = "global:${dimension.key}",
        )

    fun profile(base: FieldWeights = FieldWeights()): LearnedFieldCalibrationProfile {
        val state = ledger.state.value
        val effective = FieldWeights(
            confidence = effective(LearnedFieldWeightDimension.CONFIDENCE, base.confidence),
            reliability = effective(LearnedFieldWeightDimension.RELIABILITY, base.reliability),
            authority = effective(LearnedFieldWeightDimension.AUTHORITY, base.authority),
            temporalValidity = effective(LearnedFieldWeightDimension.TEMPORAL_VALIDITY, base.temporalValidity),
            contextCoherence = effective(LearnedFieldWeightDimension.CONTEXT_COHERENCE, base.contextCoherence),
            semanticMass = effective(LearnedFieldWeightDimension.SEMANTIC_MASS, base.semanticMass),
            contradictionPenalty = base.contradictionPenalty,
        )
        return LearnedFieldCalibrationProfile(
            base = base,
            effective = effective,
            ledgerRevision = state.revision,
            ledgerFingerprint = state.fingerprint,
        )
    }

    fun engine(
        base: FieldWeights = FieldWeights(),
        config: ConvergenceConfig = ConvergenceConfig(),
        registry: DomainFieldRegistry = DomainFieldRegistry.EMPTY,
    ): FieldConvergenceEngine {
        val profile = profile(base)
        return FieldConvergenceEngine(
            forceCalculator = FieldForceCalculator(profile.effective),
            config = config,
            registry = registry,
            calibrationFingerprint = profile.fingerprint,
        )
    }

    private fun effective(dimension: LearnedFieldWeightDimension, baseline: Double): Double =
        ledger.effectiveValue(target(dimension), baseline)
}

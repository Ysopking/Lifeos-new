package app.lifeos.core.field

import app.lifeos.core.model.WorldStateSignature
import kotlin.math.exp
import kotlin.math.sqrt

data class WorldCouplingScale(
    val mu: Double = 1.0,
    val runningCoupling: Double = 1.0,
) {
    init {
        require(mu.isFinite() && mu > 0.0) { "mu must be finite and positive" }
        require(runningCoupling.isFinite() && runningCoupling >= 0.0) {
            "runningCoupling must be finite and non-negative"
        }
    }
}

data class WorldCouplingResult(
    val massInteraction: Double,
    val coherence: Double,
    val relation: Double,
    val temporal: Double,
    val polarity: Double,
    val runningCoupling: Double,
    val signedCoupling: Double,
) {
    init {
        require(listOf(massInteraction, coherence, relation, temporal).all { it in 0.0..1.0 })
        require(polarity in -1.0..1.0)
        require(runningCoupling.isFinite() && runningCoupling >= 0.0)
        require(signedCoupling.isFinite())
    }

    val magnitude: Double get() = kotlin.math.abs(signedCoupling)
}

/**
 * Domain-neutral coupling kernel used by all LIFEOS fields.
 *
 * K_ij = g(mu) * M_iM_j * C_ij * R_ij * T_ij * P_ij
 *
 * Mass is normalized before interaction so unbounded semanticMass values cannot create an
 * unbounded numerical force. Domain-specific meaning remains outside this kernel.
 */
class WorldCouplingKernel(
    private val temporalDecay: Double = 1.0,
) {
    init { require(temporalDecay.isFinite() && temporalDecay >= 0.0) }

    fun couple(
        source: WorldStateSignature,
        target: WorldStateSignature,
        relationStrength: Double,
        interactionPolarity: Double,
        temporalDistance: Double = 0.0,
        scale: WorldCouplingScale = WorldCouplingScale(),
    ): WorldCouplingResult {
        require(relationStrength in 0.0..1.0) { "relationStrength must be in 0..1" }
        require(interactionPolarity in -1.0..1.0) { "interactionPolarity must be in -1..1" }
        require(temporalDistance.isFinite() && temporalDistance >= 0.0) {
            "temporalDistance must be finite and non-negative"
        }

        val sourceMass = normalizedMass(source.semanticMass)
        val targetMass = normalizedMass(target.semanticMass)
        val massInteraction = sqrt(sourceMass * targetMass).coerceIn(0.0, 1.0)
        val coherence = sqrt(source.coherence * target.coherence).coerceIn(0.0, 1.0)
        val relation = (relationStrength * sqrt(source.coupling * target.coupling)).coerceIn(0.0, 1.0)
        val temporal = exp(-temporalDecay * temporalDistance / scale.mu).coerceIn(0.0, 1.0)
        val signed = scale.runningCoupling * massInteraction * coherence * relation * temporal * interactionPolarity

        return WorldCouplingResult(
            massInteraction = massInteraction,
            coherence = coherence,
            relation = relation,
            temporal = temporal,
            polarity = interactionPolarity,
            runningCoupling = scale.runningCoupling,
            signedCoupling = signed,
        )
    }

    fun couple(
        source: WorldStateSignature,
        target: WorldStateSignature,
        relation: FieldRelation,
        temporalDistance: Double = 0.0,
        scale: WorldCouplingScale = WorldCouplingScale(),
    ): WorldCouplingResult = couple(
        source = source,
        target = target,
        relationStrength = relation.weight,
        interactionPolarity = relation.type.worldPolarity(),
        temporalDistance = temporalDistance,
        scale = scale,
    )

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-coupling-kernel/v1",
        fieldDouble(temporalDecay),
    )

    private fun normalizedMass(value: Double): Double = if (value == 0.0) 0.0 else value / (1.0 + value)
}

internal fun FieldRelationType.worldPolarity(): Double = when (this) {
    FieldRelationType.SUPPORTS,
    FieldRelationType.ATTRACTS,
    FieldRelationType.DERIVED_FROM,
    FieldRelationType.REFERS_TO,
    FieldRelationType.TEMPORALLY_PRECEDES,
    FieldRelationType.TEMPORALLY_FOLLOWS,
    -> 1.0

    FieldRelationType.CONTRADICTS,
    FieldRelationType.REPELS,
    -> -1.0

    FieldRelationType.CONSTRAINS,
    FieldRelationType.DEPENDS_ON,
    -> 0.5
}

fun WorldStateSignature.worldFingerprint(): String = StableFieldIds.fingerprint(
    "world-state/v1",
    fieldDouble(semanticMass),
    fieldDouble(energy),
    fieldDouble(phase),
    fieldDouble(polarity),
    fieldDouble(entropy),
    fieldDouble(coherence),
    fieldDouble(coupling),
    fieldDouble(temporalDepth),
    fieldDouble(potential),
)

package app.lifeos.core.field

import java.time.Duration
import kotlin.math.exp

data class FieldWeights(
    val confidence: Double = 0.30,
    val reliability: Double = 0.20,
    val authority: Double = 0.15,
    val temporalValidity: Double = 0.15,
    val contextCoherence: Double = 0.10,
    val semanticMass: Double = 0.10,
    val contradictionPenalty: Double = 1.0,
) {
    init {
        val positive = listOf(confidence, reliability, authority, temporalValidity, contextCoherence, semanticMass)
        require(positive.all { it.isFinite() && it >= 0.0 }) { "Field weights must be finite and non-negative" }
        require(positive.sum() > 0.0) { "At least one positive field weight is required" }
        require(contradictionPenalty.isFinite() && contradictionPenalty >= 0.0) {
            "Contradiction penalty must be finite and non-negative"
        }
    }

    val positiveWeightSum: Double
        get() = confidence + reliability + authority + temporalValidity + contextCoherence + semanticMass

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "field-weights/v1",
        fieldDouble(confidence),
        fieldDouble(reliability),
        fieldDouble(authority),
        fieldDouble(temporalValidity),
        fieldDouble(contextCoherence),
        fieldDouble(semanticMass),
        fieldDouble(contradictionPenalty),
    )
}

data class EvidenceForceBreakdown(
    val evidenceId: EvidenceId,
    val confidence: Double,
    val reliability: Double,
    val authority: Double,
    val temporalValidity: Double,
    val contextCoherence: Double,
    val semanticMass: Double,
    val composite: Double,
) {
    init {
        require(
            listOf(confidence, reliability, authority, temporalValidity, contextCoherence, semanticMass, composite)
                .all { it in 0.0..1.0 },
        ) { "Evidence force components must be in 0..1" }
    }
}

data class HypothesisForceBreakdown(
    val hypothesisId: HypothesisId,
    val support: Double,
    val contradiction: Double,
    val context: Double,
    val nodeCoherence: Double,
    val total: Double,
) {
    init {
        require(listOf(support, contradiction, context, nodeCoherence, total).all { it.isFinite() })
        require(support in 0.0..1.0)
        require(contradiction >= 0.0)
        require(context in 0.0..1.0)
        require(nodeCoherence in 0.0..1.0)
        require(total in 0.0..1.0)
    }
}

/** Deterministic force decomposition shared by all domain fields. */
class FieldForceCalculator(
    private val weights: FieldWeights = FieldWeights(),
    private val staleHalfLife: Duration = Duration.ofDays(3650),
) {
    init { require(!staleHalfLife.isZero && !staleHalfLife.isNegative) }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "force-calculator/v1",
        weights.fingerprint(),
        staleHalfLife.seconds.toString(),
        staleHalfLife.nano.toString(),
    )

    fun evidenceForce(
        evidence: FieldEvidence,
        context: FieldContext,
        semanticMass: Double = 1.0,
    ): EvidenceForceBreakdown {
        require(evidence.domainId == context.domain.domainId) { "Evidence and context domains must match" }
        require(semanticMass.isFinite() && semanticMass >= 0.0)

        val confidence = evidence.confidence
        val reliability = evidence.reliability.score
        val authority = evidence.authority.defaultWeight
        val temporal = temporalFactor(evidence, context)
        val contextCoherence = context.semanticSupport(evidence.semanticKey)
        val normalizedMass = semanticMass / (1.0 + semanticMass)

        val weighted =
            confidence * weights.confidence +
                reliability * weights.reliability +
                authority * weights.authority +
                temporal * weights.temporalValidity +
                contextCoherence * weights.contextCoherence +
                normalizedMass * weights.semanticMass
        val composite = (weighted / weights.positiveWeightSum).coerceIn(0.0, 1.0)

        return EvidenceForceBreakdown(
            evidenceId = evidence.id,
            confidence = confidence,
            reliability = reliability,
            authority = authority,
            temporalValidity = temporal,
            contextCoherence = contextCoherence,
            semanticMass = normalizedMass,
            composite = composite,
        )
    }

    fun relationForce(relation: FieldRelation, sourceEnergy: Double): FieldForce {
        require(sourceEnergy.isFinite() && sourceEnergy >= 0.0)
        val polarity = when (relation.type) {
            FieldRelationType.SUPPORTS,
            FieldRelationType.ATTRACTS,
            FieldRelationType.DERIVED_FROM,
            FieldRelationType.REFERS_TO,
            FieldRelationType.TEMPORALLY_PRECEDES,
            FieldRelationType.TEMPORALLY_FOLLOWS,
            -> ForcePolarity.ATTRACTION

            FieldRelationType.CONTRADICTS,
            FieldRelationType.REPELS,
            -> ForcePolarity.REPULSION

            FieldRelationType.CONSTRAINS,
            FieldRelationType.DEPENDS_ON,
            -> ForcePolarity.CONSTRAINT
        }
        return FieldForce(
            sourceNodeId = relation.source,
            targetNodeId = relation.target,
            polarity = polarity,
            magnitude = (relation.weight * sourceEnergy).coerceAtLeast(0.0),
            reason = "${relation.type.name.lowercase()}:${relation.explanation}",
        )
    }

    fun hypothesisForce(
        hypothesis: FieldHypothesis,
        evidenceById: Map<EvidenceId, FieldEvidence>,
        nodeEnergy: Map<FieldNodeId, Double>,
        context: FieldContext,
    ): HypothesisForceBreakdown {
        require(hypothesis.domainId == context.domain.domainId) { "Hypothesis and context domains must match" }

        var supportWeight = 0.0
        var supportSum = 0.0
        var contradiction = 0.0
        hypothesis.evidenceLinks.sortedBy { it.evidenceId.value }.forEach { link ->
            val evidence = evidenceById[link.evidenceId] ?: return@forEach
            val score = evidenceForce(evidence, context).composite * link.weight
            when (link.relation) {
                EvidenceRelationType.CONTRADICTS ->
                    contradiction += score * weights.contradictionPenalty

                EvidenceRelationType.SUPPORTS,
                EvidenceRelationType.REFINES,
                EvidenceRelationType.DERIVED_FROM,
                -> {
                    supportSum += score
                    supportWeight += link.weight
                }

                // Duplicate evidence remains traceable but contributes no independent support mass.
                EvidenceRelationType.DUPLICATES -> Unit
            }
        }
        val support = if (supportWeight > 0.0) (supportSum / supportWeight).coerceIn(0.0, 1.0) else 0.0
        val nodes = hypothesis.nodeIds.mapNotNull(nodeEnergy::get)
        val nodeCoherence = if (nodes.isEmpty()) 0.0 else nodes.average().coerceIn(0.0, 1.0)
        val contextScore = context.semanticSupport(hypothesis.semanticKey)
        val positive = support * 0.55 + nodeCoherence * 0.30 + contextScore * 0.15
        val total = (positive - contradiction * 0.55).coerceIn(0.0, 1.0)

        return HypothesisForceBreakdown(
            hypothesisId = hypothesis.id,
            support = support,
            contradiction = contradiction,
            context = contextScore,
            nodeCoherence = nodeCoherence,
            total = total,
        )
    }

    private fun temporalFactor(evidence: FieldEvidence, context: FieldContext): Double {
        if (!evidence.validity.contains(context.temporal.queryTime)) return 0.0
        val age = context.temporal.ageOf(evidence.observedAt)
        if (age.isNegative || age.isZero) return 1.0
        val halfLifeSeconds = staleHalfLife.seconds.coerceAtLeast(1).toDouble()
        return exp(-0.6931471805599453 * age.seconds.toDouble() / halfLifeSeconds).coerceIn(0.0, 1.0)
    }
}

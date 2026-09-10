package app.lifeos.core.field.world

import app.lifeos.core.field.StableFieldIds
import kotlin.math.abs

data class WorldTransferCoefficient(
    val id: WorldCoefficientId,
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
    val multiplier: Double,
    val confidenceMultiplier: Double = 1.0,
    val maxAbsoluteContribution: Double = 1.0,
    val explanation: String,
) {
    init {
        require(multiplier.isFinite() && multiplier in -1.0..1.0) {
            "World coefficient multiplier must be finite and in -1..1"
        }
        require(confidenceMultiplier.isFinite() && confidenceMultiplier in 0.0..1.0) {
            "World coefficient confidence multiplier must be finite and in 0..1"
        }
        require(maxAbsoluteContribution.isFinite() && maxAbsoluteContribution in 0.0..1.0) {
            "World coefficient contribution cap must be finite and in 0..1"
        }
        require(explanation.isNotBlank()) { "World coefficient explanation must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-transfer-coefficient/v1",
        id.value,
        sourceDimension.name,
        targetDimension.name,
        java.lang.Double.toHexString(multiplier),
        java.lang.Double.toHexString(confidenceMultiplier),
        java.lang.Double.toHexString(maxAbsoluteContribution),
        explanation,
    )

    companion object {
        fun create(
            semanticKey: String,
            sourceDimension: WorldSignalDimension,
            targetDimension: WorldSignalDimension,
            multiplier: Double,
            confidenceMultiplier: Double = 1.0,
            maxAbsoluteContribution: Double = 1.0,
            explanation: String,
        ): WorldTransferCoefficient {
            require(semanticKey.isNotBlank()) { "World coefficient semantic key must not be blank" }
            return WorldTransferCoefficient(
                id = WorldCoefficientId(
                    worldId(
                        "world-coefficient",
                        semanticKey,
                        sourceDimension.name,
                        targetDimension.name,
                    )
                ),
                sourceDimension = sourceDimension,
                targetDimension = targetDimension,
                multiplier = multiplier,
                confidenceMultiplier = confidenceMultiplier,
                maxAbsoluteContribution = maxAbsoluteContribution,
                explanation = explanation,
            )
        }
    }
}

data class WorldEquationSpec(
    val version: String,
    val coefficients: List<WorldTransferCoefficient>,
) {
    init {
        require(version.isNotBlank()) { "World equation version must not be blank" }
        require(coefficients.map { it.id }.distinct().size == coefficients.size) {
            "World equation coefficient ids must be unique"
        }
    }

    private val byId = coefficients.associateBy { it.id }

    fun coefficient(id: WorldCoefficientId): WorldTransferCoefficient? = byId[id]

    fun stableCoefficients(): List<WorldTransferCoefficient> = coefficients.sortedBy { it.id.value }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-spec/v1",
        version,
        *stableCoefficients().map { it.fingerprint() }.toTypedArray(),
    )
}

data class WorldEquationContribution(
    val edgeId: WorldFieldEdgeId,
    val sourceNodeId: WorldFieldNodeId,
    val targetNodeId: WorldFieldNodeId,
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
    val coefficientId: WorldCoefficientId,
    val signedDelta: Double,
    val confidence: Double,
    val provenanceFingerprint: String,
) {
    init {
        require(signedDelta.isFinite() && signedDelta in -1.0..1.0) {
            "World equation contribution must be finite and in -1..1"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "World equation contribution confidence must be finite and in 0..1"
        }
        require(provenanceFingerprint.isNotBlank()) {
            "World equation contribution requires provenance"
        }
    }
}

data class WorldEquationResult(
    val state: WorldFieldState,
    val contributions: List<WorldEquationContribution>,
    val equationFingerprint: String,
) {
    init {
        require(equationFingerprint.isNotBlank())
        require(state.equationFingerprint == equationFingerprint)
        require(contributions.map { it.edgeId }.distinct().size == contributions.size) {
            "A world equation step can emit at most one contribution per edge"
        }
    }
}

/**
 * One deterministic synchronous step of the field-of-fields equation.
 *
 * Values are aggregated only within their explicit target dimension. Cross-dimensional influence is
 * possible only when an edge names a coefficient whose source/target dimensions match exactly.
 * The result intentionally exposes no scalar total/truth score.
 */
class WorldFieldEquation(
    val spec: WorldEquationSpec,
) {
    fun evaluate(
        graph: WorldFieldGraph,
        state: WorldFieldState,
    ): WorldEquationResult {
        val graphFingerprint = graph.fingerprint()
        val equationFingerprint = spec.fingerprint()
        require(state.graphFingerprint == graphFingerprint) {
            "World state belongs to a different graph"
        }
        require(state.equationFingerprint == equationFingerprint) {
            "World state belongs to a different equation version"
        }
        require(state.vectors.keys == graph.stableNodes().mapTo(mutableSetOf()) { it.id }) {
            "World state must contain exactly the graph nodes"
        }

        val contributions = graph.stableEdges().mapNotNull { edge ->
            val coefficient = spec.coefficient(edge.coefficientId)
                ?: error("Missing world coefficient ${edge.coefficientId.value}")
            require(coefficient.sourceDimension == edge.sourceDimension) {
                "World edge source dimension does not match coefficient"
            }
            require(coefficient.targetDimension == edge.targetDimension) {
                "World edge target dimension does not match coefficient"
            }
            val sourceValue = state[edge.sourceNodeId]?.get(edge.sourceDimension)
                ?: return@mapNotNull null
            val rawDelta = sourceValue.value *
                sourceValue.confidence *
                edge.strength *
                coefficient.multiplier
            val signedDelta = rawDelta.coerceIn(
                -coefficient.maxAbsoluteContribution,
                coefficient.maxAbsoluteContribution,
            )
            val confidence = (
                sourceValue.confidence *
                    edge.strength *
                    coefficient.confidenceMultiplier
                ).coerceIn(0.0, 1.0)
            val provenance = StableFieldIds.fingerprint(
                "world-equation-contribution/v1",
                state.fingerprint(),
                edge.fingerprint(),
                coefficient.fingerprint(),
                sourceValue.fingerprint(),
            )
            WorldEquationContribution(
                edgeId = edge.id,
                sourceNodeId = edge.sourceNodeId,
                targetNodeId = edge.targetNodeId,
                sourceDimension = edge.sourceDimension,
                targetDimension = edge.targetDimension,
                coefficientId = coefficient.id,
                signedDelta = signedDelta,
                confidence = confidence,
                provenanceFingerprint = provenance,
            )
        }

        val contributionsByTarget = contributions.groupBy { it.targetNodeId }
        val nextVectors = graph.stableNodes().associate { node ->
            val incoming = contributionsByTarget[node.id].orEmpty()
            val dimensions = (
                node.intrinsic.dimensions() + incoming.map { it.targetDimension }
                ).toSortedSet(compareBy { it.name })
            val values = dimensions.map { dimension ->
                val intrinsic = node.intrinsic[dimension]
                val typedIncoming = incoming.filter { it.targetDimension == dimension }
                val delta = typedIncoming.sumOf { it.signedDelta }
                val nextValue = ((intrinsic?.value ?: 0.0) + delta).coerceIn(0.0, 1.0)
                val nextConfidence = maxOf(
                    intrinsic?.confidence ?: 0.0,
                    typedIncoming.maxOfOrNull { it.confidence } ?: 0.0,
                ).coerceIn(0.0, 1.0)
                val provenance = buildSet {
                    intrinsic?.provenanceFingerprints?.let(::addAll)
                    typedIncoming.mapTo(this) { it.provenanceFingerprint }
                }
                WorldDimensionValue(
                    dimension = dimension,
                    value = nextValue,
                    confidence = nextConfidence,
                    provenanceFingerprints = provenance,
                )
            }
            node.id to WorldFieldVector(values)
        }.toSortedMap(compareBy { it.value })

        val nextState = state.next(nextVectors)
        return WorldEquationResult(
            state = nextState,
            contributions = contributions.sortedBy { it.edgeId.value },
            equationFingerprint = equationFingerprint,
        )
    }

    fun validate(graph: WorldFieldGraph): List<String> = graph.stableEdges().mapNotNull { edge ->
        val coefficient = spec.coefficient(edge.coefficientId)
            ?: return@mapNotNull "edge:${edge.id.value}:missing-coefficient:${edge.coefficientId.value}"
        when {
            coefficient.sourceDimension != edge.sourceDimension ->
                "edge:${edge.id.value}:source-dimension-mismatch"
            coefficient.targetDimension != edge.targetDimension ->
                "edge:${edge.id.value}:target-dimension-mismatch"
            abs(coefficient.multiplier) == 0.0 && edge.strength > 0.0 ->
                "edge:${edge.id.value}:zero-transfer-coefficient"
            else -> null
        }
    }.sorted()
}

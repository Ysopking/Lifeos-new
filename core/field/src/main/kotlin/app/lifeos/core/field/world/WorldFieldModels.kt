package app.lifeos.core.field.world

import app.lifeos.core.field.StableFieldIds

@JvmInline
value class WorldFieldNodeId(val value: String) {
    init { require(value.isNotBlank()) { "World field node id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class WorldFieldEdgeId(val value: String) {
    init { require(value.isNotBlank()) { "World field edge id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class WorldCoefficientId(val value: String) {
    init { require(value.isNotBlank()) { "World coefficient id must not be blank" } }
    override fun toString(): String = value
}

enum class WorldNodeKind {
    DOMAIN_FIELD,
    PHOTON,
    EVIDENCE,
    HYPOTHESIS,
    GOAL,
    THOUGHT,
    CAPABILITY,
    HEALTH,
}

/**
 * Independent world-analysis dimensions. They are never implicitly collapsed into one truth score
 * and, while V4 is informational-only, none of them grant decision authority.
 */
enum class WorldSignalDimension {
    EVIDENCE_SUPPORT,
    RELIABILITY,
    AUTHORITY,
    UNCERTAINTY,
    CONFLICT_PRESSURE,
    TEMPORAL_FRESHNESS,
    SEMANTIC_RELEVANCE,
    GOAL_RELEVANCE,
    CONTEXT_RELEVANCE,
    ANALYTIC_SALIENCE,
    CAPABILITY_READINESS,
    HEALTH_STABILITY,
    COGNITIVE_PRIORITY,
}

data class WorldTargetRef(
    val kind: WorldNodeKind,
    val key: String,
) {
    init { require(key.isNotBlank()) { "World target key must not be blank" } }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-target-ref/v1",
        kind.name,
        key,
    )
}

data class WorldDimensionValue(
    val dimension: WorldSignalDimension,
    val value: Double,
    val confidence: Double,
    val provenanceFingerprints: Set<String>,
) {
    init {
        require(value.isFinite() && value in 0.0..1.0) {
            "World dimension value must be finite and in 0..1"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "World dimension confidence must be finite and in 0..1"
        }
        require(provenanceFingerprints.isNotEmpty()) {
            "World dimension value requires provenance"
        }
        require(provenanceFingerprints.none { it.isBlank() }) {
            "World dimension provenance must not be blank"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-dimension-value/v1",
        dimension.name,
        java.lang.Double.toHexString(value),
        java.lang.Double.toHexString(confidence),
        *provenanceFingerprints.sorted().toTypedArray(),
    )
}

/** A sparse typed vector. Missing dimensions are distinct from measured zero-valued dimensions. */
data class WorldFieldVector(
    val values: List<WorldDimensionValue> = emptyList(),
) {
    init {
        require(values.map { it.dimension }.distinct().size == values.size) {
            "World field vector cannot contain duplicate dimensions"
        }
    }

    private val byDimension: Map<WorldSignalDimension, WorldDimensionValue> =
        values.associateBy { it.dimension }

    operator fun get(dimension: WorldSignalDimension): WorldDimensionValue? = byDimension[dimension]

    fun dimensions(): Set<WorldSignalDimension> = byDimension.keys

    fun stableValues(): List<WorldDimensionValue> = values.sortedBy { it.dimension.name }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-field-vector/v1",
        *stableValues().map { it.fingerprint() }.toTypedArray(),
    )

    fun replacing(value: WorldDimensionValue): WorldFieldVector = WorldFieldVector(
        (values.filterNot { it.dimension == value.dimension } + value)
            .sortedBy { it.dimension.name }
    )

    companion object {
        val EMPTY = WorldFieldVector()
    }
}

internal fun worldId(prefix: String, vararg parts: String): String =
    "$prefix:${StableFieldIds.fingerprint(*parts)}"

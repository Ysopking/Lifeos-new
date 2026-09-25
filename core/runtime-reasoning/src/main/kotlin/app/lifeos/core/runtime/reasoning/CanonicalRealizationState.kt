package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

/**
 * B516 canonical realization state.
 *
 * A realization component keeps its concrete representation identity separate from the semantic
 * fingerprint used for equivalence. This allows LIFEOS to distinguish a concrete K-representation
 * from the semantic state class [K] without granting truth or execution authority to either.
 */
enum class RealizationComponentKind {
    PERSONAL_CONTEXT,
    PRODUCTIVE_WORLD,
    COGNITIVE_STATE,
    GOAL_STATE,
    RESOURCE_STATE,
    POLICY_STATE,
    SENSOR_STATE,
}

data class RealizationComponentRef(
    val kind: RealizationComponentKind,
    val representationId: String,
    val semanticFingerprint: String,
    val provenanceFingerprints: List<String> = emptyList(),
) {
    init {
        require(representationId.isNotBlank()) {
            "Realization component representation id must not be blank"
        }
        require(semanticFingerprint.isNotBlank()) {
            "Realization component semantic fingerprint must not be blank"
        }
        require(provenanceFingerprints.none { it.isBlank() }) {
            "Realization component provenance fingerprints must not be blank"
        }
        require(
            provenanceFingerprints == provenanceFingerprints.distinct().sorted()
        ) {
            "Realization component provenance must be unique and canonical"
        }
    }

    fun semanticIdentity(): String = StableFieldIds.fingerprint(
        "realization-component-semantic/v1",
        kind.name,
        semanticFingerprint,
    )

    fun representationFingerprint(): String = StableFieldIds.fingerprint(
        "realization-component-representation/v1",
        kind.name,
        representationId,
        semanticFingerprint,
        *provenanceFingerprints.toTypedArray(),
    )
}

data class CanonicalRealizationState private constructor(
    val id: String,
    val revisionId: String,
    val revision: Long,
    val asOf: Instant,
    val predecessorRevisionId: String?,
    val transitionFingerprint: String?,
    val components: List<RealizationComponentRef>,
    val equivalenceFingerprint: String,
    val representationFingerprint: String,
) {
    init {
        require(revision > 0L) { "Realization revision must be positive" }
        require(components.isNotEmpty()) { "Canonical realization state requires components" }
        require(components == components.sortedBy { it.kind.name }) {
            "Canonical realization components must be sorted by kind"
        }
        require(components.map { it.kind }.distinct().size == components.size) {
            "Canonical realization state allows only one component per kind"
        }
        require((predecessorRevisionId == null) == (transitionFingerprint == null)) {
            "Realization predecessor and transition must be present or absent together"
        }
        if (revision == 1L) {
            require(predecessorRevisionId == null) {
                "Initial realization revision cannot have a predecessor"
            }
        } else {
            require(!predecessorRevisionId.isNullOrBlank()) {
                "Non-initial realization revision requires a predecessor revision"
            }
            require(!transitionFingerprint.isNullOrBlank()) {
                "Non-initial realization revision requires a transition fingerprint"
            }
        }
        require(id == "realization-state:$equivalenceFingerprint") {
            "Canonical realization state id must bind semantic equivalence"
        }
        require(revisionId == "realization-revision:$representationFingerprint") {
            "Canonical realization revision id must bind concrete representation"
        }
        require(
            equivalenceFingerprint == equivalenceFingerprint(components)
        ) {
            "Canonical realization equivalence fingerprint does not match components"
        }
        require(
            representationFingerprint == representationFingerprint(
                revision = revision,
                asOf = asOf,
                predecessorRevisionId = predecessorRevisionId,
                transitionFingerprint = transitionFingerprint,
                components = components,
            )
        ) {
            "Canonical realization representation fingerprint does not match content"
        }
    }

    /** A canonical state is an epistemic representation, not a truth grant. */
    val truthAuthority: Boolean
        get() = false

    /** A canonical state cannot authorize effects merely by existing. */
    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            revision: Long,
            asOf: Instant,
            components: Collection<RealizationComponentRef>,
            predecessorRevisionId: String? = null,
            transitionFingerprint: String? = null,
        ): CanonicalRealizationState {
            require(revision > 0L)
            val canonical = components.sortedBy { it.kind.name }
            require(canonical.isNotEmpty())
            require(canonical.map { it.kind }.distinct().size == canonical.size) {
                "Canonical realization state allows only one component per kind"
            }

            val equivalence = equivalenceFingerprint(canonical)
            val representation = representationFingerprint(
                revision = revision,
                asOf = asOf,
                predecessorRevisionId = predecessorRevisionId,
                transitionFingerprint = transitionFingerprint,
                components = canonical,
            )

            return CanonicalRealizationState(
                id = "realization-state:$equivalence",
                revisionId = "realization-revision:$representation",
                revision = revision,
                asOf = asOf,
                predecessorRevisionId = predecessorRevisionId,
                transitionFingerprint = transitionFingerprint,
                components = canonical,
                equivalenceFingerprint = equivalence,
                representationFingerprint = representation,
            )
        }

        private fun equivalenceFingerprint(
            components: List<RealizationComponentRef>,
        ): String = StableFieldIds.fingerprint(
            "canonical-realization-equivalence/v1",
            *components.map { it.semanticIdentity() }.toTypedArray(),
        )

        private fun representationFingerprint(
            revision: Long,
            asOf: Instant,
            predecessorRevisionId: String?,
            transitionFingerprint: String?,
            components: List<RealizationComponentRef>,
        ): String = StableFieldIds.fingerprint(
            "canonical-realization-representation/v1",
            revision.toString(),
            asOf.toString(),
            predecessorRevisionId.orEmpty(),
            transitionFingerprint.orEmpty(),
            *components.map { it.representationFingerprint() }.toTypedArray(),
        )
    }
}

package app.lifeos.core.field

import app.lifeos.core.model.WorldStateSignature

enum class WorldInvariantSeverity { WARNING, BLOCKING }

data class WorldTransition(
    val previous: WorldStateSignature?,
    val current: WorldStateSignature,
)

data class WorldInvariantViolation(
    val invariantId: String,
    val severity: WorldInvariantSeverity,
    val explanation: String,
) {
    init {
        require(invariantId.isNotBlank())
        require(explanation.isNotBlank())
    }
}

interface WorldInvariant {
    val id: String
    val severity: WorldInvariantSeverity
    fun evaluate(transition: WorldTransition): String?
    fun fingerprint(): String = StableFieldIds.fingerprint("world-invariant/v1", id, severity.name)
}

data class WorldInvariantReport(
    val violations: List<WorldInvariantViolation>,
    val registryFingerprint: String,
) {
    val hasBlockingViolation: Boolean
        get() = violations.any { it.severity == WorldInvariantSeverity.BLOCKING }
}

/**
 * Deterministic registry for scale/domain-independent constraints.
 *
 * It is intentionally not a policy authority: OwnerPolicy, health, trust and resource gates remain
 * outside this registry. These invariants only describe mathematical state/transition validity.
 */
class WorldInvariantRegistry(
    invariants: List<WorldInvariant>,
) {
    private val ordered = invariants.sortedBy { it.id }

    init {
        require(ordered.map { it.id }.distinct().size == ordered.size) { "World invariant ids must be unique" }
    }

    fun evaluate(previous: WorldStateSignature?, current: WorldStateSignature): WorldInvariantReport {
        val transition = WorldTransition(previous, current)
        val violations = ordered.mapNotNull { invariant ->
            invariant.evaluate(transition)?.let { explanation ->
                WorldInvariantViolation(invariant.id, invariant.severity, explanation)
            }
        }
        return WorldInvariantReport(violations, fingerprint())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-invariant-registry/v1",
        *ordered.flatMap { listOf(it.id, it.severity.name, it.fingerprint()) }.toTypedArray(),
    )

    companion object {
        fun structural(): WorldInvariantRegistry = WorldInvariantRegistry(listOf(StructuralWorldStateInvariant))
    }
}

/** Explicitly seals the bounds guaranteed by WorldStateSignature into replay/audit evidence. */
object StructuralWorldStateInvariant : WorldInvariant {
    override val id: String = "world.structural.bounds"
    override val severity: WorldInvariantSeverity = WorldInvariantSeverity.BLOCKING

    override fun evaluate(transition: WorldTransition): String? {
        val state = transition.current
        if (!state.semanticMass.isFinite() || state.semanticMass < 0.0) return "invalid semantic mass"
        if (!state.energy.isFinite() || state.energy < 0.0) return "invalid energy"
        if (state.phase !in 0.0..1.0) return "invalid phase"
        if (state.polarity !in -1.0..1.0) return "invalid polarity"
        if (state.entropy !in 0.0..1.0) return "invalid entropy"
        if (state.coherence !in 0.0..1.0) return "invalid coherence"
        if (state.coupling !in 0.0..1.0) return "invalid coupling"
        if (!state.temporalDepth.isFinite() || state.temporalDepth < 0.0) return "invalid temporal depth"
        if (!state.potential.isFinite()) return "invalid potential"
        return null
    }
}

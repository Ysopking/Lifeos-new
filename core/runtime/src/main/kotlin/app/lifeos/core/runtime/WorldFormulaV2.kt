package app.lifeos.core.runtime

import app.lifeos.core.model.WorldCoupling

@JvmInline
value class WorldFormulaVersion(val value: String) { init { require(value.isNotBlank()) } }

enum class WorldEvaluationScope { LOCAL, REGIONAL, GLOBAL }

data class WorldFormulaDeltaInput(val key: String, val deltaMicros: Long) {
    init { require(key.isNotBlank()); require(deltaMicros in -1_000_000L..1_000_000L) }
}

data class WorldFormulaDeltaResult(
    val version: WorldFormulaVersion,
    val scope: WorldEvaluationScope,
    val deltasMicros: Map<String, Long>,
    val iterations: Int,
    val converged: Boolean,
)

/** Versioned delta propagation. It never owns policy, authority or scheduling decisions. */
class HierarchicalDeltaWorldFormula(
    val version: WorldFormulaVersion,
    private val epsilonMicros: Long = 1_000L,
    private val dampingMicros: Long = 500_000L,
) {
    init { require(epsilonMicros in 0..1_000_000L); require(dampingMicros in 1..1_000_000L) }

    fun evaluate(
        input: WorldFormulaDeltaInput,
        couplings: Collection<WorldCoupling>,
        scope: WorldEvaluationScope,
        maxIterations: Int,
        maxEdges: Int,
    ): WorldFormulaDeltaResult {
        require(maxIterations > 0 && maxEdges > 0)
        val edges = couplings.sortedBy { it.stableFingerprint }.take(maxEdges)
        var frontier = linkedMapOf(input.key to input.deltaMicros)
        val accumulated = linkedMapOf(input.key to input.deltaMicros)
        var iteration = 0
        while (frontier.isNotEmpty() && iteration < maxIterations) {
            val next = linkedMapOf<String, Long>()
            frontier.toSortedMap().forEach { (source, delta) ->
                edges.asSequence().filter { it.sourceKey == source }.forEach { edge ->
                    val propagated = delta * edge.strengthMicros / 1_000_000L * dampingMicros / 1_000_000L
                    if (kotlin.math.abs(propagated) >= epsilonMicros) {
                        next[edge.targetKey] = (next[edge.targetKey] ?: 0L) + propagated
                        accumulated[edge.targetKey] = (accumulated[edge.targetKey] ?: 0L) + propagated
                    }
                }
            }
            frontier = next
            iteration++
        }
        return WorldFormulaDeltaResult(version, scope, accumulated.toSortedMap(), iteration, frontier.isEmpty())
    }
}

data class ReproducibilityEnvelope(
    val worldRoot: String,
    val formulaVersion: WorldFormulaVersion,
    val inputRevisionKeys: Set<String>,
    val moduleFingerprints: Set<String>,
    val resourceBudgetFingerprint: String,
    val externalEvidenceFingerprints: Set<String>,
) {
    init { require(worldRoot.isNotBlank()); require(resourceBudgetFingerprint.isNotBlank()); require((inputRevisionKeys + moduleFingerprints + externalEvidenceFingerprints).none { it.isBlank() }) }
}

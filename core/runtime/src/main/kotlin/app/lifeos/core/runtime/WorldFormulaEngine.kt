package app.lifeos.core.runtime

import app.lifeos.core.model.WorldCoupling
import app.lifeos.core.model.WorldState

data class WorldFormulaBudget(
    val maxNodes: Int,
    val maxEdges: Int,
    val maxIterations: Int,
    val deadlineMillis: Long,
    val memoryBudgetBytes: Long,
) { init { require(maxNodes > 0 && maxEdges > 0 && maxIterations > 0 && deadlineMillis > 0 && memoryBudgetBytes > 0) } }

data class WorldDelta(val valuesMicros: Map<String, Long>, val iterations: Int, val truncated: Boolean)

/** Sparse, local, deterministic first kernel: F_i=sum(C_ij*S_j), delta=alpha*F. */
class WorldFormulaEngine(private val alphaMicros: Long = 250_000L) {
    init { require(alphaMicros in 1..1_000_000L) }

    fun evaluate(state: WorldState, changedKeys: Set<String>, couplings: Collection<WorldCoupling>, budget: WorldFormulaBudget): WorldDelta {
        val active = linkedSetOf<String>().apply { addAll(changedKeys.sorted()) }
        val edges = couplings.sortedBy { it.stableFingerprint }.take(budget.maxEdges)
        edges.forEach { if (it.sourceKey in active && active.size < budget.maxNodes) active += it.targetKey }
        var values = active.associateWith { 1_000_000L }.toMutableMap()
        var iteration = 0
        while (iteration < budget.maxIterations) {
            val next = values.toMutableMap()
            edges.filter { it.sourceKey in active && it.targetKey in active }.forEach { edge ->
                val source = values[edge.sourceKey] ?: 0L
                val force = source * edge.strengthMicros / 1_000_000L
                val delta = force * alphaMicros / 1_000_000L
                next[edge.targetKey] = (next[edge.targetKey] ?: 0L) + delta
            }
            iteration++
            if (next == values) break
            values = next
        }
        return WorldDelta(values.toSortedMap(), iteration, couplings.size > budget.maxEdges || active.size >= budget.maxNodes)
    }
}

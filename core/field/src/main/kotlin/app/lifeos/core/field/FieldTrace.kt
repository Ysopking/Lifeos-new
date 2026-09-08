package app.lifeos.core.field

data class NodeEvidenceForceTrace(
    val nodeId: FieldNodeId,
    val breakdown: EvidenceForceBreakdown,
)

data class FieldSeedTrace(
    val nodeEvidenceForces: List<NodeEvidenceForceTrace>,
    val hypothesisForces: List<HypothesisForceBreakdown>,
    val initialEnergyFingerprint: String,
)

data class DomainFieldEvaluationTrace(
    val descriptor: DomainFieldDescriptor,
    val evaluation: DomainFieldEvaluation,
)

data class FieldIterationTrace(
    val iteration: FieldIteration,
    val beforeEnergyFingerprint: String,
    val afterEnergy: FieldEnergySnapshot,
    val relationForces: List<FieldForce>,
    val domainEvaluations: List<DomainFieldEvaluationTrace>,
    val mergedHypothesisBias: Map<HypothesisId, Double>,
) {
    init {
        require(iteration.fingerprint == afterEnergy.fingerprint()) {
            "Iteration trace must reference the resulting energy fingerprint"
        }
        require(mergedHypothesisBias.values.all { it.isFinite() && it in -1.0..1.0 }) {
            "Merged hypothesis bias must be finite and in -1..1"
        }
    }
}

/**
 * Complete deterministic explanation of one convergence run.
 *
 * The trace retains the seed force decomposition and every iterative force/bias application. It is
 * immutable and contains no clock-derived metadata, so equivalent inputs produce equal traces.
 */
data class FieldTrace(
    val runId: FieldRunId,
    val domainId: FieldDomainId,
    val inputFingerprint: String,
    val fieldSetFingerprint: String,
    val seed: FieldSeedTrace,
    val graphConflicts: List<FieldConflict>,
    val iterations: List<FieldIterationTrace>,
    val status: ConvergenceStatus,
    val finalEnergyFingerprint: String,
) {
    init {
        require(inputFingerprint.isNotBlank())
        require(fieldSetFingerprint.isNotBlank())
        require(finalEnergyFingerprint.isNotBlank())
        require(iterations.map { it.iteration.index } == (1..iterations.size).toList()) {
            "Trace iterations must be contiguous and one-based"
        }
        require(iterations.lastOrNull()?.afterEnergy?.fingerprint() ?: seed.initialEnergyFingerprint == finalEnergyFingerprint) {
            "Final energy fingerprint must match the final trace state"
        }
    }

    fun fingerprint(): String {
        val parts = buildList {
            add("field-trace/v1")
            add(runId.value)
            add(domainId.value)
            add(inputFingerprint)
            add(fieldSetFingerprint)
            add(seed.initialEnergyFingerprint)

            seed.nodeEvidenceForces
                .sortedWith(compareBy<NodeEvidenceForceTrace> { it.nodeId.value }.thenBy { it.breakdown.evidenceId.value })
                .forEach { trace ->
                    val value = trace.breakdown
                    add("seed-evidence")
                    add(trace.nodeId.value)
                    add(value.evidenceId.value)
                    add(traceDouble(value.confidence))
                    add(traceDouble(value.reliability))
                    add(traceDouble(value.authority))
                    add(traceDouble(value.temporalValidity))
                    add(traceDouble(value.contextCoherence))
                    add(traceDouble(value.semanticMass))
                    add(traceDouble(value.composite))
                }
            seed.hypothesisForces.sortedBy { it.hypothesisId.value }.forEach { value ->
                add("seed-hypothesis")
                add(value.hypothesisId.value)
                add(traceDouble(value.support))
                add(traceDouble(value.contradiction))
                add(traceDouble(value.context))
                add(traceDouble(value.nodeCoherence))
                add(traceDouble(value.total))
            }
            graphConflicts.sortedBy { it.key }.forEach { addConflict(it) }

            iterations.forEach { step ->
                add("iteration")
                add(step.iteration.index.toString())
                add(traceDouble(step.iteration.maxDelta))
                add(step.iteration.stableRounds.toString())
                add(step.beforeEnergyFingerprint)
                add(step.afterEnergy.fingerprint())
                step.relationForces.stableTraceOrder().forEach { addForce(it) }
                step.domainEvaluations.sortedWith(
                    compareByDescending<DomainFieldEvaluationTrace> { it.descriptor.priority }
                        .thenBy { it.descriptor.domainId.value }
                        .thenBy { it.descriptor.name.trim().lowercase() }
                        .thenBy { it.descriptor.version },
                ).forEach { domainTrace ->
                    val descriptor = domainTrace.descriptor
                    add("domain-evaluation")
                    add(descriptor.domainId.value)
                    add(descriptor.name.trim().lowercase())
                    add(descriptor.version.toString())
                    add(descriptor.priority.toString())
                    domainTrace.evaluation.forces.stableTraceOrder().forEach { addForce(it) }
                    domainTrace.evaluation.hypothesisBias.entries.sortedBy { it.key.value }.forEach { (id, bias) ->
                        add("domain-bias")
                        add(id.value)
                        add(traceDouble(bias))
                    }
                    domainTrace.evaluation.conflicts.sortedBy { it.key }.forEach { addConflict(it) }
                }
                step.mergedHypothesisBias.entries.sortedBy { it.key.value }.forEach { (id, bias) ->
                    add("merged-bias")
                    add(id.value)
                    add(traceDouble(bias))
                }
            }
            add(status.name)
            add(finalEnergyFingerprint)
        }
        return StableFieldIds.fingerprint(*parts.toTypedArray())
    }

    private fun MutableList<String>.addForce(force: FieldForce) {
        add("force")
        add(force.sourceNodeId.value)
        add(force.targetNodeId.value)
        add(force.polarity.name)
        add(traceDouble(force.magnitude))
        add(force.reason)
        force.evidenceIds.sortedBy { it.value }.forEach { add(it.value) }
    }

    private fun MutableList<String>.addConflict(conflict: FieldConflict) {
        add("conflict")
        add(conflict.key)
        add(conflict.leftNodeId.value)
        add(conflict.rightNodeId.value)
        add(traceDouble(conflict.severity))
        add(conflict.explanation)
    }
}

private fun List<FieldForce>.stableTraceOrder(): List<FieldForce> = sortedWith(
    compareBy<FieldForce> { it.targetNodeId.value }
        .thenBy { it.sourceNodeId.value }
        .thenBy { it.polarity.name }
        .thenBy { it.reason }
        .thenBy { it.magnitude },
)

private fun traceDouble(value: Double): String = java.lang.Double.toHexString(value)

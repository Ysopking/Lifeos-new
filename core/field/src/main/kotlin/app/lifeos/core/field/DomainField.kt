package app.lifeos.core.field

data class DomainFieldDescriptor(
    val domainId: FieldDomainId,
    val name: String,
    val version: Int,
    val priority: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Domain field name must not be blank" }
        require(version > 0) { "Domain field version must be positive" }
    }
}

data class DomainFieldSeed(
    val graph: FieldGraph,
    val hypotheses: List<FieldHypothesis>,
) {
    init {
        require(hypotheses.all { it.domainId == graph.domainId }) {
            "Seed hypotheses must belong to graph domain"
        }
        val nodeIds = graph.nodes.mapTo(mutableSetOf()) { it.id }
        require(hypotheses.all { hypothesis -> hypothesis.nodeIds.all(nodeIds::contains) }) {
            "Seed hypotheses must reference graph nodes"
        }
    }
}

data class DomainFieldEvaluation(
    val forces: List<FieldForce> = emptyList(),
    val hypothesisBias: Map<HypothesisId, Double> = emptyMap(),
    val conflicts: List<FieldConflict> = emptyList(),
) {
    init {
        require(hypothesisBias.values.all { it.isFinite() && it in -1.0..1.0 }) {
            "Hypothesis bias must be finite and in -1..1"
        }
    }
}

/**
 * Domain-specific physics plug into the common convergence engine through this contract.
 * A field may emit forces/biases, but must never mutate source evidence or the current state.
 */
interface DomainField {
    val descriptor: DomainFieldDescriptor

    fun accepts(evidence: FieldEvidence): Boolean = evidence.domainId == descriptor.domainId

    fun seed(
        evidence: List<FieldEvidence>,
        context: FieldContext,
    ): DomainFieldSeed

    fun evaluate(
        state: FieldState,
        graph: FieldGraph,
        context: FieldContext,
    ): DomainFieldEvaluation = DomainFieldEvaluation()
}

fun List<DomainField>.stableDomainFieldOrder(): List<DomainField> =
    sortedWith(
        compareByDescending<DomainField> { it.descriptor.priority }
            .thenBy { it.descriptor.domainId.value }
            .thenBy { it.descriptor.name }
            .thenBy { it.descriptor.version },
    )

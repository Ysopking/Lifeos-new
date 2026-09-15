package app.lifeos.core.runtime.context

import app.lifeos.core.field.DomainField
import app.lifeos.core.field.DomainFieldDescriptor
import app.lifeos.core.field.DomainFieldEvaluation
import app.lifeos.core.field.DomainFieldSeed
import app.lifeos.core.field.FieldContext
import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldForce
import app.lifeos.core.field.FieldGraph
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldState
import app.lifeos.core.field.ForcePolarity
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.PhotonContextReference
import kotlin.math.abs

/**
 * Cross-cutting context physics for one target domain.
 *
 * The field consumes only the immutable [FieldContext] snapshot prepared before convergence. It
 * never reads persistence, creates Evidence, or mutates the request graph/state. Equal context
 * support stays equal; competition penalties are applied only when support is materially lower.
 */
class ContextDomainField(
    domainId: FieldDomainId,
    hypotheses: List<FieldHypothesis>,
) : DomainField {
    override val descriptor: DomainFieldDescriptor = DomainFieldDescriptor(
        domainId = domainId,
        name = NAME,
        version = VERSION,
        priority = PRIORITY,
    )

    private val hypotheses = hypotheses
        .filter { it.domainId == domainId }
        .sortedBy { it.id.value }

    init {
        require(this.hypotheses.size == hypotheses.size) {
            "Context field hypotheses must belong to the configured domain"
        }
        require(this.hypotheses.map { it.id }.distinct().size == this.hypotheses.size) {
            "Context field hypotheses must be unique"
        }
    }

    override fun seed(
        evidence: List<app.lifeos.core.field.FieldEvidence>,
        context: FieldContext,
    ): DomainFieldSeed = DomainFieldSeed(
        graph = FieldGraph(domainId = descriptor.domainId, nodes = emptyList()),
        hypotheses = emptyList(),
    )

    override fun evaluate(
        state: FieldState,
        graph: FieldGraph,
        context: FieldContext,
    ): DomainFieldEvaluation {
        require(state.domainId == descriptor.domainId) { "Context field state domain mismatch" }
        require(graph.domainId == descriptor.domainId) { "Context field graph domain mismatch" }
        require(context.domain.domainId == descriptor.domainId) { "Context field context domain mismatch" }

        val references = context.relevantReferences()
        if (references.isEmpty()) return DomainFieldEvaluation()

        val nodeSignals = graph.stableNodes().associate { node ->
            node.id to signalFor(node.semanticTerms(), references)
        }
        val hypothesisSignals = hypotheses.associate { hypothesis ->
            val terms = buildSet {
                addAll(semanticTerms(hypothesis.semanticKey))
                hypothesis.nodeIds.sortedBy { it.value }.forEach { nodeId ->
                    graph.node(nodeId)?.let { addAll(it.semanticTerms()) }
                }
            }
            hypothesis.id to signalFor(terms, references)
        }

        val bias = hypothesisSignals
            .mapValues { (_, signal) -> signal.support * POSITIVE_BIAS_SCALE }
            .toMutableMap()

        graph.competitionGroups.sortedBy { it.key }.forEach { group ->
            val competingHypotheses = hypotheses.filter { hypothesis ->
                hypothesis.nodeIds.any(group.nodeIds::contains)
            }
            if (competingHypotheses.size < 2) return@forEach
            val maxSupport = competingHypotheses.maxOf { hypothesis ->
                hypothesisSignals[hypothesis.id]?.support ?: 0.0
            }
            if (maxSupport <= EPSILON) return@forEach
            competingHypotheses.forEach { hypothesis ->
                val support = hypothesisSignals[hypothesis.id]?.support ?: 0.0
                val gap = maxSupport - support
                if (gap > EPSILON) {
                    bias[hypothesis.id] = ((bias[hypothesis.id] ?: 0.0) - gap * COMPETITION_BIAS_SCALE)
                        .coerceIn(-1.0, 1.0)
                }
            }
        }

        val forces = mutableListOf<FieldForce>()
        val competitionPairs = graph.competitionGroups
            .flatMap { group ->
                group.nodeIds.flatMap { a -> group.nodeIds.filter { b -> a != b }.map { b -> a to b } }
            }
            .toSet()

        val nodes = graph.stableNodes()
        for (leftIndex in nodes.indices) {
            for (rightIndex in leftIndex + 1 until nodes.size) {
                val left = nodes[leftIndex]
                val right = nodes[rightIndex]
                val leftSignal = nodeSignals.getValue(left.id)
                val rightSignal = nodeSignals.getValue(right.id)
                val shared = leftSignal.referenceIds.intersect(rightSignal.referenceIds)
                if (
                    shared.isNotEmpty() &&
                    leftSignal.support > EPSILON &&
                    rightSignal.support > EPSILON &&
                    (left.id to right.id) !in competitionPairs
                ) {
                    val magnitude = minOf(leftSignal.support, rightSignal.support) * ATTRACTION_SCALE
                    forces += FieldForce(
                        sourceNodeId = left.id,
                        targetNodeId = right.id,
                        polarity = ForcePolarity.ATTRACTION,
                        magnitude = magnitude,
                        reason = "context-shared-reference:${shared.sorted().joinToString(",")}",
                    )
                    forces += FieldForce(
                        sourceNodeId = right.id,
                        targetNodeId = left.id,
                        polarity = ForcePolarity.ATTRACTION,
                        magnitude = magnitude,
                        reason = "context-shared-reference:${shared.sorted().joinToString(",")}",
                    )
                }
            }
        }

        graph.competitionGroups.sortedBy { it.key }.forEach { group ->
            val ordered = group.nodeIds.sortedBy { it.value }
            val maxSupport = ordered.maxOfOrNull { nodeSignals[it]?.support ?: 0.0 } ?: 0.0
            if (maxSupport <= EPSILON) return@forEach
            val strongest = ordered.filter {
                abs((nodeSignals[it]?.support ?: 0.0) - maxSupport) <= EPSILON
            }
            val source = strongest.firstOrNull() ?: return@forEach
            ordered.filter { it !in strongest }.forEach { target ->
                val targetSupport = nodeSignals[target]?.support ?: 0.0
                val gap = maxSupport - targetSupport
                if (gap > EPSILON) {
                    forces += FieldForce(
                        sourceNodeId = source,
                        targetNodeId = target,
                        polarity = ForcePolarity.REPULSION,
                        magnitude = gap * REPULSION_SCALE,
                        reason = "context-competition:${group.key}",
                    )
                }
            }
        }

        return DomainFieldEvaluation(
            forces = forces.sortedWith(
                compareBy<FieldForce> { it.targetNodeId.value }
                    .thenBy { it.sourceNodeId.value }
                    .thenBy { it.polarity.name }
                    .thenBy { it.reason }
            ),
            hypothesisBias = bias
                .filterValues { abs(it) > EPSILON }
                .toSortedMap(compareBy<HypothesisId> { it.value }),
        )
    }

    private data class ContextSignal(
        val support: Double,
        val referenceIds: Set<String>,
    )

    private fun signalFor(
        semanticTerms: Set<String>,
        references: List<PhotonContextReference>,
    ): ContextSignal {
        if (semanticTerms.isEmpty()) return ContextSignal(0.0, emptySet())
        val matches = references.mapNotNull { reference ->
            val referenceTerms = reference.semanticTerms.flatMapTo(mutableSetOf(), ::semanticTerms)
            val overlap = semanticTerms.intersect(referenceTerms)
            if (overlap.isEmpty()) return@mapNotNull null
            val lexical = (overlap.size.toDouble() / semanticTerms.size.toDouble()).coerceIn(0.0, 1.0)
            val scopeWeight = reference.scopes.maxOfOrNull(::scopeWeight) ?: 0.0
            val support = (reference.confidence * scopeWeight * lexical).coerceIn(0.0, 1.0)
            reference.photonId.value to support
        }
        return ContextSignal(
            support = matches.maxOfOrNull { it.second } ?: 0.0,
            referenceIds = matches.filter { it.second > EPSILON }.mapTo(sortedSetOf()) { it.first },
        )
    }

    private fun FieldNode.semanticTerms(): Set<String> = buildSet {
        addAll(semanticTerms(semanticKey))
        attributes.toSortedMap().forEach { (key, value) ->
            addAll(semanticTerms(key))
            addAll(semanticTerms(value))
        }
    }

    private fun semanticTerms(value: String): Set<String> {
        val normalized = value.trim().lowercase().replace("ß", "ss")
        if (normalized.isBlank()) return emptySet()
        return buildSet {
            add(normalized)
            normalized.split(Regex("[^\\p{L}\\p{N}_-]+"))
                .map(String::trim)
                .filter { it.isNotBlank() }
                .forEach(::add)
        }
    }

    private fun scopeWeight(scope: FieldContextScope): Double = when (scope) {
        FieldContextScope.CURRENT_GOAL -> 1.00
        FieldContextScope.CURRENT_TASK -> 1.00
        FieldContextScope.CURRENT_CONVERSATION -> 0.95
        FieldContextScope.CURRENT_PROJECT -> 0.85
        FieldContextScope.PERSON_CONTEXT -> 0.75
        FieldContextScope.DOCUMENT_CONTEXT -> 0.75
        FieldContextScope.ORGANIZATION_CONTEXT,
        FieldContextScope.FINANCIAL_CONTEXT,
        FieldContextScope.LEGAL_CONTEXT,
        FieldContextScope.SCIENTIFIC_CONTEXT -> 0.80
        FieldContextScope.GLOBAL_MEMORY -> 0.65
    }

    companion object {
        const val NAME: String = "durable-context"
        const val VERSION: Int = 1
        const val PRIORITY: Int = 60
        private const val POSITIVE_BIAS_SCALE = 0.80
        private const val COMPETITION_BIAS_SCALE = 0.80
        private const val ATTRACTION_SCALE = 0.20
        private const val REPULSION_SCALE = 0.25
        private const val EPSILON = 1e-12
    }
}

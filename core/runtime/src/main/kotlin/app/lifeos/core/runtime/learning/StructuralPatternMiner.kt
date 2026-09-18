package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet

data class PatternSignature(
    val sourceKind: ThoughtGraphNodeKind,
    val relationKind: ThoughtGraphEdgeKind,
    val targetKind: ThoughtGraphNodeKind,
) {
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "structural-pattern-signature/v1",
        sourceKind.name,
        relationKind.name,
        targetKind.name,
    )
}

data class PatternOccurrence(
    val cycleId: String,
    val workingSetFingerprint: String,
    val nodeIds: Set<String>,
    val signature: PatternSignature,
) {
    init {
        require(cycleId.isNotBlank())
        require(workingSetFingerprint.isNotBlank())
        require(nodeIds.size in 2..8)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "structural-pattern-occurrence/v1",
        cycleId,
        workingSetFingerprint,
        signature.fingerprint(),
        *nodeIds.sorted().toTypedArray(),
    )
}

data class CandidateCluster(
    val signature: PatternSignature,
    val occurrences: List<PatternOccurrence>,
) {
    init {
        require(occurrences.isNotEmpty())
        require(occurrences.all { it.signature == signature })
        require(occurrences.size <= StructuralPatternMiner.MAX_OCCURRENCES)
    }

    val supportCount: Int get() = occurrences.size
}

class StructuralPatternMiner(
    private val maxNodesPerPattern: Int = MAX_NODES_PER_PATTERN,
    private val maxCandidatePatterns: Int = MAX_CANDIDATE_PATTERNS,
    private val maxOccurrences: Int = MAX_OCCURRENCES,
) {
    init {
        require(maxNodesPerPattern in 2..MAX_NODES_PER_PATTERN)
        require(maxCandidatePatterns in 1..MAX_CANDIDATE_PATTERNS)
        require(maxOccurrences in 1..MAX_OCCURRENCES)
    }

    fun mine(
        workingSetsByCycle: Map<String, ThoughtGraphWorkingSet>,
    ): List<CandidateCluster> {
        val occurrences = mutableListOf<PatternOccurrence>()
        workingSetsByCycle.toSortedMap().forEach { (cycleId, workingSet) ->
            val nodes = workingSet.nodes.associateBy { it.id }
            workingSet.edges
                .sortedBy { it.id.value }
                .forEach { edge ->
                    if (occurrences.size >= maxOccurrences) return@forEach
                    val source = nodes[edge.sourceNodeId] ?: return@forEach
                    val target = nodes[edge.targetNodeId] ?: return@forEach
                    val ids = linkedSetOf(source.id.value, target.id.value)
                    if (ids.size > maxNodesPerPattern) return@forEach
                    occurrences += PatternOccurrence(
                        cycleId = cycleId,
                        workingSetFingerprint = workingSet.fingerprint,
                        nodeIds = ids,
                        signature = PatternSignature(
                            sourceKind = source.kind,
                            relationKind = edge.kind,
                            targetKind = target.kind,
                        ),
                    )
                }
        }

        return occurrences
            .distinctBy { it.fingerprint() }
            .groupBy { it.signature }
            .map { (signature, items) ->
                CandidateCluster(
                    signature = signature,
                    occurrences = items.sortedBy { it.fingerprint() }.take(maxOccurrences),
                )
            }
            .sortedWith(
                compareByDescending<CandidateCluster> { it.supportCount }
                    .thenBy { it.signature.fingerprint() }
            )
            .take(maxCandidatePatterns)
    }

    companion object {
        const val MAX_NODES_PER_PATTERN = 8
        const val MAX_CANDIDATE_PATTERNS = 64
        const val MAX_OCCURRENCES = 256
    }
}

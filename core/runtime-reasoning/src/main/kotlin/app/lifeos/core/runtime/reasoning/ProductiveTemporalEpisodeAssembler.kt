package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

data class ProductiveTemporalEpisodeInput(
    val observations: List<TemporalEpisodeNode> = emptyList(),
    val goals: List<TemporalEpisodeNode> = emptyList(),
    val actions: List<TemporalEpisodeNode> = emptyList(),
    val receipts: List<TemporalEpisodeNode> = emptyList(),
    val outcomes: List<TemporalEpisodeNode> = emptyList(),
    val explicitRelations: List<ProductiveTemporalRelation> = emptyList(),
) {
    init {
        require(observations.all { it.kind == TemporalEpisodeNodeKind.OBSERVATION })
        require(goals.all { it.kind == TemporalEpisodeNodeKind.GOAL })
        require(actions.all { it.kind == TemporalEpisodeNodeKind.ACTION })
        require(receipts.all { it.kind == TemporalEpisodeNodeKind.RECEIPT })
        require(outcomes.all { it.kind == TemporalEpisodeNodeKind.OUTCOME })
    }
}

data class ProductiveTemporalRelation(
    val source: TemporalEpisodeNodeId,
    val target: TemporalEpisodeNodeId,
    val kind: TemporalEpisodeEdgeKind,
    val evidenceFingerprint: String,
) {
    init {
        require(source != target)
        require(evidenceFingerprint.isNotBlank())
    }
}

/**
 * B489 deterministic episode assembly. Chronology is association only; it never fabricates
 * causal knowledge. Typed RECEIPT_FOR / EXPECTS / VERIFIES relations require explicit evidence.
 */
class ProductiveTemporalEpisodeAssembler {
    fun assemble(input: ProductiveTemporalEpisodeInput): TemporalEpisodeGraph {
        val nodes = (
            input.observations +
                input.goals +
                input.actions +
                input.receipts +
                input.outcomes
            )
            .distinctBy { it.id }
            .sortedWith(
                compareBy<TemporalEpisodeNode> { it.occurredAt }
                    .thenBy { it.id.value }
            )
        require(nodes.isNotEmpty())

        val nodeIds = nodes.map { it.id }.toSet()
        input.explicitRelations.forEach { relation ->
            require(relation.source in nodeIds)
            require(relation.target in nodeIds)
        }

        val chronological = nodes.zipWithNext().map { (source, target) ->
            TemporalEpisodeEdge.create(
                source = source.id,
                target = target.id,
                kind = TemporalEpisodeEdgeKind.PRECEDES,
                provenanceFingerprint = StableFieldIds.fingerprint(
                    "productive-temporal-precedes/v1",
                    source.id.value,
                    target.id.value,
                ),
            )
        }

        val explicit = input.explicitRelations.map { relation ->
            TemporalEpisodeEdge.create(
                source = relation.source,
                target = relation.target,
                kind = relation.kind,
                provenanceFingerprint = relation.evidenceFingerprint,
            )
        }

        return TemporalEpisodeGraph.create(
            nodes = nodes,
            edges = (chronological + explicit).distinctBy { it.id },
        )
    }
}

package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

@JvmInline
value class SemanticPropositionNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "semantic-proposition:"
    }
}

@JvmInline
value class SemanticPropositionEdgeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "semantic-proposition-edge:"
    }
}

enum class PropositionSourceKind {
    DIRECT_SPEAKER,
    QUOTED_SPEECH,
    CONTEXT_INFERRED,
}

data class PropositionSource(
    val kind: PropositionSourceKind,
    val actorReference: PhotonRevisionRef? = null,
    val attributionResolved: Boolean = actorReference != null,
) {
    init {
        require(attributionResolved == (actorReference != null)) {
            "Proposition source attribution flag must match actor reference"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "proposition-source/v1",
        kind.name,
        actorReference?.stableKey.orEmpty(),
        attributionResolved.toString(),
    )
}

enum class PropositionRelationType {
    COORDINATED_WITH,
    ALTERNATIVE_TO,
    PRECEDES,
    FOLLOWS,
    CONDITION_FOR,
    DEPENDS_ON,
    REFERENCES_RESULT_OF,
    CLAIMS_CAUSAL_RELATION,
    PURPOSE_OF,
}

data class SemanticPropositionNode(
    val id: SemanticPropositionNodeId,
    val sourceActionNodeId: SemanticNodeId,
    val clauseId: Int,
    val predicate: PredicateConcept,
    val roles: Map<SemanticRole, SemanticValue>,
    val source: PropositionSource,
    val realization: LanguagePropositionRealization,
    val confidence: Double,
) {
    init {
        require(clauseId >= 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(realization.nodeId == sourceActionNodeId)
        require(id == expectedId())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "semantic-proposition-node/v1",
        sourceActionNodeId.value,
        clauseId.toString(),
        predicate.name,
        source.fingerprint,
        realization.fingerprint,
        java.lang.Double.toHexString(confidence),
        *roles.entries
            .sortedBy { it.key.name }
            .flatMap { (role, value) ->
                listOf(
                    role.name,
                    value.rawText,
                    value.normalized,
                    value.entityType?.name.orEmpty(),
                    value.referencePhoton?.stableKey.orEmpty(),
                    value.resolved.toString(),
                    java.lang.Double.toHexString(value.confidence),
                )
            }
            .toTypedArray(),
    )

    val directWorldTruthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    private fun expectedId(): SemanticPropositionNodeId =
        SemanticPropositionNodeId(
            SemanticPropositionNodeId.PREFIX + fingerprint
        )

    companion object {
        fun create(
            actionNode: SemanticActionNode,
            source: PropositionSource,
            realization: LanguagePropositionRealization,
        ): SemanticPropositionNode {
            val fingerprint = StableCognitiveIds.fingerprint(
                "semantic-proposition-node/v1",
                actionNode.id.value,
                actionNode.frame.clauseId.toString(),
                actionNode.frame.predicate.name,
                source.fingerprint,
                realization.fingerprint,
                java.lang.Double.toHexString(actionNode.frame.confidence),
                *actionNode.frame.roles.entries
                    .sortedBy { it.key.name }
                    .flatMap { (role, value) ->
                        listOf(
                            role.name,
                            value.rawText,
                            value.normalized,
                            value.entityType?.name.orEmpty(),
                            value.referencePhoton?.stableKey.orEmpty(),
                            value.resolved.toString(),
                            java.lang.Double.toHexString(value.confidence),
                        )
                    }
                    .toTypedArray(),
            )
            return SemanticPropositionNode(
                id = SemanticPropositionNodeId(
                    SemanticPropositionNodeId.PREFIX + fingerprint
                ),
                sourceActionNodeId = actionNode.id,
                clauseId = actionNode.frame.clauseId,
                predicate = actionNode.frame.predicate,
                roles = actionNode.frame.roles.toSortedMap(compareBy { it.name }),
                source = source,
                realization = realization,
                confidence = actionNode.frame.confidence,
            )
        }
    }
}

data class SemanticPropositionEdge(
    val id: SemanticPropositionEdgeId,
    val from: SemanticPropositionNodeId,
    val to: SemanticPropositionNodeId,
    val relation: PropositionRelationType,
    val sourceActionEdgeId: SemanticActionEdgeId,
    val confidence: Double,
) {
    init {
        require(from != to)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(id == expectedId())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "semantic-proposition-edge/v1",
        from.value,
        to.value,
        relation.name,
        sourceActionEdgeId.value,
        java.lang.Double.toHexString(confidence),
    )

    val worldCausalityAuthority: Boolean
        get() = false

    private fun expectedId(): SemanticPropositionEdgeId =
        SemanticPropositionEdgeId(
            SemanticPropositionEdgeId.PREFIX + fingerprint
        )

    companion object {
        fun create(
            from: SemanticPropositionNodeId,
            to: SemanticPropositionNodeId,
            relation: PropositionRelationType,
            sourceActionEdgeId: SemanticActionEdgeId,
            confidence: Double,
        ): SemanticPropositionEdge {
            val fingerprint = StableCognitiveIds.fingerprint(
                "semantic-proposition-edge/v1",
                from.value,
                to.value,
                relation.name,
                sourceActionEdgeId.value,
                java.lang.Double.toHexString(confidence),
            )
            return SemanticPropositionEdge(
                id = SemanticPropositionEdgeId(
                    SemanticPropositionEdgeId.PREFIX + fingerprint
                ),
                from = from,
                to = to,
                relation = relation,
                sourceActionEdgeId = sourceActionEdgeId,
                confidence = confidence,
            )
        }
    }
}

data class SemanticPropositionGraph(
    val nodes: List<SemanticPropositionNode>,
    val edges: List<SemanticPropositionEdge>,
    val fingerprint: String,
) {
    init {
        require(nodes == nodes.sortedBy { it.id.value }) {
            "Semantic proposition nodes must be deterministic"
        }
        require(edges == edges.sortedBy { it.id.value }) {
            "Semantic proposition edges must be deterministic"
        }
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(edges.map { it.id }.distinct().size == edges.size)
        val nodeIds = nodes.mapTo(linkedSetOf()) { it.id }
        require(edges.all { it.from in nodeIds && it.to in nodeIds })
        require(fingerprint == expectedFingerprint())
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val worldCausalityAuthority: Boolean
        get() = false

    private fun expectedFingerprint(): String =
        StableCognitiveIds.fingerprint(
            "semantic-proposition-graph/v1",
            *buildList {
                nodes.forEach { add("node:${it.id.value}:${it.fingerprint}") }
                edges.forEach { add("edge:${it.id.value}:${it.fingerprint}") }
            }.toTypedArray(),
        )

    companion object {
        fun empty(): SemanticPropositionGraph {
            val fingerprint = StableCognitiveIds.fingerprint(
                "semantic-proposition-graph/v1"
            )
            return SemanticPropositionGraph(
                nodes = emptyList(),
                edges = emptyList(),
                fingerprint = fingerprint,
            )
        }

        fun create(
            nodes: Collection<SemanticPropositionNode>,
            edges: Collection<SemanticPropositionEdge>,
        ): SemanticPropositionGraph {
            val canonicalNodes = nodes.sortedBy { it.id.value }
            val canonicalEdges = edges.sortedBy { it.id.value }
            val fingerprint = StableCognitiveIds.fingerprint(
                "semantic-proposition-graph/v1",
                *buildList {
                    canonicalNodes.forEach {
                        add("node:${it.id.value}:${it.fingerprint}")
                    }
                    canonicalEdges.forEach {
                        add("edge:${it.id.value}:${it.fingerprint}")
                    }
                }.toTypedArray(),
            )
            return SemanticPropositionGraph(
                nodes = canonicalNodes,
                edges = canonicalEdges,
                fingerprint = fingerprint,
            )
        }
    }
}

/**
 * B470 transforms the existing SemanticActionGraph into an epistemic proposition graph.
 *
 * A proposition node records what was said/requested/questioned and its realization level. It does
 * not assert that the proposition is true in Personal World. In particular, a language edge labelled
 * CAUSES becomes CLAIMS_CAUSAL_RELATION rather than causal world knowledge.
 */
class SemanticPropositionGraphBuilder {
    fun build(
        actionGraph: SemanticActionGraph,
        realization: LanguageRealizationState,
    ): SemanticPropositionGraph {
        if (actionGraph.nodes.isEmpty()) {
            require(realization.propositions.isEmpty()) {
                "Empty action graph cannot carry proposition realizations"
            }
            return SemanticPropositionGraph.empty()
        }

        val realizations = realization.propositions.associateBy { it.nodeId }
        require(realizations.keys == actionGraph.nodes.mapTo(linkedSetOf()) { it.id }) {
            "Language realization/action graph node mismatch"
        }

        val nodes = actionGraph.nodes.map { actionNode ->
            val propositionRealization = requireNotNull(realizations[actionNode.id])
            val source = PropositionSource(
                kind = if (
                    LanguageModalStatus.QUOTED in propositionRealization.modalStatuses ||
                    propositionRealization.speechAct == SpeechActType.QUOTATION
                ) {
                    PropositionSourceKind.QUOTED_SPEECH
                } else {
                    PropositionSourceKind.DIRECT_SPEAKER
                },
            )
            SemanticPropositionNode.create(
                actionNode = actionNode,
                source = source,
                realization = propositionRealization,
            )
        }
        val propositionByActionId = nodes.associateBy { it.sourceActionNodeId }

        val edges = actionGraph.edges.map { edge ->
            SemanticPropositionEdge.create(
                from = requireNotNull(propositionByActionId[edge.from]).id,
                to = requireNotNull(propositionByActionId[edge.to]).id,
                relation = edge.type.toPropositionRelation(),
                sourceActionEdgeId = edge.id,
                confidence = edge.confidence,
            )
        }

        return SemanticPropositionGraph.create(nodes, edges)
    }

    private fun SemanticActionEdgeType.toPropositionRelation(): PropositionRelationType =
        when (this) {
            SemanticActionEdgeType.AND ->
                PropositionRelationType.COORDINATED_WITH
            SemanticActionEdgeType.OR,
            SemanticActionEdgeType.ELSE,
            -> PropositionRelationType.ALTERNATIVE_TO
            SemanticActionEdgeType.THEN,
            SemanticActionEdgeType.BEFORE,
            -> PropositionRelationType.PRECEDES
            SemanticActionEdgeType.AFTER ->
                PropositionRelationType.FOLLOWS
            SemanticActionEdgeType.IF ->
                PropositionRelationType.CONDITION_FOR
            SemanticActionEdgeType.DEPENDS_ON ->
                PropositionRelationType.DEPENDS_ON
            SemanticActionEdgeType.USES_RESULT_OF ->
                PropositionRelationType.REFERENCES_RESULT_OF
            SemanticActionEdgeType.CAUSES ->
                PropositionRelationType.CLAIMS_CAUSAL_RELATION
            SemanticActionEdgeType.PURPOSE_OF ->
                PropositionRelationType.PURPOSE_OF
        }
}

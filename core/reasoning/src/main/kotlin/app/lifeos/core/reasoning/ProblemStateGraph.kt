package app.lifeos.core.reasoning

import app.lifeos.core.field.EvidenceId
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId

@JvmInline
value class ProblemStateGraphId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    companion object { const val PREFIX = "problem-state:" }
}

@JvmInline
value class ProblemStateNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    companion object { const val PREFIX = "problem-node:" }
}

@JvmInline
value class ProblemStateEdgeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    companion object { const val PREFIX = "problem-edge:" }
}

data class ProblemSourceRevision(
    val photonId: PhotonId,
    val revision: Long,
) {
    init { require(revision > 0L) }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-source-revision/v1",
        photonId.value,
        revision.toString(),
    )

    companion object {
        fun from(photon: Photon): ProblemSourceRevision =
            ProblemSourceRevision(photon.id, photon.revision)
    }
}

data class ProblemEvidenceRef(
    val evidenceId: EvidenceId,
    val source: ProblemSourceRevision,
    val evidenceFingerprint: String,
) {
    init { require(evidenceFingerprint.matches(Regex("[0-9a-f]{64}"))) }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-evidence-ref/v1",
        evidenceId.value,
        source.fingerprint(),
        evidenceFingerprint,
    )

    companion object {
        fun from(evidence: FieldEvidence): ProblemEvidenceRef = ProblemEvidenceRef(
            evidenceId = evidence.id,
            source = ProblemSourceRevision(evidence.sourcePhotonId, evidence.sourceRevision),
            evidenceFingerprint = StableFieldIds.fingerprint(
                "problem-field-evidence/v1",
                evidence.id.value,
                evidence.sourceFingerprint,
                evidence.payload.stableFingerprint(),
                java.lang.Double.toHexString(evidence.confidence),
                java.lang.Double.toHexString(evidence.reliability.score),
                evidence.reliability.reason,
                evidence.authority.name,
                evidence.observedAt.toString(),
                evidence.validity.validFrom?.toString().orEmpty(),
                evidence.validity.validUntilExclusive?.toString().orEmpty(),
                evidence.explanation,
            ),
        )
    }
}

enum class ProblemStateNodeKind { GOAL, CONSTRAINT, FACT, UNKNOWN, ASSUMPTION }

enum class ProblemStateEdgeKind { CONSTRAINS, INFORMS, REQUIRES_RESOLUTION, ASSUMES }

data class ProblemStateNode(
    val id: ProblemStateNodeId,
    val kind: ProblemStateNodeKind,
    val semanticKey: String,
    val statement: String,
    val confidence: Double,
    val sourceRevisions: List<ProblemSourceRevision>,
    val evidenceRefs: List<ProblemEvidenceRef> = emptyList(),
) {
    init {
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceRevisions.isNotEmpty())
        require(sourceRevisions == canonicalSources(sourceRevisions))
        require(evidenceRefs == canonicalEvidence(evidenceRefs))
        if (kind == ProblemStateNodeKind.FACT) {
            require(evidenceRefs.isNotEmpty()) { "Problem facts require explicit evidence" }
        }
        if (kind == ProblemStateNodeKind.ASSUMPTION) {
            require(evidenceRefs.isEmpty()) {
                "Problem assumptions must not masquerade as evidence-backed facts"
            }
        }
        require(
            id == createId(
                kind,
                semanticKey,
                statement,
                confidence,
                sourceRevisions,
                evidenceRefs,
            )
        )
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-state-node/v1",
        id.value,
        kind.name,
        semanticKey,
        statement,
        java.lang.Double.toHexString(confidence),
        *sourceRevisions.map { "source:" + it.fingerprint() }.toTypedArray(),
        *evidenceRefs.map { "evidence:" + it.fingerprint() }.toTypedArray(),
    )

    companion object {
        fun create(
            kind: ProblemStateNodeKind,
            semanticKey: String,
            statement: String,
            confidence: Double,
            sourceRevisions: Collection<ProblemSourceRevision>,
            evidenceRefs: Collection<ProblemEvidenceRef> = emptyList(),
        ): ProblemStateNode {
            val sources = canonicalSources(sourceRevisions)
            val evidence = canonicalEvidence(evidenceRefs)
            return ProblemStateNode(
                id = createId(kind, semanticKey, statement, confidence, sources, evidence),
                kind = kind,
                semanticKey = semanticKey,
                statement = statement,
                confidence = confidence,
                sourceRevisions = sources,
                evidenceRefs = evidence,
            )
        }

        private fun createId(
            kind: ProblemStateNodeKind,
            semanticKey: String,
            statement: String,
            confidence: Double,
            sourceRevisions: List<ProblemSourceRevision>,
            evidenceRefs: List<ProblemEvidenceRef>,
        ): ProblemStateNodeId = ProblemStateNodeId(
            ProblemStateNodeId.PREFIX + StableFieldIds.fingerprint(
                "problem-state-node-id/v1",
                kind.name,
                semanticKey,
                statement,
                java.lang.Double.toHexString(confidence),
                *sourceRevisions.map { "source:" + it.fingerprint() }.toTypedArray(),
                *evidenceRefs.map { "evidence:" + it.fingerprint() }.toTypedArray(),
            )
        )
    }
}

data class ProblemStateEdge(
    val id: ProblemStateEdgeId,
    val from: ProblemStateNodeId,
    val to: ProblemStateNodeId,
    val kind: ProblemStateEdgeKind,
) {
    init {
        require(from != to)
        require(id == createId(from, to, kind))
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "problem-state-edge/v1",
        from.value,
        to.value,
        kind.name,
    )

    companion object {
        fun create(
            from: ProblemStateNodeId,
            to: ProblemStateNodeId,
            kind: ProblemStateEdgeKind,
        ): ProblemStateEdge = ProblemStateEdge(
            id = createId(from, to, kind),
            from = from,
            to = to,
            kind = kind,
        )

        private fun createId(
            from: ProblemStateNodeId,
            to: ProblemStateNodeId,
            kind: ProblemStateEdgeKind,
        ): ProblemStateEdgeId = ProblemStateEdgeId(
            ProblemStateEdgeId.PREFIX + StableFieldIds.fingerprint(
                "problem-state-edge-id/v1",
                from.value,
                to.value,
                kind.name,
            )
        )
    }
}

data class ProblemStateGraph(
    val id: ProblemStateGraphId,
    val source: ProblemSourceRevision,
    val goalNodeId: ProblemStateNodeId,
    val nodes: List<ProblemStateNode>,
    val edges: List<ProblemStateEdge>,
    val fingerprint: String,
) {
    init {
        require(nodes == canonicalNodes(nodes))
        require(edges == canonicalEdges(edges))
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(edges.map { it.id }.distinct().size == edges.size)
        val ids = nodes.mapTo(linkedSetOf()) { it.id }
        require(goalNodeId in ids)
        require(nodes.single { it.id == goalNodeId }.kind == ProblemStateNodeKind.GOAL)
        require(nodes.count { it.kind == ProblemStateNodeKind.GOAL } == 1)
        require(edges.all { it.from in ids && it.to in ids })
        require(fingerprint == graphFingerprint(source, goalNodeId, nodes, edges))
        require(id == ProblemStateGraphId(ProblemStateGraphId.PREFIX + fingerprint))
    }

    val constraints: List<ProblemStateNode>
        get() = nodes.filter { it.kind == ProblemStateNodeKind.CONSTRAINT }
    val facts: List<ProblemStateNode>
        get() = nodes.filter { it.kind == ProblemStateNodeKind.FACT }
    val unknowns: List<ProblemStateNode>
        get() = nodes.filter { it.kind == ProblemStateNodeKind.UNKNOWN }
    val assumptions: List<ProblemStateNode>
        get() = nodes.filter { it.kind == ProblemStateNodeKind.ASSUMPTION }

    companion object {
        fun create(
            source: ProblemSourceRevision,
            goalNodeId: ProblemStateNodeId,
            nodes: Collection<ProblemStateNode>,
            edges: Collection<ProblemStateEdge>,
        ): ProblemStateGraph {
            val canonicalNodes = canonicalNodes(nodes)
            val canonicalEdges = canonicalEdges(edges)
            val fingerprint = graphFingerprint(source, goalNodeId, canonicalNodes, canonicalEdges)
            return ProblemStateGraph(
                id = ProblemStateGraphId(ProblemStateGraphId.PREFIX + fingerprint),
                source = source,
                goalNodeId = goalNodeId,
                nodes = canonicalNodes,
                edges = canonicalEdges,
                fingerprint = fingerprint,
            )
        }
    }
}

data class ProblemFactInput(
    val semanticKey: String,
    val statement: String,
    val confidence: Double,
    val evidence: List<FieldEvidence>,
) {
    init {
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(evidence.isNotEmpty()) { "Problem facts require evidence" }
        require(evidence.all { it.semanticKey == semanticKey }) {
            "Problem fact evidence must match the fact semantic key"
        }
        val weakestEvidence = evidence.minOf {
            minOf(it.confidence, it.reliability.score, it.authority.defaultWeight)
        }
        require(confidence <= weakestEvidence + 1e-12) {
            "Problem fact confidence cannot exceed its weakest evidence bound"
        }
    }
}

data class ProblemUnknownInput(
    val semanticKey: String,
    val statement: String,
    val confidence: Double = 1.0,
) {
    init {
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class ProblemAssumptionInput(
    val semanticKey: String,
    val statement: String,
    val confidence: Double,
) {
    init {
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

/**
 * B366 entry point. Language understanding frames the problem but does not create truth.
 * FACT nodes require explicit FieldEvidence, unresolved semantics stay UNKNOWN, and
 * assumptions remain separately typed.
 */
class ProblemStateGraphBuilder {
    fun build(
        goal: GoalFrame,
        sourcePhoton: Photon,
        facts: Collection<ProblemFactInput> = emptyList(),
        additionalUnknowns: Collection<ProblemUnknownInput> = emptyList(),
        assumptions: Collection<ProblemAssumptionInput> = emptyList(),
    ): ProblemStateGraph {
        val source = ProblemSourceRevision.from(sourcePhoton)
        val goalNode = ProblemStateNode.create(
            kind = ProblemStateNodeKind.GOAL,
            semanticKey = "goal:" + goal.intent.name.lowercase(),
            statement = goal.objective,
            confidence = goal.confidence,
            sourceRevisions = listOf(source),
        )
        val constraintNodes = goal.constraints.map { constraint ->
            ProblemStateNode.create(
                kind = ProblemStateNodeKind.CONSTRAINT,
                semanticKey = "constraint:" + constraint.key.trim().lowercase(),
                statement = constraint.key + "=" + constraint.value,
                confidence = constraint.confidence,
                sourceRevisions = listOf(source),
            )
        }
        val factNodes = facts.map { fact ->
            val evidenceRefs = fact.evidence.map(ProblemEvidenceRef::from)
            ProblemStateNode.create(
                kind = ProblemStateNodeKind.FACT,
                semanticKey = fact.semanticKey,
                statement = fact.statement,
                confidence = fact.confidence,
                sourceRevisions = evidenceRefs.map { it.source },
                evidenceRefs = evidenceRefs,
            )
        }
        val unknownNodes = (
            goal.ambiguities.map { ambiguity ->
                ProblemUnknownInput(
                    semanticKey = "ambiguity:" + ambiguity.code + ":" +
                        StableFieldIds.fingerprint(
                            ambiguity.message,
                            *ambiguity.alternatives.sorted().toTypedArray(),
                        ),
                    statement = ambiguity.message,
                    confidence = ambiguity.severity,
                )
            } + unresolvedSemanticUnknowns(goal) + additionalUnknowns
            )
            .distinctBy { it.semanticKey to it.statement }
            .map { unknown ->
                ProblemStateNode.create(
                    kind = ProblemStateNodeKind.UNKNOWN,
                    semanticKey = unknown.semanticKey,
                    statement = unknown.statement,
                    confidence = unknown.confidence,
                    sourceRevisions = listOf(source),
                )
            }
        val assumptionNodes = assumptions
            .distinctBy { it.semanticKey to it.statement }
            .map { assumption ->
                ProblemStateNode.create(
                    kind = ProblemStateNodeKind.ASSUMPTION,
                    semanticKey = assumption.semanticKey,
                    statement = assumption.statement,
                    confidence = assumption.confidence,
                    sourceRevisions = listOf(source),
                )
            }
        val children = constraintNodes + factNodes + unknownNodes + assumptionNodes
        val edges = children.map { child ->
            ProblemStateEdge.create(
                from = child.id,
                to = goalNode.id,
                kind = when (child.kind) {
                    ProblemStateNodeKind.CONSTRAINT -> ProblemStateEdgeKind.CONSTRAINS
                    ProblemStateNodeKind.FACT -> ProblemStateEdgeKind.INFORMS
                    ProblemStateNodeKind.UNKNOWN -> ProblemStateEdgeKind.REQUIRES_RESOLUTION
                    ProblemStateNodeKind.ASSUMPTION -> ProblemStateEdgeKind.ASSUMES
                    ProblemStateNodeKind.GOAL -> error("Nested goal cannot be a child edge")
                },
            )
        }
        return ProblemStateGraph.create(
            source = source,
            goalNodeId = goalNode.id,
            nodes = listOf(goalNode) + children,
            edges = edges,
        )
    }

    private fun unresolvedSemanticUnknowns(goal: GoalFrame): List<ProblemUnknownInput> =
        buildList {
            goal.semanticActionGraph.nodes.forEach { node ->
                node.unresolvedRoles.sortedBy { it.name }.forEach { role ->
                    add(
                        ProblemUnknownInput(
                            semanticKey = "semantic:" + node.id.value + ":role:" + role.name.lowercase(),
                            statement = "Unresolved " + role.name.lowercase() +
                                " for " + node.frame.predicate.name.lowercase(),
                        )
                    )
                }
                if (node.unresolvedReference) {
                    add(
                        ProblemUnknownInput(
                            semanticKey = "semantic:" + node.id.value + ":reference",
                            statement = "Unresolved reference for " +
                                node.frame.predicate.name.lowercase(),
                        )
                    )
                }
                if (node.unresolvedCondition) {
                    add(
                        ProblemUnknownInput(
                            semanticKey = "semantic:" + node.id.value + ":condition",
                            statement = "Unresolved condition for " +
                                node.frame.predicate.name.lowercase(),
                        )
                    )
                }
            }
        }
}

private fun canonicalSources(
    sources: Collection<ProblemSourceRevision>,
): List<ProblemSourceRevision> = sources
    .distinct()
    .sortedWith(compareBy({ it.photonId.value }, { it.revision }))

private fun canonicalEvidence(
    evidence: Collection<ProblemEvidenceRef>,
): List<ProblemEvidenceRef> = evidence
    .distinctBy { it.fingerprint() }
    .sortedBy { it.fingerprint() }

private fun canonicalNodes(
    nodes: Collection<ProblemStateNode>,
): List<ProblemStateNode> = nodes
    .distinctBy { it.id }
    .sortedBy { it.id.value }

private fun canonicalEdges(
    edges: Collection<ProblemStateEdge>,
): List<ProblemStateEdge> = edges
    .distinctBy { it.id }
    .sortedBy { it.id.value }

private fun graphFingerprint(
    source: ProblemSourceRevision,
    goalNodeId: ProblemStateNodeId,
    nodes: List<ProblemStateNode>,
    edges: List<ProblemStateEdge>,
): String = StableFieldIds.fingerprint(
    "problem-state-graph/v1",
    source.fingerprint(),
    goalNodeId.value,
    *nodes.map { "node:" + it.fingerprint() }.toTypedArray(),
    *edges.map { "edge:" + it.fingerprint() }.toTypedArray(),
)

package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionEntry
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionReason
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import java.time.Instant

class GoalThoughtGraphProjector(
    private val maxNodes: Int = 64,
    private val maxEdges: Int = 128,
) {
    init {
        require(maxNodes in 1..256)
        require(maxEdges in 1..512)
    }

    fun project(
        goalPhoton: Photon,
        source: CrossDomainConvergenceRequest,
        at: Instant = goalPhoton.provenance.createdAt,
    ): ThoughtGraphWorkingSet {
        val domainIds = source.domains.map { it.request.domainId.value }.sorted()
        val goalProvenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.GOAL,
            sourceId = goalPhoton.id.value,
            sourceRevision = goalPhoton.revision,
            sourceFingerprint = goalPhotonFingerprint(goalPhoton),
            origin = goalPhoton.provenance.source,
            actor = goalPhoton.provenance.actor,
            createdAt = goalPhoton.provenance.createdAt,
        )
        val goalNode = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.GOAL,
            semanticKey = "goal:${goalPhoton.id.value}",
            summary = goalPhoton.content.take(MAX_SUMMARY_CHARS),
            confidence = goalPhoton.confidence,
            authority = 1.0,
            validity = TemporalValidity.UNBOUNDED,
            provenance = goalProvenance,
            attributes = mapOf(
                "goalPhotonId" to goalPhoton.id.value,
                "goalRevision" to goalPhoton.revision.toString(),
                "domains" to domainIds.joinToString(","),
            ),
        )

        val evidenceNodes = linkedMapOf<String, ThoughtGraphNodeVersion>()
        val hypothesisNodes = linkedMapOf<String, ThoughtGraphNodeVersion>()
        source.domains.sortedBy { it.request.domainId.value }.forEach { domain ->
            val request = domain.request
            request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                evidenceNodes[evidence.id.value] = ThoughtGraphNodeVersion.create(
                    kind = ThoughtGraphNodeKind.EVIDENCE,
                    semanticKey = evidence.semanticKey,
                    summary = evidence.explanation.take(MAX_SUMMARY_CHARS),
                    confidence = evidence.confidence,
                    authority = evidence.authority.defaultWeight,
                    validity = evidence.validity,
                    provenance = ThoughtGraphProvenance(
                        sourceKind = ThoughtGraphSourceKind.EVIDENCE,
                        sourceId = evidence.id.value,
                        sourceRevision = evidence.sourceRevision,
                        sourceFingerprint = evidence.sourceFingerprint,
                        origin = "productive-goal-convergence",
                        actor = "system",
                        createdAt = evidence.observedAt,
                    ),
                    attributes = mapOf("domainId" to request.domainId.value),
                )
            }
            request.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                hypothesisNodes[hypothesis.id.value] = ThoughtGraphNodeVersion.create(
                    kind = ThoughtGraphNodeKind.HYPOTHESIS,
                    semanticKey = hypothesis.semanticKey,
                    summary = hypothesis.explanation.take(MAX_SUMMARY_CHARS),
                    confidence = hypothesis.score.total,
                    authority = request.evidence
                        .filter { evidence ->
                            hypothesis.evidenceLinks.any { it.evidenceId == evidence.id }
                        }
                        .maxOfOrNull { it.authority.defaultWeight } ?: 0.0,
                    validity = TemporalValidity.UNBOUNDED,
                    provenance = ThoughtGraphProvenance(
                        sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
                        sourceId = hypothesis.id.value,
                        sourceRevision = maxOf(
                            goalPhoton.revision,
                            request.evidence.maxOfOrNull { it.sourceRevision } ?: 1L,
                        ),
                        sourceFingerprint = StableFieldIds.fingerprint(
                            "goal-hypothesis/v2",
                            request.domainId.value,
                            hypothesis.id.value,
                            hypothesis.semanticKey,
                            goalPhotonFingerprint(goalPhoton),
                        ),
                        origin = "productive-goal-convergence",
                        actor = "system",
                        createdAt = at,
                    ),
                    attributes = mapOf(
                        "domainId" to request.domainId.value,
                        "state" to hypothesis.state.name,
                    ),
                )
            }
        }

        val nodes = (listOf(goalNode) + evidenceNodes.values + hypothesisNodes.values)
            .distinctBy { it.id }
        require(nodes.size <= maxNodes) {
            "Goal ThoughtGraph working set exceeds bounded node limit"
        }

        val edges = buildList {
            source.domains.sortedBy { it.request.domainId.value }.forEach { domain ->
                domain.request.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                    val hypothesisNode = hypothesisNodes.getValue(hypothesis.id.value)
                    hypothesis.evidenceLinks.sortedBy { it.evidenceId.value }.forEach { link ->
                        val evidenceNode = evidenceNodes[link.evidenceId.value] ?: return@forEach
                        add(
                            ThoughtGraphEdgeVersion.create(
                                sourceNodeId = evidenceNode.id,
                                targetNodeId = hypothesisNode.id,
                                kind = when (link.relation.name) {
                                    "CONTRADICTS" -> ThoughtGraphEdgeKind.CONTRADICTS
                                    "DERIVED_FROM" -> ThoughtGraphEdgeKind.DERIVED_FROM
                                    else -> ThoughtGraphEdgeKind.SUPPORTS
                                },
                                semanticKey = "goal-evidence:${link.evidenceId.value}:${hypothesis.id.value}",
                                confidence = link.weight,
                                authority = minOf(evidenceNode.authority, hypothesisNode.authority),
                                validity = TemporalValidity.UNBOUNDED,
                                provenance = hypothesisNode.provenance,
                                explanation = "Goal evidence relation ${link.relation.name}",
                            )
                        )
                    }
                    add(
                        ThoughtGraphEdgeVersion.create(
                            sourceNodeId = hypothesisNode.id,
                            targetNodeId = goalNode.id,
                            kind = ThoughtGraphEdgeKind.TARGETS_GOAL,
                            semanticKey = "goal-target:${hypothesis.id.value}:${goalPhoton.id.value}",
                            confidence = hypothesis.score.total,
                            authority = hypothesisNode.authority,
                            validity = TemporalValidity.UNBOUNDED,
                            provenance = hypothesisNode.provenance,
                            explanation = "Hypothesis targets exact persisted goal Photon",
                        )
                    )
                }
            }
        }.distinctBy { it.id }.sortedBy { it.id.value }
        require(edges.size <= maxEdges) {
            "Goal ThoughtGraph working set exceeds bounded edge limit"
        }

        val ranked = nodes.map { node ->
            val reasons = buildList {
                if (node.kind == ThoughtGraphNodeKind.GOAL) add(ThoughtGraphAttentionReason.GOAL)
                if (node.kind == ThoughtGraphNodeKind.HYPOTHESIS) add(ThoughtGraphAttentionReason.HYPOTHESIS)
                if (node.confidence >= 0.75) add(ThoughtGraphAttentionReason.HIGH_CONFIDENCE)
                if (node.authority >= 0.75) add(ThoughtGraphAttentionReason.HIGH_AUTHORITY)
                add(ThoughtGraphAttentionReason.TEMPORALLY_VALID)
            }.distinct().sortedBy { it.name }
            val score =
                (if (node.kind == ThoughtGraphNodeKind.GOAL) 4.0 else 0.0) +
                    (if (node.kind == ThoughtGraphNodeKind.HYPOTHESIS) 2.0 else 1.0) +
                    node.confidence * 0.5 +
                    node.authority * 0.4
            node to ThoughtGraphAttentionEntry(node.id, score, reasons)
        }.sortedWith(
            compareByDescending<Pair<ThoughtGraphNodeVersion, ThoughtGraphAttentionEntry>> {
                it.second.score
            }.thenBy { it.first.id.value }
        )

        val orderedNodes = ranked.map { it.first }
        val entries = ranked.map { it.second }
        val historyFingerprint = StableFieldIds.fingerprint(
            "goal-thought-working-set/v2",
            goalPhotonFingerprint(goalPhoton),
            source.id,
            *orderedNodes.map { it.fingerprint }.toTypedArray(),
            *edges.map { it.fingerprint }.toTypedArray(),
        )
        return ThoughtGraphWorkingSet(
            sourceSnapshotId = "goal-thought-snapshot:$historyFingerprint",
            sourceRevision = orderedNodes.maxOf { it.sourceRevision },
            sourceHistoryFingerprint = historyFingerprint,
            asOf = at,
            policy = ThoughtGraphAttentionPolicy(
                maxNodes = maxNodes,
                maxEdges = maxEdges,
                maxConflicts = 0,
                goalDepth = 1,
            ),
            entries = entries,
            nodes = orderedNodes,
            edges = edges,
            conflicts = emptyList(),
        )
    }

    private fun goalPhotonFingerprint(photon: Photon): String = StableFieldIds.fingerprint(
        "goal-photon-exact/v1",
        photon.id.value,
        photon.revision.toString(),
        photon.content,
        photon.mimeType,
        photon.phase.name,
        java.lang.Double.toHexString(photon.semanticMass),
        java.lang.Double.toHexString(photon.energy),
        java.lang.Double.toHexString(photon.confidence),
        photon.provenance.source,
        photon.provenance.actor,
        photon.provenance.createdAt.toString(),
        *photon.provenance.parentIds.map { it.value }.sorted().toTypedArray(),
        *photon.tags.sorted().toTypedArray(),
    )

    private companion object {
        const val MAX_SUMMARY_CHARS = 4096
    }
}

package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.runtime.convergence.CrossDomainConvergenceRequest
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionEntry
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionPolicy
import app.lifeos.core.runtime.thought.ThoughtGraphAttentionReason
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeVersion
import app.lifeos.core.runtime.thought.ThoughtGraphProvenance
import app.lifeos.core.runtime.thought.ThoughtGraphSourceKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet
import java.time.Instant

class GoalThoughtGraphProjector(
    private val maxNodes: Int = 64,
) {
    init {
        require(maxNodes in 1..256)
    }

    fun project(
        source: CrossDomainConvergenceRequest,
        at: Instant,
    ): ThoughtGraphWorkingSet {
        val nodes = buildList {
            source.domains.sortedBy { it.request.domainId.value }.forEach { domain ->
                val request = domain.request
                request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                    add(
                        ThoughtGraphNodeVersion.create(
                            kind = ThoughtGraphNodeKind.EVIDENCE,
                            semanticKey = evidence.semanticKey,
                            summary = evidence.explanation,
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
                            attributes = mapOf(
                                "domainId" to request.domainId.value,
                                "state" to "EVIDENCE",
                            ),
                        )
                    )
                }
                request.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                    add(
                        ThoughtGraphNodeVersion.create(
                            kind = ThoughtGraphNodeKind.HYPOTHESIS,
                            semanticKey = hypothesis.semanticKey,
                            summary = hypothesis.explanation,
                            confidence = hypothesis.score.total,
                            authority = 0.7,
                            validity = TemporalValidity.UNBOUNDED,
                            provenance = ThoughtGraphProvenance(
                                sourceKind = ThoughtGraphSourceKind.HYPOTHESIS,
                                sourceId = hypothesis.id.value,
                                sourceRevision = request.evidence.maxOfOrNull { it.sourceRevision } ?: 1L,
                                sourceFingerprint = StableFieldIds.fingerprint(
                                    "goal-hypothesis/v1",
                                    request.domainId.value,
                                    hypothesis.id.value,
                                    hypothesis.semanticKey,
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
                    )
                }
            }
        }
        require(nodes.isNotEmpty()) { "Goal convergence requires ThoughtGraph nodes" }
        require(nodes.size <= maxNodes) {
            "Goal ThoughtGraph working set exceeds bounded node limit"
        }

        val ranked = nodes
            .map { node ->
                val reasons = buildList {
                    if (node.kind == ThoughtGraphNodeKind.HYPOTHESIS) {
                        add(ThoughtGraphAttentionReason.HYPOTHESIS)
                    }
                    if (node.confidence >= 0.75) {
                        add(ThoughtGraphAttentionReason.HIGH_CONFIDENCE)
                    }
                    if (node.authority >= 0.75) {
                        add(ThoughtGraphAttentionReason.HIGH_AUTHORITY)
                    }
                    add(ThoughtGraphAttentionReason.TEMPORALLY_VALID)
                }.distinct().sortedBy { it.name }
                val score =
                    (if (node.kind == ThoughtGraphNodeKind.HYPOTHESIS) 2.0 else 1.0) +
                        node.confidence * 0.5 +
                        node.authority * 0.4
                node to ThoughtGraphAttentionEntry(
                    nodeId = node.id,
                    score = score,
                    reasons = reasons,
                )
            }
            .sortedWith(
                compareByDescending<Pair<ThoughtGraphNodeVersion, ThoughtGraphAttentionEntry>> {
                    it.second.score
                }.thenBy { it.first.id.value }
            )

        val orderedNodes = ranked.map { it.first }
        val entries = ranked.map { it.second }
        val historyFingerprint = StableFieldIds.fingerprint(
            "goal-thought-working-set/v1",
            source.id,
            *orderedNodes.map { it.fingerprint }.toTypedArray(),
        )
        return ThoughtGraphWorkingSet(
            sourceSnapshotId = "goal-thought-snapshot:$historyFingerprint",
            sourceRevision = orderedNodes.maxOf { it.sourceRevision },
            sourceHistoryFingerprint = historyFingerprint,
            asOf = at,
            policy = ThoughtGraphAttentionPolicy(
                maxNodes = maxNodes,
                maxEdges = 0,
                maxConflicts = 0,
                goalDepth = 0,
            ),
            entries = entries,
            nodes = orderedNodes,
            edges = emptyList(),
            conflicts = emptyList(),
        )
    }
}

package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidence
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId
import app.lifeos.core.runtime.deepsearch.DeepSearchRequestId
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus

enum class DeepSearchClaimEdgeKind {
    SUPPORTS,
    CONTRADICTS,
}

data class DeepSearchEvidenceNode(
    val evidence: DeepSearchEvidence,
) {
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-evidence-node/v1",
        evidence.id.value,
        evidence.requestId.value,
        evidence.branchId.value,
        evidence.sourceId,
        evidence.statement,
        java.lang.Double.toHexString(evidence.confidence),
        evidence.sourcePhotonId?.value.orEmpty(),
        evidence.sourcePhotonRevision?.toString().orEmpty(),
        evidence.fieldEvidenceId?.value.orEmpty(),
        evidence.contradiction.toString(),
    )
}

data class DeepSearchClaimNode(
    val hypothesis: DeepSearchHypothesis,
    val branchIds: Set<String>,
) {
    init {
        require(branchIds.isNotEmpty()) { "DeepSearch claim node requires branch lineage" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-claim-node/v1",
        hypothesis.id.value,
        hypothesis.requestId.value,
        hypothesis.statement,
        java.lang.Double.toHexString(hypothesis.confidence),
        hypothesis.fieldHypothesisId?.value.orEmpty(),
        *hypothesis.semanticTerms.sorted().toTypedArray(),
        *hypothesis.evidenceIds.map { it.value }.sorted().toTypedArray(),
        *branchIds.sorted().toTypedArray(),
    )
}

data class DeepSearchClaimEdge(
    val evidenceId: DeepSearchEvidenceId,
    val hypothesisId: DeepSearchHypothesisId,
    val kind: DeepSearchClaimEdgeKind,
) {
    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-claim-edge/v1",
        evidenceId.value,
        hypothesisId.value,
        kind.name,
    )
}

data class DeepSearchClaimGraph private constructor(
    val id: String,
    val requestId: DeepSearchRequestId,
    val sourceStatus: DeepSearchStatus,
    val evidenceNodes: List<DeepSearchEvidenceNode>,
    val claimNodes: List<DeepSearchClaimNode>,
    val edges: List<DeepSearchClaimEdge>,
    val sourceTraceFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(evidenceNodes.map { it.evidence.id }.distinct().size == evidenceNodes.size)
        require(claimNodes.map { it.hypothesis.id }.distinct().size == claimNodes.size)
        require(edges.map { it.fingerprint() }.distinct().size == edges.size)
        require(sourceTraceFingerprint.isNotBlank())

        val evidenceIds = evidenceNodes.mapTo(linkedSetOf()) { it.evidence.id }
        val claimIds = claimNodes.mapTo(linkedSetOf()) { it.hypothesis.id }
        require(edges.all { it.evidenceId in evidenceIds && it.hypothesisId in claimIds }) {
            "DeepSearch claim graph edge references unknown node"
        }
        require(
            claimNodes.all { node ->
                node.hypothesis.evidenceIds.all(evidenceIds::contains)
            }
        ) {
            "DeepSearch claim graph dropped evidence referenced by a claim"
        }
        require(id == expectedId()) { "DeepSearch claim graph id does not match content" }
    }

    /**
     * Graph projection is evidence organization only. It does not grant truth, convergence,
     * execution or extension-activation authority.
     */
    val activationAllowed: Boolean
        get() = false

    val truthAuthority: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "deep-search-claim-graph/v1",
        requestId.value,
        sourceStatus.name,
        sourceTraceFingerprint,
        *evidenceNodes
            .sortedBy { it.evidence.id.value }
            .map { it.fingerprint() }
            .toTypedArray(),
        *claimNodes
            .sortedBy { it.hypothesis.id.value }
            .map { it.fingerprint() }
            .toTypedArray(),
        *edges
            .sortedBy { it.fingerprint() }
            .map { it.fingerprint() }
            .toTypedArray(),
    )

    private fun expectedId(): String = "deepsearch-claim-graph:${fingerprint()}"

    companion object {
        fun create(
            requestId: DeepSearchRequestId,
            sourceStatus: DeepSearchStatus,
            evidenceNodes: List<DeepSearchEvidenceNode>,
            claimNodes: List<DeepSearchClaimNode>,
            edges: List<DeepSearchClaimEdge>,
            sourceTraceFingerprint: String,
        ): DeepSearchClaimGraph {
            val canonicalEvidence = evidenceNodes.sortedBy { it.evidence.id.value }
            val canonicalClaims = claimNodes.sortedBy { it.hypothesis.id.value }
            val canonicalEdges = edges
                .distinctBy { it.fingerprint() }
                .sortedBy { it.fingerprint() }

            val fingerprint = StableFieldIds.fingerprint(
                "deep-search-claim-graph/v1",
                requestId.value,
                sourceStatus.name,
                sourceTraceFingerprint,
                *canonicalEvidence.map { it.fingerprint() }.toTypedArray(),
                *canonicalClaims.map { it.fingerprint() }.toTypedArray(),
                *canonicalEdges.map { it.fingerprint() }.toTypedArray(),
            )
            return DeepSearchClaimGraph(
                id = "deepsearch-claim-graph:$fingerprint",
                requestId = requestId,
                sourceStatus = sourceStatus,
                evidenceNodes = canonicalEvidence,
                claimNodes = canonicalClaims,
                edges = canonicalEdges,
                sourceTraceFingerprint = sourceTraceFingerprint,
            )
        }
    }
}

/**
 * B153 deterministic projection from the existing DeepSearch result into an evidence/claim graph.
 * DeepSearch remains the search authority; Convergence remains the epistemic decision authority.
 */
class DeepSearchClaimGraphProjector {
    fun project(result: DeepSearchResult): DeepSearchClaimGraph {
        val branches = buildList {
            result.best?.let(::add)
            addAll(result.alternatives)
        }
            .distinctBy { it.id }
            .sortedBy { it.id.value }

        val evidenceById = result.evidence.associateBy { it.id }
        val branchIdsByHypothesis = branches
            .groupBy { it.hypothesis.id }
            .mapValues { (_, values) -> values.mapTo(sortedSetOf()) { it.id.value } }

        val hypotheses = branches
            .map(DeepSearchBranch::hypothesis)
            .distinctBy { it.id }
            .sortedBy { it.id.value }

        hypotheses.forEach { hypothesis ->
            require(hypothesis.requestId == result.requestId) {
                "DeepSearch claim graph hypothesis belongs to another request"
            }
            require(hypothesis.evidenceIds.all(evidenceById::containsKey)) {
                "DeepSearch claim graph hypothesis references missing evidence"
            }
        }

        result.evidence.forEach { evidence ->
            require(evidence.requestId == result.requestId) {
                "DeepSearch claim graph evidence belongs to another request"
            }
        }

        val evidenceNodes = result.evidence
            .distinctBy { it.id }
            .map(::DeepSearchEvidenceNode)

        val claimNodes = hypotheses.map { hypothesis ->
            DeepSearchClaimNode(
                hypothesis = hypothesis,
                branchIds = branchIdsByHypothesis.getValue(hypothesis.id),
            )
        }

        val edges = buildList {
            hypotheses.forEach { hypothesis ->
                hypothesis.evidenceIds
                    .sortedBy { it.value }
                    .forEach { evidenceId ->
                        val evidence = evidenceById.getValue(evidenceId)
                        add(
                            DeepSearchClaimEdge(
                                evidenceId = evidenceId,
                                hypothesisId = hypothesis.id,
                                kind = if (evidence.contradiction) {
                                    DeepSearchClaimEdgeKind.CONTRADICTS
                                } else {
                                    DeepSearchClaimEdgeKind.SUPPORTS
                                },
                            )
                        )
                    }
            }
        }

        val traceFingerprint = StableFieldIds.fingerprint(
            "deep-search-trace/v1",
            result.requestId.value,
            *result.trace.map { event ->
                StableFieldIds.fingerprint(
                    event.sequence.toString(),
                    event.type.name,
                    event.branchId?.value.orEmpty(),
                    event.sourceId.orEmpty(),
                    event.hypothesisId?.value.orEmpty(),
                    event.detail,
                    *event.evidenceIds.map { it.value }.sorted().toTypedArray(),
                )
            }.toTypedArray(),
        )

        return DeepSearchClaimGraph.create(
            requestId = result.requestId,
            sourceStatus = result.status,
            evidenceNodes = evidenceNodes,
            claimNodes = claimNodes,
            edges = edges,
            sourceTraceFingerprint = traceFingerprint,
        )
    }
}

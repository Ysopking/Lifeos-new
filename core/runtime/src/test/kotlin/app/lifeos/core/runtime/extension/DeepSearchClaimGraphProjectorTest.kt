package app.lifeos.core.runtime.extension

import app.lifeos.core.runtime.deepsearch.DeepSearchBranch
import app.lifeos.core.runtime.deepsearch.DeepSearchBranchId
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidence
import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId
import app.lifeos.core.runtime.deepsearch.DeepSearchRequestId
import app.lifeos.core.runtime.deepsearch.DeepSearchResult
import app.lifeos.core.runtime.deepsearch.DeepSearchScore
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DeepSearchClaimGraphProjectorTest {
    @Test
    fun projectsEvidenceAndClaimsDeterministicallyWithoutAuthority() {
        val requestId = DeepSearchRequestId("request-1")
        val supporting = evidence(requestId, "e1", contradiction = false)
        val contradicting = evidence(requestId, "e2", contradiction = true)
        val hypothesis = hypothesis(
            requestId = requestId,
            evidenceIds = setOf(supporting.id, contradicting.id),
        )
        val branch = branch(requestId, hypothesis)
        val result = result(
            requestId = requestId,
            branch = branch,
            evidence = listOf(contradicting, supporting),
        )

        val first = DeepSearchClaimGraphProjector().project(result)
        val second = DeepSearchClaimGraphProjector().project(
            result.copy(evidence = result.evidence.reversed())
        )

        assertEquals(first.id, second.id)
        assertEquals(
            setOf(
                DeepSearchClaimEdgeKind.SUPPORTS,
                DeepSearchClaimEdgeKind.CONTRADICTS,
            ),
            first.edges.mapTo(linkedSetOf()) { it.kind },
        )
        assertFalse(first.activationAllowed)
        assertFalse(first.truthAuthority)
    }

    @Test
    fun missingReferencedEvidenceFailsClosed() {
        val requestId = DeepSearchRequestId("request-2")
        val missing = DeepSearchEvidenceId("missing")
        val hypothesis = hypothesis(
            requestId = requestId,
            evidenceIds = setOf(missing),
        )
        val branch = branch(requestId, hypothesis)

        assertFailsWith<IllegalArgumentException> {
            DeepSearchClaimGraphProjector().project(
                result(
                    requestId = requestId,
                    branch = branch,
                    evidence = emptyList(),
                )
            )
        }
    }

    private fun evidence(
        requestId: DeepSearchRequestId,
        id: String,
        contradiction: Boolean,
    ) = DeepSearchEvidence(
        id = DeepSearchEvidenceId(id),
        requestId = requestId,
        branchId = DeepSearchBranchId("branch-1"),
        sourceId = "source-1",
        statement = "evidence-$id",
        confidence = 0.8,
        sourcePhotonId = null,
        fieldEvidenceId = null,
        contradiction = contradiction,
    )

    private fun hypothesis(
        requestId: DeepSearchRequestId,
        evidenceIds: Set<DeepSearchEvidenceId>,
    ) = DeepSearchHypothesis(
        id = DeepSearchHypothesisId("hypothesis-1"),
        requestId = requestId,
        statement = "candidate explanation",
        semanticTerms = setOf("candidate", "explanation"),
        confidence = 0.75,
        evidenceIds = evidenceIds,
    )

    private fun branch(
        requestId: DeepSearchRequestId,
        hypothesis: DeepSearchHypothesis,
    ) = DeepSearchBranch(
        id = DeepSearchBranchId("branch-1"),
        requestId = requestId,
        parentId = null,
        sourceId = "source-1",
        depth = 0,
        hypothesis = hypothesis,
        score = DeepSearchScore(
            relevance = 0.8,
            evidenceStrength = 0.8,
            sourceReliability = 0.8,
            novelty = 0.8,
            depthCost = 0.0,
            contradictionPenalty = 0.2,
            total = 0.7,
        ),
    )

    private fun result(
        requestId: DeepSearchRequestId,
        branch: DeepSearchBranch,
        evidence: List<DeepSearchEvidence>,
    ) = DeepSearchResult(
        requestId = requestId,
        status = DeepSearchStatus.UNRESOLVED,
        best = branch,
        alternatives = listOf(branch),
        evidence = evidence,
        trace = emptyList(),
        workUnitsUsed = 1,
        blockedSourceIds = emptySet(),
        failedSourceIds = emptySet(),
    )
}

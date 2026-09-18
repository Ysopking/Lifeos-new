package app.lifeos.core.runtime.extension

import app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId
import app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionCandidateFactoryTest {
    @Test
    fun createsEvidenceBoundNonActivatingCandidate() {
        val fixture = fixture()
        val candidate = ExtensionCandidateFactory().propose(
            ExtensionCandidateRequest(
                gap = fixture.gap,
                claimGraph = fixture.graph,
                selectedClaimIds = setOf(fixture.claimId),
                selectedEvidenceIds = setOf(fixture.evidenceId),
                rationale = "claim graph documents missing world signal coverage",
            )
        )

        assertEquals(fixture.gap.id, candidate.gapId)
        assertEquals(fixture.graph.id, candidate.claimGraphId)
        assertEquals(fixture.gap.requiredExtensionKinds, candidate.requestedKinds)
        assertFalse(candidate.activationAllowed)
        assertFalse(candidate.directRegistryMutationAllowed)
    }

    @Test
    fun equationCandidateRequiresEvolutionPromotion() {
        val fixture = fixture(
            requiredKinds = setOf(ExtensionKind.WORLD_EQUATION_PACK),
        )
        val candidate = ExtensionCandidateFactory().propose(
            ExtensionCandidateRequest(
                gap = fixture.gap,
                claimGraph = fixture.graph,
                selectedClaimIds = setOf(fixture.claimId),
                selectedEvidenceIds = setOf(fixture.evidenceId),
                rationale = "evidence identifies equation coverage gap",
            )
        )

        assertTrue(candidate.requiresEvolutionPromotion)
        assertFalse(candidate.activationAllowed)
    }

    @Test
    fun rejectsEvidenceOutsideSelectedClaimLineage() {
        val fixture = fixture()

        assertFailsWith<IllegalArgumentException> {
            ExtensionCandidateRequest(
                gap = fixture.gap,
                claimGraph = fixture.graph,
                selectedClaimIds = setOf(fixture.claimId),
                selectedEvidenceIds = setOf(DeepSearchEvidenceId("other-evidence")),
                rationale = "invalid lineage",
            )
        }
    }

    private fun fixture(
        requiredKinds: Set<ExtensionKind> = setOf(ExtensionKind.WORLD_SIGNAL_PACK),
    ): Fixture {
        val claimId = DeepSearchHypothesisId("claim-1")
        val evidenceId = DeepSearchEvidenceId("evidence-1")
        val gap = ExtensionGap.create(
            kind = ExtensionGapKind.WORLD_SIGNAL_GAP,
            semanticKey = "world-signal-dimension:GOAL_RELEVANCE",
            reason = "required-world-signal-dimension-not-represented",
            sourceFingerprint = "source-v1",
            requiredExtensionKinds = requiredKinds,
        )

        val graph = DeepSearchClaimGraph.create(
            requestId = app.lifeos.core.runtime.deepsearch.DeepSearchRequestId("request-1"),
            sourceStatus = app.lifeos.core.runtime.deepsearch.DeepSearchStatus.UNRESOLVED,
            evidenceNodes = listOf(
                DeepSearchEvidenceNode(
                    app.lifeos.core.runtime.deepsearch.DeepSearchEvidence(
                        id = evidenceId,
                        requestId = app.lifeos.core.runtime.deepsearch.DeepSearchRequestId("request-1"),
                        branchId = app.lifeos.core.runtime.deepsearch.DeepSearchBranchId("branch-1"),
                        sourceId = "source-1",
                        statement = "signal coverage evidence",
                        confidence = 0.8,
                        sourcePhotonId = null,
                        fieldEvidenceId = null,
                        contradiction = false,
                    )
                )
            ),
            claimNodes = listOf(
                DeepSearchClaimNode(
                    hypothesis = app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis(
                        id = claimId,
                        requestId = app.lifeos.core.runtime.deepsearch.DeepSearchRequestId("request-1"),
                        statement = "signal coverage is missing",
                        semanticTerms = setOf("signal", "coverage"),
                        confidence = 0.75,
                        evidenceIds = setOf(evidenceId),
                    ),
                    branchIds = setOf("branch-1"),
                )
            ),
            edges = listOf(
                DeepSearchClaimEdge(
                    evidenceId = evidenceId,
                    hypothesisId = claimId,
                    kind = DeepSearchClaimEdgeKind.SUPPORTS,
                )
            ),
            sourceTraceFingerprint = "trace-v1",
        )
        return Fixture(gap, graph, claimId, evidenceId)
    }

    private data class Fixture(
        val gap: ExtensionGap,
        val graph: DeepSearchClaimGraph,
        val claimId: DeepSearchHypothesisId,
        val evidenceId: DeepSearchEvidenceId,
    )
}

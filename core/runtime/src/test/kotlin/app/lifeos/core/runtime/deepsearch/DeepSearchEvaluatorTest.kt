package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.runtime.capability.CapabilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSearchEvaluatorTest {
    private val evaluator = DeepSearchEvaluator()
    private val request = DeepSearchRequest(
        query = "alpha beta",
        minimumResolutionScore = 0.50,
        minimumWinnerMargin = 0.08,
    )
    private val localSource = DeepSearchSourceDescriptor(
        sourceId = "local",
        kind = DeepSearchSourceKind.LOCAL,
        reliability = 0.9,
    )

    @Test
    fun `same inputs produce exactly the same typed score`() {
        val finding = finding("alpha beta answer", confidence = 0.9)

        val first = evaluator.score(request, finding, localSource, depth = 1, previouslySeenSignatures = emptySet())
        val second = evaluator.score(request, finding, localSource, depth = 1, previouslySeenSignatures = emptySet())

        assertEquals(first, second)
        assertTrue(first.relevance > 0.99)
        assertTrue(first.total in 0.0..1.0)
    }

    @Test
    fun `contradictory evidence lowers score explicitly`() {
        val supported = finding("alpha beta answer", confidence = 0.9)
        val contradicted = supported.copy(
            evidence = supported.evidence + DeepSearchEvidenceDraft(
                statement = "counter evidence",
                confidence = 0.95,
                contradiction = true,
            )
        )

        val supportedScore = evaluator.score(request, supported, localSource, 1, emptySet())
        val contradictedScore = evaluator.score(request, contradicted, localSource, 1, emptySet())

        assertTrue(contradictedScore.contradictionPenalty > 0.9)
        assertTrue(contradictedScore.total < supportedScore.total)
    }

    @Test
    fun `close candidates remain unresolved instead of arbitrary tie break`() {
        val first = branch("a", "alpha answer", 0.80)
        val second = branch("b", "beta answer", 0.76)

        val resolution = evaluator.resolve(request, listOf(second, first))

        assertEquals(first.id, resolution.best?.id)
        assertFalse(resolution.resolved)
        assertTrue(resolution.winnerMargin < request.minimumWinnerMargin)
    }

    @Test
    fun `single strong candidate may resolve`() {
        val candidate = branch("strong", "alpha beta answer", 0.88)

        val resolution = evaluator.resolve(request, listOf(candidate))

        assertTrue(resolution.resolved)
        assertEquals(candidate.id, resolution.best?.id)
    }

    @Test
    fun `external descriptor cannot bypass explicit permission contract`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            DeepSearchSourceDescriptor(
                sourceId = "external",
                kind = DeepSearchSourceKind.EXTERNAL,
                capabilityId = CapabilityId("network.search"),
                permissionState = DeepSearchPermissionState.NOT_REQUIRED,
            )
        }
    }

    private fun finding(statement: String, confidence: Double) = DeepSearchFindingDraft(
        statement = statement,
        semanticTerms = tokenizeSearchText(statement),
        confidence = confidence,
        evidence = listOf(
            DeepSearchEvidenceDraft(
                statement = "evidence for $statement",
                confidence = confidence,
            )
        ),
    )

    private fun branch(id: String, statement: String, total: Double): DeepSearchBranch {
        val evidenceId = DeepSearchEvidenceId("e-$id")
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("h-$id"),
            requestId = request.id,
            statement = statement,
            semanticTerms = tokenizeSearchText(statement),
            confidence = total,
            evidenceIds = setOf(evidenceId),
        )
        return DeepSearchBranch(
            id = DeepSearchBranchId("b-$id"),
            requestId = request.id,
            parentId = DeepSearchBranchId("root"),
            sourceId = "local",
            depth = 1,
            hypothesis = hypothesis,
            score = DeepSearchScore(
                relevance = total,
                evidenceStrength = total,
                sourceReliability = 0.9,
                novelty = 1.0,
                depthCost = 0.25,
                contradictionPenalty = 0.0,
                total = total,
            ),
        )
    }
}

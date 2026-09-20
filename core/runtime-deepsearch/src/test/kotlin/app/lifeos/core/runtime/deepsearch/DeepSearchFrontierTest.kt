package app.lifeos.core.runtime.deepsearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSearchFrontierTest {
    private val request = DeepSearchRequest(
        query = "frontier test",
        budget = DeepSearchBudget(maxDepth = 3, maxBreadth = 2, maxWorkUnits = 16),
    )

    @Test
    fun `poll order is deterministic by typed score then stable id`() {
        val frontier = DeepSearchFrontier(request)
        val low = branch("low", "low finding", 0.55)
        val high = branch("high", "high finding", 0.85)

        frontier.offer(low)
        frontier.offer(high)

        assertEquals(high.id, frontier.poll()?.id)
        assertEquals(low.id, frontier.poll()?.id)
        assertTrue(frontier.isEmpty())
    }

    @Test
    fun `duplicate semantic hypothesis replaces weaker candidate without growing frontier`() {
        val frontier = DeepSearchFrontier(request)
        val weak = branch("weak", "same semantic finding", 0.55)
        val strong = branch("strong", "same semantic finding", 0.82)

        assertEquals(DeepSearchFrontierOfferStatus.ACCEPTED, frontier.offer(weak).status)
        val replacement = frontier.offer(strong)

        assertEquals(DeepSearchFrontierOfferStatus.DUPLICATE_REPLACED, replacement.status)
        assertEquals(weak.id, replacement.replacedBranch?.id)
        assertEquals(1, frontier.size())
        assertEquals(strong.id, frontier.poll()?.id)
    }

    @Test
    fun `weaker duplicate is rejected and existing candidate is preserved`() {
        val frontier = DeepSearchFrontier(request)
        val strong = branch("strong", "same semantic finding", 0.82)
        val weak = branch("weak", "same semantic finding", 0.55)

        frontier.offer(strong)
        val result = frontier.offer(weak)

        assertEquals(DeepSearchFrontierOfferStatus.DUPLICATE_REJECTED, result.status)
        assertEquals(strong.id, frontier.poll()?.id)
    }

    @Test
    fun `breadth limit keeps only strongest candidates at a depth`() {
        val frontier = DeepSearchFrontier(request)
        val a = branch("a", "finding a", 0.50)
        val b = branch("b", "finding b", 0.60)
        val c = branch("c", "finding c", 0.90)

        frontier.offer(a)
        frontier.offer(b)
        val result = frontier.offer(c)

        assertEquals(DeepSearchFrontierOfferStatus.BREADTH_REPLACED, result.status)
        assertEquals(a.id, result.replacedBranch?.id)
        assertEquals(2, frontier.sizeAtDepth(1))
        assertEquals(listOf(c.id, b.id), frontier.admittedBranches().map { it.id })
    }

    @Test
    fun `breadth rejected candidate never enters queue`() {
        val frontier = DeepSearchFrontier(request)
        frontier.offer(branch("a", "finding a", 0.80))
        frontier.offer(branch("b", "finding b", 0.70))
        val rejected = branch("c", "finding c", 0.20)

        val result = frontier.offer(rejected)

        assertEquals(DeepSearchFrontierOfferStatus.BREADTH_REJECTED, result.status)
        assertFalse(frontier.admittedBranches().any { it.id == rejected.id })
        assertEquals(2, frontier.size())
    }

    private fun branch(id: String, statement: String, total: Double): DeepSearchBranch {
        val hypothesis = DeepSearchHypothesis(
            id = DeepSearchHypothesisId("h-$id"),
            requestId = request.id,
            statement = statement,
            semanticTerms = tokenizeSearchText(statement),
            confidence = total,
            evidenceIds = setOf(DeepSearchEvidenceId("e-$id")),
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
                depthCost = 0.33,
                contradictionPenalty = 0.0,
                total = total,
            ),
        )
    }
}

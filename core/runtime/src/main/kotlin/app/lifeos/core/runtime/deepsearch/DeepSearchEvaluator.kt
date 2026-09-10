package app.lifeos.core.runtime.deepsearch

import kotlin.math.abs

/** Typed deterministic scoring; no single score is treated as unqualified truth. */
class DeepSearchEvaluator {
    fun score(
        request: DeepSearchRequest,
        finding: DeepSearchFindingDraft,
        source: DeepSearchSourceDescriptor,
        depth: Int,
        previouslySeenSignatures: Set<String>,
    ): DeepSearchScore {
        require(depth in 0..request.budget.maxDepth)

        val queryTerms = request.queryTerms + request.contextTerms.flatMap(::tokenizeSearchText)
        val candidateTerms = tokenizeSearchText(finding.statement) +
            finding.semanticTerms.flatMap(::tokenizeSearchText)
        val relevance = coverage(queryTerms, candidateTerms)

        val positive = finding.evidence.filterNot { it.contradiction }
        val contradictory = finding.evidence.filter { it.contradiction }
        val evidenceStrength = if (positive.isEmpty()) {
            0.0
        } else {
            positive.map { it.confidence }.average().coerceIn(0.0, 1.0)
        }
        val contradictionPenalty = if (contradictory.isEmpty()) {
            0.0
        } else {
            contradictory.map { it.confidence }.average().coerceIn(0.0, 1.0)
        }
        val signature = findingSignature(finding)
        val novelty = if (signature in previouslySeenSignatures) 0.0 else 1.0
        val depthCost = (depth.toDouble() / request.budget.maxDepth.toDouble()).coerceIn(0.0, 1.0)

        val positiveScore =
            relevance * 0.30 +
                evidenceStrength * 0.30 +
                source.reliability * 0.20 +
                novelty * 0.10 +
                finding.confidence * 0.10
        val total = (
            positiveScore -
                contradictionPenalty * 0.20 -
                depthCost * 0.08
            ).coerceIn(0.0, 1.0)

        return DeepSearchScore(
            relevance = relevance,
            evidenceStrength = evidenceStrength,
            sourceReliability = source.reliability,
            novelty = novelty,
            depthCost = depthCost,
            contradictionPenalty = contradictionPenalty,
            total = total,
        )
    }

    fun resolve(
        request: DeepSearchRequest,
        branches: List<DeepSearchBranch>,
    ): DeepSearchResolution {
        if (branches.isEmpty()) return DeepSearchResolution(null, emptyList(), false, 0.0)
        val ordered = branches.sortedWith(branchOrder())
        val best = ordered.first()
        val runnerUp = ordered.getOrNull(1)
        val margin = if (runnerUp == null) {
            best.score.total
        } else {
            (best.score.total - runnerUp.score.total).coerceAtLeast(0.0)
        }
        val resolved = best.score.total >= request.minimumResolutionScore &&
            margin >= request.minimumWinnerMargin
        return DeepSearchResolution(
            best = best,
            alternatives = ordered.drop(1),
            resolved = resolved,
            winnerMargin = margin,
        )
    }

    fun semanticallyEquivalent(
        first: DeepSearchHypothesis,
        second: DeepSearchHypothesis,
    ): Boolean = first.signature() == second.signature()

    private fun coverage(queryTerms: Set<String>, candidateTerms: Set<String>): Double {
        if (queryTerms.isEmpty() || candidateTerms.isEmpty()) return 0.0
        val overlap = queryTerms.count(candidateTerms::contains)
        return (overlap.toDouble() / queryTerms.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun findingSignature(finding: DeepSearchFindingDraft): String =
        (setOf(normalizeSearchText(finding.statement)) +
            finding.semanticTerms.map(::normalizeSearchText))
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString("|")

    companion object {
        fun branchOrder(): Comparator<DeepSearchBranch> =
            compareByDescending<DeepSearchBranch> { it.score.total }
                .thenByDescending { it.score.relevance }
                .thenByDescending { it.score.evidenceStrength }
                .thenBy { it.depth }
                .thenBy { it.id.value }
    }
}

data class DeepSearchResolution(
    val best: DeepSearchBranch?,
    val alternatives: List<DeepSearchBranch>,
    val resolved: Boolean,
    val winnerMargin: Double,
) {
    init {
        require(winnerMargin.isFinite() && winnerMargin >= 0.0)
        require(!resolved || best != null)
        require(best == null || alternatives.none { it.id == best.id })
        if (alternatives.isNotEmpty() && best != null) {
            require(best.score.total + 1e-12 >= alternatives.first().score.total)
        }
    }
}

package app.lifeos.core.runtime.deepsearch

enum class DeepSearchFrontierOfferStatus {
    ACCEPTED,
    DUPLICATE_REPLACED,
    DUPLICATE_REJECTED,
    BREADTH_REPLACED,
    BREADTH_REJECTED,
}

data class DeepSearchFrontierOffer(
    val status: DeepSearchFrontierOfferStatus,
    val branch: DeepSearchBranch,
    val replacedBranch: DeepSearchBranch? = null,
) {
    val accepted: Boolean
        get() = status == DeepSearchFrontierOfferStatus.ACCEPTED ||
            status == DeepSearchFrontierOfferStatus.DUPLICATE_REPLACED ||
            status == DeepSearchFrontierOfferStatus.BREADTH_REPLACED
}

/**
 * Deterministic bounded search frontier.
 *
 * Dedupe is global for the request by semantic hypothesis signature. Breadth is enforced per depth
 * across all admitted branches, not just currently queued branches, so polling cannot reopen budget.
 */
class DeepSearchFrontier(
    private val request: DeepSearchRequest,
) {
    private val bestBySignature = linkedMapOf<String, DeepSearchBranch>()
    private val admittedByDepth = mutableMapOf<Int, MutableMap<String, DeepSearchBranch>>()
    private val queuedIds = linkedSetOf<DeepSearchBranchId>()
    private val expandedIds = linkedSetOf<DeepSearchBranchId>()

    fun offer(branch: DeepSearchBranch): DeepSearchFrontierOffer {
        require(branch.requestId == request.id) { "DeepSearch branch belongs to another request" }
        require(branch.depth <= request.budget.maxDepth) { "DeepSearch branch exceeds configured depth" }

        val signature = branch.hypothesis.signature()
        val existing = bestBySignature[signature]
        if (existing != null) {
            if (DeepSearchEvaluator.branchOrder().compare(branch, existing) < 0) {
                replace(existing, branch, signature)
                return DeepSearchFrontierOffer(
                    status = DeepSearchFrontierOfferStatus.DUPLICATE_REPLACED,
                    branch = branch,
                    replacedBranch = existing,
                )
            }
            return DeepSearchFrontierOffer(
                status = DeepSearchFrontierOfferStatus.DUPLICATE_REJECTED,
                branch = branch,
                replacedBranch = existing,
            )
        }

        val depthEntries = admittedByDepth.getOrPut(branch.depth) { linkedMapOf() }
        if (depthEntries.size < request.budget.maxBreadth) {
            admit(branch, signature)
            return DeepSearchFrontierOffer(DeepSearchFrontierOfferStatus.ACCEPTED, branch)
        }

        val worst = depthEntries.values.maxWithOrNull(DeepSearchEvaluator.branchOrder())
            ?: error("Breadth-limited DeepSearch depth unexpectedly has no branches")
        if (DeepSearchEvaluator.branchOrder().compare(branch, worst) < 0) {
            val worstSignature = worst.hypothesis.signature()
            remove(worst, worstSignature)
            admit(branch, signature)
            return DeepSearchFrontierOffer(
                status = DeepSearchFrontierOfferStatus.BREADTH_REPLACED,
                branch = branch,
                replacedBranch = worst,
            )
        }
        return DeepSearchFrontierOffer(
            status = DeepSearchFrontierOfferStatus.BREADTH_REJECTED,
            branch = branch,
            replacedBranch = worst,
        )
    }

    fun poll(): DeepSearchBranch? {
        val next = bestBySignature.values
            .asSequence()
            .filter { it.id in queuedIds && it.id !in expandedIds }
            .sortedWith(DeepSearchEvaluator.branchOrder())
            .firstOrNull()
            ?: return null
        queuedIds.remove(next.id)
        expandedIds.add(next.id)
        return next
    }

    fun admittedBranches(): List<DeepSearchBranch> = bestBySignature.values
        .sortedWith(DeepSearchEvaluator.branchOrder())

    fun signatures(): Set<String> = bestBySignature.keys.toSortedSet()

    fun isEmpty(): Boolean = bestBySignature.values.none {
        it.id in queuedIds && it.id !in expandedIds
    }

    fun size(): Int = bestBySignature.size

    fun sizeAtDepth(depth: Int): Int = admittedByDepth[depth]?.size ?: 0

    private fun replace(
        existing: DeepSearchBranch,
        replacement: DeepSearchBranch,
        signature: String,
    ) {
        remove(existing, signature)
        admit(replacement, signature)
    }

    private fun admit(branch: DeepSearchBranch, signature: String) {
        bestBySignature[signature] = branch
        admittedByDepth.getOrPut(branch.depth) { linkedMapOf() }[signature] = branch
        if (branch.id !in expandedIds) queuedIds.add(branch.id)
    }

    private fun remove(branch: DeepSearchBranch, signature: String) {
        bestBySignature.remove(signature)
        admittedByDepth[branch.depth]?.remove(signature)
        if (admittedByDepth[branch.depth]?.isEmpty() == true) admittedByDepth.remove(branch.depth)
        queuedIds.remove(branch.id)
        expandedIds.remove(branch.id)
    }
}

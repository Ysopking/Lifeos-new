package app.lifeos.core.runtime.deepsearch

/** Reconstructs an exact terminal result from durable planner state without invoking any source. */
class DeepSearchCheckpointResultProjector(
    private val evaluator: DeepSearchEvaluator = DeepSearchEvaluator(),
) {
    fun project(checkpoint: DeepSearchPlannerCheckpoint): DeepSearchResult =
        project(checkpoint, terminalStatus(checkpoint))

    fun project(
        checkpoint: DeepSearchPlannerCheckpoint,
        status: DeepSearchStatus,
    ): DeepSearchResult {
        require(isTerminal(checkpoint)) { "DeepSearch checkpoint is not terminal" }
        val frontier = DeepSearchFrontier(checkpoint.request, checkpoint.frontier)
        val resolution = evaluator.resolve(checkpoint.request, frontier.admittedBranches())
        val admittedEvidenceIds = frontier.admittedBranches()
            .flatMapTo(mutableSetOf()) { it.hypothesis.evidenceIds }
        return DeepSearchResult(
            requestId = checkpoint.request.id,
            status = status,
            best = resolution.best,
            alternatives = resolution.alternatives,
            evidence = checkpoint.evidence
                .filter { it.id in admittedEvidenceIds }
                .sortedBy { it.id.value },
            trace = checkpoint.trace,
            workUnitsUsed = checkpoint.workUnitsUsed,
            blockedSourceIds = checkpoint.blockedSourceIds,
            failedSourceIds = checkpoint.failedSourceIds,
        )
    }

    fun isTerminal(checkpoint: DeepSearchPlannerCheckpoint): Boolean =
        checkpoint.trace.lastOrNull()?.let { event ->
            event.type == DeepSearchTraceType.RESOLVED ||
                event.type == DeepSearchTraceType.UNRESOLVED ||
                event.type == DeepSearchTraceType.TIME_LIMIT_REACHED &&
                event.detail == "authorization-time-budget-exhausted"
        } == true

    fun terminalStatus(checkpoint: DeepSearchPlannerCheckpoint): DeepSearchStatus {
        val event = checkpoint.trace.lastOrNull()
            ?: error("DeepSearch checkpoint has no trace")
        if (event.type == DeepSearchTraceType.RESOLVED) return DeepSearchStatus.RESOLVED
        if (event.detail.startsWith(TERMINAL_PREFIX)) {
            val encoded = event.detail
                .removePrefix(TERMINAL_PREFIX)
                .substringBefore(':')
                .uppercase()
            return runCatching { DeepSearchStatus.valueOf(encoded) }.getOrElse {
                error("Invalid terminal DeepSearch checkpoint status")
            }
        }
        return when (event.detail) {
            "no-usable-source" -> DeepSearchStatus.NO_USABLE_SOURCE
            "all-mission-sources-blocked" -> DeepSearchStatus.PERMISSION_BLOCKED
            "authorization-time-budget-exhausted" -> DeepSearchStatus.TIME_BUDGET_EXHAUSTED
            else -> error("DeepSearch checkpoint has no terminal status marker")
        }
    }

    private companion object {
        const val TERMINAL_PREFIX = "terminal-v2:"
    }
}

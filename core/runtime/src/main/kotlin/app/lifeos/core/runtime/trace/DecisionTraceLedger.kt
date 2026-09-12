package app.lifeos.core.runtime.trace

data class DecisionTraceRepositoryLoadReport(
    val traces: List<DecisionTrace>,
    val unreadableEntries: List<String> = emptyList(),
) { init { require(unreadableEntries.none { it.isBlank() }) } }

interface DecisionTraceRepository {
    suspend fun loadReport(): DecisionTraceRepositoryLoadReport
    suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean
}

class DecisionTraceLedger(private val repository: DecisionTraceRepository) {
    suspend fun snapshot(id: DecisionTraceId): DecisionTrace? {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Decision trace store is unreadable: ${report.unreadableEntries.joinToString(",")}"
        }
        val revisions = report.traces.filter { it.id == id }.sortedBy { it.revision }
        if (revisions.isEmpty()) return null
        require(revisions.map { it.revision } == (1L..revisions.size.toLong()).toList()) {
            "Decision trace revisions must be contiguous"
        }
        return revisions.last()
    }

    suspend fun append(
        id: DecisionTraceId,
        nodes: List<DecisionTraceNode>,
        links: List<DecisionTraceLink>,
    ): DecisionTrace {
        require(nodes.isNotEmpty() || links.isNotEmpty())
        repeat(MAX_CAS_ATTEMPTS) {
            val current = snapshot(id)
            val mergedNodes = mergeNodes(current?.nodes.orEmpty(), nodes)
            val mergedLinks = (current?.links.orEmpty() + links).distinct()
                .sortedWith(compareBy({ it.from.value }, { it.to.value }, { it.type.name }))
            if (current != null && current.nodes == mergedNodes && current.links == mergedLinks) {
                return current
            }
            val next = DecisionTrace(id, (current?.revision ?: 0L) + 1L, mergedNodes, mergedLinks)
            if (repository.save(current?.revision ?: 0L, next)) return next
        }
        error("Decision trace CAS retry limit exceeded")
    }

    private fun mergeNodes(
        existing: List<DecisionTraceNode>,
        additions: List<DecisionTraceNode>,
    ): List<DecisionTraceNode> {
        val merged = existing.associateByTo(linkedMapOf()) { it.id }
        additions.forEach { incoming ->
            val previous = merged[incoming.id]
            if (previous == null) {
                merged[incoming.id] = incoming
            } else {
                require(previous.copy(recordedAt = incoming.recordedAt) == incoming) {
                    "Decision trace node identity collision: ${incoming.id.value}"
                }
                // recordedAt is observation metadata, not part of the durable node identity.
                // Converge repeated projections deterministically on the earliest observation.
                if (incoming.recordedAt < previous.recordedAt) {
                    merged[incoming.id] = previous.copy(recordedAt = incoming.recordedAt)
                }
            }
        }
        return merged.values.sortedBy { it.id.value }
    }

    private companion object { const val MAX_CAS_ATTEMPTS = 32 }
}

data class DecisionTraceProjection(
    val traceId: DecisionTraceId,
    val revision: Long,
    val facts: List<DecisionTraceNode>,
    val constraints: List<DecisionTraceNode>,
    val alternatives: List<DecisionTraceNode>,
    val outcomes: List<DecisionTraceNode>,
    val unresolved: Boolean,
)

class DecisionTraceProjector {
    fun project(trace: DecisionTrace) = DecisionTraceProjection(
        trace.id,
        trace.revision,
        trace.nodes.filter { it.type == DecisionTraceNodeType.OBSERVED_FACT },
        trace.nodes.filter {
            it.type == DecisionTraceNodeType.POLICY_CONSTRAINT || it.type == DecisionTraceNodeType.RESOURCE_CONSTRAINT
        },
        trace.nodes.filter {
            it.type == DecisionTraceNodeType.CANDIDATE_ALTERNATIVE ||
                it.type == DecisionTraceNodeType.REJECTION || it.type == DecisionTraceNodeType.SELECTION
        },
        trace.nodes.filter {
            it.type == DecisionTraceNodeType.EXECUTION_OUTCOME || it.type == DecisionTraceNodeType.RECOVERY_OUTCOME
        },
        trace.nodes.any { it.type == DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY },
    )
}

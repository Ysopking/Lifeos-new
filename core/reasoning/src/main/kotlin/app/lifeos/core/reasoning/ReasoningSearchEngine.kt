package app.lifeos.core.reasoning

import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.FieldNode
import app.lifeos.core.field.FieldNodeKind
import app.lifeos.core.field.FieldRelationType
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds

data class ReasoningSearchConfig(
    val maxExpandedStates: Int = 512,
    val maxFrontierStates: Int = 128,
) {
    init {
        require(maxExpandedStates in 1..16_384)
        require(maxFrontierStates in 1..4_096)
    }
}

data class ReasoningSearchMetrics(
    val supportingEvidenceCount: Int,
    val supportingWeight: Double,
    val contradictionWeight: Double,
    val assumptionDependencyWeight: Double,
    val unresolvedCompetitionCount: Int,
) {
    init {
        require(supportingEvidenceCount >= 0)
        require(supportingWeight.isFinite() && supportingWeight >= 0.0)
        require(contradictionWeight.isFinite() && contradictionWeight >= 0.0)
        require(assumptionDependencyWeight.isFinite() && assumptionDependencyWeight >= 0.0)
        require(unresolvedCompetitionCount >= 0)
    }
}

data class ReasoningSearchState(
    val selectedHypothesisIds: List<HypothesisId>,
    val resolvedCompetitionKeys: List<String>,
    val unresolvedCompetitionKeys: List<String>,
    val metrics: ReasoningSearchMetrics,
    val fingerprint: String,
) {
    init {
        require(selectedHypothesisIds == selectedHypothesisIds.distinct().sortedBy { it.value })
        require(resolvedCompetitionKeys == resolvedCompetitionKeys.distinct().sorted())
        require(unresolvedCompetitionKeys == unresolvedCompetitionKeys.distinct().sorted())
        require(resolvedCompetitionKeys.intersect(unresolvedCompetitionKeys.toSet()).isEmpty())
        require(
            fingerprint == stateFingerprint(
                selectedHypothesisIds,
                resolvedCompetitionKeys,
                unresolvedCompetitionKeys,
                metrics,
            )
        )
    }

    val complete: Boolean
        get() = unresolvedCompetitionKeys.isEmpty()
}

data class ReasoningSearchResult(
    val seedFingerprint: String,
    val states: List<ReasoningSearchState>,
    val exploredStates: Int,
    val truncated: Boolean,
    val fingerprint: String,
) {
    init {
        require(seedFingerprint.isNotBlank())
        require(exploredStates >= 0)
        require(states == states.sortedWith(reasoningStateOrder()))
        require(
            fingerprint == resultFingerprint(
                seedFingerprint = seedFingerprint,
                states = states,
                exploredStates = exploredStates,
                truncated = truncated,
            )
        )
    }

    val completeStates: List<ReasoningSearchState>
        get() = states.filter { it.complete }
}

/**
 * B368 bounded structural search.
 *
 * This engine never decides truth and never mutates convergence state. It explores combinations
 * of already-admitted B367 hypotheses and exposes only structural evidence/contradiction/assumption
 * metrics. FieldConvergenceEngine remains authoritative for convergence.
 */
class ReasoningSearchEngine(
    private val config: ReasoningSearchConfig = ReasoningSearchConfig(),
) {
    fun search(seed: ProblemHypothesisSeed): ReasoningSearchResult {
        val index = validateAndIndex(seed)
        val groups = seed.graph.competitionGroups.sortedBy { it.key }
        require(groups.isNotEmpty()) { "Reasoning search requires at least one competition group" }

        var frontier = listOf(
            buildState(
                selected = emptyList(),
                resolvedKeys = emptyList(),
                groups = groups.map { it.key },
                seed = seed,
                index = index,
            )
        )
        var explored = 0
        var truncated = false
        var groupIndex = 0

        groupLoop@ while (groupIndex < groups.size) {
            val group = groups[groupIndex]
            val alternatives = group.nodeIds
                .map { nodeId -> index.hypothesisByNodeId.getValue(nodeId) }
                .distinctBy { it.id }
                .sortedBy { it.id.value }
            require(alternatives.size >= 2) {
                "Reasoning competition group requires at least two hypothesis alternatives"
            }

            val expanded = mutableListOf<ReasoningSearchState>()
            for (state in frontier.sortedWith(reasoningStateOrder())) {
                for (candidate in alternatives) {
                    if (!compatible(candidate, state.selectedHypothesisIds, index.hypothesisById)) {
                        continue
                    }
                    if (explored >= config.maxExpandedStates) {
                        truncated = true
                        break@groupLoop
                    }
                    explored += 1
                    val selected = (state.selectedHypothesisIds + candidate.id)
                        .distinct()
                        .sortedBy { it.value }
                    val resolvedKeys = (state.resolvedCompetitionKeys + group.key)
                        .distinct()
                        .sorted()
                    expanded += buildState(
                        selected = selected,
                        resolvedKeys = resolvedKeys,
                        groups = groups.map { it.key },
                        seed = seed,
                        index = index,
                    )
                }
            }

            if (expanded.isEmpty()) {
                frontier = emptyList()
                break
            }

            val ordered = expanded
                .distinctBy { it.fingerprint }
                .sortedWith(reasoningStateOrder())
            if (ordered.size > config.maxFrontierStates) {
                truncated = true
            }
            frontier = ordered.take(config.maxFrontierStates)
            groupIndex += 1
        }

        val states = frontier.sortedWith(reasoningStateOrder())
        val fingerprint = resultFingerprint(
            seedFingerprint = seed.fingerprint,
            states = states,
            exploredStates = explored,
            truncated = truncated,
        )
        return ReasoningSearchResult(
            seedFingerprint = seed.fingerprint,
            states = states,
            exploredStates = explored,
            truncated = truncated,
            fingerprint = fingerprint,
        )
    }

    private fun buildState(
        selected: List<HypothesisId>,
        resolvedKeys: List<String>,
        groups: List<String>,
        seed: ProblemHypothesisSeed,
        index: SearchIndex,
    ): ReasoningSearchState {
        val selectedHypotheses = selected.map(index.hypothesisById::getValue)
        val metrics = metrics(
            selectedHypotheses = selectedHypotheses,
            seed = seed,
            index = index,
            unresolvedCompetitionCount = groups.size - resolvedKeys.size,
        )
        val unresolvedKeys = groups
            .filterNot(resolvedKeys.toSet()::contains)
            .sorted()
        return ReasoningSearchState(
            selectedHypothesisIds = selected.sortedBy { it.value },
            resolvedCompetitionKeys = resolvedKeys.sorted(),
            unresolvedCompetitionKeys = unresolvedKeys,
            metrics = metrics,
            fingerprint = stateFingerprint(
                selectedHypothesisIds = selected.sortedBy { it.value },
                resolvedCompetitionKeys = resolvedKeys.sorted(),
                unresolvedCompetitionKeys = unresolvedKeys,
                metrics = metrics,
            ),
        )
    }

    private fun metrics(
        selectedHypotheses: List<FieldHypothesis>,
        seed: ProblemHypothesisSeed,
        index: SearchIndex,
        unresolvedCompetitionCount: Int,
    ): ReasoningSearchMetrics {
        val supportingEvidence = linkedSetOf<app.lifeos.core.field.EvidenceId>()
        var supportingWeight = 0.0
        var contradictionWeight = 0.0
        var assumptionDependencyWeight = 0.0

        selectedHypotheses.forEach { hypothesis ->
            hypothesis.evidenceLinks.forEach { link ->
                when (link.relation) {
                    EvidenceRelationType.SUPPORTS -> {
                        supportingEvidence += link.evidenceId
                        supportingWeight += link.weight
                    }
                    EvidenceRelationType.CONTRADICTS -> {
                        contradictionWeight += link.weight
                    }
                    else -> Unit
                }
            }

            val hypothesisNode = index.nodeByHypothesisId.getValue(hypothesis.id)
            seed.graph.incoming(hypothesisNode.id)
                .filter { it.type == FieldRelationType.DEPENDS_ON }
                .forEach { relation ->
                    assumptionDependencyWeight += relation.weight
                }
        }

        return ReasoningSearchMetrics(
            supportingEvidenceCount = supportingEvidence.size,
            supportingWeight = supportingWeight,
            contradictionWeight = contradictionWeight,
            assumptionDependencyWeight = assumptionDependencyWeight,
            unresolvedCompetitionCount = unresolvedCompetitionCount,
        )
    }

    private fun compatible(
        candidate: FieldHypothesis,
        selectedIds: List<HypothesisId>,
        hypothesisById: Map<HypothesisId, FieldHypothesis>,
    ): Boolean {
        val selected = selectedIds.toSet()
        if (candidate.conflicts.any { it.competingHypothesisId in selected }) {
            return false
        }
        return selectedIds.none { selectedId ->
            hypothesisById.getValue(selectedId).conflicts.any {
                it.competingHypothesisId == candidate.id
            }
        }
    }

    private fun validateAndIndex(seed: ProblemHypothesisSeed): SearchIndex {
        val hypothesisById = seed.hypotheses.associateBy { it.id }
        require(hypothesisById.size == seed.hypotheses.size)

        val hypothesisNodes = seed.graph.nodes
            .filter { it.kind == FieldNodeKind.HYPOTHESIS }
            .sortedBy { it.id.value }
        val hypothesisByNodeId = linkedMapOf<app.lifeos.core.field.FieldNodeId, FieldHypothesis>()
        val nodeByHypothesisId = linkedMapOf<HypothesisId, FieldNode>()

        hypothesisNodes.forEach { node ->
            val matches = seed.hypotheses.filter { hypothesis ->
                hypothesis.semanticKey == node.semanticKey &&
                    node.id in hypothesis.nodeIds
            }
            require(matches.size == 1) {
                "Each B367 hypothesis node must map to exactly one FieldHypothesis"
            }
            val hypothesis = matches.single()
            hypothesisByNodeId[node.id] = hypothesis
            val previous = nodeByHypothesisId.put(hypothesis.id, node)
            require(previous == null) {
                "One FieldHypothesis cannot map to multiple hypothesis nodes"
            }
        }
        require(nodeByHypothesisId.size == seed.hypotheses.size) {
            "Every B367 FieldHypothesis must have one FieldGraph HYPOTHESIS node"
        }

        seed.graph.competitionGroups.forEach { group ->
            require(group.nodeIds.all(hypothesisByNodeId::containsKey)) {
                "Competition groups may reference only admitted hypothesis nodes"
            }
        }

        return SearchIndex(
            hypothesisById = hypothesisById,
            hypothesisByNodeId = hypothesisByNodeId,
            nodeByHypothesisId = nodeByHypothesisId,
        )
    }

    private data class SearchIndex(
        val hypothesisById: Map<HypothesisId, FieldHypothesis>,
        val hypothesisByNodeId: Map<app.lifeos.core.field.FieldNodeId, FieldHypothesis>,
        val nodeByHypothesisId: Map<HypothesisId, FieldNode>,
    )
}

private fun reasoningStateOrder(): Comparator<ReasoningSearchState> =
    compareBy<ReasoningSearchState> { it.metrics.unresolvedCompetitionCount }
        .thenBy { it.metrics.contradictionWeight }
        .thenByDescending { it.metrics.supportingEvidenceCount }
        .thenByDescending { it.metrics.supportingWeight }
        .thenBy { it.metrics.assumptionDependencyWeight }
        .thenBy { it.fingerprint }

private fun stateFingerprint(
    selectedHypothesisIds: List<HypothesisId>,
    resolvedCompetitionKeys: List<String>,
    unresolvedCompetitionKeys: List<String>,
    metrics: ReasoningSearchMetrics,
): String = StableFieldIds.fingerprint(
    "reasoning-search-state/v1",
    java.lang.Double.toHexString(metrics.supportingWeight),
    java.lang.Double.toHexString(metrics.contradictionWeight),
    java.lang.Double.toHexString(metrics.assumptionDependencyWeight),
    metrics.supportingEvidenceCount.toString(),
    metrics.unresolvedCompetitionCount.toString(),
    *selectedHypothesisIds.map { "selected:" + it.value }.sorted().toTypedArray(),
    *resolvedCompetitionKeys.map { "resolved:" + it }.sorted().toTypedArray(),
    *unresolvedCompetitionKeys.map { "unresolved:" + it }.sorted().toTypedArray(),
)

private fun resultFingerprint(
    seedFingerprint: String,
    states: List<ReasoningSearchState>,
    exploredStates: Int,
    truncated: Boolean,
): String = StableFieldIds.fingerprint(
    "reasoning-search-result/v1",
    seedFingerprint,
    exploredStates.toString(),
    truncated.toString(),
    *states.map { "state:" + it.fingerprint }.toTypedArray(),
)

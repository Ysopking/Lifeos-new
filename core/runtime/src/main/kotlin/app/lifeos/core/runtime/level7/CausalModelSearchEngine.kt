package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class CausalVariable(
    val id: String,
    val semanticKey: String,
) {
    init {
        require(id.isNotBlank())
        require(semanticKey.isNotBlank())
    }
}

data class CausalGraphEdgeCandidate(
    val sourceVariableId: String,
    val targetVariableId: String,
    val supportFingerprint: String,
) {
    init {
        require(sourceVariableId.isNotBlank())
        require(targetVariableId.isNotBlank())
        require(sourceVariableId != targetVariableId)
        require(supportFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "causal-graph-edge-candidate/v1",
        sourceVariableId,
        targetVariableId,
        supportFingerprint,
    )
}

data class CausalGraphCandidate(
    val id: String,
    val variables: List<CausalVariable>,
    val edges: List<CausalGraphEdgeCandidate>,
    val score: Double,
    val causalAuthority: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(variables.isNotEmpty())
        require(edges.isNotEmpty())
        require(score.isFinite())
        require(!causalAuthority) {
            "Causal graph search output is candidate evidence, not causal authority"
        }
    }
}

data class CausalDiscriminationRequest(
    val candidateIds: Set<String>,
    val interventionVariableId: String,
    val expectedInformationGain: Double,
    val rationale: String,
) {
    init {
        require(candidateIds.size >= 2)
        require(interventionVariableId.isNotBlank())
        require(expectedInformationGain.isFinite() && expectedInformationGain > 0.0)
        require(rationale.isNotBlank())
    }
}

class CausalModelSearchEngine(
    private val maxCandidates: Int = 16,
) {
    init { require(maxCandidates in 2..64) }

    fun search(
        variables: List<CausalVariable>,
        observations: List<CausalObservation>,
    ): List<CausalGraphCandidate> {
        require(variables.size >= 2)
        require(observations.isNotEmpty())
        val bySemantic = variables.associateBy { it.semanticKey }
        val candidates = mutableListOf<CausalGraphCandidate>()

        observations
            .sortedBy { it.fingerprint() }
            .take(maxCandidates)
            .forEach { observation ->
                val source = bySemantic[observation.sourceDimension.name] ?: return@forEach
                val target = bySemantic[observation.targetDimension.name] ?: return@forEach
                val support = observation.fingerprint()
                candidates += graph(variables, source.id, target.id, support, observation.confidence)
                if (observation.evidenceKind == CausalEvidenceKind.TEMPORAL_CORRELATION) {
                    candidates += graph(
                        variables,
                        target.id,
                        source.id,
                        support,
                        observation.confidence * 0.95,
                    )
                }
            }

        return candidates
            .distinctBy { it.id }
            .sortedWith(compareByDescending<CausalGraphCandidate> { it.score }.thenBy { it.id })
            .take(maxCandidates)
    }

    fun discriminationRequests(
        candidates: List<CausalGraphCandidate>,
    ): List<CausalDiscriminationRequest> {
        if (candidates.size < 2) return emptyList()
        val competing = candidates.take(2)
        val differingSources = competing
            .flatMap { graph -> graph.edges.map { it.sourceVariableId } }
            .distinct()
            .sorted()
        return differingSources.take(4).mapIndexed { index, variable ->
            CausalDiscriminationRequest(
                candidateIds = competing.mapTo(linkedSetOf()) { it.id },
                interventionVariableId = variable,
                expectedInformationGain = (1.0 / (index + 1.0)).coerceAtMost(1.0),
                rationale = "distinguish-competing-causal-graphs",
            )
        }
    }

    private fun graph(
        variables: List<CausalVariable>,
        source: String,
        target: String,
        support: String,
        score: Double,
    ): CausalGraphCandidate {
        val edge = CausalGraphEdgeCandidate(source, target, support)
        val id = "causal-graph:${StableFieldIds.fingerprint(
            "causal-graph-candidate/v1",
            *variables.sortedBy { it.id }.flatMap { listOf(it.id, it.semanticKey) }.toTypedArray(),
            edge.fingerprint(),
        )}"
        return CausalGraphCandidate(
            id = id,
            variables = variables.sortedBy { it.id },
            edges = listOf(edge),
            score = score,
            causalAuthority = false,
        )
    }
}

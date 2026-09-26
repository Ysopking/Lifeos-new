package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

data class LayeredCandidateObservation(
    val candidateId: String,
    val layers: Map<Int, String>,
) {
    init {
        require(candidateId.isNotBlank())
        require(layers.isNotEmpty())
        require(layers.keys.all { it >= 0 })
        require(layers.values.none { it.isBlank() })
    }
}

data class CandidatePair(
    val firstId: String,
    val secondId: String,
) {
    init {
        require(firstId.isNotBlank())
        require(secondId.isNotBlank())
        require(firstId < secondId) {
            "Candidate pair ids must be canonical"
        }
    }
}

data class PairSeparation(
    val pair: CandidatePair,
    val firstSeparatingDepth: Int?,
)

data class LayeredIdentifiabilityAssessment(
    val pairSeparations: List<PairSeparation>,
    val completeSeparatingDepth: Int?,
    val unresolvedPairs: List<CandidatePair>,
    val maximumDepth: Int,
    val fingerprint: String,
) {
    init {
        require(maximumDepth >= 0)
        require(pairSeparations == pairSeparations.sortedBy { it.pair.firstId + "\u0000" + it.pair.secondId })
        require(unresolvedPairs == unresolvedPairs.sortedBy { it.firstId + "\u0000" + it.secondId })
    }

    val fullyIdentifiable: Boolean
        get() = unresolvedPairs.isEmpty()

    val truthAuthority: Boolean
        get() = false

    val mergeAuthority: Boolean
        get() = false
}

class IdentifiabilityDepthAnalyzer {
    fun assess(
        candidates: Collection<LayeredCandidateObservation>,
        maximumDepth: Int,
    ): LayeredIdentifiabilityAssessment {
        require(candidates.size >= 2)
        require(maximumDepth >= 0)
        val canonical = candidates
            .distinctBy { it.candidateId }
            .sortedBy { it.candidateId }
        require(canonical.size == candidates.size) {
            "Candidate ids must be unique"
        }

        val separations = buildList {
            for (leftIndex in 0 until canonical.lastIndex) {
                for (rightIndex in leftIndex + 1 until canonical.size) {
                    val left = canonical[leftIndex]
                    val right = canonical[rightIndex]
                    val pair = CandidatePair(left.candidateId, right.candidateId)
                    val depth = (0..maximumDepth).firstOrNull { layer ->
                        val a = left.layers[layer]
                        val b = right.layers[layer]
                        a != null && b != null && a != b
                    }
                    add(PairSeparation(pair, depth))
                }
            }
        }.sortedBy { it.pair.firstId + "\u0000" + it.pair.secondId }

        val unresolved = separations
            .filter { it.firstSeparatingDepth == null }
            .map { it.pair }
            .sortedBy { it.firstId + "\u0000" + it.secondId }
        val completeDepth = if (unresolved.isEmpty()) {
            separations.maxOfOrNull { requireNotNull(it.firstSeparatingDepth) }
        } else {
            null
        }

        return LayeredIdentifiabilityAssessment(
            pairSeparations = separations,
            completeSeparatingDepth = completeDepth,
            unresolvedPairs = unresolved,
            maximumDepth = maximumDepth,
            fingerprint = StableFieldIds.fingerprint(
                "meta-identifiability-depth/v1",
                maximumDepth.toString(),
                *separations.flatMap {
                    listOf(
                        it.pair.firstId,
                        it.pair.secondId,
                        it.firstSeparatingDepth?.toString() ?: "UNRESOLVED",
                    )
                }.toTypedArray(),
            ),
        )
    }
}

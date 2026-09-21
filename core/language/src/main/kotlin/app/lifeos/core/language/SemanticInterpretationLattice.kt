package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

data class SemanticInterpretationCandidate(
    val intent: IntentType,
    val reference: PhotonRevisionRef?,
    val score: Double,
    val blockers: Set<String>,
    val fingerprint: String,
) {
    init {
        require(score.isFinite() && score in 0.0..1.0)
        require(fingerprint.isNotBlank())
    }
}

data class SemanticInterpretationLattice(
    val candidates: List<SemanticInterpretationCandidate>,
    val winner: SemanticInterpretationCandidate?,
    val margin: Double,
    val converged: Boolean,
) {
    init {
        require(margin.isFinite() && margin in 0.0..1.0)
        require(winner == null || winner in candidates)
    }

    companion object {
        fun empty(): SemanticInterpretationLattice =
            SemanticInterpretationLattice(emptyList(), null, 0.0, false)
    }
}

class SemanticInterpretationLatticeEngine(
    private val maxCandidates: Int = 12,
    private val minimumMargin: Double = 0.08,
) {
    init {
        require(maxCandidates in 2..64)
        require(minimumMargin in 0.0..1.0)
    }

    fun converge(
        intents: List<IntentEvidence>,
        references: List<ResolvedReference>,
        actionGraph: SemanticActionGraph,
        linguisticField: LinguisticFieldResult,
    ): SemanticInterpretationLattice {
        val intentCandidates = intents.take(3)
        if (intentCandidates.isEmpty()) return SemanticInterpretationLattice.empty()

        val referenceCandidates = buildList<PhotonRevisionRef?> {
            add(null)
            references.forEach { resolved ->
                resolved.targetPhotonRef?.let(::add)
                resolved.revisionAlternatives.take(2).forEach { add(it.first) }
            }
        }.distinct()

        val candidates = intentCandidates.flatMap { intent ->
            referenceCandidates.map { reference ->
                val actionSupport = actionGraph.nodes
                    .filter { it.frame.predicate.toIntentTypeOrNull() == intent.intent }
                    .maxOfOrNull { it.frame.confidence }
                    ?: 0.0
                val referenceSupport = if (reference == null) {
                    if (references.isEmpty()) 0.80 else 0.45
                } else {
                    references.asSequence()
                        .flatMap { resolved ->
                            sequence {
                                resolved.targetPhotonRef?.let { yield(it to resolved.score) }
                                yieldAll(resolved.revisionAlternatives.asSequence())
                            }
                        }
                        .firstOrNull { it.first == reference }
                        ?.second
                        ?: 0.0
                }
                val fieldSupport = linguisticField.intentField
                    .firstOrNull { it.intent == intent.intent }
                    ?.activation
                    ?: 0.0
                val blockers = buildSet {
                    actionGraph.nodes
                        .filter { it.frame.predicate.toIntentTypeOrNull() == intent.intent }
                        .forEach { node ->
                            if (node.frame.negated) add("negation")
                            if (node.frame.quoted) add("quotation")
                            if (node.frame.hypothetical) add("hypothetical")
                            if (node.unresolvedCondition) add("condition")
                            if (node.unresolvedReference) add("reference")
                            if (node.unresolvedRoles.isNotEmpty()) add("roles")
                        }
                }
                val blockerPenalty = (blockers.size * 0.08).coerceAtMost(0.32)
                val score = (
                    intent.score * 0.46 +
                        actionSupport * 0.24 +
                        referenceSupport * 0.18 +
                        fieldSupport * 0.12 -
                        blockerPenalty
                    ).coerceIn(0.0, 1.0)
                SemanticInterpretationCandidate(
                    intent = intent.intent,
                    reference = reference,
                    score = score,
                    blockers = blockers,
                    fingerprint = StableCognitiveIds.fingerprint(
                        "semantic-interpretation/v1",
                        intent.intent.name,
                        reference?.stableKey.orEmpty(),
                        java.lang.Double.toHexString(score),
                        blockers.sorted().joinToString(","),
                    ),
                )
            }
        }.sortedWith(
            compareByDescending<SemanticInterpretationCandidate> { it.score }
                .thenBy { it.intent.name }
                .thenBy { it.reference?.stableKey.orEmpty() }
        ).take(maxCandidates)

        val first = candidates.firstOrNull()
        val second = candidates.getOrNull(1)
        val margin = if (first == null) 0.0 else (first.score - (second?.score ?: 0.0)).coerceIn(0.0, 1.0)
        return SemanticInterpretationLattice(
            candidates = candidates,
            winner = first,
            margin = margin,
            converged = first != null && margin >= minimumMargin,
        )
    }
}

package app.lifeos.core.language

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds

data class SemanticInterpretationCandidate(
    val intent: IntentType,
    val reference: PhotonRevisionRef?,
    val score: Double,
    val blockers: Set<String>,
    val fingerprint: String,
    val worldSupport: Double = 0.0,
    val worldConflict: Double = 0.0,
    val worldStateSufficient: Boolean = true,
) {
    init {
        require(score.isFinite() && score in 0.0..1.0)
        require(worldSupport.isFinite() && worldSupport in 0.0..1.0)
        require(worldConflict.isFinite() && worldConflict in 0.0..1.0)
        require(fingerprint.isNotBlank())
    }
}

data class SemanticInterpretationLattice(
    val candidates: List<SemanticInterpretationCandidate>,
    val winner: SemanticInterpretationCandidate?,
    val margin: Double,
    val converged: Boolean,
    val worldEvidenceFingerprint: String? = null,
    val unresolvedDueToWorldState: Boolean = false,
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


data class LanguageWorldInterpretationEvidence(
    val intent: IntentType,
    val reference: PhotonRevisionRef? = null,
    val support: Double,
    val contradiction: Double,
    val stateSufficient: Boolean,
    val sourceFingerprint: String,
) {
    init {
        require(support.isFinite() && support in 0.0..1.0)
        require(contradiction.isFinite() && contradiction in 0.0..1.0)
        require(sourceFingerprint.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "language-world-interpretation-evidence/v1",
        intent.name,
        reference?.stableKey.orEmpty(),
        java.lang.Double.toHexString(support),
        java.lang.Double.toHexString(contradiction),
        stateSufficient.toString(),
        sourceFingerprint,
    )
}

private data class AggregatedWorldInterpretationEvidence(
    val matched: Boolean,
    val support: Double,
    val contradiction: Double,
    val stateSufficient: Boolean,
    val fingerprint: String,
)

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
        worldEvidence: List<LanguageWorldInterpretationEvidence> = emptyList(),
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
                val world = aggregateWorldEvidence(
                    intent = intent.intent,
                    reference = reference,
                    evidence = worldEvidence,
                )
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
                    if (world.matched && !world.stateSufficient) {
                        add("world-state-insufficient")
                    }
                    if (world.matched && world.contradiction >= WORLD_CONTRADICTION_BLOCK_THRESHOLD) {
                        add("world-contradiction")
                    }
                }
                val linguisticBlockers = blockers.count {
                    it != "world-state-insufficient" && it != "world-contradiction"
                }
                val blockerPenalty = (linguisticBlockers * 0.08).coerceAtMost(0.32)
                val worldAdjustment = if (world.matched) {
                    world.support * WORLD_SUPPORT_WEIGHT -
                        world.contradiction * WORLD_CONFLICT_WEIGHT -
                        if (world.stateSufficient) 0.0 else WORLD_INSUFFICIENCY_PENALTY
                } else {
                    0.0
                }
                val score = (
                    intent.score * 0.46 +
                        actionSupport * 0.24 +
                        referenceSupport * 0.18 +
                        fieldSupport * 0.12 -
                        blockerPenalty +
                        worldAdjustment
                    ).coerceIn(0.0, 1.0)
                val fingerprint = if (world.matched) {
                    StableCognitiveIds.fingerprint(
                        "semantic-interpretation/v2",
                        intent.intent.name,
                        reference?.stableKey.orEmpty(),
                        java.lang.Double.toHexString(score),
                        blockers.sorted().joinToString(","),
                        java.lang.Double.toHexString(world.support),
                        java.lang.Double.toHexString(world.contradiction),
                        world.stateSufficient.toString(),
                        world.fingerprint,
                    )
                } else {
                    StableCognitiveIds.fingerprint(
                        "semantic-interpretation/v1",
                        intent.intent.name,
                        reference?.stableKey.orEmpty(),
                        java.lang.Double.toHexString(score),
                        blockers.sorted().joinToString(","),
                    )
                }
                SemanticInterpretationCandidate(
                    intent = intent.intent,
                    reference = reference,
                    score = score,
                    blockers = blockers,
                    fingerprint = fingerprint,
                    worldSupport = world.support,
                    worldConflict = world.contradiction,
                    worldStateSufficient = world.stateSufficient,
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
        val worldBlocked = first?.blockers?.any {
            it == "world-state-insufficient" || it == "world-contradiction"
        } == true
        val evidenceFingerprint = worldEvidence
            .takeIf { it.isNotEmpty() }
            ?.sortedBy { it.fingerprint }
            ?.let { ordered ->
                StableCognitiveIds.fingerprint(
                    "language-world-interpretation-evidence-set/v1",
                    *ordered.map { it.fingerprint }.toTypedArray(),
                )
            }
        return SemanticInterpretationLattice(
            candidates = candidates,
            winner = first,
            margin = margin,
            converged = first != null && margin >= minimumMargin && !worldBlocked,
            worldEvidenceFingerprint = evidenceFingerprint,
            unresolvedDueToWorldState = worldBlocked,
        )
    }

    private fun aggregateWorldEvidence(
        intent: IntentType,
        reference: PhotonRevisionRef?,
        evidence: List<LanguageWorldInterpretationEvidence>,
    ): AggregatedWorldInterpretationEvidence {
        val matching = evidence.filter {
            it.intent == intent && (it.reference == null || it.reference == reference)
        }
        if (matching.isEmpty()) {
            return AggregatedWorldInterpretationEvidence(
                matched = false,
                support = 0.0,
                contradiction = 0.0,
                stateSufficient = true,
                fingerprint = "",
            )
        }
        val ordered = matching.sortedBy { it.fingerprint }
        return AggregatedWorldInterpretationEvidence(
            matched = true,
            support = ordered.maxOf { it.support },
            contradiction = ordered.maxOf { it.contradiction },
            stateSufficient = ordered.all { it.stateSufficient },
            fingerprint = StableCognitiveIds.fingerprint(
                "aggregated-language-world-evidence/v1",
                *ordered.map { it.fingerprint }.toTypedArray(),
            ),
        )
    }

    private companion object {
        const val WORLD_SUPPORT_WEIGHT = 0.14
        const val WORLD_CONFLICT_WEIGHT = 0.18
        const val WORLD_INSUFFICIENCY_PENALTY = 0.08
        const val WORLD_CONTRADICTION_BLOCK_THRESHOLD = 0.50
    }
}

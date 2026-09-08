package app.lifeos.core.field

enum class HypothesisState {
    SEED,
    ACTIVE,
    COMPETING,
    SUPPORTED,
    WEAK,
    CONVERGED,
    REJECTED,
    UNRESOLVED,
}

enum class HypothesisScope {
    TOKEN,
    MESSAGE,
    CONVERSATION,
    DOCUMENT,
    ACCOUNT,
    LEGAL_ISSUE,
    SCIENTIFIC_CLAIM,
    DOMAIN,
    WORLD,
}

data class HypothesisEvidenceLink(
    val evidenceId: EvidenceId,
    val relation: EvidenceRelationType,
    val weight: Double,
) {
    init { require(weight in 0.0..1.0) { "Hypothesis evidence weight must be in 0..1" } }
}

data class HypothesisConflictLink(
    val competingHypothesisId: HypothesisId,
    val strength: Double,
    val reason: String,
) {
    init {
        require(strength in 0.0..1.0) { "Hypothesis conflict strength must be in 0..1" }
        require(reason.isNotBlank()) { "Hypothesis conflict reason must not be blank" }
    }
}

data class HypothesisScore(
    val evidence: Double = 0.0,
    val support: Double = 0.0,
    val contradiction: Double = 0.0,
    val context: Double = 0.0,
    val temporal: Double = 0.0,
    val authority: Double = 0.0,
    val total: Double = 0.0,
) {
    init {
        require(
            listOf(evidence, support, contradiction, context, temporal, authority, total)
                .all { it.isFinite() },
        ) { "Hypothesis scores must be finite" }
        require(total in 0.0..1.0) { "Hypothesis total must be in 0..1" }
        require(contradiction >= 0.0) { "Contradiction component must be non-negative" }
    }
}

data class FieldHypothesis(
    val id: HypothesisId,
    val domainId: FieldDomainId,
    val semanticKey: String,
    val scope: HypothesisScope,
    val state: HypothesisState = HypothesisState.SEED,
    val nodeIds: Set<FieldNodeId>,
    val evidenceLinks: List<HypothesisEvidenceLink> = emptyList(),
    val conflicts: List<HypothesisConflictLink> = emptyList(),
    val score: HypothesisScore = HypothesisScore(),
    val explanation: String,
) {
    init {
        require(semanticKey.isNotBlank()) { "Hypothesis semantic key must not be blank" }
        require(nodeIds.isNotEmpty()) { "Hypothesis must reference at least one field node" }
        require(explanation.isNotBlank()) { "Hypothesis explanation must not be blank" }
        require(evidenceLinks.map { it.evidenceId to it.relation }.distinct().size == evidenceLinks.size) {
            "Duplicate hypothesis evidence links are not allowed"
        }
        require(conflicts.none { it.competingHypothesisId == id }) {
            "Hypothesis cannot conflict with itself"
        }
    }

    companion object {
        fun create(
            domainId: FieldDomainId,
            semanticKey: String,
            scope: HypothesisScope,
            nodeIds: Set<FieldNodeId>,
            evidenceLinks: List<HypothesisEvidenceLink> = emptyList(),
            conflicts: List<HypothesisConflictLink> = emptyList(),
            score: HypothesisScore = HypothesisScore(),
            state: HypothesisState = HypothesisState.SEED,
            explanation: String,
        ): FieldHypothesis = FieldHypothesis(
            id = StableFieldIds.hypothesis(domainId, semanticKey),
            domainId = domainId,
            semanticKey = semanticKey,
            scope = scope,
            state = state,
            nodeIds = nodeIds,
            evidenceLinks = evidenceLinks,
            conflicts = conflicts,
            score = score,
            explanation = explanation,
        )
    }
}

fun List<FieldHypothesis>.stableHypothesisOrder(): List<FieldHypothesis> =
    sortedWith(
        compareByDescending<FieldHypothesis> { it.score.total }
            .thenBy { it.id.value },
    )

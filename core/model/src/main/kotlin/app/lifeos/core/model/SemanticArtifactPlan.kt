package app.lifeos.core.model

enum class SemanticArtifactKind { TEXT, IMAGE, PDF, TASK }

data class SemanticArtifactClaim(
    val claimId: String,
    val evidence: Set<PhotonRevisionRef>,
    val confidenceMicros: Long,
) {
    init { require(claimId.isNotBlank()); require(confidenceMicros in 0L..1_000_000L) }
}

/** Closed semantic truth plan. Renderers may realize it, never add factual claims. */
data class SemanticArtifactPlan(
    val kind: SemanticArtifactKind,
    val claims: List<SemanticArtifactClaim>,
    val unresolvedClaimIds: Set<String> = emptySet(),
    val sourceWorldRevision: Long,
) { init { require(sourceWorldRevision >= 0) } }

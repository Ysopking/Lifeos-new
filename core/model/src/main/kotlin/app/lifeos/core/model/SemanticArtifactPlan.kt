package app.lifeos.core.model

enum class SemanticArtifactKind { TEXT, IMAGE, PDF, TASK }

data class SemanticArtifactClaim(
    val claimId: String,
    val evidence: Set<PhotonRevisionRef>,
    val confidenceMicros: Long,
    val canonicalContent: String = claimId,
) {
    init {
        require(claimId.isNotBlank())
        require(confidenceMicros in 0L..1_000_000L)
        require(canonicalContent.isNotBlank()) { "Semantic artifact claim content must not be blank" }
        require(evidence.isNotEmpty()) { "Semantic artifact claim requires revision evidence" }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "semantic-artifact-claim/v2",
        claimId,
        canonicalContent,
        confidenceMicros.toString(),
        *evidence.sortedWith(
            compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
        ).map { it.stableKey }.toTypedArray(),
    )
}

/** Closed semantic truth plan. Renderers may realize it, never add factual claims. */
data class SemanticArtifactPlan(
    val kind: SemanticArtifactKind,
    val claims: List<SemanticArtifactClaim>,
    val unresolvedClaimIds: Set<String> = emptySet(),
    val sourceWorldRevision: Long,
    val planId: String = StableCognitiveIds.fingerprint(
        "semantic-artifact-plan/v2",
        kind.name,
        sourceWorldRevision.toString(),
        *claims.sortedBy { it.claimId }.map { it.fingerprint }.toTypedArray(),
        *unresolvedClaimIds.sorted().map { "unresolved:$it" }.toTypedArray(),
    ),
    val planRevision: Long = 1L,
) {
    init {
        require(sourceWorldRevision >= 0)
        require(planId.isNotBlank())
        require(planRevision > 0L)
        require(claims.isNotEmpty()) { "Semantic artifact plan requires claims" }
        require(claims.map { it.claimId }.distinct().size == claims.size) {
            "Semantic artifact claim ids must be unique"
        }
        require(unresolvedClaimIds.all { id -> claims.any { it.claimId == id } }) {
            "Unresolved semantic artifact claim must exist in plan"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "semantic-artifact-plan-fingerprint/v2",
        planId,
        planRevision.toString(),
        sourceWorldRevision.toString(),
        *claims.sortedBy { it.claimId }.map { it.fingerprint }.toTypedArray(),
        *unresolvedClaimIds.sorted().toTypedArray(),
    )

    fun claim(id: String): SemanticArtifactClaim? = claims.firstOrNull { it.claimId == id }
    fun resolvedClaims(): List<SemanticArtifactClaim> =
        claims.filterNot { it.claimId in unresolvedClaimIds }.sortedBy { it.claimId }
}

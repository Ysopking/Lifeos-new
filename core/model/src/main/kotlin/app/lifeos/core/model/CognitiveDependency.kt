package app.lifeos.core.model

enum class CognitiveDependencyKind { DERIVED_FROM, DEPENDS_ON, SUPERSEDES, INVALIDATES, SUPPORTS, CONTRADICTS }
enum class ProjectionValidity { VALID, STALE, RECOMPUTING, INVALID }
enum class GraphActivityClass { HOT, WARM, COLD }

data class TemporalValidity(
    val validFromRevision: Long,
    val validUntilRevision: Long? = null,
    val observedAtRevision: Long,
    val supersededAtRevision: Long? = null,
) {
    init {
        require(validFromRevision > 0 && observedAtRevision > 0)
        validUntilRevision?.let { require(it >= validFromRevision) }
        supersededAtRevision?.let { require(it >= observedAtRevision) }
    }
}

data class CognitiveDependency(
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val targetPhotonId: PhotonId,
    val targetRevision: Long,
    val kind: CognitiveDependencyKind,
    val traceId: CausalTraceId,
) {
    init { require(sourceRevision > 0 && targetRevision > 0) }
    val stableFingerprint: String get() = StableCognitiveIds.fingerprint(
        sourcePhotonId.value, sourceRevision.toString(), targetPhotonId.value,
        targetRevision.toString(), kind.name, traceId.value,
    )
}

data class ConflictClaim(
    val photonId: PhotonId,
    val revision: Long,
    val evidencePhotonIds: List<PhotonId>,
    val temporalValidity: TemporalValidity,
) {
    init {
        require(revision > 0)
        require(evidencePhotonIds.distinct().size == evidencePhotonIds.size)
    }
}

data class ConflictSet(
    val conflictId: String,
    val claims: List<ConflictClaim>,
    val unresolvedReason: String,
    val reactivationKeys: Set<String>,
) {
    init {
        require(conflictId.isNotBlank())
        require(claims.size >= 2)
        require(unresolvedReason.isNotBlank())
        require(reactivationKeys.none { it.isBlank() })
    }
}

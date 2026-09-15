package app.lifeos.core.model

enum class CognitiveDependencyKind { DERIVED_FROM, DEPENDS_ON, SUPERSEDES, INVALIDATES, SUPPORTS, CONTRADICTS }
enum class ProjectionValidity { VALID, STALE, RECOMPUTING, INVALID }
enum class GraphActivityClass { HOT, WARM, COLD }

data class PhotonRevisionRef(
    val photonId: PhotonId,
    val revision: Long,
) {
    init { require(revision > 0) }
    val stableKey: String get() = "${photonId.value}@$revision"
}

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
    val couplingMicros: Long = 1_000_000L,
    val polarityMicros: Long = 1_000_000L,
    val decayMicros: Long = 1_000_000L,
) {
    init {
        require(sourceRevision > 0 && targetRevision > 0)
        require(couplingMicros in 0L..1_000_000L)
        require(polarityMicros in -1_000_000L..1_000_000L)
        require(decayMicros in 0L..1_000_000L)
    }
    val sourceRef: PhotonRevisionRef get() = PhotonRevisionRef(sourcePhotonId, sourceRevision)
    val targetRef: PhotonRevisionRef get() = PhotonRevisionRef(targetPhotonId, targetRevision)
    val stableFingerprint: String get() = StableCognitiveIds.fingerprint(
        sourcePhotonId.value, sourceRevision.toString(), targetPhotonId.value,
        targetRevision.toString(), kind.name, traceId.value,
        couplingMicros.toString(), polarityMicros.toString(), decayMicros.toString(),
    )
}

data class PropagatedCognitiveDelta(
    val target: PhotonRevisionRef,
    val magnitudeMicros: Long,
    val reason: CognitiveDependencyKind,
    val traceId: CausalTraceId,
) {
    init { require(magnitudeMicros >= 0L) }
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
    val revisionRef: PhotonRevisionRef get() = PhotonRevisionRef(photonId, revision)
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

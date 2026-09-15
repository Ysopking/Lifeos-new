package app.lifeos.host.buildstudio

import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildPermissionDelta
import app.lifeos.core.runtime.buildstudio.BuildProvenance
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioCandidate
import app.lifeos.core.runtime.buildstudio.BuildVerification
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.CandidateRuntimeSeal
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan

/** Complete host publication payload. The signature authenticates the immutable CandidateArtifact
 * and never grants promotion or activation authority. */
data class SealedCandidateArtifact(
    val artifact: CandidateArtifact,
    val seal: CandidateRuntimeSeal,
) {
    init {
        require(!artifact.activationAllowed)
        require(!seal.activationAllowed)
        require(seal.candidateArtifactId == artifact.id)
        require(seal.candidateId == artifact.candidate.id)
        require(seal.verificationId == artifact.verification.id)
        require(seal.provenanceId == artifact.provenance.id)
        require(seal.debugApkSha256 == artifact.debugApkSha256.lowercase())
    }
}

fun interface SealedCandidateArtifactPublisher {
    suspend fun publish(candidate: SealedCandidateArtifact): CandidatePublication
}

/**
 * Single host-side boundary at which complete provenance is assembled and only then signed.
 * No APK-only, Candidate-only or incomplete verification evidence can reach the sealer.
 */
class CandidateArtifactPublicationBoundary(
    private val sealer: EcdsaCandidateRuntimeSealer,
    private val publisher: SealedCandidateArtifactPublisher,
) {
    suspend fun publish(
        spec: BuildSpec,
        design: BuildDesignSpec,
        patch: SourcePatchPlan,
        candidate: BuildStudioCandidate,
        verification: BuildVerification,
        capabilityChanges: List<BuildCapabilityChange>,
        permissionDelta: BuildPermissionDelta = BuildPermissionDelta(),
        actors: List<BuildActorEvidence> = emptyList(),
    ): CandidatePublication {
        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = spec,
            design = design,
            patch = patch,
            candidate = candidate,
            verification = verification,
            capabilityChanges = capabilityChanges,
            permissionDelta = permissionDelta,
            actors = actors,
        )
        val artifact = CandidateArtifact(candidate, verification, provenance)
        val seal = sealer.seal(artifact)
        return publisher.publish(SealedCandidateArtifact(artifact, seal))
    }
}

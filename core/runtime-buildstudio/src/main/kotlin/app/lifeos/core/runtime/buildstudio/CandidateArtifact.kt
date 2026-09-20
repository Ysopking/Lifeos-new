package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds

/**
 * Immutable evidence bundle for a J01 candidate. It binds the exact candidate identity to the exact
 * verification and build provenance and never confers promotion or activation authority.
 */
data class CandidateArtifact(
    val candidate: BuildStudioCandidate,
    val verification: BuildVerification,
    val provenance: BuildProvenance,
) {
    init {
        require(!candidate.activationAllowed)
        require(!provenance.activationAllowed)
        require(verification.status == BuildVerificationStatus.VERIFIED)
        require(candidate.verificationId == verification.id) {
            "Candidate artifact verification does not match candidate"
        }
        require(provenance.buildSpecId == candidate.buildSpecId)
        require(provenance.designSpecId == candidate.designSpecId)
        require(provenance.patchPlanId == candidate.patchPlanId)
        require(provenance.branchName == candidate.branchName)
        require(provenance.branchHeadCommit.equals(candidate.branchHeadCommit, ignoreCase = true))
        require(provenance.patchPlanId == verification.evidence.patchPlanId)
        require(provenance.branchName == verification.evidence.branchName)
        require(provenance.branchHeadCommit.equals(verification.evidence.branchHeadCommit, ignoreCase = true))
        require(provenance.commandResults == verification.evidence.commandResults) {
            "Candidate artifact provenance gate results differ from verification evidence"
        }
        require(provenance.artifact == verification.evidence.artifact) {
            "Candidate artifact APK evidence differs from verification evidence"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "candidate-artifact/v1",
        candidate.id,
        verification.id,
        provenance.id,
    )

    val sourceCommit: String get() = provenance.sourceCommit
    val branchName: String get() = candidate.branchName
    val branchHeadCommit: String get() = candidate.branchHeadCommit
    val debugApkRef: String get() = provenance.artifact.debugApkRef
    val debugApkSha256: String get() = provenance.artifact.debugApkSha256

    /** Artifact creation is not promotion. Later promotion blocks must consume this evidence. */
    val activationAllowed: Boolean = false
}

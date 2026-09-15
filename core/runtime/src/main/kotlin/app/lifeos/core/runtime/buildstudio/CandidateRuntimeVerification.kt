package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.ToolPermission

/**
 * Signed host evidence presented to the runtime together with CandidateArtifact. A valid seal
 * authenticates the exact candidate bundle; it never grants activation authority by itself.
 */
data class CandidateRuntimeSeal(
    val candidateArtifactId: String,
    val candidateId: String,
    val sourceCommit: String,
    val branchHeadCommit: String,
    val verificationId: String,
    val provenanceId: String,
    val debugApkSha256: String,
    val signerId: String,
    val signature: String,
) {
    init {
        require(candidateArtifactId.isNotBlank())
        require(candidateId.isNotBlank())
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(verificationId.isNotBlank())
        require(provenanceId.isNotBlank())
        require(debugApkSha256.matches(Regex("[0-9a-f]{64}")))
        require(signerId.isNotBlank())
        require(signature.isNotBlank())
    }

    val payloadFingerprint: String = payloadFingerprint(
        candidateArtifactId = candidateArtifactId,
        candidateId = candidateId,
        sourceCommit = sourceCommit,
        branchHeadCommit = branchHeadCommit,
        verificationId = verificationId,
        provenanceId = provenanceId,
        debugApkSha256 = debugApkSha256,
        signerId = signerId,
    )

    val activationAllowed: Boolean = false

    companion object {
        fun payloadFingerprint(
            candidateArtifactId: String,
            candidateId: String,
            sourceCommit: String,
            branchHeadCommit: String,
            verificationId: String,
            provenanceId: String,
            debugApkSha256: String,
            signerId: String,
        ): String = StableFieldIds.fingerprint(
            "candidate-runtime-seal/v2",
            candidateArtifactId,
            candidateId,
            sourceCommit.lowercase(),
            branchHeadCommit.lowercase(),
            verificationId,
            provenanceId,
            debugApkSha256.lowercase(),
            signerId,
        )
    }
}

fun interface CandidateSealVerifier {
    fun verify(seal: CandidateRuntimeSeal): Boolean
}

fun interface CandidateArtifactDigestProvider {
    suspend fun sha256(debugApkRef: String): String?
}

data class RuntimeCandidatePolicy(
    val allowedCapabilities: Set<CapabilityId> = emptySet(),
    val allowedAddedPermissions: Set<ToolPermission> = emptySet(),
)

enum class RuntimeCandidateRejectionReason {
    INVALID_OR_UNTRUSTED_SEAL,
    CANDIDATE_ARTIFACT_ID_MISMATCH,
    CANDIDATE_ID_MISMATCH,
    SOURCE_COMMIT_MISMATCH,
    BRANCH_HEAD_COMMIT_MISMATCH,
    VERIFICATION_ID_MISMATCH,
    PROVENANCE_ID_MISMATCH,
    APK_DIGEST_BINDING_MISMATCH,
    BUILD_VERIFICATION_NOT_VERIFIED,
    REQUIRED_BUILD_GATE_MISSING_OR_FAILED,
    APK_UNAVAILABLE,
    APK_DIGEST_INVALID,
    APK_DIGEST_MISMATCH,
    CAPABILITY_NOT_ALLOWED,
    ADDED_PERMISSION_NOT_ALLOWED,
}

sealed interface RuntimeCandidateVerificationResult {
    data class Verified(val candidate: VerifiedRuntimeCandidate) : RuntimeCandidateVerificationResult
    data class Rejected(
        val reasons: List<RuntimeCandidateRejectionReason>,
        val details: List<String> = emptyList(),
    ) : RuntimeCandidateVerificationResult {
        init {
            require(reasons.isNotEmpty())
            require(reasons == reasons.distinct().sortedBy { it.name })
            require(details.none { it.isBlank() })
        }
    }
}

class VerifiedRuntimeCandidate private constructor(
    val artifact: CandidateArtifact,
    val seal: CandidateRuntimeSeal,
    val observedApkSha256: String,
) {
    val id: String = StableFieldIds.fingerprint(
        "verified-runtime-candidate/v1",
        artifact.id,
        seal.payloadFingerprint,
        observedApkSha256,
    )

    val candidateId: String get() = artifact.candidate.id
    val sourceCommit: String get() = artifact.sourceCommit
    val branchHeadCommit: String get() = artifact.branchHeadCommit
    val debugApkRef: String get() = artifact.debugApkRef
    val debugApkSha256: String get() = observedApkSha256
    val activationAllowed: Boolean = false

    class Verifier(
        private val sealVerifier: CandidateSealVerifier,
        private val digestProvider: CandidateArtifactDigestProvider,
    ) {
        suspend fun verify(
            artifact: CandidateArtifact,
            seal: CandidateRuntimeSeal,
            policy: RuntimeCandidatePolicy,
        ): RuntimeCandidateVerificationResult {
            val reasons = linkedSetOf<RuntimeCandidateRejectionReason>()
            val details = mutableListOf<String>()

            val trustedSeal = try {
                sealVerifier.verify(seal)
            } catch (_: Exception) {
                false
            }
            if (!trustedSeal || seal.activationAllowed) {
                reasons += RuntimeCandidateRejectionReason.INVALID_OR_UNTRUSTED_SEAL
            }
            if (seal.candidateArtifactId != artifact.id) reasons += RuntimeCandidateRejectionReason.CANDIDATE_ARTIFACT_ID_MISMATCH
            if (seal.candidateId != artifact.candidate.id) reasons += RuntimeCandidateRejectionReason.CANDIDATE_ID_MISMATCH
            if (!seal.sourceCommit.equals(artifact.sourceCommit, ignoreCase = true)) reasons += RuntimeCandidateRejectionReason.SOURCE_COMMIT_MISMATCH
            if (!seal.branchHeadCommit.equals(artifact.branchHeadCommit, ignoreCase = true)) reasons += RuntimeCandidateRejectionReason.BRANCH_HEAD_COMMIT_MISMATCH
            if (seal.verificationId != artifact.verification.id) reasons += RuntimeCandidateRejectionReason.VERIFICATION_ID_MISMATCH
            if (seal.provenanceId != artifact.provenance.id) reasons += RuntimeCandidateRejectionReason.PROVENANCE_ID_MISMATCH
            if (!seal.debugApkSha256.equals(artifact.debugApkSha256, ignoreCase = true)) {
                reasons += RuntimeCandidateRejectionReason.APK_DIGEST_BINDING_MISMATCH
            }

            if (artifact.verification.status != BuildVerificationStatus.VERIFIED) {
                reasons += RuntimeCandidateRejectionReason.BUILD_VERIFICATION_NOT_VERIFIED
            }
            val gateResults = artifact.verification.evidence.commandResults.associateBy { it.command }
            if (BuildGateCommand.entries.any { gateResults[it]?.success != true }) {
                reasons += RuntimeCandidateRejectionReason.REQUIRED_BUILD_GATE_MISSING_OR_FAILED
            }

            artifact.provenance.capabilityChanges
                .filter { it.capabilityId !in policy.allowedCapabilities }
                .sortedBy { it.capabilityId.value }
                .forEach {
                    reasons += RuntimeCandidateRejectionReason.CAPABILITY_NOT_ALLOWED
                    details += "capability-not-allowed:${it.capabilityId.value}:${it.type.name}"
                }
            artifact.provenance.permissionDelta.added
                .filter { it !in policy.allowedAddedPermissions }
                .sortedBy { it.name }
                .forEach {
                    reasons += RuntimeCandidateRejectionReason.ADDED_PERMISSION_NOT_ALLOWED
                    details += "permission-not-allowed:${it.name}"
                }

            val observedDigest = try {
                digestProvider.sha256(artifact.debugApkRef)
            } catch (_: Exception) {
                null
            }
            when {
                observedDigest == null -> reasons += RuntimeCandidateRejectionReason.APK_UNAVAILABLE
                !observedDigest.matches(Regex("[0-9a-f]{64}")) -> reasons += RuntimeCandidateRejectionReason.APK_DIGEST_INVALID
                !observedDigest.equals(artifact.debugApkSha256, ignoreCase = true) ||
                    !observedDigest.equals(seal.debugApkSha256, ignoreCase = true) ->
                    reasons += RuntimeCandidateRejectionReason.APK_DIGEST_MISMATCH
            }

            if (reasons.isNotEmpty()) {
                return RuntimeCandidateVerificationResult.Rejected(
                    reasons = reasons.sortedBy { it.name },
                    details = details.distinct().sorted(),
                )
            }
            return RuntimeCandidateVerificationResult.Verified(
                VerifiedRuntimeCandidate(
                    artifact = artifact,
                    seal = seal,
                    observedApkSha256 = requireNotNull(observedDigest).lowercase(),
                )
            )
        }
    }
}

typealias RuntimeCandidateVerifier = VerifiedRuntimeCandidate.Verifier

package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.ToolPermission

/**
 * Signed/sealed host evidence presented to the runtime together with [CandidateArtifact].
 * The seal never grants activation authority; it only authenticates the exact evidence bundle
 * that the runtime is allowed to verify further.
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
        require(signerId.isNotBlank()) { "Candidate runtime seal requires signer identity" }
        require(signature.isNotBlank()) { "Unsigned BuildStudio candidates are not valid runtime seals" }
    }

    val payloadFingerprint: String = StableFieldIds.fingerprint(
        "candidate-runtime-seal/v1",
        candidateArtifactId,
        candidateId,
        sourceCommit.lowercase(),
        branchHeadCommit.lowercase(),
        verificationId,
        provenanceId,
        debugApkSha256,
        signerId,
    )

    /** A valid seal is authentication evidence, never direct activation permission. */
    val activationAllowed: Boolean = false
}

fun interface CandidateSealVerifier {
    fun verify(seal: CandidateRuntimeSeal): Boolean
}

/** Computes the digest from the actual candidate bytes resolved by debugApkRef. */
fun interface CandidateArtifactDigestProvider {
    suspend fun sha256(debugApkRef: String): String?
}

/**
 * Explicit runtime allow-list. Empty means deny all capability additions/updates/removals and all
 * newly requested tool permissions. Nothing is implicitly trusted because BuildStudio produced it.
 */
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

/**
 * Runtime-only proof object. Its constructor is internal and this is deliberately not a data class,
 * so callers cannot use copy() to manufacture a different verified candidate.
 */
class VerifiedRuntimeCandidate internal constructor(
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
}

/**
 * Fail-closed runtime verifier. It authenticates the host seal, rebinds every identity carried by
 * the immutable CandidateArtifact, rechecks mandatory build gates, recomputes the APK digest from
 * the actual artifact, and applies explicit capability/permission policy before producing the only
 * candidate type accepted by the Hot-Swap coordinator.
 */
class RuntimeCandidateVerifier(
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
        if (!trustedSeal) reasons += RuntimeCandidateRejectionReason.INVALID_OR_UNTRUSTED_SEAL
        if (seal.activationAllowed) reasons += RuntimeCandidateRejectionReason.INVALID_OR_UNTRUSTED_SEAL

        if (seal.candidateArtifactId != artifact.id) {
            reasons += RuntimeCandidateRejectionReason.CANDIDATE_ARTIFACT_ID_MISMATCH
        }
        if (seal.candidateId != artifact.candidate.id) {
            reasons += RuntimeCandidateRejectionReason.CANDIDATE_ID_MISMATCH
        }
        if (!seal.sourceCommit.equals(artifact.sourceCommit, ignoreCase = true)) {
            reasons += RuntimeCandidateRejectionReason.SOURCE_COMMIT_MISMATCH
        }
        if (!seal.branchHeadCommit.equals(artifact.branchHeadCommit, ignoreCase = true)) {
            reasons += RuntimeCandidateRejectionReason.BRANCH_HEAD_COMMIT_MISMATCH
        }
        if (seal.verificationId != artifact.verification.id) {
            reasons += RuntimeCandidateRejectionReason.VERIFICATION_ID_MISMATCH
        }
        if (seal.provenanceId != artifact.provenance.id) {
            reasons += RuntimeCandidateRejectionReason.PROVENANCE_ID_MISMATCH
        }
        if (seal.debugApkSha256 != artifact.debugApkSha256) {
            reasons += RuntimeCandidateRejectionReason.APK_DIGEST_BINDING_MISMATCH
        }

        if (artifact.verification.status != BuildVerificationStatus.VERIFIED) {
            reasons += RuntimeCandidateRejectionReason.BUILD_VERIFICATION_NOT_VERIFIED
        }
        val results = artifact.verification.evidence.commandResults.associateBy { it.command }
        if (BuildGateCommand.entries.any { command -> results[command]?.success != true }) {
            reasons += RuntimeCandidateRejectionReason.REQUIRED_BUILD_GATE_MISSING_OR_FAILED
        }

        artifact.provenance.capabilityChanges
            .filter { it.capabilityId !in policy.allowedCapabilities }
            .sortedBy { it.capabilityId.value }
            .forEach { change ->
                reasons += RuntimeCandidateRejectionReason.CAPABILITY_NOT_ALLOWED
                details += "capability-not-allowed:${change.capabilityId.value}:${change.type.name}"
            }
        artifact.provenance.permissionDelta.added
            .filter { it !in policy.allowedAddedPermissions }
            .sortedBy { it.name }
            .forEach { permission ->
                reasons += RuntimeCandidateRejectionReason.ADDED_PERMISSION_NOT_ALLOWED
                details += "permission-not-allowed:${permission.name}"
            }

        val observedDigest = try {
            digestProvider.sha256(artifact.debugApkRef)
        } catch (_: Exception) {
            null
        }
        when {
            observedDigest == null -> reasons += RuntimeCandidateRejectionReason.APK_UNAVAILABLE
            !observedDigest.matches(Regex("[0-9a-f]{64}")) -> {
                reasons += RuntimeCandidateRejectionReason.APK_DIGEST_INVALID
            }
            observedDigest != artifact.debugApkSha256 || observedDigest != seal.debugApkSha256 -> {
                reasons += RuntimeCandidateRejectionReason.APK_DIGEST_MISMATCH
            }
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
                observedApkSha256 = requireNotNull(observedDigest),
            )
        )
    }
}

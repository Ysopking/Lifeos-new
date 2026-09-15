package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.VerifiedRuntimeCandidate

/**
 * Immutable J03 evidence that binds an eligible generated-tool trial to the exact runtime-verified
 * BuildStudio candidate that is being promoted. Creating this evidence does not activate anything.
 */
class GeneratedToolPromotionEvidence private constructor(
    override val toolId: String,
    val candidateArtifactId: String,
    val candidateId: String,
    val verificationId: String,
    val provenanceId: String,
    override val recordFingerprint: String,
    override val trialEvidenceId: String,
    override val promotionPolicyFingerprint: String,
    val apkSha256: String,
    val capabilityChangeFingerprint: String,
    val permissionDeltaFingerprint: String,
    val reviewerEvidenceFingerprints: List<String>,
    val promotionActorEvidenceFingerprints: List<String>,
) : GeneratedToolActivationEvidence {
    init {
        require(toolId.isNotBlank()) { "Promotion evidence tool id must not be blank" }
        require(candidateArtifactId.isNotBlank()) { "Promotion evidence requires candidate artifact" }
        require(candidateId.isNotBlank() && verificationId.isNotBlank() && provenanceId.isNotBlank())
        require(recordFingerprint.isNotBlank() && trialEvidenceId.isNotBlank())
        require(promotionPolicyFingerprint.isNotBlank())
        require(apkSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Promotion evidence requires a SHA-256 APK hash"
        }
        require(capabilityChangeFingerprint.isNotBlank())
        require(permissionDeltaFingerprint.isNotBlank())
        require(reviewerEvidenceFingerprints.isNotEmpty()) {
            "Promotion evidence requires reviewer approval"
        }
        require(promotionActorEvidenceFingerprints.isNotEmpty()) {
            "Promotion evidence requires a distinct promotion actor"
        }
    }

    override val id: String = StableFieldIds.fingerprint(
        "generated-tool-promotion-evidence/v1",
        toolId,
        candidateArtifactId,
        candidateId,
        verificationId,
        provenanceId,
        recordFingerprint,
        trialEvidenceId,
        promotionPolicyFingerprint,
        apkSha256,
        capabilityChangeFingerprint,
        permissionDeltaFingerprint,
        *reviewerEvidenceFingerprints.sorted().map { "reviewer:$it" }.toTypedArray(),
        *promotionActorEvidenceFingerprints.sorted().map { "promotion:$it" }.toTypedArray(),
    )

    /** Evidence is not authority. Only the lifecycle's guarded promotion operation can activate. */
    override val activationAllowed: Boolean = false

    internal fun matchesRecord(record: GeneratedToolRecord): Boolean =
        toolId == record.manifest.toolId && recordFingerprint == record.promotionRecordFingerprint()

    internal fun matches(
        record: GeneratedToolRecord,
        trialEvidence: GeneratedToolTrialEvidence,
        policy: GeneratedToolPromotionPolicy,
    ): Boolean =
        matchesRecord(record) &&
            trialEvidenceId == trialEvidence.id &&
            promotionPolicyFingerprint == policy.fingerprint()

    companion object {
        internal fun create(
            candidate: VerifiedRuntimeCandidate,
            record: GeneratedToolRecord,
            trialEvidence: GeneratedToolTrialEvidence,
            policy: GeneratedToolPromotionPolicy,
        ): GeneratedToolPromotionEvidence {
            require(record.state == GeneratedToolState.TRIAL) {
                "Promotion evidence may only bind a TRIAL tool"
            }
            require(trialEvidence.toolId == record.manifest.toolId) {
                "Trial evidence belongs to another generated tool"
            }
            require(!candidate.activationAllowed) {
                "Runtime-verified candidate must remain non-activating evidence"
            }
            require(!candidate.seal.activationAllowed) {
                "Runtime candidate seal must remain non-activating evidence"
            }

            val artifact = candidate.artifact
            require(!artifact.activationAllowed) {
                "CandidateArtifact must remain non-activating evidence"
            }
            require(candidate.debugApkSha256.equals(artifact.debugApkSha256, ignoreCase = true)) {
                "Runtime-verified APK digest no longer matches CandidateArtifact"
            }

            val provenance = artifact.provenance
            val manifest = record.manifest
            require(provenance.sourceRequirement.capabilityId == manifest.sourceCapability) {
                "Candidate capability does not match generated tool capability"
            }

            val capabilityChange = requireNotNull(
                matchingCapabilityChange(provenance.capabilityChanges, manifest)
            ) {
                "Candidate capability delta does not exactly match generated tool contract"
            }

            require(provenance.permissionDelta.removed.isEmpty()) {
                "Generated tool promotion cannot carry removed permissions"
            }
            require(provenance.permissionDelta.added == manifest.permissions) {
                "Candidate permission delta does not match generated tool permissions"
            }

            val buildHash = requireNotNull(manifest.buildHash) {
                "Generated tool requires a verified build hash before promotion"
            }
            require(buildHash.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Generated tool build hash must be SHA-256 for BuildStudio promotion"
            }
            require(buildHash.equals(candidate.debugApkSha256, ignoreCase = true)) {
                "Generated tool build hash does not match runtime-verified CandidateArtifact APK"
            }

            val reviewerApprovals = provenance.actors.filter {
                it.role == BuildActorRole.REVIEWER && it.action == BuildActorAction.APPROVED
            }
            require(reviewerApprovals.isNotEmpty()) {
                "Candidate requires at least one reviewer approval"
            }
            require(provenance.actors.none {
                it.role == BuildActorRole.REVIEWER && it.action == BuildActorAction.REJECTED
            }) {
                "Candidate contains reviewer rejection evidence"
            }

            val promotionActors = provenance.actors.filter {
                it.role == BuildActorRole.PROMOTION_ACTOR && it.action == BuildActorAction.PROMOTED
            }
            require(promotionActors.isNotEmpty()) {
                "Candidate requires promotion actor evidence"
            }
            require(provenance.actors.none {
                it.role == BuildActorRole.PROMOTION_ACTOR && it.action == BuildActorAction.ROLLED_BACK
            }) {
                "Candidate contains rollback evidence"
            }

            val reviewerIds = reviewerApprovals.map { it.actorId }.toSet()
            val promoterIds = promotionActors.map { it.actorId }.toSet()
            require((reviewerIds intersect promoterIds).isEmpty()) {
                "Reviewer and promotion actor must be separated"
            }

            val latestApproval = requireNotNull(reviewerApprovals.maxByOrNull { it.occurredAt }).occurredAt
            val earliestPromotion = requireNotNull(promotionActors.minByOrNull { it.occurredAt }).occurredAt
            require(!earliestPromotion.isBefore(latestApproval)) {
                "Promotion actor evidence predates reviewer approval"
            }

            return GeneratedToolPromotionEvidence(
                toolId = manifest.toolId,
                candidateArtifactId = artifact.id,
                candidateId = artifact.candidate.id,
                verificationId = artifact.verification.id,
                provenanceId = artifact.provenance.id,
                recordFingerprint = record.promotionRecordFingerprint(),
                trialEvidenceId = trialEvidence.id,
                promotionPolicyFingerprint = policy.fingerprint(),
                apkSha256 = candidate.debugApkSha256.lowercase(),
                capabilityChangeFingerprint = capabilityChange.fingerprint(),
                permissionDeltaFingerprint = provenance.permissionDelta.fingerprint(),
                reviewerEvidenceFingerprints = reviewerApprovals.map { it.fingerprint() }.sorted(),
                promotionActorEvidenceFingerprints = promotionActors.map { it.fingerprint() }.sorted(),
            )
        }

        private fun matchingCapabilityChange(
            changes: List<BuildCapabilityChange>,
            manifest: GeneratedToolManifest,
        ): BuildCapabilityChange? = changes.singleOrNull { change ->
            change.capabilityId == manifest.sourceCapability &&
                change.type != BuildCapabilityChangeType.REMOVED &&
                change.requiredInputs == manifest.requiredInputs &&
                change.outputs == manifest.requiredOutputs
        }
    }
}

internal fun GeneratedToolRecord.promotionRecordFingerprint(): String = StableFieldIds.fingerprint(
    "generated-tool-promotion-record/v1",
    manifest.toolId,
    manifest.sourceCapability.value,
    manifest.sourceHash,
    manifest.buildHash.orEmpty(),
    manifest.generatedAt.toString(),
    state.name,
    verificationConfidence.toString(),
    promotionEvidenceId.orEmpty(),
    *manifest.permissions.sortedBy { it.name }.map { "permission:${it.name}" }.toTypedArray(),
    *manifest.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *manifest.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)

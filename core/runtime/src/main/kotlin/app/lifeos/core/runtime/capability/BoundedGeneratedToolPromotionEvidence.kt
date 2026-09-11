package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.evolution.NovelCapabilityAdmissionEvidence
import app.lifeos.core.runtime.evolution.NovelCapabilityAdmissionSubject
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReadinessDecision
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReadinessEvidence
import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionSealEvidence
import app.lifeos.core.runtime.evolution.evolutionFingerprint

/**
 * Immutable activation evidence for a bounded generated tool that fills a genuinely missing
 * capability. It carries no activation authority; only the guarded lifecycle mutation may consume
 * it after the capability claim has closed the final race window.
 */
class BoundedGeneratedToolPromotionEvidence private constructor(
    override val toolId: String,
    val artifactId: String,
    override val recordFingerprint: String,
    override val trialEvidenceId: String,
    override val promotionPolicyFingerprint: String,
    val novelAdmissionEvidenceId: String,
    val canaryReadinessEvidenceId: String,
    val promotionSealId: String,
    val reviewerEvidenceFingerprints: List<String>,
    val activationActorEvidenceFingerprints: List<String>,
) : GeneratedToolActivationEvidence {
    init {
        require(toolId.isNotBlank())
        require(artifactId.isNotBlank())
        require(recordFingerprint.isNotBlank())
        require(trialEvidenceId.isNotBlank())
        require(promotionPolicyFingerprint.isNotBlank())
        require(novelAdmissionEvidenceId.isNotBlank())
        require(canaryReadinessEvidenceId.isNotBlank())
        require(promotionSealId.isNotBlank())
        require(reviewerEvidenceFingerprints.isNotEmpty())
        require(activationActorEvidenceFingerprints.isNotEmpty())
    }

    override val id: String = boundedGeneratedToolPromotionEvidenceId(
        toolId = toolId,
        artifactId = artifactId,
        recordFingerprint = recordFingerprint,
        trialEvidenceId = trialEvidenceId,
        promotionPolicyFingerprint = promotionPolicyFingerprint,
        novelAdmissionEvidenceId = novelAdmissionEvidenceId,
        canaryReadinessEvidenceId = canaryReadinessEvidenceId,
        promotionSealId = promotionSealId,
        reviewerEvidenceFingerprints = reviewerEvidenceFingerprints,
        activationActorEvidenceFingerprints = activationActorEvidenceFingerprints,
    )

    override val activationAllowed: Boolean = false

    companion object {
        internal fun create(
            record: GeneratedToolRecord,
            artifact: GeneratedToolArtifact,
            trialEvidence: GeneratedToolTrialEvidence,
            promotionPolicy: GeneratedToolPromotionPolicy,
            subject: NovelCapabilityAdmissionSubject,
            admission: NovelCapabilityAdmissionEvidence,
            readiness: NovelCapabilityCanaryReadinessEvidence,
            seal: NovelCapabilityPromotionSealEvidence,
            actors: List<BuildActorEvidence>,
        ): BoundedGeneratedToolPromotionEvidence {
            require(record.state == GeneratedToolState.TRIAL) {
                "Bounded promotion evidence may only bind a TRIAL tool"
            }
            require(trialEvidence.toolId == record.manifest.toolId)
            require(!artifact.activationAllowed && artifact.matches(record)) {
                "Bounded promotion requires the exact immutable generated-tool artifact"
            }
            require(GeneratedToolArtifact.isBoundedSourceHash(record.manifest.sourceHash)) {
                "Bounded promotion rejects non-bounded generated-tool source hashes"
            }
            require(record.manifest.permissions.isEmpty()) {
                "Novel bounded promotion currently permits only zero-permission generated tools"
            }
            requirePromotionEligible(record, trialEvidence.stats, promotionPolicy)

            require(!subject.activationAllowed)
            require(subject.toolId == record.manifest.toolId)
            require(subject.artifactId == artifact.id)
            require(subject.candidateRecordFingerprint == record.evolutionFingerprint())
            require(subject.sourceHash == artifact.sourceHash && subject.buildHash == artifact.buildHash)

            require(!admission.activationAllowed)
            require(admission.subjectId == subject.id)
            require(admission.toolId == subject.toolId)
            require(admission.artifactId == artifact.id)
            require(admission.candidateRecordFingerprint == subject.candidateRecordFingerprint)

            require(!readiness.activationAllowed)
            require(readiness.decision == NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW) {
                "Bounded promotion requires READY_FOR_REVIEW novel canary evidence"
            }
            require(readiness.admissionEvidenceId == admission.id)
            require(readiness.subjectId == subject.id)
            require(readiness.toolId == record.manifest.toolId)
            require(readiness.candidateRecordFingerprint == record.evolutionFingerprint())
            require(readiness.artifactId == artifact.id)
            require(readiness.reservedInvocations == readiness.completedOutcomes)
            require(readiness.completedOutcomes > 0)
            require(readiness.successes == readiness.completedOutcomes)
            require(readiness.expectedOutputs == readiness.completedOutcomes)
            require(readiness.safetyViolations == 0)
            require(readiness.killSwitchEvidenceId == null)

            require(!seal.activationAllowed)
            require(seal.admissionEvidenceId == admission.id)
            require(seal.subjectId == subject.id)
            require(seal.toolId == record.manifest.toolId)
            require(seal.candidateRecordFingerprint == record.evolutionFingerprint())
            require(seal.artifactId == artifact.id)
            require(seal.readinessEvidenceId == readiness.id)
            require(seal.expectedReservedInvocations == readiness.reservedInvocations)

            val reviewers = actors.filter {
                it.role == BuildActorRole.REVIEWER && it.action == BuildActorAction.APPROVED
            }
            require(reviewers.isNotEmpty()) { "Bounded promotion requires reviewer approval" }
            require(actors.none {
                it.role == BuildActorRole.REVIEWER && it.action == BuildActorAction.REJECTED
            }) { "Bounded promotion contains reviewer rejection evidence" }
            require(reviewers.all { it.evidenceRef == readiness.id }) {
                "Bounded reviewer evidence must reference exact canary readiness"
            }

            val activators = actors.filter {
                it.role == BuildActorRole.PROMOTION_ACTOR && it.action == BuildActorAction.PROMOTED
            }
            require(activators.isNotEmpty()) { "Bounded promotion requires activation actor evidence" }
            require(actors.none {
                it.role == BuildActorRole.PROMOTION_ACTOR && it.action == BuildActorAction.ROLLED_BACK
            }) { "Bounded promotion contains rollback actor evidence" }
            require(activators.all { it.evidenceRef == seal.id }) {
                "Bounded activation actor evidence must reference exact promotion seal"
            }

            val reviewerIds = reviewers.map { it.actorId }.toSet()
            val activatorIds = activators.map { it.actorId }.toSet()
            require((reviewerIds intersect activatorIds).isEmpty()) {
                "Bounded reviewer and activation actor identities must be separated"
            }
            val earliestReview = reviewers.minOf { it.occurredAt }
            require(!earliestReview.isBefore(seal.sealedAt)) {
                "Bounded review cannot predate the sealed canary state"
            }
            val latestReview = reviewers.maxOf { it.occurredAt }
            val earliestActivation = activators.minOf { it.occurredAt }
            require(!earliestActivation.isBefore(latestReview)) {
                "Bounded activation actor evidence cannot predate reviewer approval"
            }

            return BoundedGeneratedToolPromotionEvidence(
                toolId = record.manifest.toolId,
                artifactId = artifact.id,
                recordFingerprint = record.promotionRecordFingerprint(),
                trialEvidenceId = trialEvidence.id,
                promotionPolicyFingerprint = promotionPolicy.fingerprint(),
                novelAdmissionEvidenceId = admission.id,
                canaryReadinessEvidenceId = readiness.id,
                promotionSealId = seal.id,
                reviewerEvidenceFingerprints = reviewers.map { it.fingerprint() }.sorted(),
                activationActorEvidenceFingerprints = activators.map { it.fingerprint() }.sorted(),
            )
        }

        private fun requirePromotionEligible(
            record: GeneratedToolRecord,
            stats: GeneratedToolTrialStats,
            policy: GeneratedToolPromotionPolicy,
        ) {
            require(stats.trials >= policy.minimumTrials)
            require(stats.successRate >= policy.minimumSuccessRate)
            require(stats.expectedOutputRate >= policy.minimumExpectedOutputRate)
            require(stats.safetyViolations == 0)
            require(record.verificationConfidence >= policy.minimumVerificationConfidence)
        }
    }
}

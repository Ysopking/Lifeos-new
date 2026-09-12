package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolPromotionPolicy
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import java.time.Instant

data class BoundedNovelPromotionResult(
    val activeRecord: GeneratedToolRecord,
    val evidence: BoundedGeneratedToolPromotionEvidence,
    val readiness: NovelCapabilityCanaryReadinessEvidence,
    val seal: NovelCapabilityPromotionSealEvidence,
)

/**
 * Sole V1.4 bridge from a sealed novel canary to ACTIVE. Every input is replayed against live state;
 * the generated tool remains TRIAL until durable bounded receipt persistence succeeds.
 */
class BoundedNovelPromotionCoordinator(
    private val admissionGate: NovelCapabilityAdmissionGate,
    private val readinessGate: NovelCapabilityCanaryReadinessGate,
    private val promotionStore: NovelCapabilityPromotionStore,
    private val capabilities: CapabilityRegistry,
    private val tools: GeneratedToolRegistry,
    private val artifacts: GeneratedToolArtifactRepository,
    private val trials: GeneratedToolTrialLedger,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun promote(
        subject: NovelCapabilityAdmissionSubject,
        admission: NovelCapabilityAdmissionEvidence,
        actors: List<BuildActorEvidence>,
    ): BoundedNovelPromotionResult {
        require(!subject.activationAllowed && !admission.activationAllowed)
        admissionGate.validate(admission, subject)

        val readiness = readinessGate.evaluate(subject, admission)
        require(readiness.decision == NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW) {
            "Novel capability is not ready for bounded promotion: ${readiness.decision}:${readiness.reasons.joinToString()}"
        }
        require(!readiness.activationAllowed)

        val current = requireNotNull(tools.get(subject.toolId)) {
            "Unknown bounded promotion candidate ${subject.toolId}"
        }
        require(current.state == GeneratedToolState.TRIAL) {
            "Bounded promotion candidate must remain TRIAL"
        }
        require(current.evolutionFingerprint() == subject.candidateRecordFingerprint) {
            "Bounded promotion candidate changed after novel admission"
        }
        val artifact = requireNotNull(artifacts.load(subject.toolId)) {
            "Bounded promotion artifact is missing"
        }
        require(artifact.id == subject.artifactId && artifact.matches(current)) {
            "Bounded promotion artifact changed after novel admission"
        }
        val exactTrials = trials.evidence(subject.toolId)

        val seal = promotionStore.sealNovelForPromotion(
            admissionEvidenceId = admission.id,
            subjectId = subject.id,
            toolId = subject.toolId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            artifactId = subject.artifactId,
            readinessEvidenceId = readiness.id,
            expectedReservedInvocations = readiness.reservedInvocations,
            sealedAt = now(),
        )
        require(!seal.activationAllowed)
        admissionGate.validate(admission, subject)

        val evidence = BoundedGeneratedToolPromotionEvidence.create(
            record = current,
            artifact = artifact,
            trialEvidence = exactTrials,
            promotionPolicy = promotionPolicy,
            subject = subject,
            admission = admission,
            readiness = readiness,
            seal = seal,
            actors = actors,
        )
        val activationActors = actors.filter {
            it.role == BuildActorRole.PROMOTION_ACTOR && it.action == BuildActorAction.PROMOTED
        }
        require(activationActors.size == 1) {
            "Bounded promotion requires exactly one activation actor for the audit mutation"
        }
        val actor = activationActors.single()

        val claim = capabilities.claimMissingForGenerated(
            capabilityId = subject.requirement.capabilityId,
            toolId = subject.toolId,
            activationEvidenceId = evidence.id,
        )
        return try {
            val active = lifecycle.promote(
                toolId = subject.toolId,
                evidence = evidence,
                activationEvidenceRef = seal.id,
                actorId = actor.actorId,
                novelClaim = claim,
            )
            val result = BoundedNovelPromotionResult(active, evidence, readiness, seal)
            NovelPromotionRuntimeEventRegistry.publishActivated(result)
            result
        } finally {
            capabilities.releaseNovelActivationClaim(claim)
        }
    }
}

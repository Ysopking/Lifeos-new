package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.buildstudio.VerifiedRuntimeCandidate
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Atomic marker that closes one J05 canary to new candidate reservations before final promotion
 * review. It is evidence only and never grants activation authority.
 */
data class EvolutionCanaryPromotionSealEvidence(
    val adoptionEvidenceId: String,
    val candidateToolId: String,
    val readinessEvidenceId: String,
    val expectedReservedInvocations: Int,
    val sealedAt: Instant,
) {
    init {
        require(adoptionEvidenceId.isNotBlank()) { "Promotion seal requires adoption evidence" }
        require(candidateToolId.isNotBlank()) { "Promotion seal requires candidate tool" }
        require(readinessEvidenceId.isNotBlank()) { "Promotion seal requires readiness evidence" }
        require(expectedReservedInvocations >= 0) { "Promotion seal reservation count must not be negative" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-promotion-seal/v1",
        adoptionEvidenceId,
        candidateToolId,
        readinessEvidenceId,
        expectedReservedInvocations.toString(),
        sealedAt.toString(),
    )

    val activationAllowed: Boolean = false
}

data class EvolutionPromotionReviewRequest(
    val actorId: String,
    val evidenceRef: String,
    val rationale: String,
    val occurredAt: Instant = Instant.now(),
) {
    init {
        require(actorId.isNotBlank()) { "Promotion review actor must not be blank" }
        require(evidenceRef.isNotBlank()) { "Promotion review requires evidence reference" }
        require(rationale.isNotBlank()) { "Promotion review requires rationale" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-promotion-review-request/v1",
        actorId,
        evidenceRef,
        rationale,
        occurredAt.toString(),
    )
}

/**
 * Immutable J08 evidence binding the exact J07 readiness snapshot, frozen canary seal and exact J03
 * runtime-verified CandidateArtifact-backed promotion evidence to one explicit final promotion actor.
 */
class EvolutionPromotionReviewEvidence private constructor(
    val subjectId: String,
    val adoptionEvidenceId: String,
    val readinessEvidenceId: String,
    val promotionSealEvidenceId: String,
    val candidateToolId: String,
    val candidateRecordFingerprint: String,
    val baselineDescriptorFingerprint: String,
    val candidateArtifactId: String,
    val j03PromotionEvidenceId: String,
    val j03TrialEvidenceId: String,
    val reviewRequestId: String,
    val actorId: String,
) {
    init {
        require(subjectId.isNotBlank())
        require(adoptionEvidenceId.isNotBlank())
        require(readinessEvidenceId.isNotBlank())
        require(promotionSealEvidenceId.isNotBlank())
        require(candidateToolId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(baselineDescriptorFingerprint.isNotBlank())
        require(candidateArtifactId.isNotBlank())
        require(j03PromotionEvidenceId.isNotBlank())
        require(j03TrialEvidenceId.isNotBlank())
        require(reviewRequestId.isNotBlank())
        require(actorId.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-promotion-review-evidence/v1",
        subjectId,
        adoptionEvidenceId,
        readinessEvidenceId,
        promotionSealEvidenceId,
        candidateToolId,
        candidateRecordFingerprint,
        baselineDescriptorFingerprint,
        candidateArtifactId,
        j03PromotionEvidenceId,
        j03TrialEvidenceId,
        reviewRequestId,
        actorId,
    )

    /** Review is explicit evidence, not authority by itself. */
    val activationAllowed: Boolean = false

    companion object {
        internal fun create(
            subject: EvolutionSubject,
            readiness: EvolutionCanaryReadinessEvidence,
            seal: EvolutionCanaryPromotionSealEvidence,
            candidate: VerifiedRuntimeCandidate,
            j03: GeneratedToolPromotionEvidence,
            request: EvolutionPromotionReviewRequest,
        ): EvolutionPromotionReviewEvidence = EvolutionPromotionReviewEvidence(
            subjectId = subject.id,
            adoptionEvidenceId = readiness.adoptionEvidenceId,
            readinessEvidenceId = readiness.id,
            promotionSealEvidenceId = seal.id,
            candidateToolId = subject.candidateToolId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            baselineDescriptorFingerprint = subject.baselineDescriptorFingerprint,
            candidateArtifactId = candidate.artifact.id,
            j03PromotionEvidenceId = j03.id,
            j03TrialEvidenceId = j03.trialEvidenceId,
            reviewRequestId = request.id,
            actorId = request.actorId,
        )
    }
}

data class EvolutionPromotionResult(
    val record: GeneratedToolRecord,
    val readiness: EvolutionCanaryReadinessEvidence,
    val reviewEvidence: EvolutionPromotionReviewEvidence,
    val j03PromotionEvidence: GeneratedToolPromotionEvidence,
) {
    init {
        require(record.state == GeneratedToolState.ACTIVE) { "J08 promotion result must be ACTIVE" }
        require(record.manifest.toolId == reviewEvidence.candidateToolId)
        require(record.promotionEvidenceId == j03PromotionEvidence.id)
        require(reviewEvidence.j03PromotionEvidenceId == j03PromotionEvidence.id)
    }
}

/**
 * J08 is the public activation bridge for evolved generated tools. It accepts only a candidate that
 * already passed the BuildStudio runtime trust gate, seals routing, replays J07 readiness from the
 * live stores, rebuilds J03 evidence from the live trial ledger, and only then invokes the internal
 * lifecycle activation primitive.
 */
class EvolutionPromotionBridge(
    private val runtimeStore: EvolutionPromotionRuntimeStore,
    private val outcomeStore: EvolutionCanaryOutcomeStore,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
) {
    private val readinessGate = EvolutionCanaryReadinessGate(runtimeStore, outcomeStore)
    private val mutex = Mutex()

    suspend fun prepareReview(
        evidence: EvolutionCanaryEvidenceBundle,
        candidate: VerifiedRuntimeCandidate,
        request: EvolutionPromotionReviewRequest,
    ): EvolutionPromotionReviewEvidence = mutex.withLock {
        val beforeSeal = prepareSnapshot(evidence, candidate, request)

        val seal = runtimeStore.sealForPromotion(
            adoptionEvidenceId = evidence.adoptionEvidence.id,
            candidateToolId = evidence.subject.candidateToolId,
            readinessEvidenceId = beforeSeal.readiness.id,
            expectedReservedInvocations = beforeSeal.readiness.reservedInvocations,
            sealedAt = Instant.now(),
        )
        require(!seal.activationAllowed)

        // Replay after the atomic seal so a reservation racing the first readiness read cannot pass.
        val afterSeal = prepareSnapshot(evidence, candidate, request)
        require(afterSeal.readiness.id == beforeSeal.readiness.id) {
            "Canary readiness changed while promotion review was being sealed"
        }
        require(afterSeal.j03.id == beforeSeal.j03.id) {
            "J03 trial evidence changed while promotion review was being sealed"
        }

        EvolutionPromotionReviewEvidence.create(
            subject = evidence.subject,
            readiness = afterSeal.readiness,
            seal = seal,
            candidate = candidate,
            j03 = afterSeal.j03,
            request = request,
        )
    }

    suspend fun promote(
        evidence: EvolutionCanaryEvidenceBundle,
        candidate: VerifiedRuntimeCandidate,
        request: EvolutionPromotionReviewRequest,
        review: EvolutionPromotionReviewEvidence,
    ): EvolutionPromotionResult = mutex.withLock {
        val seal = requireNotNull(runtimeStore.promotionSeal(evidence.adoptionEvidence.id)) {
            "J08 promotion requires an existing canary promotion seal"
        }
        require(seal.id == review.promotionSealEvidenceId) {
            "Promotion review belongs to another or stale canary seal"
        }
        require(runtimeStore.killSwitch(evidence.adoptionEvidence.id) == null) {
            "Stopped canary cannot be promoted"
        }

        val fresh = prepareSnapshot(evidence, candidate, request)
        require(fresh.readiness.id == seal.readinessEvidenceId) {
            "Canary readiness changed after promotion seal"
        }
        require(fresh.readiness.reservedInvocations == seal.expectedReservedInvocations) {
            "Canary reservation count changed after promotion seal"
        }

        val expectedReview = EvolutionPromotionReviewEvidence.create(
            subject = evidence.subject,
            readiness = fresh.readiness,
            seal = seal,
            candidate = candidate,
            j03 = fresh.j03,
            request = request,
        )
        require(expectedReview.id == review.id) {
            "J08 promotion review is stale, forged, or belongs to another candidate"
        }
        require(!review.activationAllowed && !fresh.readiness.activationAllowed)

        // Final stop check immediately before the internal activation primitive.
        require(runtimeStore.killSwitch(evidence.adoptionEvidence.id) == null) {
            "Canary was stopped during final promotion review"
        }

        val active = lifecycle.promote(
            toolId = evidence.subject.candidateToolId,
            evidence = fresh.j03,
            activationEvidenceRef = review.id,
            actorId = request.actorId,
        )
        EvolutionPromotionResult(
            record = active,
            readiness = fresh.readiness,
            reviewEvidence = review,
            j03PromotionEvidence = fresh.j03,
        )
    }

    private suspend fun prepareSnapshot(
        evidence: EvolutionCanaryEvidenceBundle,
        candidate: VerifiedRuntimeCandidate,
        request: EvolutionPromotionReviewRequest,
    ): PreparedSnapshot {
        val artifact = candidate.artifact
        require(artifact.id == evidence.subject.candidateArtifactId) {
            "J08 runtime-verified CandidateArtifact differs from independently evaluated candidate"
        }
        require(request.actorId != evidence.subject.candidateToolId) {
            "Generated candidate cannot approve its own promotion"
        }
        require(request.actorId != evidence.evaluationReport.evaluatorId) {
            "Evaluation and final promotion actors must be separated"
        }
        require(request.actorId != evidence.dataset.curatorId) {
            "Holdout curator and final promotion actors must be separated"
        }
        require(request.actorId != evidence.adoptionRequest.actorId) {
            "Canary adoption and final promotion actors must be separated"
        }
        require(!request.occurredAt.isBefore(evidence.adoptionRequest.occurredAt)) {
            "Final promotion review cannot predate canary adoption"
        }

        val matchingPromotionActors = artifact.provenance.actors.filter {
            it.role == BuildActorRole.PROMOTION_ACTOR &&
                it.action == BuildActorAction.PROMOTED &&
                it.actorId == request.actorId
        }
        require(matchingPromotionActors.isNotEmpty()) {
            "Final promotion actor must match CandidateArtifact promotion actor evidence"
        }

        val readiness = readinessGate.evaluate(evidence)
        require(readiness.decision == EvolutionCanaryReadinessDecision.READY_FOR_PROMOTION_REVIEW) {
            "J07 canary is not ready for final promotion review: ${readiness.decision} ${readiness.reasons}"
        }
        require(readiness.adoptionEvidenceId == evidence.adoptionEvidence.id)
        require(readiness.candidateToolId == evidence.subject.candidateToolId)
        require(readiness.candidateRecordFingerprint == evidence.subject.candidateRecordFingerprint)
        require(readiness.baselineDescriptorFingerprint == evidence.subject.baselineDescriptorFingerprint)
        require(readiness.killSwitchEvidenceId == null)

        val j03 = lifecycle.preparePromotionEvidence(evidence.subject.candidateToolId, candidate)
        require(j03.toolId == evidence.subject.candidateToolId)
        require(j03.candidateArtifactId == artifact.id)
        require(!j03.activationAllowed)

        return PreparedSnapshot(readiness, j03)
    }

    private data class PreparedSnapshot(
        val readiness: EvolutionCanaryReadinessEvidence,
        val j03: GeneratedToolPromotionEvidence,
    )
}

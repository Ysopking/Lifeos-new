package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ToolPermission
import java.time.Instant

enum class EvolutionAdoptionDecision {
    APPROVED_FOR_CANARY,
    REJECTED,
}

/**
 * Bounded future routing scope. J05 only records this scope; it does not route or activate anything.
 */
data class EvolutionCanaryScope(
    val assignmentPermille: Int,
    val maxInvocations: Int,
    val maxDurationSeconds: Long,
    val allowedTaskTags: Set<String>,
    val productiveEffectsAllowed: Boolean = false,
) {
    init {
        require(assignmentPermille in 1..1000) { "Canary assignment must be 1..1000 permille" }
        require(maxInvocations > 0) { "Canary invocation budget must be positive" }
        require(maxDurationSeconds > 0) { "Canary duration must be positive" }
        require(allowedTaskTags.isNotEmpty()) { "Canary scope requires explicit task tags" }
        require(allowedTaskTags.none { it.isBlank() }) { "Canary task tags must not be blank" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-canary-scope/v1",
        assignmentPermille.toString(),
        maxInvocations.toString(),
        maxDurationSeconds.toString(),
        productiveEffectsAllowed.toString(),
        *allowedTaskTags.sorted().map { "tag:$it" }.toTypedArray(),
    )
}

data class EvolutionAdoptionRequest(
    val actorId: String,
    val evidenceRef: String,
    val rationale: String,
    val scope: EvolutionCanaryScope,
    val occurredAt: Instant,
) {
    init {
        require(actorId.isNotBlank()) { "Adoption actor must not be blank" }
        require(evidenceRef.isNotBlank()) { "Adoption request requires evidence reference" }
        require(rationale.isNotBlank()) { "Adoption request requires rationale" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-adoption-request/v1",
        actorId,
        evidenceRef,
        rationale,
        scope.id,
        occurredAt.toString(),
    )
}

data class EvolutionAdoptionPolicy(
    val trustedEvaluationPolicy: EvolutionEvaluationPolicy = EvolutionEvaluationPolicy(),
    val maximumInitialAssignmentPermille: Int = 100,
    val maximumInitialInvocations: Int = 100,
    val maximumInitialDurationSeconds: Long = 24 * 60 * 60,
    val maximumTaskTags: Int = 8,
    val forbiddenCanaryPermissions: Set<ToolPermission> = DEFAULT_FORBIDDEN_CANARY_PERMISSIONS,
) {
    init {
        require(maximumInitialAssignmentPermille in 1..1000)
        require(maximumInitialInvocations > 0)
        require(maximumInitialDurationSeconds > 0)
        require(maximumTaskTags > 0)
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-adoption-policy/v1",
        trustedEvaluationPolicy.id,
        maximumInitialAssignmentPermille.toString(),
        maximumInitialInvocations.toString(),
        maximumInitialDurationSeconds.toString(),
        maximumTaskTags.toString(),
        *forbiddenCanaryPermissions.sortedBy { it.name }.map { "forbidden:${it.name}" }.toTypedArray(),
    )

    companion object {
        val DEFAULT_FORBIDDEN_CANARY_PERMISSIONS = setOf(
            ToolPermission.WRITE_TEMP_FILE,
            ToolPermission.WRITE_USER_FILE,
            ToolPermission.NETWORK_ACCESS,
            ToolPermission.DATABASE_WRITE,
            ToolPermission.START_WORKER,
            ToolPermission.INVOKE_TOOL,
            ToolPermission.WRITE_MEMORY,
            ToolPermission.MODIFY_REPOSITORY,
        )
    }
}

/** Immutable J05 decision evidence. Approval is not activation authority. */
data class EvolutionAdoptionEvidence(
    val subjectId: String,
    val evaluationReportId: String,
    val datasetId: String,
    val policyId: String,
    val candidateRecordFingerprint: String,
    val baselineDescriptorFingerprint: String,
    val adoptionRequestId: String,
    val actorId: String,
    val canaryScopeId: String,
    val decision: EvolutionAdoptionDecision,
    val reasons: List<String>,
) {
    init {
        require(subjectId.isNotBlank())
        require(evaluationReportId.isNotBlank())
        require(datasetId.isNotBlank())
        require(policyId.isNotBlank())
        require(candidateRecordFingerprint.isNotBlank())
        require(baselineDescriptorFingerprint.isNotBlank())
        require(adoptionRequestId.isNotBlank())
        require(actorId.isNotBlank())
        require(canaryScopeId.isNotBlank())
        require(reasons.isNotEmpty()) { "Adoption evidence requires decision reasons" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-adoption-evidence/v1",
        subjectId,
        evaluationReportId,
        datasetId,
        policyId,
        candidateRecordFingerprint,
        baselineDescriptorFingerprint,
        adoptionRequestId,
        actorId,
        canaryScopeId,
        decision.name,
        *reasons.sorted().map { "reason:$it" }.toTypedArray(),
    )

    /** J05 approval is evidence for a later canary router, never direct activation. */
    val activationAllowed: Boolean = false
}

/**
 * Replays J04 under the trusted J05 policy and binds adoption to the exact still-current candidate,
 * baseline, holdout and independent actor. No registry mutation or capability activation occurs.
 */
class EvolutionAdoptionGate(
    private val policy: EvolutionAdoptionPolicy = EvolutionAdoptionPolicy(),
) {
    fun evaluate(
        subject: EvolutionSubject,
        dataset: EvolutionDatasetRef,
        report: EvolutionEvaluationReport,
        cases: List<EvolutionTestCase>,
        observations: List<EvolutionShadowObservation>,
        currentCandidate: GeneratedToolRecord,
        currentBaseline: CapabilityDescriptor,
        request: EvolutionAdoptionRequest,
    ): EvolutionAdoptionEvidence {
        require(report.subjectId == subject.id) { "Adoption report belongs to another evolution subject" }
        require(report.datasetId == dataset.id) { "Adoption report belongs to another holdout dataset" }
        require(!report.activationAllowed) { "Evaluation report must remain non-activating" }

        val replayedReport = ShadowEvaluationEngine(policy.trustedEvaluationPolicy).evaluate(
            subject = subject,
            dataset = dataset,
            evaluatorId = report.evaluatorId,
            cases = cases,
            observations = observations,
        )
        require(replayedReport.id == report.id) {
            "Adoption requires exact replay under the trusted evaluation policy"
        }

        require(currentCandidate.manifest.toolId == subject.candidateToolId)
        require(currentCandidate.state == GeneratedToolState.TRIAL) {
            "Adoption candidate must still be in TRIAL"
        }
        val candidateFingerprint = currentCandidate.evolutionFingerprint()
        require(candidateFingerprint == subject.candidateRecordFingerprint) {
            "Adoption candidate changed after shadow evaluation"
        }

        require(currentBaseline.providerId == subject.baselineProviderId)
        require(currentBaseline.state == ProviderState.ACTIVE || currentBaseline.state == ProviderState.DEGRADED) {
            "Adoption baseline must still be usable"
        }
        val baselineFingerprint = currentBaseline.evolutionFingerprint()
        require(baselineFingerprint == subject.baselineDescriptorFingerprint) {
            "Adoption baseline changed after shadow evaluation"
        }

        require(request.actorId != subject.candidateToolId) {
            "Evolution candidate cannot approve its own adoption"
        }
        require(request.actorId != report.evaluatorId) {
            "Evaluation and adoption actors must be separated"
        }
        require(request.actorId != dataset.curatorId) {
            "Holdout curator and adoption actor must be separated"
        }

        val reasons = mutableListOf<String>()
        if (report.decision != EvolutionEvaluationDecision.ELIGIBLE) {
            reasons += "evaluation-not-eligible:${report.decision.name}"
        }
        if (request.scope.assignmentPermille > policy.maximumInitialAssignmentPermille) {
            reasons += "assignment-scope-too-large:${request.scope.assignmentPermille}>${policy.maximumInitialAssignmentPermille}"
        }
        if (request.scope.maxInvocations > policy.maximumInitialInvocations) {
            reasons += "invocation-budget-too-large:${request.scope.maxInvocations}>${policy.maximumInitialInvocations}"
        }
        if (request.scope.maxDurationSeconds > policy.maximumInitialDurationSeconds) {
            reasons += "duration-too-large:${request.scope.maxDurationSeconds}>${policy.maximumInitialDurationSeconds}"
        }
        if (request.scope.allowedTaskTags.size > policy.maximumTaskTags) {
            reasons += "too-many-task-tags:${request.scope.allowedTaskTags.size}>${policy.maximumTaskTags}"
        }
        if (request.scope.productiveEffectsAllowed) {
            reasons += "productive-effects-forbidden-in-initial-canary"
        }
        val forbiddenPermissions = currentCandidate.manifest.permissions intersect policy.forbiddenCanaryPermissions
        if (forbiddenPermissions.isNotEmpty()) {
            reasons += "forbidden-canary-permissions:" + forbiddenPermissions.sortedBy { it.name }.joinToString(",") { it.name }
        }

        val decision = if (reasons.isEmpty()) {
            reasons += "approved-for-bounded-canary"
            EvolutionAdoptionDecision.APPROVED_FOR_CANARY
        } else {
            EvolutionAdoptionDecision.REJECTED
        }

        return EvolutionAdoptionEvidence(
            subjectId = subject.id,
            evaluationReportId = report.id,
            datasetId = dataset.id,
            policyId = policy.id,
            candidateRecordFingerprint = candidateFingerprint,
            baselineDescriptorFingerprint = baselineFingerprint,
            adoptionRequestId = request.id,
            actorId = request.actorId,
            canaryScopeId = request.scope.id,
            decision = decision,
            reasons = reasons.distinct().sorted(),
        )
    }
}

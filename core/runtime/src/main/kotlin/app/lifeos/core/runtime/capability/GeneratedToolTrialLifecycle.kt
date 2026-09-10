package app.lifeos.core.runtime.capability

import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GeneratedToolTrialResult(
    val invocationId: String,
    val success: Boolean,
    val producedExpectedOutput: Boolean,
    val safetyViolation: Boolean = false,
    val latencyMs: Long,
    val recordedAt: Instant = Instant.now(),
) {
    init {
        require(invocationId.isNotBlank()) { "Trial invocation id must not be blank" }
        require(latencyMs >= 0) { "Trial latency must not be negative" }
    }
}

data class GeneratedToolTrialStats(
    val trials: Int,
    val successes: Int,
    val expectedOutputs: Int,
    val safetyViolations: Int,
    val averageLatencyMs: Double,
) {
    val successRate: Double = if (trials == 0) 0.0 else successes.toDouble() / trials
    val expectedOutputRate: Double = if (trials == 0) 0.0 else expectedOutputs.toDouble() / trials
}

class GeneratedToolTrialLedger {
    private val mutex = Mutex()
    private val results = mutableMapOf<String, LinkedHashMap<String, GeneratedToolTrialResult>>()

    suspend fun record(toolId: String, result: GeneratedToolTrialResult): Boolean = mutex.withLock {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        val toolResults = results.getOrPut(toolId) { linkedMapOf() }
        val existing = toolResults[result.invocationId]
        if (existing != null) {
            require(existing == result) {
                "Conflicting trial result for invocation ${result.invocationId}"
            }
            return@withLock false
        }
        toolResults[result.invocationId] = result
        true
    }

    suspend fun stats(toolId: String): GeneratedToolTrialStats = mutex.withLock {
        val values = results[toolId]?.values.orEmpty()
        GeneratedToolTrialStats(
            trials = values.size,
            successes = values.count { it.success },
            expectedOutputs = values.count { it.producedExpectedOutput },
            safetyViolations = values.count { it.safetyViolation },
            averageLatencyMs = values
                .map { it.latencyMs.toDouble() }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?: 0.0,
        )
    }
}

data class GeneratedToolPromotionPolicy(
    val minimumTrials: Int = 3,
    val minimumSuccessRate: Double = 1.0,
    val minimumExpectedOutputRate: Double = 1.0,
    val minimumVerificationConfidence: Double = 0.90,
) {
    init {
        require(minimumTrials > 0) { "Minimum trial count must be positive" }
        require(minimumSuccessRate in 0.0..1.0) { "Minimum success rate must be normalized" }
        require(minimumExpectedOutputRate in 0.0..1.0) {
            "Minimum expected-output rate must be normalized"
        }
        require(minimumVerificationConfidence in 0.0..1.0) {
            "Minimum promotion confidence must be normalized"
        }
    }
}

sealed interface GeneratedToolTrialAdmissionResult {
    data class TrialStarted(
        val record: GeneratedToolRecord,
        val sandbox: GeneratedToolSandboxDecision.Admitted,
    ) : GeneratedToolTrialAdmissionResult

    data class Rejected(
        val record: GeneratedToolRecord,
        val reasons: List<String>,
    ) : GeneratedToolTrialAdmissionResult
}

sealed interface GeneratedToolTrialRecordResult {
    data class Recorded(
        val record: GeneratedToolRecord,
        val stats: GeneratedToolTrialStats,
    ) : GeneratedToolTrialRecordResult

    data class Quarantined(
        val record: GeneratedToolRecord,
        val stats: GeneratedToolTrialStats,
        val reason: String,
    ) : GeneratedToolTrialRecordResult
}

sealed interface GeneratedToolPromotionEvaluation {
    data class Eligible(
        val stats: GeneratedToolTrialStats,
        val evidence: GeneratedToolPromotionEvidenceSnapshot,
    ) : GeneratedToolPromotionEvaluation

    data class NotReady(
        val stats: GeneratedToolTrialStats,
        val reasons: List<String>,
        val evidence: GeneratedToolPromotionEvidenceSnapshot,
    ) : GeneratedToolPromotionEvaluation

    data class Blocked(val state: GeneratedToolState, val reason: String) : GeneratedToolPromotionEvaluation
}

/**
 * Explicit lifecycle gate after workshop verification. Nothing here executes a generated artifact.
 * Promotion requires both clean trial statistics and J03 build/field/health/rollback evidence.
 */
class GeneratedToolLifecycleCoordinator(
    private val tools: GeneratedToolRegistry,
    private val sandboxAdmission: GeneratedToolSandboxAdmission = GeneratedToolSandboxAdmission(),
    private val trialLedger: GeneratedToolTrialLedger = GeneratedToolTrialLedger(),
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
    private val promotionEvidenceLedger: GeneratedToolPromotionEvidenceLedger =
        GeneratedToolPromotionEvidenceLedger(),
    private val promotionEvidencePolicy: GeneratedToolPromotionEvidencePolicy =
        GeneratedToolPromotionEvidencePolicy(),
) {
    suspend fun admitToTrial(toolId: String): GeneratedToolTrialAdmissionResult {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.VERIFIED) {
            "Only VERIFIED tools may enter sandbox trial"
        }

        return when (val admission = sandboxAdmission.evaluate(record)) {
            is GeneratedToolSandboxDecision.Admitted -> {
                val trial = tools.transition(
                    toolId = toolId,
                    to = GeneratedToolState.TRIAL,
                    message = "sandbox-admitted:${admission.profileId}",
                )
                GeneratedToolTrialAdmissionResult.TrialStarted(trial, admission)
            }

            is GeneratedToolSandboxDecision.Denied -> {
                val rejected = tools.transition(
                    toolId = toolId,
                    to = GeneratedToolState.REJECTED,
                    message = admission.reasons.joinToString(";"),
                )
                GeneratedToolTrialAdmissionResult.Rejected(rejected, admission.reasons)
            }
        }
    }

    suspend fun bindDesignFieldSnapshots(toolId: String, snapshotIds: Set<FieldSnapshotId>): Boolean {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.VERIFIED || record.state == GeneratedToolState.TRIAL) {
            "Design field evidence may only bind VERIFIED or TRIAL tools"
        }
        return promotionEvidenceLedger.recordDesignFieldSnapshots(toolId, snapshotIds)
    }

    suspend fun bindBuildArtifact(toolId: String, artifact: CandidateArtifact): Boolean {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.VERIFIED || record.state == GeneratedToolState.TRIAL) {
            "Build provenance may only bind VERIFIED or TRIAL tools"
        }
        return promotionEvidenceLedger.bindBuildArtifact(toolId, record, artifact)
    }

    suspend fun recordHealthIncident(
        toolId: String,
        incident: GeneratedToolHealthIncident,
    ): Boolean {
        requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        return promotionEvidenceLedger.recordHealthIncident(toolId, incident)
    }

    suspend fun resolveHealthIncident(
        toolId: String,
        incident: GeneratedToolHealthIncident,
        resolutionEvidenceRef: String,
    ): Boolean {
        requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        return promotionEvidenceLedger.resolveHealthIncident(
            toolId = toolId,
            incident = incident,
            resolutionEvidenceRef = resolutionEvidenceRef,
        )
    }

    suspend fun recordRollback(
        toolId: String,
        rollback: GeneratedToolRollbackEvidence,
    ): Boolean {
        requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        return promotionEvidenceLedger.recordRollback(toolId, rollback)
    }

    suspend fun promotionEvidence(toolId: String): GeneratedToolPromotionEvidenceSnapshot {
        requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        return promotionEvidenceLedger.snapshot(toolId)
    }

    suspend fun authorizeTrialInvocation(
        toolId: String,
        invocationId: String,
        requestedPermissions: Set<ToolPermission>,
    ): GeneratedToolInvocationDecision {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        val decision = sandboxAdmission.authorizeTrialInvocation(
            record = record,
            invocationId = invocationId,
            requestedPermissions = requestedPermissions,
        )
        if (decision is GeneratedToolInvocationDecision.Denied) {
            tools.transition(
                toolId = toolId,
                to = GeneratedToolState.QUARANTINED,
                message = "sandbox-invocation-denied:${decision.reasons.joinToString(";")}",
            )
        }
        return decision
    }

    suspend fun recordTrial(
        toolId: String,
        result: GeneratedToolTrialResult,
    ): GeneratedToolTrialRecordResult {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.TRIAL) {
            "Trial results may only be recorded for TRIAL tools"
        }

        trialLedger.record(toolId, result)
        promotionEvidenceLedger.recordTrial(toolId, result)
        val stats = trialLedger.stats(toolId)
        if (result.safetyViolation) {
            val reason = "sandbox-safety-violation:${result.invocationId}"
            val quarantined = tools.transition(
                toolId = toolId,
                to = GeneratedToolState.QUARANTINED,
                message = reason,
            )
            return GeneratedToolTrialRecordResult.Quarantined(quarantined, stats, reason)
        }

        return GeneratedToolTrialRecordResult.Recorded(record, stats)
    }

    suspend fun evaluatePromotion(toolId: String): GeneratedToolPromotionEvaluation {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        if (record.state != GeneratedToolState.TRIAL) {
            return GeneratedToolPromotionEvaluation.Blocked(
                state = record.state,
                reason = "tool-not-in-trial",
            )
        }

        val stats = trialLedger.stats(toolId)
        val evidence = promotionEvidenceLedger.snapshot(toolId)
        val reasons = buildList {
            if (stats.trials < promotionPolicy.minimumTrials) {
                add("insufficient-trials:${stats.trials}<${promotionPolicy.minimumTrials}")
            }
            if (stats.successRate < promotionPolicy.minimumSuccessRate) {
                add("success-rate:${stats.successRate}<${promotionPolicy.minimumSuccessRate}")
            }
            if (stats.expectedOutputRate < promotionPolicy.minimumExpectedOutputRate) {
                add("expected-output-rate:${stats.expectedOutputRate}<${promotionPolicy.minimumExpectedOutputRate}")
            }
            if (stats.safetyViolations > 0) {
                add("safety-violations:${stats.safetyViolations}")
            }
            if (record.verificationConfidence < promotionPolicy.minimumVerificationConfidence) {
                add(
                    "verification-confidence:${record.verificationConfidence}<" +
                        promotionPolicy.minimumVerificationConfidence
                )
            }
            addAll(promotionEvidencePolicy.reasons(stats, evidence))
        }.distinct().sorted()

        return if (reasons.isEmpty()) {
            GeneratedToolPromotionEvaluation.Eligible(stats, evidence)
        } else {
            GeneratedToolPromotionEvaluation.NotReady(stats, reasons, evidence)
        }
    }

    suspend fun promote(toolId: String): GeneratedToolRecord {
        val evaluation = evaluatePromotion(toolId)
        require(evaluation is GeneratedToolPromotionEvaluation.Eligible) {
            "Generated tool is not eligible for promotion: $evaluation"
        }
        require(!evaluation.evidence.activationAllowed)

        val frozenEvidence = promotionEvidenceLedger.freezeForPromotion(
            toolId = toolId,
            expectedSnapshotId = evaluation.evidence.id,
        )
        require(frozenEvidence.id == evaluation.evidence.id)
        tools.bindPromotionEvidence(toolId, frozenEvidence.id)
        val active = tools.promoteWithEvidence(
            toolId = toolId,
            evidenceId = frozenEvidence.id,
            message = "trial-promoted:evidence:${frozenEvidence.id}",
        )
        capabilityRegistry?.register(active.toCapabilityDescriptor(evaluation.stats))
        return active
    }

    private fun GeneratedToolRecord.toCapabilityDescriptor(
        stats: GeneratedToolTrialStats,
    ) = CapabilityDescriptor(
        capabilityId = manifest.sourceCapability,
        providerId = manifest.toolId,
        providerType = ProviderType.GENERATED_TOOL,
        contract = CapabilityContract(
            requiredInputs = manifest.requiredInputs,
            outputs = manifest.requiredOutputs,
        ),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.LOW,
        reliability = stats.successRate,
        cost = 0.0,
    )
}

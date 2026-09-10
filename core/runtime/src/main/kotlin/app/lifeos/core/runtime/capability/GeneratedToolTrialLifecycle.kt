package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
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

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "generated-tool-trial-result/v1",
        invocationId,
        success.toString(),
        producedExpectedOutput.toString(),
        safetyViolation.toString(),
        latencyMs.toString(),
        recordedAt.toString(),
    )
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

data class GeneratedToolTrialEvidence(
    val toolId: String,
    val results: List<GeneratedToolTrialResult>,
) {
    init {
        require(toolId.isNotBlank()) { "Trial evidence tool id must not be blank" }
        require(results.map { it.invocationId }.distinct().size == results.size) {
            "Trial evidence cannot contain duplicate invocation ids"
        }
    }

    val orderedResults: List<GeneratedToolTrialResult> = results.sortedBy { it.invocationId }

    val stats: GeneratedToolTrialStats = GeneratedToolTrialStats(
        trials = orderedResults.size,
        successes = orderedResults.count { it.success },
        expectedOutputs = orderedResults.count { it.producedExpectedOutput },
        safetyViolations = orderedResults.count { it.safetyViolation },
        averageLatencyMs = orderedResults
            .map { it.latencyMs.toDouble() }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0,
    )

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-trial-evidence/v1",
        toolId,
        *orderedResults.map { it.fingerprint() }.toTypedArray(),
    )
}

class GeneratedToolTrialLedger {
    private val mutex = Mutex()
    private val results = mutableMapOf<String, LinkedHashMap<String, GeneratedToolTrialResult>>()

    suspend fun record(toolId: String, result: GeneratedToolTrialResult): Boolean = mutex.withLock {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        val toolResults = results.getOrPut(toolId) { linkedMapOf() }
        if (result.invocationId in toolResults) return@withLock false
        toolResults[result.invocationId] = result
        true
    }

    suspend fun evidence(toolId: String): GeneratedToolTrialEvidence = mutex.withLock {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        GeneratedToolTrialEvidence(
            toolId = toolId,
            results = results[toolId]?.values?.toList().orEmpty(),
        )
    }

    suspend fun stats(toolId: String): GeneratedToolTrialStats = evidence(toolId).stats
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

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "generated-tool-promotion-policy/v1",
        minimumTrials.toString(),
        minimumSuccessRate.toString(),
        minimumExpectedOutputRate.toString(),
        minimumVerificationConfidence.toString(),
    )
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
    data class Eligible(val stats: GeneratedToolTrialStats) : GeneratedToolPromotionEvaluation
    data class NotReady(val stats: GeneratedToolTrialStats, val reasons: List<String>) : GeneratedToolPromotionEvaluation
    data class Blocked(val state: GeneratedToolState, val reason: String) : GeneratedToolPromotionEvaluation
}

/**
 * Explicit lifecycle gate after workshop verification. Nothing here executes a
 * generated artifact. It controls sandbox trial admission, per-invocation
 * permission permits, trial evidence, explicit promotion and evidence-bound rollback.
 */
class GeneratedToolLifecycleCoordinator(
    private val tools: GeneratedToolRegistry,
    private val sandboxAdmission: GeneratedToolSandboxAdmission = GeneratedToolSandboxAdmission(),
    private val trialLedger: GeneratedToolTrialLedger = GeneratedToolTrialLedger(),
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
) {
    private val activationMutex = Mutex()

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
        }

        return if (reasons.isEmpty()) {
            GeneratedToolPromotionEvaluation.Eligible(stats)
        } else {
            GeneratedToolPromotionEvaluation.NotReady(stats, reasons)
        }
    }

    /**
     * Creates immutable evidence for the current exact TRIAL snapshot. The evidence remains
     * non-activating and is invalidated by any later trial result, tool mutation or policy change.
     */
    suspend fun preparePromotionEvidence(
        toolId: String,
        artifact: CandidateArtifact,
    ): GeneratedToolPromotionEvidence {
        val evaluation = evaluatePromotion(toolId)
        require(evaluation is GeneratedToolPromotionEvaluation.Eligible) {
            "Generated tool is not eligible for promotion evidence: $evaluation"
        }
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        val trialEvidence = trialLedger.evidence(toolId)
        require(trialEvidence.stats == evaluation.stats) {
            "Trial evidence changed while promotion was being prepared"
        }
        return GeneratedToolPromotionEvidence.create(
            artifact = artifact,
            record = record,
            trialEvidence = trialEvidence,
            policy = promotionPolicy,
        )
    }

    /** Legacy activation without J03 build evidence is deliberately disabled. */
    @Deprecated("J03 requires CandidateArtifact-backed promotion evidence")
    suspend fun promote(toolId: String): GeneratedToolRecord {
        error("J03 CandidateArtifact-backed promotion evidence is required for $toolId")
    }

    suspend fun promote(
        toolId: String,
        evidence: GeneratedToolPromotionEvidence,
    ): GeneratedToolRecord = activationMutex.withLock {
        val evaluation = evaluatePromotion(toolId)
        require(evaluation is GeneratedToolPromotionEvaluation.Eligible) {
            "Generated tool is not eligible for promotion: $evaluation"
        }

        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        val exactTrials = trialLedger.evidence(toolId)
        require(exactTrials.stats == evaluation.stats) {
            "Trial evidence changed while promotion was being evaluated"
        }
        require(evidence.matches(record, exactTrials, promotionPolicy)) {
            "J03 promotion evidence is stale or belongs to another candidate/tool/policy"
        }

        val active = tools.promote(
            toolId = toolId,
            evidence = evidence,
        )
        capabilityRegistry?.registerGenerated(
            descriptor = active.toCapabilityDescriptor(exactTrials.stats),
            activeRecord = active,
            evidence = evidence,
        )
        active
    }

    /**
     * Reproducibly undoes the exact active promotion named by [request]. The generated capability
     * is removed and the tool is left QUARANTINED; no automatic re-trial or re-promotion occurs.
     */
    suspend fun rollback(request: GeneratedToolRollbackRequest): GeneratedToolRollbackResult =
        activationMutex.withLock {
            val current = requireNotNull(tools.get(request.toolId)) {
                "Unknown generated tool ${request.toolId}"
            }
            require(current.state == GeneratedToolState.ACTIVE) {
                "Only ACTIVE generated tools can be rolled back"
            }
            require(current.promotionEvidenceId == request.expectedPromotionEvidenceId) {
                "Rollback request does not match current promotion evidence"
            }

            val mutation = tools.rollback(request)
            val removed = capabilityRegistry?.unregister(
                capabilityId = current.manifest.sourceCapability,
                providerId = current.manifest.toolId,
            )
            GeneratedToolRollbackResult(
                record = mutation.record,
                request = request,
                removedCapabilityProvider = removed,
                auditEntry = mutation.auditEntry,
            )
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

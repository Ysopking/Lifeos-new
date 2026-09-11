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
        averageLatencyMs = orderedResults.map { it.latencyMs.toDouble() }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
    )
    val id: String = StableFieldIds.fingerprint(
        "generated-tool-trial-evidence/v1",
        toolId,
        *orderedResults.map { it.fingerprint() }.toTypedArray(),
    )
}

class GeneratedToolTrialLedger(
    private val durableState: GeneratedToolStateRepository? = null,
) {
    private val mutex = Mutex()
    private val results = mutableMapOf<String, LinkedHashMap<String, GeneratedToolTrialResult>>()

    suspend fun record(toolId: String, result: GeneratedToolTrialResult): Boolean = mutationLocked {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        val currentResults = results[toolId]
        currentResults?.get(result.invocationId)?.let { existing ->
            require(existing == result) { "Conflicting generated-tool trial retry for ${result.invocationId}" }
            return@mutationLocked false
        }
        val evidence = GeneratedToolTrialEvidence(toolId, currentResults?.values?.toList().orEmpty() + result)
        durableState?.persistTrialEvidence(evidence)
        results.getOrPut(toolId) { linkedMapOf() }[result.invocationId] = result
        true
    }

    suspend fun evidence(toolId: String): GeneratedToolTrialEvidence = mutex.withLock {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        GeneratedToolTrialEvidence(toolId, results[toolId]?.values?.toList().orEmpty())
    }

    suspend fun stats(toolId: String): GeneratedToolTrialStats = evidence(toolId).stats

    internal suspend fun restore(evidence: GeneratedToolTrialEvidence) = mutex.withLock {
        require(results[evidence.toolId].isNullOrEmpty()) { "Generated-tool trial ledger ${evidence.toolId} is already loaded" }
        if (evidence.results.isNotEmpty()) {
            results[evidence.toolId] = linkedMapOf<String, GeneratedToolTrialResult>().apply {
                evidence.results.forEach { put(it.invocationId, it) }
            }
        }
    }

    internal suspend fun isEmpty(): Boolean = mutex.withLock { results.isEmpty() }

    private suspend fun <T> mutationLocked(action: suspend () -> T): T {
        mutex.lock()
        return try { action() } finally { mutex.unlock() }
    }
}

data class GeneratedToolPromotionPolicy(
    val minimumTrials: Int = 3,
    val minimumSuccessRate: Double = 1.0,
    val minimumExpectedOutputRate: Double = 1.0,
    val minimumVerificationConfidence: Double = 0.90,
) {
    init {
        require(minimumTrials > 0)
        require(minimumSuccessRate in 0.0..1.0)
        require(minimumExpectedOutputRate in 0.0..1.0)
        require(minimumVerificationConfidence in 0.0..1.0)
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
    data class TrialStarted(val record: GeneratedToolRecord, val sandbox: GeneratedToolSandboxDecision.Admitted) : GeneratedToolTrialAdmissionResult
    data class Rejected(val record: GeneratedToolRecord, val reasons: List<String>) : GeneratedToolTrialAdmissionResult
}

sealed interface GeneratedToolTrialRecordResult {
    data class Recorded(val record: GeneratedToolRecord, val stats: GeneratedToolTrialStats) : GeneratedToolTrialRecordResult
    data class Quarantined(val record: GeneratedToolRecord, val stats: GeneratedToolTrialStats, val reason: String) : GeneratedToolTrialRecordResult
}

sealed interface GeneratedToolPromotionEvaluation {
    data class Eligible(val stats: GeneratedToolTrialStats) : GeneratedToolPromotionEvaluation
    data class NotReady(val stats: GeneratedToolTrialStats, val reasons: List<String>) : GeneratedToolPromotionEvaluation
    data class Blocked(val state: GeneratedToolState, val reason: String) : GeneratedToolPromotionEvaluation
}

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
        require(record.state == GeneratedToolState.VERIFIED) { "Only VERIFIED tools may enter sandbox trial" }
        return when (val admission = sandboxAdmission.evaluate(record)) {
            is GeneratedToolSandboxDecision.Admitted -> {
                val trial = tools.transition(toolId, GeneratedToolState.TRIAL, message = "sandbox-admitted:${admission.profileId}")
                GeneratedToolTrialAdmissionResult.TrialStarted(trial, admission)
            }
            is GeneratedToolSandboxDecision.Denied -> {
                val rejected = tools.transition(toolId, GeneratedToolState.REJECTED, message = admission.reasons.joinToString(";"))
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
        val decision = sandboxAdmission.authorizeTrialInvocation(record, invocationId, requestedPermissions)
        if (decision is GeneratedToolInvocationDecision.Denied) {
            tools.transition(
                toolId,
                GeneratedToolState.QUARANTINED,
                message = "sandbox-invocation-denied:${decision.reasons.joinToString(";")}",
            )
        }
        return decision
    }

    suspend fun recordTrial(toolId: String, result: GeneratedToolTrialResult): GeneratedToolTrialRecordResult {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.TRIAL) { "Trial results may only be recorded for TRIAL tools" }
        if (result.safetyViolation) {
            val reason = "sandbox-safety-violation:${result.invocationId}"
            val quarantined = tools.transition(toolId, GeneratedToolState.QUARANTINED, message = reason)
            trialLedger.record(toolId, result)
            return GeneratedToolTrialRecordResult.Quarantined(quarantined, trialLedger.stats(toolId), reason)
        }
        trialLedger.record(toolId, result)
        return GeneratedToolTrialRecordResult.Recorded(record, trialLedger.stats(toolId))
    }

    suspend fun evaluatePromotion(toolId: String): GeneratedToolPromotionEvaluation {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        if (record.state != GeneratedToolState.TRIAL) {
            return GeneratedToolPromotionEvaluation.Blocked(record.state, "tool-not-in-trial")
        }
        val stats = trialLedger.stats(toolId)
        val reasons = buildList {
            if (stats.trials < promotionPolicy.minimumTrials) add("insufficient-trials:${stats.trials}<${promotionPolicy.minimumTrials}")
            if (stats.successRate < promotionPolicy.minimumSuccessRate) add("success-rate:${stats.successRate}<${promotionPolicy.minimumSuccessRate}")
            if (stats.expectedOutputRate < promotionPolicy.minimumExpectedOutputRate) add("expected-output-rate:${stats.expectedOutputRate}<${promotionPolicy.minimumExpectedOutputRate}")
            if (stats.safetyViolations > 0) add("safety-violations:${stats.safetyViolations}")
            if (record.verificationConfidence < promotionPolicy.minimumVerificationConfidence) {
                add("verification-confidence:${record.verificationConfidence}<${promotionPolicy.minimumVerificationConfidence}")
            }
        }
        return if (reasons.isEmpty()) GeneratedToolPromotionEvaluation.Eligible(stats)
        else GeneratedToolPromotionEvaluation.NotReady(stats, reasons)
    }

    suspend fun preparePromotionEvidence(toolId: String, artifact: CandidateArtifact): GeneratedToolPromotionEvidence {
        val evaluation = evaluatePromotion(toolId)
        require(evaluation is GeneratedToolPromotionEvaluation.Eligible) {
            "Generated tool is not eligible for promotion evidence: $evaluation"
        }
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        val trialEvidence = trialLedger.evidence(toolId)
        require(trialEvidence.stats == evaluation.stats) { "Trial evidence changed while promotion was being prepared" }
        return GeneratedToolPromotionEvidence.create(artifact, record, trialEvidence, promotionPolicy)
    }

    @Deprecated("J08 requires readiness-bound EvolutionPromotionBridge activation")
    suspend fun promote(toolId: String): GeneratedToolRecord {
        error("J08 EvolutionPromotionBridge activation is required for $toolId")
    }

    internal suspend fun promote(
        toolId: String,
        evidence: GeneratedToolActivationEvidence,
        activationEvidenceRef: String = evidence.id,
        actorId: String? = null,
        novelClaim: GeneratedToolNovelActivationClaim? = null,
        registerCapability: Boolean = true,
    ): GeneratedToolRecord = activationMutex.withLock {
        require(activationEvidenceRef.isNotBlank())
        require(actorId == null || actorId.isNotBlank())
        require(!evidence.activationAllowed)
        val evaluation = evaluatePromotion(toolId)
        require(evaluation is GeneratedToolPromotionEvaluation.Eligible) {
            "Generated tool is not eligible for promotion: $evaluation"
        }
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        val exactTrials = trialLedger.evidence(toolId)
        require(exactTrials.stats == evaluation.stats) { "Trial evidence changed while promotion was being evaluated" }
        require(evidence.matches(record, exactTrials, promotionPolicy)) {
            "Activation evidence is stale or belongs to another tool/trial/policy"
        }

        val prospective = record.copy(
            state = GeneratedToolState.ACTIVE,
            promotionEvidenceId = evidence.id,
        )
        val descriptor = prospective.toCapabilityDescriptor(exactTrials.stats)
        val capabilities = capabilityRegistry
        when (evidence) {
            is GeneratedToolPromotionEvidence -> require(novelClaim == null) {
                "J03 promotion cannot consume a novel activation claim"
            }
            is BoundedGeneratedToolPromotionEvidence -> {
                val claim = requireNotNull(novelClaim) { "Bounded promotion requires a novel activation claim" }
                require(registerCapability) { "Bounded novel promotion cannot be staged as a replacement hot-swap" }
                requireNotNull(capabilities) { "Bounded promotion requires a capability registry" }
                    .preflightGeneratedNovel(descriptor, prospective, evidence, claim)
            }
        }

        val active = tools.promote(
            toolId = toolId,
            evidence = evidence,
            activationEvidenceRef = activationEvidenceRef,
            actorId = actorId,
        )
        if (capabilities != null && registerCapability) {
            when (evidence) {
                is GeneratedToolPromotionEvidence -> capabilities.registerGenerated(
                    active.toCapabilityDescriptor(exactTrials.stats), active, evidence
                )
                is BoundedGeneratedToolPromotionEvidence -> capabilities.registerGeneratedNovel(
                    active.toCapabilityDescriptor(exactTrials.stats),
                    active,
                    evidence,
                    requireNotNull(novelClaim),
                )
            }
        }
        active
    }

    internal suspend fun activeDescriptor(toolId: String): CapabilityDescriptor = activationMutex.withLock {
        val record = requireNotNull(tools.get(toolId)) { "Unknown generated tool $toolId" }
        require(record.state == GeneratedToolState.ACTIVE) { "Hot-swap candidate must be ACTIVE before cutover" }
        record.toCapabilityDescriptor(trialLedger.stats(toolId))
    }

    suspend fun rollback(request: GeneratedToolRollbackRequest): GeneratedToolRollbackResult = activationMutex.withLock {
        val current = requireNotNull(tools.get(request.toolId)) { "Unknown generated tool ${request.toolId}" }
        require(current.state == GeneratedToolState.ACTIVE) { "Only ACTIVE generated tools can be rolled back" }
        require(current.promotionEvidenceId == request.expectedPromotionEvidenceId) {
            "Rollback request does not match current promotion evidence"
        }
        val removed = capabilityRegistry?.unregister(current.manifest.sourceCapability, current.manifest.toolId)
        val mutation = tools.rollback(request)
        GeneratedToolRollbackResult(mutation.record, request, removed, mutation.auditEntry)
    }

    private fun GeneratedToolRecord.toCapabilityDescriptor(stats: GeneratedToolTrialStats) = CapabilityDescriptor(
        capabilityId = manifest.sourceCapability,
        providerId = manifest.toolId,
        providerType = ProviderType.GENERATED_TOOL,
        contract = CapabilityContract(manifest.requiredInputs, manifest.requiredOutputs),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.LOW,
        reliability = stats.successRate,
        cost = 0.0,
    )
}

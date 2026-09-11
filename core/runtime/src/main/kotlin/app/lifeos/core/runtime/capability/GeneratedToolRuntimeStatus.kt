package app.lifeos.core.runtime.capability

/** Read-only projection of one generated tool for private diagnostics and user controls. */
data class GeneratedToolRuntimeItem(
    val toolId: String,
    val capabilityId: String,
    val state: GeneratedToolState,
    val verificationConfidence: Double,
    val trials: Int,
    val successes: Int,
    val expectedOutputs: Int,
    val safetyViolations: Int,
    val averageLatencyMs: Double,
    val promotionEvidenceId: String?,
    val boundedAdmissionEvidenceId: String? = null,
    val boundedReadinessEvidenceId: String? = null,
    val boundedPromotionSealId: String? = null,
    val lastMessage: String?,
) {
    init {
        require(toolId.isNotBlank())
        require(capabilityId.isNotBlank())
        require(verificationConfidence in 0.0..1.0)
        require(trials >= 0)
        require(successes in 0..trials)
        require(expectedOutputs in 0..trials)
        require(safetyViolations in 0..trials)
        require(averageLatencyMs >= 0.0)
        require(promotionEvidenceId == null || promotionEvidenceId.isNotBlank())
        val boundedEvidenceIds = listOf(
            boundedAdmissionEvidenceId,
            boundedReadinessEvidenceId,
            boundedPromotionSealId,
        )
        require(boundedEvidenceIds.all { it == null } || boundedEvidenceIds.all { !it.isNullOrBlank() }) {
            "Bounded generated-tool diagnostics require the complete admission/readiness/seal identity set"
        }
        if (boundedEvidenceIds.any { it != null }) {
            require(!promotionEvidenceId.isNullOrBlank()) {
                "Bounded generated-tool diagnostics require accepted promotion evidence"
            }
        }
    }
}

data class GeneratedToolRuntimeStatus(
    val tools: List<GeneratedToolRuntimeItem>,
) {
    init {
        require(tools.map { it.toolId } == tools.map { it.toolId }.sorted()) {
            "Generated-tool runtime status must be sorted by tool id"
        }
        require(tools.map { it.toolId }.distinct().size == tools.size) {
            "Generated-tool runtime status contains duplicate tool ids"
        }
    }

    val totalTools: Int get() = tools.size
    val activeTools: Int get() = tools.count { it.state == GeneratedToolState.ACTIVE }
    val trialTools: Int get() = tools.count { it.state == GeneratedToolState.TRIAL }
    val quarantinedTools: Int get() = tools.count { it.state == GeneratedToolState.QUARANTINED }
    val rejectedTools: Int get() = tools.count { it.state == GeneratedToolState.REJECTED }
    val totalTrials: Int get() = tools.sumOf { it.trials }
    val totalSafetyViolations: Int get() = tools.sumOf { it.safetyViolations }
}

/**
 * Read-only boundary over the encrypted generated-tool source of truth. The mutable repository never
 * leaves this reader; UI callers receive immutable projections including the exact bounded admission,
 * readiness, promotion-seal and accepted promotion evidence identities when present.
 */
class GeneratedToolRuntimeStatusReader(
    private val repository: GeneratedToolStateRepository,
) {
    suspend fun snapshot(): GeneratedToolRuntimeStatus {
        val states = repository.loadAll().sortedBy { it.record.manifest.toolId }
        return GeneratedToolRuntimeStatus(
            tools = states.map { state ->
                val record = state.record
                val stats = state.trialEvidence.stats
                val bounded = state.boundedPromotionReceipt
                GeneratedToolRuntimeItem(
                    toolId = record.manifest.toolId,
                    capabilityId = record.manifest.sourceCapability.value,
                    state = record.state,
                    verificationConfidence = record.verificationConfidence,
                    trials = stats.trials,
                    successes = stats.successes,
                    expectedOutputs = stats.expectedOutputs,
                    safetyViolations = stats.safetyViolations,
                    averageLatencyMs = stats.averageLatencyMs,
                    promotionEvidenceId = record.promotionEvidenceId,
                    boundedAdmissionEvidenceId = bounded?.novelAdmissionEvidenceId,
                    boundedReadinessEvidenceId = bounded?.canaryReadinessEvidenceId,
                    boundedPromotionSealId = bounded?.promotionSealId,
                    lastMessage = record.lastMessage,
                )
            },
        )
    }
}

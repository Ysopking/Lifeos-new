package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import java.time.Instant
import kotlin.math.sqrt

enum class EvolutionObservationSide {
    BASELINE,
    CANDIDATE,
}

enum class EvolutionExecutionMode {
    SHADOW,
}

enum class EvolutionHardFailure {
    SAFETY_VIOLATION,
    UNAUTHORIZED_EFFECT,
    DATA_CORRUPTION,
    CONTRACT_VIOLATION,
    RESOURCE_BUDGET_EXCEEDED,
}

enum class EvolutionEvaluationDecision {
    ELIGIBLE,
    INSUFFICIENT_EVIDENCE,
    NOT_BETTER,
    REJECTED,
}

/** Frozen holdout dataset identity. Raw test inputs remain outside the evidence model. */
data class EvolutionDatasetRef(
    val datasetId: String,
    val contentFingerprint: String,
    val curatorId: String,
) {
    init {
        require(datasetId.isNotBlank()) { "Evolution dataset id must not be blank" }
        require(contentFingerprint.isNotBlank()) { "Evolution dataset fingerprint must not be blank" }
        require(curatorId.isNotBlank()) { "Evolution dataset curator must not be blank" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-dataset-ref/v1",
        datasetId,
        contentFingerprint,
        curatorId,
    )
}

data class EvolutionTestCase(
    val caseId: String,
    val inputFingerprint: String,
    val expectedOutputFingerprint: String? = null,
) {
    init {
        require(caseId.isNotBlank()) { "Evolution case id must not be blank" }
        require(inputFingerprint.isNotBlank()) { "Evolution input fingerprint must not be blank" }
        require(expectedOutputFingerprint == null || expectedOutputFingerprint.isNotBlank()) {
            "Expected output fingerprint must not be blank"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-test-case/v1",
        caseId,
        inputFingerprint,
        expectedOutputFingerprint.orEmpty(),
    )
}

/**
 * Exact baseline/candidate comparison subject. Creation binds the current TRIAL tool to the exact
 * verified CandidateArtifact and exact usable baseline descriptor for the same capability.
 */
data class EvolutionSubject private constructor(
    val candidateToolId: String,
    val candidateArtifactId: String,
    val candidateRecordFingerprint: String,
    val candidateBuildHash: String,
    val capabilityId: String,
    val baselineProviderId: String,
    val baselineDescriptorFingerprint: String,
    val baselineReliability: Double,
) {
    val id: String = StableFieldIds.fingerprint(
        "evolution-subject/v1",
        candidateToolId,
        candidateArtifactId,
        candidateRecordFingerprint,
        candidateBuildHash,
        capabilityId,
        baselineProviderId,
        baselineDescriptorFingerprint,
        baselineReliability.toString(),
    )

    /** Evaluation evidence cannot activate a tool. */
    val activationAllowed: Boolean = false

    companion object {
        fun create(
            artifact: CandidateArtifact,
            candidate: GeneratedToolRecord,
            baseline: CapabilityDescriptor,
        ): EvolutionSubject {
            require(candidate.state == GeneratedToolState.TRIAL) {
                "Evolution candidate must be in TRIAL"
            }
            require(!artifact.activationAllowed) { "CandidateArtifact must remain non-activating" }
            require(baseline.state == ProviderState.ACTIVE || baseline.state == ProviderState.DEGRADED) {
                "Evolution baseline provider must be usable"
            }
            require(baseline.providerId != candidate.manifest.toolId) {
                "Candidate cannot be its own evolution baseline"
            }
            require(baseline.capabilityId == candidate.manifest.sourceCapability) {
                "Evolution baseline capability differs from candidate capability"
            }
            require(baseline.contract.requiredInputs == candidate.manifest.requiredInputs) {
                "Evolution baseline input contract differs from candidate contract"
            }
            require(baseline.contract.outputs == candidate.manifest.requiredOutputs) {
                "Evolution baseline output contract differs from candidate contract"
            }
            require(artifact.provenance.sourceRequirement.capabilityId == candidate.manifest.sourceCapability) {
                "CandidateArtifact capability differs from candidate tool capability"
            }
            require(artifact.provenance.sourceRequirement.requiredInputs == candidate.manifest.requiredInputs) {
                "CandidateArtifact input requirement differs from candidate tool"
            }
            require(artifact.provenance.sourceRequirement.requiredOutputs == candidate.manifest.requiredOutputs) {
                "CandidateArtifact output requirement differs from candidate tool"
            }
            val buildHash = requireNotNull(candidate.manifest.buildHash) {
                "Evolution candidate requires build hash"
            }
            require(buildHash.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Evolution candidate build hash must be SHA-256"
            }
            require(buildHash.equals(artifact.debugApkSha256, ignoreCase = true)) {
                "Evolution candidate build hash differs from CandidateArtifact APK"
            }

            return EvolutionSubject(
                candidateToolId = candidate.manifest.toolId,
                candidateArtifactId = artifact.id,
                candidateRecordFingerprint = candidate.evolutionFingerprint(),
                candidateBuildHash = buildHash.lowercase(),
                capabilityId = candidate.manifest.sourceCapability.value,
                baselineProviderId = baseline.providerId,
                baselineDescriptorFingerprint = baseline.evolutionFingerprint(),
                baselineReliability = baseline.reliability,
            )
        }
    }
}

data class EvolutionShadowObservation(
    val caseId: String,
    val side: EvolutionObservationSide,
    val providerId: String,
    val success: Boolean,
    val outputFingerprint: String?,
    val qualityScore: Double,
    val latencyMs: Long,
    val peakMemoryBytes: Long,
    val hardFailures: Set<EvolutionHardFailure> = emptySet(),
    val productiveEffectAttempted: Boolean = false,
    val executionMode: EvolutionExecutionMode = EvolutionExecutionMode.SHADOW,
    val recordedAt: Instant,
) {
    init {
        require(caseId.isNotBlank()) { "Shadow observation case id must not be blank" }
        require(providerId.isNotBlank()) { "Shadow observation provider id must not be blank" }
        require(qualityScore in 0.0..1.0) { "Shadow quality score must be normalized" }
        require(latencyMs >= 0) { "Shadow latency must not be negative" }
        require(peakMemoryBytes >= 0) { "Shadow memory usage must not be negative" }
        if (success) {
            require(!outputFingerprint.isNullOrBlank()) {
                "Successful shadow observation requires output fingerprint"
            }
        }
        if (productiveEffectAttempted) {
            require(EvolutionHardFailure.UNAUTHORIZED_EFFECT in hardFailures) {
                "Productive effect attempt must be recorded as unauthorized effect"
            }
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-shadow-observation/v1",
        caseId,
        side.name,
        providerId,
        success.toString(),
        outputFingerprint.orEmpty(),
        qualityScore.toString(),
        latencyMs.toString(),
        peakMemoryBytes.toString(),
        productiveEffectAttempted.toString(),
        executionMode.name,
        recordedAt.toString(),
        *hardFailures.sortedBy { it.name }.map { "failure:${it.name}" }.toTypedArray(),
    )
}

data class EvolutionEvaluationPolicy(
    val minimumCases: Int = 5,
    val minimumCandidateSuccessRate: Double = 1.0,
    val minimumCandidateMeanQuality: Double = 0.90,
    val minimumQualityDelta: Double = 0.02,
    val maximumLatencyRatio: Double = 1.25,
    val maximumPeakMemoryRatio: Double = 1.25,
) {
    init {
        require(minimumCases > 0) { "Evolution minimum cases must be positive" }
        require(minimumCandidateSuccessRate in 0.0..1.0)
        require(minimumCandidateMeanQuality in 0.0..1.0)
        require(minimumQualityDelta.isFinite() && minimumQualityDelta >= 0.0)
        require(maximumLatencyRatio.isFinite() && maximumLatencyRatio > 0.0)
        require(maximumPeakMemoryRatio.isFinite() && maximumPeakMemoryRatio > 0.0)
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-evaluation-policy/v1",
        minimumCases.toString(),
        minimumCandidateSuccessRate.toString(),
        minimumCandidateMeanQuality.toString(),
        minimumQualityDelta.toString(),
        maximumLatencyRatio.toString(),
        maximumPeakMemoryRatio.toString(),
    )
}

data class EvolutionEvaluationStats(
    val cases: Int,
    val successes: Int,
    val meanQuality: Double,
    val meanLatencyMs: Double,
    val meanPeakMemoryBytes: Double,
    val qualityStandardError: Double,
    val successWilsonLower95: Double,
) {
    val successRate: Double = if (cases == 0) 0.0 else successes.toDouble() / cases
}

data class EvolutionEvaluationReport(
    val subjectId: String,
    val datasetId: String,
    val evaluatorId: String,
    val policyId: String,
    val baselineStats: EvolutionEvaluationStats,
    val candidateStats: EvolutionEvaluationStats,
    val decision: EvolutionEvaluationDecision,
    val reasons: List<String>,
    val caseEvidenceIds: List<String>,
    val observationEvidenceIds: List<String>,
) {
    init {
        require(subjectId.isNotBlank() && datasetId.isNotBlank() && evaluatorId.isNotBlank() && policyId.isNotBlank())
        require(reasons.isNotEmpty()) { "Evolution report requires at least one decision reason" }
        require(caseEvidenceIds.isNotEmpty()) { "Evolution report requires test case evidence" }
        require(observationEvidenceIds.isNotEmpty()) { "Evolution report requires observation evidence" }
    }

    val id: String = StableFieldIds.fingerprint(
        "evolution-evaluation-report/v1",
        subjectId,
        datasetId,
        evaluatorId,
        policyId,
        decision.name,
        statsFingerprint(baselineStats),
        statsFingerprint(candidateStats),
        *reasons.sorted().map { "reason:$it" }.toTypedArray(),
        *caseEvidenceIds.sorted().map { "case:$it" }.toTypedArray(),
        *observationEvidenceIds.sorted().map { "observation:$it" }.toTypedArray(),
    )

    /** An ELIGIBLE report is evidence only; J03 promotion remains a separate explicit gate. */
    val activationAllowed: Boolean = false

    private fun statsFingerprint(stats: EvolutionEvaluationStats): String = StableFieldIds.fingerprint(
        "evolution-evaluation-stats/v1",
        stats.cases.toString(),
        stats.successes.toString(),
        stats.meanQuality.toString(),
        stats.meanLatencyMs.toString(),
        stats.meanPeakMemoryBytes.toString(),
        stats.qualityStandardError.toString(),
        stats.successWilsonLower95.toString(),
    )
}

internal fun GeneratedToolRecord.evolutionFingerprint(): String = StableFieldIds.fingerprint(
    "evolution-generated-tool-record/v1",
    manifest.toolId,
    manifest.sourceCapability.value,
    manifest.sourceHash,
    manifest.buildHash.orEmpty(),
    manifest.generatedAt.toString(),
    state.name,
    verificationConfidence.toString(),
    lastMessage.orEmpty(),
    promotionEvidenceId.orEmpty(),
    *manifest.permissions.sortedBy { it.name }.map { "permission:${it.name}" }.toTypedArray(),
    *manifest.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *manifest.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)

internal fun CapabilityDescriptor.evolutionFingerprint(): String = StableFieldIds.fingerprint(
    "evolution-baseline-descriptor/v1",
    capabilityId.value,
    providerId,
    providerType.name,
    state.name,
    trustLevel.name,
    reliability.toString(),
    cost.toString(),
    *contract.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *contract.outputs.sorted().map { "output:$it" }.toTypedArray(),
)

internal fun evaluationStats(observations: List<EvolutionShadowObservation>): EvolutionEvaluationStats {
    if (observations.isEmpty()) {
        return EvolutionEvaluationStats(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
    val ordered = observations.sortedBy { it.caseId }
    val qualities = ordered.map { it.qualityScore }
    val meanQuality = qualities.average()
    val variance = if (qualities.size <= 1) 0.0 else qualities.sumOf { score ->
        val delta = score - meanQuality
        delta * delta
    } / (qualities.size - 1)
    val standardError = sqrt(variance / qualities.size)
    val successes = ordered.count { it.success }
    val n = ordered.size.toDouble()
    val p = successes / n
    val z = 1.959963984540054
    val denominator = 1.0 + z * z / n
    val center = p + z * z / (2.0 * n)
    val margin = z * sqrt((p * (1.0 - p) + z * z / (4.0 * n)) / n)
    val wilsonLower = ((center - margin) / denominator).coerceIn(0.0, 1.0)
    return EvolutionEvaluationStats(
        cases = ordered.size,
        successes = successes,
        meanQuality = meanQuality,
        meanLatencyMs = ordered.map { it.latencyMs.toDouble() }.average(),
        meanPeakMemoryBytes = ordered.map { it.peakMemoryBytes.toDouble() }.average(),
        qualityStandardError = standardError,
        successWilsonLower95 = wilsonLower,
    )
}

package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralSimilarityEngine
import app.lifeos.core.runtime.level7.StructuralTransferCandidate
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class CrossDomainTransferValidationStatus {
    TARGET_EVIDENCE_REQUIRED,
    TARGET_EVIDENCE_SATISFIED,
}

data class CrossDomainReasoningTransferHypothesis(
    val sourcePerformanceProfileFingerprint: String,
    val strategyId: ReasoningStrategyId,
    val strategyDescriptorFingerprint: String,
    val structuralTransferCandidateId: String,
    val structuralTransferCandidateFingerprint: String,
    val sourceProblemClassFingerprint: String,
    val targetProblemClassFingerprint: String,
    val targetStructuralSignatureFingerprint: String,
    val sourceVerifiedSampleCount: Int,
    val structuralSimilarity: Double,
    val transferredSuccessPrior: Double,
    val requiredTargetValidationSamples: Int,
    val validationStatus: CrossDomainTransferValidationStatus,
    val fingerprint: String,
) {
    init {
        require(sourcePerformanceProfileFingerprint.matches(SHA_256_REGEX))
        require(strategyDescriptorFingerprint.matches(SHA_256_REGEX))
        require(structuralTransferCandidateId.startsWith("structural-transfer:"))
        require(structuralTransferCandidateFingerprint.matches(SHA_256_REGEX))
        require(sourceProblemClassFingerprint.matches(SHA_256_REGEX))
        require(targetProblemClassFingerprint.matches(SHA_256_REGEX))
        require(targetStructuralSignatureFingerprint.matches(SHA_256_REGEX))
        require(sourceProblemClassFingerprint != targetProblemClassFingerprint) {
            "Cross-domain reasoning transfer requires a distinct target problem class"
        }
        require(sourceVerifiedSampleCount > 0)
        require(structuralSimilarity.isFinite() && structuralSimilarity in 0.0..1.0)
        require(transferredSuccessPrior.isFinite() && transferredSuccessPrior in 0.0..1.0)
        require(requiredTargetValidationSamples in 1..MAX_TARGET_VALIDATION_SAMPLES)
        require(
            fingerprint == transferHypothesisFingerprint(
                sourcePerformanceProfileFingerprint = sourcePerformanceProfileFingerprint,
                strategyId = strategyId,
                strategyDescriptorFingerprint = strategyDescriptorFingerprint,
                structuralTransferCandidateId = structuralTransferCandidateId,
                structuralTransferCandidateFingerprint = structuralTransferCandidateFingerprint,
                sourceProblemClassFingerprint = sourceProblemClassFingerprint,
                targetProblemClassFingerprint = targetProblemClassFingerprint,
                targetStructuralSignatureFingerprint = targetStructuralSignatureFingerprint,
                sourceVerifiedSampleCount = sourceVerifiedSampleCount,
                structuralSimilarity = structuralSimilarity,
                transferredSuccessPrior = transferredSuccessPrior,
                requiredTargetValidationSamples = requiredTargetValidationSamples,
                validationStatus = validationStatus,
            )
        )
    }

    val semanticIdentityEstablished: Boolean
        get() = false

    val directActivationAllowed: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false

    val selectionAuthority: Boolean
        get() = false
}

data class CrossDomainTransferValidation(
    val hypothesisFingerprint: String,
    val status: CrossDomainTransferValidationStatus,
    val targetPerformanceProfileFingerprint: String?,
    val targetVerifiedSampleCount: Int,
    val fingerprint: String,
) {
    init {
        require(hypothesisFingerprint.matches(SHA_256_REGEX))
        require(targetVerifiedSampleCount >= 0)
        when (status) {
            CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED ->
                require(targetPerformanceProfileFingerprint == null)
            CrossDomainTransferValidationStatus.TARGET_EVIDENCE_SATISFIED ->
                require(targetPerformanceProfileFingerprint?.matches(SHA_256_REGEX) == true)
        }
        require(
            fingerprint == transferValidationFingerprint(
                hypothesisFingerprint,
                status,
                targetPerformanceProfileFingerprint,
                targetVerifiedSampleCount,
            )
        )
    }

    val activationAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false
}

/**
 * B385 generates conservative cross-domain reasoning transfer hypotheses only.
 *
 * It reuses the existing Level7 structural transfer engine and exact B383/B384 strategy identities.
 * A hypothesis carries no selection, execution or promotion authority. Target-domain B384 evidence
 * remains the only empirical source that can satisfy target validation.
 */
class CrossDomainTransferEngine(
    private val registry: ReasoningStrategyRegistry,
    private val similarityEngine: StructuralSimilarityEngine = StructuralSimilarityEngine(),
    private val minimumStructuralSimilarity: Double = DEFAULT_MINIMUM_STRUCTURAL_SIMILARITY,
    private val requiredTargetValidationSamples: Int = DEFAULT_TARGET_VALIDATION_SAMPLES,
) {
    init {
        require(minimumStructuralSimilarity.isFinite())
        require(minimumStructuralSimilarity in 0.0..1.0)
        require(requiredTargetValidationSamples in 1..MAX_TARGET_VALIDATION_SAMPLES)
    }

    fun propose(
        sourceProfile: ReasoningStrategyPerformanceProfile,
        sourceProblemClass: ReasoningProblemClass,
        targetProblemClass: ReasoningProblemClass,
        sourceSignature: StructuralSignature,
        targetSignature: StructuralSignature,
    ): CrossDomainReasoningTransferHypothesis? {
        require(sourceProfile.problemClassFingerprint == sourceProblemClass.fingerprint) {
            "Source performance profile does not match source problem class"
        }
        require(sourceProblemClass.fingerprint != targetProblemClass.fingerprint) {
            "Cross-domain transfer requires distinct reasoning problem classes"
        }
        require(sourceSignature.domainId != targetSignature.domainId) {
            "Cross-domain transfer requires distinct structural domains"
        }

        val descriptor = requireNotNull(registry.descriptor(sourceProfile.strategyId)) {
            "Source performance profile references unknown strategy"
        }
        require(descriptor.fingerprint == sourceProfile.strategyDescriptorFingerprint) {
            "Source performance profile strategy descriptor is stale or substituted"
        }

        val verifiedSamples =
            sourceProfile.verifiedSuccessCount + sourceProfile.verifiedFailureCount
        require(verifiedSamples > 0 && sourceProfile.empiricalSuccessRate != null) {
            "Inconclusive-only source performance cannot seed transfer"
        }

        val validationFingerprint = crossDomainTransferFingerprint(
            "cross-domain-structural-validation/v1",
            sourceProfile.fingerprint,
            sourceProblemClass.fingerprint,
            targetProblemClass.fingerprint,
            sourceSignature.fingerprint(),
            targetSignature.fingerprint(),
            descriptor.fingerprint,
        )
        val candidate = similarityEngine.candidate(
            source = sourceSignature,
            target = targetSignature,
            validationFingerprint = validationFingerprint,
        )
        return proposeFromCandidate(
            sourceProfile = sourceProfile,
            sourceProblemClass = sourceProblemClass,
            targetProblemClass = targetProblemClass,
            sourceSignature = sourceSignature,
            targetSignature = targetSignature,
            candidate = candidate,
        )
    }

    fun proposeFromCandidate(
        sourceProfile: ReasoningStrategyPerformanceProfile,
        sourceProblemClass: ReasoningProblemClass,
        targetProblemClass: ReasoningProblemClass,
        sourceSignature: StructuralSignature,
        targetSignature: StructuralSignature,
        candidate: StructuralTransferCandidate,
    ): CrossDomainReasoningTransferHypothesis? {
        require(sourceProfile.problemClassFingerprint == sourceProblemClass.fingerprint)
        require(sourceProblemClass.fingerprint != targetProblemClass.fingerprint)
        require(sourceSignature.domainId != targetSignature.domainId)
        require(candidate.source == sourceSignature) {
            "Structural transfer candidate source was substituted"
        }
        require(candidate.target == targetSignature) {
            "Structural transfer candidate target was substituted"
        }
        require(
            candidate.structuralSimilarity ==
                similarityEngine.similarity(sourceSignature, targetSignature)
        ) {
            "Structural transfer candidate similarity does not match existing Level7 engine"
        }
        require(!candidate.semanticIdentityEstablished)
        require(!candidate.directTransferActivationAllowed)

        val descriptor = requireNotNull(registry.descriptor(sourceProfile.strategyId))
        require(descriptor.fingerprint == sourceProfile.strategyDescriptorFingerprint)
        val verifiedSamples =
            sourceProfile.verifiedSuccessCount + sourceProfile.verifiedFailureCount
        val sourceRate = sourceProfile.empiricalSuccessRate
        require(verifiedSamples > 0 && sourceRate != null) {
            "Inconclusive-only source performance cannot seed transfer"
        }

        if (candidate.structuralSimilarity < minimumStructuralSimilarity) return null

        val supportDiscount = verifiedSamples.toDouble() /
            (verifiedSamples + requiredTargetValidationSamples).toDouble()
        val transferredPrior = (
            sourceRate *
                candidate.structuralSimilarity *
                supportDiscount
            ).coerceIn(0.0, sourceRate)

        return CrossDomainReasoningTransferHypothesis(
            sourcePerformanceProfileFingerprint = sourceProfile.fingerprint,
            strategyId = sourceProfile.strategyId,
            strategyDescriptorFingerprint = sourceProfile.strategyDescriptorFingerprint,
            structuralTransferCandidateId = candidate.id,
            structuralTransferCandidateFingerprint = candidate.fingerprint(),
            sourceProblemClassFingerprint = sourceProblemClass.fingerprint,
            targetProblemClassFingerprint = targetProblemClass.fingerprint,
            targetStructuralSignatureFingerprint = targetSignature.fingerprint(),
            sourceVerifiedSampleCount = verifiedSamples,
            structuralSimilarity = candidate.structuralSimilarity,
            transferredSuccessPrior = transferredPrior,
            requiredTargetValidationSamples = requiredTargetValidationSamples,
            validationStatus = CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED,
            fingerprint = transferHypothesisFingerprint(
                sourcePerformanceProfileFingerprint = sourceProfile.fingerprint,
                strategyId = sourceProfile.strategyId,
                strategyDescriptorFingerprint = sourceProfile.strategyDescriptorFingerprint,
                structuralTransferCandidateId = candidate.id,
                structuralTransferCandidateFingerprint = candidate.fingerprint(),
                sourceProblemClassFingerprint = sourceProblemClass.fingerprint,
                targetProblemClassFingerprint = targetProblemClass.fingerprint,
                targetStructuralSignatureFingerprint = targetSignature.fingerprint(),
                sourceVerifiedSampleCount = verifiedSamples,
                structuralSimilarity = candidate.structuralSimilarity,
                transferredSuccessPrior = transferredPrior,
                requiredTargetValidationSamples = requiredTargetValidationSamples,
                validationStatus =
                    CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED,
            ),
        )
    }

    fun validateTargetEvidence(
        hypothesis: CrossDomainReasoningTransferHypothesis,
        targetProfile: ReasoningStrategyPerformanceProfile?,
    ): CrossDomainTransferValidation {
        if (targetProfile == null) {
            return validation(
                hypothesis = hypothesis,
                status = CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED,
                targetProfileFingerprint = null,
                verifiedSamples = 0,
            )
        }

        require(targetProfile.strategyId == hypothesis.strategyId) {
            "Target evidence belongs to another strategy"
        }
        require(
            targetProfile.strategyDescriptorFingerprint ==
                hypothesis.strategyDescriptorFingerprint
        ) {
            "Target evidence strategy descriptor was substituted"
        }
        require(
            targetProfile.problemClassFingerprint ==
                hypothesis.targetProblemClassFingerprint
        ) {
            "Target evidence belongs to another problem class"
        }

        val verifiedSamples =
            targetProfile.verifiedSuccessCount + targetProfile.verifiedFailureCount
        val satisfied =
            verifiedSamples >= hypothesis.requiredTargetValidationSamples &&
                targetProfile.empiricalSuccessRate != null

        return validation(
            hypothesis = hypothesis,
            status =
                if (satisfied) {
                    CrossDomainTransferValidationStatus.TARGET_EVIDENCE_SATISFIED
                } else {
                    CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED
                },
            targetProfileFingerprint =
                if (satisfied) targetProfile.fingerprint else null,
            verifiedSamples = verifiedSamples,
        )
    }

    private fun validation(
        hypothesis: CrossDomainReasoningTransferHypothesis,
        status: CrossDomainTransferValidationStatus,
        targetProfileFingerprint: String?,
        verifiedSamples: Int,
    ): CrossDomainTransferValidation =
        CrossDomainTransferValidation(
            hypothesisFingerprint = hypothesis.fingerprint,
            status = status,
            targetPerformanceProfileFingerprint = targetProfileFingerprint,
            targetVerifiedSampleCount = verifiedSamples,
            fingerprint = transferValidationFingerprint(
                hypothesisFingerprint = hypothesis.fingerprint,
                status = status,
                targetPerformanceProfileFingerprint = targetProfileFingerprint,
                targetVerifiedSampleCount = verifiedSamples,
            ),
        )
}

private fun transferHypothesisFingerprint(
    sourcePerformanceProfileFingerprint: String,
    strategyId: ReasoningStrategyId,
    strategyDescriptorFingerprint: String,
    structuralTransferCandidateId: String,
    structuralTransferCandidateFingerprint: String,
    sourceProblemClassFingerprint: String,
    targetProblemClassFingerprint: String,
    targetStructuralSignatureFingerprint: String,
    sourceVerifiedSampleCount: Int,
    structuralSimilarity: Double,
    transferredSuccessPrior: Double,
    requiredTargetValidationSamples: Int,
    validationStatus: CrossDomainTransferValidationStatus,
): String = crossDomainTransferFingerprint(
    "cross-domain-reasoning-transfer-hypothesis/v1",
    sourcePerformanceProfileFingerprint,
    strategyId.value,
    strategyDescriptorFingerprint,
    structuralTransferCandidateId,
    structuralTransferCandidateFingerprint,
    sourceProblemClassFingerprint,
    targetProblemClassFingerprint,
    targetStructuralSignatureFingerprint,
    sourceVerifiedSampleCount.toString(),
    java.lang.Double.toHexString(structuralSimilarity),
    java.lang.Double.toHexString(transferredSuccessPrior),
    requiredTargetValidationSamples.toString(),
    validationStatus.name,
)

private fun transferValidationFingerprint(
    hypothesisFingerprint: String,
    status: CrossDomainTransferValidationStatus,
    targetPerformanceProfileFingerprint: String?,
    targetVerifiedSampleCount: Int,
): String = crossDomainTransferFingerprint(
    "cross-domain-transfer-validation/v1",
    hypothesisFingerprint,
    status.name,
    targetPerformanceProfileFingerprint.orEmpty(),
    targetVerifiedSampleCount.toString(),
)

private fun crossDomainTransferFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain, *parts).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

private const val DEFAULT_MINIMUM_STRUCTURAL_SIMILARITY = 2.0 / 3.0
private const val DEFAULT_TARGET_VALIDATION_SAMPLES = 3
private const val MAX_TARGET_VALIDATION_SAMPLES = 128

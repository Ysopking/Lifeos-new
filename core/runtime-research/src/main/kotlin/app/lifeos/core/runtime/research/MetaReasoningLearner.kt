package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ReasoningComplexityBucket {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

data class ReasoningProblemClass(
    val goalSemanticClass: String,
    val constraintCount: ReasoningComplexityBucket,
    val factCount: ReasoningComplexityBucket,
    val unknownCount: ReasoningComplexityBucket,
    val assumptionCount: ReasoningComplexityBucket,
    val gapKinds: List<KnowledgeGapKind>,
    val fingerprint: String,
) {
    init {
        require(goalSemanticClass.isNotBlank())
        require(goalSemanticClass.length <= 128)
        require(gapKinds == gapKinds.distinct().sortedBy { it.name })
        require(
            fingerprint == problemClassFingerprint(
                goalSemanticClass,
                constraintCount,
                factCount,
                unknownCount,
                assumptionCount,
                gapKinds,
            )
        )
    }

    companion object {
        fun create(
            goalSemanticClass: String,
            constraintCount: Int,
            factCount: Int,
            unknownCount: Int,
            assumptionCount: Int,
            gapKinds: Collection<KnowledgeGapKind>,
        ): ReasoningProblemClass {
            require(constraintCount >= 0)
            require(factCount >= 0)
            require(unknownCount >= 0)
            require(assumptionCount >= 0)
            val canonicalGaps = gapKinds.distinct().sortedBy { it.name }
            val constraints = bucket(constraintCount)
            val facts = bucket(factCount)
            val unknowns = bucket(unknownCount)
            val assumptions = bucket(assumptionCount)
            return ReasoningProblemClass(
                goalSemanticClass = goalSemanticClass.trim(),
                constraintCount = constraints,
                factCount = facts,
                unknownCount = unknowns,
                assumptionCount = assumptions,
                gapKinds = canonicalGaps,
                fingerprint = problemClassFingerprint(
                    goalSemanticClass.trim(),
                    constraints,
                    facts,
                    unknowns,
                    assumptions,
                    canonicalGaps,
                ),
            )
        }

        private fun bucket(value: Int): ReasoningComplexityBucket = when (value) {
            0 -> ReasoningComplexityBucket.NONE
            in 1..2 -> ReasoningComplexityBucket.LOW
            in 3..7 -> ReasoningComplexityBucket.MEDIUM
            else -> ReasoningComplexityBucket.HIGH
        }
    }
}

enum class ReasoningStrategyOutcomeState {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    INCONCLUSIVE,
}

data class ReasoningStrategyOutcomeEpisode(
    val id: String,
    val strategyId: ReasoningStrategyId,
    val strategyDescriptorFingerprint: String,
    val problemClassFingerprint: String,
    val sourceCycleId: String,
    val state: ReasoningStrategyOutcomeState,
    val verificationFingerprint: String?,
    val normalizedEffort: Double,
    val uncertaintyReduction: Double?,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(strategyDescriptorFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(problemClassFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceCycleId.isNotBlank())
        require(normalizedEffort.isFinite() && normalizedEffort in 0.0..1.0)
        uncertaintyReduction?.let {
            require(it.isFinite() && it in 0.0..1.0)
        }
        when (state) {
            ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
            ReasoningStrategyOutcomeState.VERIFIED_FAILURE,
            -> require(
                verificationFingerprint?.matches(Regex("[0-9a-f]{64}")) == true
            ) {
                "Verified strategy outcome requires exact verification fingerprint"
            }

            ReasoningStrategyOutcomeState.INCONCLUSIVE ->
                require(verificationFingerprint == null || verificationFingerprint.matches(Regex("[0-9a-f]{64}")))
        }
        require(
            fingerprint == outcomeEpisodeFingerprint(
                strategyId,
                strategyDescriptorFingerprint,
                problemClassFingerprint,
                sourceCycleId,
                state,
                verificationFingerprint,
                normalizedEffort,
                uncertaintyReduction,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val selectionAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "reasoning-strategy-outcome:"

        fun create(
            strategy: ReasoningStrategyDescriptor,
            problemClass: ReasoningProblemClass,
            sourceCycleId: String,
            state: ReasoningStrategyOutcomeState,
            verificationFingerprint: String? = null,
            normalizedEffort: Double,
            uncertaintyReduction: Double? = null,
        ): ReasoningStrategyOutcomeEpisode {
            val fingerprint = outcomeEpisodeFingerprint(
                strategy.id,
                strategy.fingerprint,
                problemClass.fingerprint,
                sourceCycleId,
                state,
                verificationFingerprint,
                normalizedEffort,
                uncertaintyReduction,
            )
            return ReasoningStrategyOutcomeEpisode(
                id = ID_PREFIX + fingerprint,
                strategyId = strategy.id,
                strategyDescriptorFingerprint = strategy.fingerprint,
                problemClassFingerprint = problemClass.fingerprint,
                sourceCycleId = sourceCycleId,
                state = state,
                verificationFingerprint = verificationFingerprint,
                normalizedEffort = normalizedEffort,
                uncertaintyReduction = uncertaintyReduction,
                fingerprint = fingerprint,
            )
        }
    }
}

data class ReasoningStrategyPerformanceProfile(
    val strategyId: ReasoningStrategyId,
    val strategyDescriptorFingerprint: String,
    val problemClassFingerprint: String,
    val verifiedSuccessCount: Int,
    val verifiedFailureCount: Int,
    val inconclusiveCount: Int,
    val empiricalSuccessRate: Double?,
    val meanVerifiedUncertaintyReduction: Double?,
    val meanNormalizedEffort: Double,
    val episodeIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(strategyDescriptorFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(problemClassFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(verifiedSuccessCount >= 0)
        require(verifiedFailureCount >= 0)
        require(inconclusiveCount >= 0)
        val verified = verifiedSuccessCount + verifiedFailureCount
        if (verified == 0) require(empiricalSuccessRate == null)
        else require(empiricalSuccessRate != null && empiricalSuccessRate.isFinite() && empiricalSuccessRate in 0.0..1.0)
        meanVerifiedUncertaintyReduction?.let {
            require(it.isFinite() && it in 0.0..1.0)
        }
        require(meanNormalizedEffort.isFinite() && meanNormalizedEffort in 0.0..1.0)
        require(episodeIds == episodeIds.distinct().sorted())
        require(
            fingerprint == performanceProfileFingerprint(
                strategyId = strategyId,
                strategyDescriptorFingerprint = strategyDescriptorFingerprint,
                problemClassFingerprint = problemClassFingerprint,
                verifiedSuccessCount = verifiedSuccessCount,
                verifiedFailureCount = verifiedFailureCount,
                inconclusiveCount = inconclusiveCount,
                empiricalSuccessRate = empiricalSuccessRate,
                meanVerifiedUncertaintyReduction = meanVerifiedUncertaintyReduction,
                meanNormalizedEffort = meanNormalizedEffort,
                episodeIds = episodeIds,
            )
        )
    }

    val selectionAuthority: Boolean
        get() = false

    val ownerUtilityAuthority: Boolean
        get() = false

    val promotionAuthority: Boolean
        get() = false
}

/**
 * B384 empirical performance learner over exact B383 reasoning-method descriptors.
 *
 * It summarizes supplied verified/inconclusive episodes only. It does not infer causal credit,
 * transfer performance to another problem class, select a strategy, or alter Owner Utility.
 */
class MetaReasoningLearner(
    private val registry: ReasoningStrategyRegistry,
) {
    fun learn(
        problemClass: ReasoningProblemClass,
        episodes: Collection<ReasoningStrategyOutcomeEpisode>,
    ): List<ReasoningStrategyPerformanceProfile> {
        val canonical = episodes
            .distinctBy { it.id }
            .sortedBy { it.id }

        canonical.forEach { episode ->
            require(episode.problemClassFingerprint == problemClass.fingerprint) {
                "Meta-reasoning episode belongs to another problem class"
            }
            val descriptor = requireNotNull(registry.descriptor(episode.strategyId)) {
                "Meta-reasoning episode references unknown strategy"
            }
            require(descriptor.fingerprint == episode.strategyDescriptorFingerprint) {
                "Meta-reasoning episode strategy descriptor is stale or substituted"
            }
        }

        return canonical
            .groupBy { it.strategyId }
            .toSortedMap(compareBy { it.value })
            .map { (strategyId, group) ->
                profile(strategyId, problemClass, group)
            }
    }

    private fun profile(
        strategyId: ReasoningStrategyId,
        problemClass: ReasoningProblemClass,
        episodes: List<ReasoningStrategyOutcomeEpisode>,
    ): ReasoningStrategyPerformanceProfile {
        val descriptor = requireNotNull(registry.descriptor(strategyId))
        val successes = episodes.count { it.state == ReasoningStrategyOutcomeState.VERIFIED_SUCCESS }
        val failures = episodes.count { it.state == ReasoningStrategyOutcomeState.VERIFIED_FAILURE }
        val inconclusive = episodes.count { it.state == ReasoningStrategyOutcomeState.INCONCLUSIVE }
        val verifiedCount = successes + failures
        val successRate = if (verifiedCount == 0) null else successes.toDouble() / verifiedCount
        val reductions = episodes
            .filter { it.state != ReasoningStrategyOutcomeState.INCONCLUSIVE }
            .mapNotNull { it.uncertaintyReduction }
        val meanReduction = reductions.takeIf { it.isNotEmpty() }?.average()
        val meanEffort = episodes.map { it.normalizedEffort }.average()
        val ids = episodes.map { it.id }.distinct().sorted()
        return ReasoningStrategyPerformanceProfile(
            strategyId = strategyId,
            strategyDescriptorFingerprint = descriptor.fingerprint,
            problemClassFingerprint = problemClass.fingerprint,
            verifiedSuccessCount = successes,
            verifiedFailureCount = failures,
            inconclusiveCount = inconclusive,
            empiricalSuccessRate = successRate,
            meanVerifiedUncertaintyReduction = meanReduction,
            meanNormalizedEffort = meanEffort,
            episodeIds = ids,
            fingerprint = performanceProfileFingerprint(
                strategyId = strategyId,
                strategyDescriptorFingerprint = descriptor.fingerprint,
                problemClassFingerprint = problemClass.fingerprint,
                verifiedSuccessCount = successes,
                verifiedFailureCount = failures,
                inconclusiveCount = inconclusive,
                empiricalSuccessRate = successRate,
                meanVerifiedUncertaintyReduction = meanReduction,
                meanNormalizedEffort = meanEffort,
                episodeIds = ids,
            ),
        )
    }
}

private fun problemClassFingerprint(
    goalSemanticClass: String,
    constraintCount: ReasoningComplexityBucket,
    factCount: ReasoningComplexityBucket,
    unknownCount: ReasoningComplexityBucket,
    assumptionCount: ReasoningComplexityBucket,
    gapKinds: List<KnowledgeGapKind>,
): String = metaReasoningFingerprint(
    "reasoning-problem-class/v1",
    goalSemanticClass,
    constraintCount.name,
    factCount.name,
    unknownCount.name,
    assumptionCount.name,
    *gapKinds.map { it.name }.toTypedArray(),
)

private fun outcomeEpisodeFingerprint(
    strategyId: ReasoningStrategyId,
    strategyDescriptorFingerprint: String,
    problemClassFingerprint: String,
    sourceCycleId: String,
    state: ReasoningStrategyOutcomeState,
    verificationFingerprint: String?,
    normalizedEffort: Double,
    uncertaintyReduction: Double?,
): String = metaReasoningFingerprint(
    "reasoning-strategy-outcome/v1",
    strategyId.value,
    strategyDescriptorFingerprint,
    problemClassFingerprint,
    sourceCycleId,
    state.name,
    verificationFingerprint.orEmpty(),
    java.lang.Double.toHexString(normalizedEffort),
    uncertaintyReduction?.let(java.lang.Double::toHexString).orEmpty(),
)

private fun performanceProfileFingerprint(
    strategyId: ReasoningStrategyId,
    strategyDescriptorFingerprint: String,
    problemClassFingerprint: String,
    verifiedSuccessCount: Int,
    verifiedFailureCount: Int,
    inconclusiveCount: Int,
    empiricalSuccessRate: Double?,
    meanVerifiedUncertaintyReduction: Double?,
    meanNormalizedEffort: Double,
    episodeIds: List<String>,
): String = metaReasoningFingerprint(
    "reasoning-strategy-performance-profile/v1",
    strategyId.value,
    strategyDescriptorFingerprint,
    problemClassFingerprint,
    verifiedSuccessCount.toString(),
    verifiedFailureCount.toString(),
    inconclusiveCount.toString(),
    empiricalSuccessRate?.let(java.lang.Double::toHexString).orEmpty(),
    meanVerifiedUncertaintyReduction?.let(java.lang.Double::toHexString).orEmpty(),
    java.lang.Double.toHexString(meanNormalizedEffort),
    *episodeIds.toTypedArray(),
)

private fun metaReasoningFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

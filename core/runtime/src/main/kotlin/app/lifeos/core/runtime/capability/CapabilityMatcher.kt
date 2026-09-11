package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.workers.WorkerCandidateQuery
import app.lifeos.core.runtime.workers.WorkerExclusionReason
import app.lifeos.core.runtime.workers.WorkerRegistry
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

data class CapabilityExecutionHistory(
    val successes: Long = 0,
    val failures: Long = 0,
) {
    init {
        require(successes >= 0L && failures >= 0L) {
            "Capability execution history counts must not be negative"
        }
        require(successes <= Long.MAX_VALUE - failures) {
            "Capability execution history count overflow"
        }
    }

    val samples: Long
        get() = successes + failures

    val successRate: Double
        get() = if (samples == 0L) 0.5 else successes.toDouble() / samples.toDouble()
}

data class CapabilityProviderProfile(
    val grantedPermissions: Set<String> = emptySet(),
    val estimatedLatencyMillis: Long? = null,
    val history: CapabilityExecutionHistory = CapabilityExecutionHistory(),
) {
    init {
        require(grantedPermissions.none { it.isBlank() }) {
            "Capability permissions must not be blank"
        }
        require(estimatedLatencyMillis == null || estimatedLatencyMillis >= 0L) {
            "Estimated capability latency must not be negative"
        }
    }
}

interface CapabilityProviderProfileSource {
    suspend fun profileFor(provider: CapabilityDescriptor): CapabilityProviderProfile
}

object EmptyCapabilityProviderProfileSource : CapabilityProviderProfileSource {
    override suspend fun profileFor(provider: CapabilityDescriptor): CapabilityProviderProfile =
        CapabilityProviderProfile()
}

data class CapabilityScoreWeights(
    val trust: Double = 0.25,
    val reliability: Double = 0.25,
    val cost: Double = 0.20,
    val latency: Double = 0.15,
    val history: Double = 0.15,
) {
    init {
        val values = listOf(trust, reliability, cost, latency, history)
        require(values.all { it.isFinite() && it >= 0.0 }) {
            "Capability score weights must be finite and non-negative"
        }
        require(values.sum() > 0.0) { "At least one capability score weight must be positive" }
    }

    internal val total: Double
        get() = trust + reliability + cost + latency + history
}

data class CapabilityMatchRequest(
    val requirement: CapabilityRequirement,
    val requiredPermissions: Set<String> = emptySet(),
    val minimumTrustLevel: TrustLevel = TrustLevel.LOW,
    val allowDegradedProviders: Boolean = true,
    val allowUnknownWorkerHealth: Boolean = false,
    val maxCost: Double? = null,
    val maxLatencyMillis: Long? = null,
    val weights: CapabilityScoreWeights = CapabilityScoreWeights(),
) {
    init {
        require(requiredPermissions.none { it.isBlank() }) {
            "Required capability permissions must not be blank"
        }
        require(maxCost == null || (maxCost.isFinite() && maxCost >= 0.0)) {
            "Maximum capability cost must be finite and non-negative"
        }
        require(maxLatencyMillis == null || maxLatencyMillis >= 0L) {
            "Maximum capability latency must not be negative"
        }
    }
}

enum class CapabilityFilterReason {
    PROVIDER_UNAVAILABLE,
    CONTRACT_MISMATCH,
    PERMISSION_MISSING,
    TRUST_TOO_LOW,
    COST_EXCEEDED,
    LATENCY_UNKNOWN,
    LATENCY_EXCEEDED,
    PROFILE_UNAVAILABLE,
    WORKER_NOT_REGISTERED,
    WORKER_CAPABILITY_NOT_DECLARED,
    WORKER_STATE_NOT_RUNNABLE,
    WORKER_SATURATED,
    WORKER_HEALTH_NOT_ELIGIBLE,
}

data class CapabilityProviderExclusion(
    val provider: CapabilityDescriptor,
    val reasons: List<CapabilityFilterReason>,
    val detail: String? = null,
) {
    init {
        require(reasons.isNotEmpty()) { "Excluded capability provider must have a reason" }
        require(reasons == reasons.distinct().sortedBy { it.ordinal }) {
            "Capability exclusion reasons must be unique and deterministically ordered"
        }
    }
}

enum class CapabilityScoreComponent {
    TRUST,
    RELIABILITY,
    COST,
    LATENCY,
    HISTORY,
}

data class CapabilityScoreTerm(
    val component: CapabilityScoreComponent,
    val rawScore: Double,
    val weight: Double,
    val contribution: Double,
) {
    init {
        require(rawScore.isFinite() && rawScore in 0.0..1.0) {
            "Capability component score must be finite and normalized"
        }
        require(weight.isFinite() && weight >= 0.0) {
            "Capability component weight must be finite and non-negative"
        }
        require(contribution.isFinite() && contribution >= 0.0) {
            "Capability component contribution must be finite and non-negative"
        }
    }
}

data class CapabilityProviderScore(
    val provider: CapabilityDescriptor,
    val profile: CapabilityProviderProfile,
    val terms: List<CapabilityScoreTerm>,
    val totalScore: Double,
) {
    init {
        require(terms.map { it.component } == CapabilityScoreComponent.entries) {
            "Capability score trace must contain every component exactly once in canonical order"
        }
        require(totalScore.isFinite() && totalScore in 0.0..1.0) {
            "Capability total score must be finite and normalized"
        }
    }
}

enum class CapabilityUnresolvedReason {
    EQUAL_TOP_SCORE,
}

sealed interface CapabilityMatchResult {
    val request: CapabilityMatchRequest
    val rankedCandidates: List<CapabilityProviderScore>
    val excluded: List<CapabilityProviderExclusion>

    data class Selected(
        override val request: CapabilityMatchRequest,
        val selected: CapabilityProviderScore,
        override val rankedCandidates: List<CapabilityProviderScore>,
        override val excluded: List<CapabilityProviderExclusion>,
    ) : CapabilityMatchResult

    data class Unresolved(
        override val request: CapabilityMatchRequest,
        val reason: CapabilityUnresolvedReason,
        val tiedCandidates: List<CapabilityProviderScore>,
        override val rankedCandidates: List<CapabilityProviderScore>,
        override val excluded: List<CapabilityProviderExclusion>,
    ) : CapabilityMatchResult {
        init {
            require(tiedCandidates.size >= 2) { "Unresolved match requires at least two tied candidates" }
        }
    }

    data class Unavailable(
        override val request: CapabilityMatchRequest,
        override val excluded: List<CapabilityProviderExclusion>,
    ) : CapabilityMatchResult {
        override val rankedCandidates: List<CapabilityProviderScore> = emptyList()
    }
}

/**
 * Deterministic capability selection with explicit filtering and a complete score trace.
 * Equal top scores are deliberately returned as UNRESOLVED rather than broken by provider id.
 */
class CapabilityMatcher(
    private val registry: CapabilityRegistry,
    private val workerRegistry: WorkerRegistry? = null,
    private val profiles: CapabilityProviderProfileSource = EmptyCapabilityProviderProfileSource,
    private val reliability: CapabilityReliabilityResolver = DescriptorCapabilityReliabilityResolver,
    private val tieTolerance: Double = 1e-12,
) {
    init {
        require(tieTolerance.isFinite() && tieTolerance >= 0.0) {
            "Capability matcher tie tolerance must be finite and non-negative"
        }
    }

    suspend fun match(request: CapabilityMatchRequest): CapabilityMatchResult {
        val providers = registry
            .providersFor(request.requirement.capabilityId, includeUnavailable = true)
            .sortedBy { it.providerId }

        val workerAvailability = if (
            workerRegistry != null && providers.any { it.providerType == ProviderType.WORKER }
        ) {
            workerRegistry.query(
                WorkerCandidateQuery(
                    capabilityId = request.requirement.capabilityId,
                    allowDegradedHealth = request.allowDegradedProviders,
                    allowUnknownHealth = request.allowUnknownWorkerHealth,
                )
            )
        } else {
            null
        }
        val eligibleWorkerIds = workerAvailability
            ?.candidates
            ?.mapTo(mutableSetOf()) { it.entry.descriptor.workerId.value }
            .orEmpty()
        val excludedWorkers = workerAvailability
            ?.excluded
            ?.associateBy { it.entry.descriptor.workerId.value }
            .orEmpty()

        val exclusions = mutableListOf<CapabilityProviderExclusion>()
        val scored = mutableListOf<CapabilityProviderScore>()

        for (provider in providers) {
            val staticReasons = mutableListOf<CapabilityFilterReason>()
            if (!providerStateEligible(provider.state, request.allowDegradedProviders)) {
                staticReasons += CapabilityFilterReason.PROVIDER_UNAVAILABLE
            }
            if (!contractCompatible(provider, request.requirement)) {
                staticReasons += CapabilityFilterReason.CONTRACT_MISMATCH
            }
            if (trustRank(provider.trustLevel) < trustRank(request.minimumTrustLevel)) {
                staticReasons += CapabilityFilterReason.TRUST_TOO_LOW
            }
            if (request.maxCost != null && provider.cost > request.maxCost) {
                staticReasons += CapabilityFilterReason.COST_EXCEEDED
            }
            if (provider.providerType == ProviderType.WORKER) {
                if (workerRegistry == null) {
                    staticReasons += CapabilityFilterReason.WORKER_NOT_REGISTERED
                } else if (provider.providerId !in eligibleWorkerIds) {
                    val workerExclusion = excludedWorkers[provider.providerId]
                    if (workerExclusion == null) {
                        staticReasons += CapabilityFilterReason.WORKER_NOT_REGISTERED
                    } else {
                        staticReasons += workerExclusion.reasons.map(::workerReason)
                    }
                }
            }

            if (staticReasons.isNotEmpty()) {
                exclusions += exclusion(provider, staticReasons)
                continue
            }

            val profile = try {
                profiles.profileFor(provider)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                exclusions += exclusion(
                    provider = provider,
                    reasons = listOf(CapabilityFilterReason.PROFILE_UNAVAILABLE),
                    detail = error.message ?: error::class.simpleName,
                )
                continue
            }

            val profileReasons = mutableListOf<CapabilityFilterReason>()
            if (!profile.grantedPermissions.containsAll(request.requiredPermissions)) {
                profileReasons += CapabilityFilterReason.PERMISSION_MISSING
            }
            request.maxLatencyMillis?.let { maximum ->
                val latency = profile.estimatedLatencyMillis
                when {
                    latency == null -> profileReasons += CapabilityFilterReason.LATENCY_UNKNOWN
                    latency > maximum -> profileReasons += CapabilityFilterReason.LATENCY_EXCEEDED
                }
            }
            if (profileReasons.isNotEmpty()) {
                exclusions += exclusion(provider, profileReasons)
                continue
            }

            scored += score(provider, profile, request.weights)
        }

        val ranked = scored.sortedWith(
            compareByDescending<CapabilityProviderScore> { it.totalScore }
                .thenBy { it.provider.providerId }
        )
        val canonicalExcluded = exclusions.sortedBy { it.provider.providerId }
        if (ranked.isEmpty()) {
            return CapabilityMatchResult.Unavailable(
                request = request,
                excluded = canonicalExcluded,
            )
        }

        val bestScore = ranked.first().totalScore
        val tied = ranked.filter { abs(it.totalScore - bestScore) <= tieTolerance }
        return if (tied.size > 1) {
            CapabilityMatchResult.Unresolved(
                request = request,
                reason = CapabilityUnresolvedReason.EQUAL_TOP_SCORE,
                tiedCandidates = tied,
                rankedCandidates = ranked,
                excluded = canonicalExcluded,
            )
        } else {
            CapabilityMatchResult.Selected(
                request = request,
                selected = ranked.first(),
                rankedCandidates = ranked,
                excluded = canonicalExcluded,
            )
        }
    }

    private fun score(
        provider: CapabilityDescriptor,
        profile: CapabilityProviderProfile,
        weights: CapabilityScoreWeights,
    ): CapabilityProviderScore {
        val effectiveReliability = reliability.resolve(provider)
        require(effectiveReliability.isFinite() && effectiveReliability in 0.0..1.0) {
            "Effective capability reliability must be normalized"
        }
        val raw = listOf(
            CapabilityScoreComponent.TRUST to trustScore(provider.trustLevel),
            CapabilityScoreComponent.RELIABILITY to effectiveReliability,
            CapabilityScoreComponent.COST to (1.0 / (1.0 + provider.cost)),
            CapabilityScoreComponent.LATENCY to latencyScore(profile.estimatedLatencyMillis),
            CapabilityScoreComponent.HISTORY to profile.history.successRate,
        )
        val componentWeights = mapOf(
            CapabilityScoreComponent.TRUST to weights.trust,
            CapabilityScoreComponent.RELIABILITY to weights.reliability,
            CapabilityScoreComponent.COST to weights.cost,
            CapabilityScoreComponent.LATENCY to weights.latency,
            CapabilityScoreComponent.HISTORY to weights.history,
        )
        val terms = raw.map { (component, rawScore) ->
            val normalizedWeight = componentWeights.getValue(component) / weights.total
            CapabilityScoreTerm(
                component = component,
                rawScore = rawScore,
                weight = normalizedWeight,
                contribution = rawScore * normalizedWeight,
            )
        }
        return CapabilityProviderScore(
            provider = provider,
            profile = profile,
            terms = terms,
            totalScore = terms.sumOf { it.contribution }.coerceIn(0.0, 1.0),
        )
    }

    private fun contractCompatible(
        provider: CapabilityDescriptor,
        requirement: CapabilityRequirement,
    ): Boolean = requirement.requiredInputs.containsAll(provider.contract.requiredInputs) &&
        provider.contract.outputs.containsAll(requirement.requiredOutputs)

    private fun providerStateEligible(state: ProviderState, allowDegraded: Boolean): Boolean = when (state) {
        ProviderState.ACTIVE -> true
        ProviderState.DEGRADED -> allowDegraded
        ProviderState.QUARANTINED,
        ProviderState.DISABLED -> false
    }

    private fun exclusion(
        provider: CapabilityDescriptor,
        reasons: List<CapabilityFilterReason>,
        detail: String? = null,
    ): CapabilityProviderExclusion = CapabilityProviderExclusion(
        provider = provider,
        reasons = reasons.distinct().sortedBy { it.ordinal },
        detail = detail,
    )

    private fun workerReason(reason: WorkerExclusionReason): CapabilityFilterReason = when (reason) {
        WorkerExclusionReason.CAPABILITY_MISSING -> CapabilityFilterReason.WORKER_CAPABILITY_NOT_DECLARED
        WorkerExclusionReason.STATE_NOT_RUNNABLE -> CapabilityFilterReason.WORKER_STATE_NOT_RUNNABLE
        WorkerExclusionReason.SATURATED -> CapabilityFilterReason.WORKER_SATURATED
        WorkerExclusionReason.HEALTH_NOT_ELIGIBLE -> CapabilityFilterReason.WORKER_HEALTH_NOT_ELIGIBLE
    }

    private fun trustRank(level: TrustLevel): Int = when (level) {
        TrustLevel.LOW -> 0
        TrustLevel.MEDIUM -> 1
        TrustLevel.HIGH -> 2
        TrustLevel.SYSTEM -> 3
    }

    private fun trustScore(level: TrustLevel): Double = when (level) {
        TrustLevel.LOW -> 0.25
        TrustLevel.MEDIUM -> 0.50
        TrustLevel.HIGH -> 0.75
        TrustLevel.SYSTEM -> 1.0
    }

    private fun latencyScore(latencyMillis: Long?): Double = latencyMillis
        ?.let { 1.0 / (1.0 + it.toDouble() / 1_000.0) }
        ?: 0.5
}

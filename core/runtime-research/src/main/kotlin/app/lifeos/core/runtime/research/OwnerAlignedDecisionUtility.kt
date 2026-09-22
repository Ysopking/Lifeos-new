package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class OwnerPolicyEligibility {
    NOT_REQUIRED,
    ALLOWED,
    BLOCKED,
}

data class OwnerAlignedDecisionCandidate(
    val id: String,
    val goalPlanFingerprint: String,
    val epistemicDecisionFingerprint: String,
    val utilityMeasures: Map<OwnerUtilityDimension, Double>,
    val ownerPolicyEligibility: OwnerPolicyEligibility,
    val ownerPolicyDecisionFingerprint: String?,
    val externalEffectRequired: Boolean,
    val fingerprint: String,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(goalPlanFingerprint.matches(SHA_256_REGEX))
        require(epistemicDecisionFingerprint.matches(SHA_256_REGEX))
        require(utilityMeasures.isNotEmpty())
        require(utilityMeasures.size <= OwnerUtilityDimension.entries.size)
        utilityMeasures.forEach { (_, value) ->
            require(value.isFinite() && value in 0.0..1.0)
        }
        when (ownerPolicyEligibility) {
            OwnerPolicyEligibility.NOT_REQUIRED ->
                require(ownerPolicyDecisionFingerprint == null)
            OwnerPolicyEligibility.ALLOWED,
            OwnerPolicyEligibility.BLOCKED,
            -> require(ownerPolicyDecisionFingerprint?.matches(SHA_256_REGEX) == true)
        }
        if (externalEffectRequired) {
            require(ownerPolicyEligibility != OwnerPolicyEligibility.NOT_REQUIRED) {
                "External-effect candidate requires an authoritative Owner Policy decision"
            }
        }
        require(
            fingerprint == decisionCandidateFingerprint(
                goalPlanFingerprint = goalPlanFingerprint,
                epistemicDecisionFingerprint = epistemicDecisionFingerprint,
                utilityMeasures = utilityMeasures,
                ownerPolicyEligibility = ownerPolicyEligibility,
                ownerPolicyDecisionFingerprint = ownerPolicyDecisionFingerprint,
                externalEffectRequired = externalEffectRequired,
            )
        )
        require(id == ID_PREFIX + fingerprint)
    }

    val executionAuthority: Boolean
        get() = false

    companion object {
        const val ID_PREFIX = "owner-aligned-decision-candidate:"

        fun create(
            goalPlanFingerprint: String,
            epistemicDecisionFingerprint: String,
            utilityMeasures: Map<OwnerUtilityDimension, Double>,
            ownerPolicyEligibility: OwnerPolicyEligibility = OwnerPolicyEligibility.NOT_REQUIRED,
            ownerPolicyDecisionFingerprint: String? = null,
            externalEffectRequired: Boolean = false,
        ): OwnerAlignedDecisionCandidate {
            val canonical = utilityMeasures.toSortedMap(compareBy { it.ordinal })
            val fingerprint = decisionCandidateFingerprint(
                goalPlanFingerprint = goalPlanFingerprint,
                epistemicDecisionFingerprint = epistemicDecisionFingerprint,
                utilityMeasures = canonical,
                ownerPolicyEligibility = ownerPolicyEligibility,
                ownerPolicyDecisionFingerprint = ownerPolicyDecisionFingerprint,
                externalEffectRequired = externalEffectRequired,
            )
            return OwnerAlignedDecisionCandidate(
                id = ID_PREFIX + fingerprint,
                goalPlanFingerprint = goalPlanFingerprint,
                epistemicDecisionFingerprint = epistemicDecisionFingerprint,
                utilityMeasures = canonical,
                ownerPolicyEligibility = ownerPolicyEligibility,
                ownerPolicyDecisionFingerprint = ownerPolicyDecisionFingerprint,
                externalEffectRequired = externalEffectRequired,
                fingerprint = fingerprint,
            )
        }
    }
}

enum class OwnerAlignedDecisionEvaluationState {
    COMPLETE,
    INCOMPLETE_OWNER_UTILITY,
    POLICY_BLOCKED,
}

data class OwnerUtilityContribution(
    val dimension: OwnerUtilityDimension,
    val importance: Double,
    val observedMeasure: Double,
    val alignedMeasure: Double,
    val weightedContribution: Double,
) {
    init {
        require(importance.isFinite() && importance in 0.0..1.0)
        require(observedMeasure.isFinite() && observedMeasure in 0.0..1.0)
        require(alignedMeasure.isFinite() && alignedMeasure in 0.0..1.0)
        require(weightedContribution.isFinite() && weightedContribution in 0.0..1.0)
    }
}

data class OwnerAlignedDecisionEvaluation(
    val candidateId: String,
    val candidateFingerprint: String,
    val ownerUtilityProfileFingerprint: String,
    val state: OwnerAlignedDecisionEvaluationState,
    val contributions: List<OwnerUtilityContribution>,
    val missingOwnerDimensions: List<OwnerUtilityDimension>,
    val ownerAlignedUtility: Double?,
    val ownerPolicyEligibility: OwnerPolicyEligibility,
    val ownerPolicyDecisionFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(candidateId.startsWith(OwnerAlignedDecisionCandidate.ID_PREFIX))
        require(candidateFingerprint.matches(SHA_256_REGEX))
        require(ownerUtilityProfileFingerprint.matches(SHA_256_REGEX))
        require(contributions == contributions.sortedBy { it.dimension.ordinal })
        require(contributions.map { it.dimension }.distinct().size == contributions.size)
        require(missingOwnerDimensions == missingOwnerDimensions.distinct().sortedBy { it.ordinal })
        when (state) {
            OwnerAlignedDecisionEvaluationState.COMPLETE -> {
                require(missingOwnerDimensions.isEmpty())
                require(ownerPolicyEligibility != OwnerPolicyEligibility.BLOCKED)
                require(ownerAlignedUtility != null && ownerAlignedUtility.isFinite() &&
                    ownerAlignedUtility in 0.0..1.0)
            }
            OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY -> {
                require(missingOwnerDimensions.isNotEmpty())
                require(ownerAlignedUtility == null)
            }
            OwnerAlignedDecisionEvaluationState.POLICY_BLOCKED -> {
                require(ownerPolicyEligibility == OwnerPolicyEligibility.BLOCKED)
                require(ownerAlignedUtility == null)
            }
        }
        require(
            fingerprint == decisionEvaluationFingerprint(
                candidateId = candidateId,
                candidateFingerprint = candidateFingerprint,
                ownerUtilityProfileFingerprint = ownerUtilityProfileFingerprint,
                state = state,
                contributions = contributions,
                missingOwnerDimensions = missingOwnerDimensions,
                ownerAlignedUtility = ownerAlignedUtility,
                ownerPolicyEligibility = ownerPolicyEligibility,
                ownerPolicyDecisionFingerprint = ownerPolicyDecisionFingerprint,
            )
        )
    }

    val epistemicTruthAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val selectionAuthority: Boolean
        get() = false
}

/**
 * B387 evaluates candidate outcomes against the exact B386 owner-utility profile.
 *
 * It never changes epistemic truth, Owner Policy, or execution authority. Policy BLOCKED always
 * dominates utility. Missing explicitly learned dimensions remain incomplete rather than being
 * silently guessed or imputed.
 */
class OwnerAlignedDecisionUtility {
    fun evaluate(
        profile: OwnerUtilityProfile,
        candidate: OwnerAlignedDecisionCandidate,
    ): OwnerAlignedDecisionEvaluation {
        val contributions = profile.preferences
            .mapNotNull { preference ->
                candidate.utilityMeasures[preference.dimension]?.let { measure ->
                    val aligned = when (preference.polarity) {
                        OwnerUtilityPolarity.MAXIMIZE -> measure
                        OwnerUtilityPolarity.MINIMIZE -> 1.0 - measure
                    }
                    OwnerUtilityContribution(
                        dimension = preference.dimension,
                        importance = preference.meanImportance,
                        observedMeasure = measure,
                        alignedMeasure = aligned,
                        weightedContribution = preference.meanImportance * aligned,
                    )
                }
            }
            .sortedBy { it.dimension.ordinal }

        val measured = contributions.map { it.dimension }.toSet()
        val missing = profile.preferences
            .map { it.dimension }
            .filterNot(measured::contains)
            .sortedBy { it.ordinal }

        val state: OwnerAlignedDecisionEvaluationState
        val utility: Double?
        when {
            candidate.ownerPolicyEligibility == OwnerPolicyEligibility.BLOCKED -> {
                state = OwnerAlignedDecisionEvaluationState.POLICY_BLOCKED
                utility = null
            }
            missing.isNotEmpty() -> {
                state = OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY
                utility = null
            }
            profile.preferences.isEmpty() -> {
                state = OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY
                utility = null
            }
            else -> {
                val totalImportance = profile.preferences.sumOf { it.meanImportance }
                if (totalImportance <= 0.0) {
                    state = OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY
                    utility = null
                } else {
                    state = OwnerAlignedDecisionEvaluationState.COMPLETE
                    utility = (contributions.sumOf { it.weightedContribution } / totalImportance)
                        .coerceIn(0.0, 1.0)
                }
            }
        }

        val effectiveMissing =
            if (profile.preferences.isEmpty()) {
                OwnerUtilityDimension.entries.toList()
            } else {
                missing
            }

        return OwnerAlignedDecisionEvaluation(
            candidateId = candidate.id,
            candidateFingerprint = candidate.fingerprint,
            ownerUtilityProfileFingerprint = profile.fingerprint,
            state = state,
            contributions = contributions,
            missingOwnerDimensions = effectiveMissing,
            ownerAlignedUtility = utility,
            ownerPolicyEligibility = candidate.ownerPolicyEligibility,
            ownerPolicyDecisionFingerprint = candidate.ownerPolicyDecisionFingerprint,
            fingerprint = decisionEvaluationFingerprint(
                candidateId = candidate.id,
                candidateFingerprint = candidate.fingerprint,
                ownerUtilityProfileFingerprint = profile.fingerprint,
                state = state,
                contributions = contributions,
                missingOwnerDimensions = effectiveMissing,
                ownerAlignedUtility = utility,
                ownerPolicyEligibility = candidate.ownerPolicyEligibility,
                ownerPolicyDecisionFingerprint = candidate.ownerPolicyDecisionFingerprint,
            ),
        )
    }

    fun evaluateAll(
        profile: OwnerUtilityProfile,
        candidates: Collection<OwnerAlignedDecisionCandidate>,
    ): List<OwnerAlignedDecisionEvaluation> {
        val grouped = candidates.groupBy { it.id }
        grouped.forEach { (id, items) ->
            require(items.all { it == items.first() }) {
                "Conflicting owner-aligned decision candidate identity: $id"
            }
        }
        return grouped.values
            .map { it.first() }
            .sortedBy { it.id }
            .map { evaluate(profile, it) }
    }
}

private fun decisionCandidateFingerprint(
    goalPlanFingerprint: String,
    epistemicDecisionFingerprint: String,
    utilityMeasures: Map<OwnerUtilityDimension, Double>,
    ownerPolicyEligibility: OwnerPolicyEligibility,
    ownerPolicyDecisionFingerprint: String?,
    externalEffectRequired: Boolean,
): String = decisionUtilityFingerprint(
    "owner-aligned-decision-candidate/v1",
    goalPlanFingerprint,
    epistemicDecisionFingerprint,
    utilityMeasures.toSortedMap(compareBy { it.ordinal })
        .entries
        .joinToString("\u001f") { (dimension, value) ->
            dimension.name + "=" + java.lang.Double.toHexString(value)
        },
    ownerPolicyEligibility.name,
    ownerPolicyDecisionFingerprint.orEmpty(),
    externalEffectRequired.toString(),
)

private fun decisionEvaluationFingerprint(
    candidateId: String,
    candidateFingerprint: String,
    ownerUtilityProfileFingerprint: String,
    state: OwnerAlignedDecisionEvaluationState,
    contributions: List<OwnerUtilityContribution>,
    missingOwnerDimensions: List<OwnerUtilityDimension>,
    ownerAlignedUtility: Double?,
    ownerPolicyEligibility: OwnerPolicyEligibility,
    ownerPolicyDecisionFingerprint: String?,
): String = decisionUtilityFingerprint(
    "owner-aligned-decision-evaluation/v1",
    candidateId,
    candidateFingerprint,
    ownerUtilityProfileFingerprint,
    state.name,
    contributions.joinToString("\u001f") {
        listOf(
            it.dimension.name,
            java.lang.Double.toHexString(it.importance),
            java.lang.Double.toHexString(it.observedMeasure),
            java.lang.Double.toHexString(it.alignedMeasure),
            java.lang.Double.toHexString(it.weightedContribution),
        ).joinToString(":")
    },
    missingOwnerDimensions.joinToString("\u001f") { it.name },
    ownerAlignedUtility?.let(java.lang.Double::toHexString).orEmpty(),
    ownerPolicyEligibility.name,
    ownerPolicyDecisionFingerprint.orEmpty(),
)

private fun decisionUtilityFingerprint(
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

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

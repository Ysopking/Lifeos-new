package app.lifeos.core.runtime.research

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class ResearchInformationGainEstimateId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "research-information-estimate:"
    }
}

data class ResearchInformationGainEstimate(
    val id: ResearchInformationGainEstimateId,
    val planFingerprint: String,
    val missionId: RecursiveResearchMissionId,
    val expectedInformationGain: Double,
    val novelty: Double,
    val sourceReliability: Double,
    val estimatedCost: Double,
    val evidenceFingerprint: String,
) {
    init {
        require(planFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(expectedInformationGain.isUnitInterval())
        require(novelty.isUnitInterval())
        require(sourceReliability.isUnitInterval())
        require(estimatedCost.isUnitInterval())
        require(evidenceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(id == expectedId())
    }

    val truthAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = informationGainFingerprint(
        "research-information-estimate/v1",
        planFingerprint,
        missionId.value,
        java.lang.Double.toHexString(expectedInformationGain),
        java.lang.Double.toHexString(novelty),
        java.lang.Double.toHexString(sourceReliability),
        java.lang.Double.toHexString(estimatedCost),
        evidenceFingerprint,
    )

    private fun expectedId(): ResearchInformationGainEstimateId =
        ResearchInformationGainEstimateId(
            ResearchInformationGainEstimateId.PREFIX + fingerprint()
        )

    companion object {
        fun create(
            planFingerprint: String,
            missionId: RecursiveResearchMissionId,
            expectedInformationGain: Double,
            novelty: Double,
            sourceReliability: Double,
            estimatedCost: Double,
            evidenceFingerprint: String,
        ): ResearchInformationGainEstimate {
            val fingerprint = informationGainFingerprint(
                "research-information-estimate/v1",
                planFingerprint,
                missionId.value,
                java.lang.Double.toHexString(expectedInformationGain),
                java.lang.Double.toHexString(novelty),
                java.lang.Double.toHexString(sourceReliability),
                java.lang.Double.toHexString(estimatedCost),
                evidenceFingerprint,
            )
            return ResearchInformationGainEstimate(
                id = ResearchInformationGainEstimateId(
                    ResearchInformationGainEstimateId.PREFIX + fingerprint
                ),
                planFingerprint = planFingerprint,
                missionId = missionId,
                expectedInformationGain = expectedInformationGain,
                novelty = novelty,
                sourceReliability = sourceReliability,
                estimatedCost = estimatedCost,
                evidenceFingerprint = evidenceFingerprint,
            )
        }
    }
}

data class ResearchInformationGainPolicy(
    val informationGainWeight: Double = 0.55,
    val noveltyWeight: Double = 0.20,
    val reliabilityWeight: Double = 0.25,
    val costPenaltyWeight: Double = 0.35,
) {
    init {
        require(informationGainWeight.isFinite() && informationGainWeight >= 0.0)
        require(noveltyWeight.isFinite() && noveltyWeight >= 0.0)
        require(reliabilityWeight.isFinite() && reliabilityWeight >= 0.0)
        require(costPenaltyWeight.isUnitInterval())
        require(informationGainWeight + noveltyWeight + reliabilityWeight > 0.0)
    }

    fun fingerprint(): String = informationGainFingerprint(
        "research-information-gain-policy/v1",
        java.lang.Double.toHexString(informationGainWeight),
        java.lang.Double.toHexString(noveltyWeight),
        java.lang.Double.toHexString(reliabilityWeight),
        java.lang.Double.toHexString(costPenaltyWeight),
    )
}

data class ResearchMissionPriority(
    val missionId: RecursiveResearchMissionId,
    val estimateId: ResearchInformationGainEstimateId,
    val score: Double,
    val fingerprint: String,
) {
    init {
        require(score.isUnitInterval())
        require(
            fingerprint == informationGainFingerprint(
                "research-mission-priority/v1",
                missionId.value,
                estimateId.value,
                java.lang.Double.toHexString(score),
            )
        )
    }
}

data class ResearchInformationGainDecision(
    val planFingerprint: String,
    val policyFingerprint: String,
    val plannedMissionIds: List<RecursiveResearchMissionId>,
    val ranked: List<ResearchMissionPriority>,
    val unscoredPlannedMissionIds: List<RecursiveResearchMissionId>,
    val fingerprint: String,
) {
    init {
        require(planFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(policyFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(plannedMissionIds == plannedMissionIds.distinct().sortedBy { it.value })
        require(ranked == ranked.distinctBy { it.missionId }.sortedWith(priorityOrder()))
        require(
            unscoredPlannedMissionIds ==
                unscoredPlannedMissionIds.distinct().sortedBy { it.value }
        )
        require(ranked.none { priority -> priority.missionId in unscoredPlannedMissionIds })
        require(
            (ranked.map { it.missionId } + unscoredPlannedMissionIds)
                .distinct()
                .sortedBy { it.value } == plannedMissionIds
        )
        require(
            fingerprint == decisionFingerprint(
                planFingerprint,
                policyFingerprint,
                plannedMissionIds,
                ranked,
                unscoredPlannedMissionIds,
            )
        )
    }

    val selectionAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val currentCycleWorldMutationAllowed: Boolean get() = false
}

/**
 * B400 ranks already-planned B399 missions by predicted information value only.
 *
 * The score is an estimate, not observed information gain and not truth. DeepSearchScore continues
 * to evaluate branches inside one search mission; B400 compares whole PLANNED missions before
 * execution. The decision grants no permission, selection, execution, or world-mutation authority.
 */
class ResearchInformationGainPolicyEngine {
    fun rank(
        plan: RecursiveResearchPlan,
        estimates: Collection<ResearchInformationGainEstimate>,
        policy: ResearchInformationGainPolicy = ResearchInformationGainPolicy(),
    ): ResearchInformationGainDecision {
        require(estimates.map { it.id }.distinct().size == estimates.size) {
            "Duplicate research information-gain estimates are not allowed"
        }
        val planned = plan.missions
            .filter { it.status == RecursiveResearchMissionStatus.PLANNED }
            .associateBy { it.id }
        estimates.forEach { estimate ->
            require(estimate.planFingerprint == plan.fingerprint) {
                "Research information estimate belongs to another plan revision"
            }
            require(estimate.missionId in planned) {
                "Research information estimate targets a non-planned or unknown mission"
            }
        }

        val ranked = estimates.map { estimate ->
            val score = score(estimate, policy)
            val fingerprint = informationGainFingerprint(
                "research-mission-priority/v1",
                estimate.missionId.value,
                estimate.id.value,
                java.lang.Double.toHexString(score),
            )
            ResearchMissionPriority(
                missionId = estimate.missionId,
                estimateId = estimate.id,
                score = score,
                fingerprint = fingerprint,
            )
        }.sortedWith(priorityOrder())
        val scoredIds = ranked.mapTo(mutableSetOf()) { it.missionId }
        val unscored = planned.keys
            .filterNot { it in scoredIds }
            .sortedBy { it.value }
        val policyFingerprint = policy.fingerprint()
        val plannedMissionIds = planned.keys.sortedBy { it.value }
        val fingerprint = decisionFingerprint(
            plan.fingerprint,
            policyFingerprint,
            plannedMissionIds,
            ranked,
            unscored,
        )
        return ResearchInformationGainDecision(
            planFingerprint = plan.fingerprint,
            policyFingerprint = policyFingerprint,
            plannedMissionIds = plannedMissionIds,
            ranked = ranked,
            unscoredPlannedMissionIds = unscored,
            fingerprint = fingerprint,
        )
    }

    private fun score(
        estimate: ResearchInformationGainEstimate,
        policy: ResearchInformationGainPolicy,
    ): Double {
        val benefitWeight =
            policy.informationGainWeight + policy.noveltyWeight + policy.reliabilityWeight
        val benefit = (
            estimate.expectedInformationGain * policy.informationGainWeight +
                estimate.novelty * policy.noveltyWeight +
                estimate.sourceReliability * policy.reliabilityWeight
            ) / benefitWeight
        return (benefit * (1.0 - policy.costPenaltyWeight * estimate.estimatedCost))
            .coerceIn(0.0, 1.0)
    }
}

private fun priorityOrder(): Comparator<ResearchMissionPriority> =
    compareByDescending<ResearchMissionPriority> { it.score }
        .thenBy { it.missionId.value }
        .thenBy { it.estimateId.value }

private fun decisionFingerprint(
    planFingerprint: String,
    policyFingerprint: String,
    plannedMissionIds: List<RecursiveResearchMissionId>,
    ranked: List<ResearchMissionPriority>,
    unscored: List<RecursiveResearchMissionId>,
): String = informationGainFingerprint(
    "research-information-gain-decision/v1",
    planFingerprint,
    policyFingerprint,
    *plannedMissionIds.sortedBy { it.value }.map { "planned:${it.value}" }.toTypedArray(),
    *ranked.sortedWith(priorityOrder()).map { "priority:${it.fingerprint}" }.toTypedArray(),
    *unscored.sortedBy { it.value }.map { "unscored:${it.value}" }.toTypedArray(),
)

private fun Double.isUnitInterval(): Boolean = isFinite() && this in 0.0..1.0

private fun informationGainFingerprint(
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

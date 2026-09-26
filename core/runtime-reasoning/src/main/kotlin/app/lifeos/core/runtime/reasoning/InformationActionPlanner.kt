package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.level7.EvidenceActionRequest

internal const val INFORMATION_SCORE_SCALE = 1_000_000L

data class InformationActionCandidate private constructor(
    val interventionId: String,
    val kind: EvidenceActionKind,
    val expectedInformationGainMicros: Long,
    val resourceCostMicros: Long,
    val privacyCostMicros: Long,
    val riskCostMicros: Long,
    val reversibilityMicros: Long,
    val rationale: String,
    val fingerprint: String,
) {
    init {
        require(interventionId.isNotBlank())
        require(rationale.isNotBlank())
        listOf(
            expectedInformationGainMicros,
            resourceCostMicros,
            privacyCostMicros,
            riskCostMicros,
            reversibilityMicros,
        ).forEach {
            require(it in 0L..INFORMATION_SCORE_SCALE) {
                "Information-action metrics must use the fixed micro scale"
            }
        }
        require(
            fingerprint == expectedFingerprint(
                interventionId,
                kind,
                expectedInformationGainMicros,
                resourceCostMicros,
                privacyCostMicros,
                riskCostMicros,
                reversibilityMicros,
                rationale,
            )
        )
    }

    companion object {
        fun create(
            interventionId: String,
            kind: EvidenceActionKind,
            expectedInformationGainMicros: Long,
            resourceCostMicros: Long = 0L,
            privacyCostMicros: Long = 0L,
            riskCostMicros: Long = 0L,
            reversibilityMicros: Long = INFORMATION_SCORE_SCALE,
            rationale: String,
        ): InformationActionCandidate {
            require(interventionId.isNotBlank())
            require(rationale.isNotBlank())
            listOf(
                expectedInformationGainMicros,
                resourceCostMicros,
                privacyCostMicros,
                riskCostMicros,
                reversibilityMicros,
            ).forEach {
                require(it in 0L..INFORMATION_SCORE_SCALE)
            }
            return InformationActionCandidate(
                interventionId = interventionId,
                kind = kind,
                expectedInformationGainMicros = expectedInformationGainMicros,
                resourceCostMicros = resourceCostMicros,
                privacyCostMicros = privacyCostMicros,
                riskCostMicros = riskCostMicros,
                reversibilityMicros = reversibilityMicros,
                rationale = rationale,
                fingerprint = expectedFingerprint(
                    interventionId,
                    kind,
                    expectedInformationGainMicros,
                    resourceCostMicros,
                    privacyCostMicros,
                    riskCostMicros,
                    reversibilityMicros,
                    rationale,
                ),
            )
        }

        private fun expectedFingerprint(
            interventionId: String,
            kind: EvidenceActionKind,
            expectedInformationGainMicros: Long,
            resourceCostMicros: Long,
            privacyCostMicros: Long,
            riskCostMicros: Long,
            reversibilityMicros: Long,
            rationale: String,
        ): String = StableFieldIds.fingerprint(
            "information-action-candidate/v1",
            interventionId,
            kind.name,
            expectedInformationGainMicros.toString(),
            resourceCostMicros.toString(),
            privacyCostMicros.toString(),
            riskCostMicros.toString(),
            reversibilityMicros.toString(),
            rationale,
        )
    }
}

data class InformationActionPolicy(
    val maxActions: Int = 4,
    val resourcePenaltyWeightMicros: Long = 200_000L,
    val privacyPenaltyWeightMicros: Long = 300_000L,
    val riskPenaltyWeightMicros: Long = 400_000L,
    val reversibilityWeightMicros: Long = 100_000L,
) {
    init {
        require(maxActions in 1..16)
        listOf(
            resourcePenaltyWeightMicros,
            privacyPenaltyWeightMicros,
            riskPenaltyWeightMicros,
            reversibilityWeightMicros,
        ).forEach {
            require(it in 0L..INFORMATION_SCORE_SCALE)
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "information-action-policy/v1",
        maxActions.toString(),
        resourcePenaltyWeightMicros.toString(),
        privacyPenaltyWeightMicros.toString(),
        riskPenaltyWeightMicros.toString(),
        reversibilityWeightMicros.toString(),
    )
}

data class PlannedInformationAction(
    val candidate: InformationActionCandidate,
    val request: EvidenceActionRequest,
    val scoreMicros: Long,
    val fingerprint: String,
) {
    init {
        require(scoreMicros in 0L..INFORMATION_SCORE_SCALE)
        require(!request.executionAuthority)
        require(
            fingerprint == StableFieldIds.fingerprint(
                "planned-information-action/v1",
                candidate.fingerprint,
                request.fingerprint(),
                scoreMicros.toString(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false
}

data class InformationActionPlan(
    val sourceCycleId: String,
    val identifiabilityFingerprint: String,
    val budgetFingerprint: String,
    val policyFingerprint: String,
    val items: List<PlannedInformationAction>,
    val omittedCandidateFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(identifiabilityFingerprint.isNotBlank())
        require(budgetFingerprint.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(items == items.sortedWith(informationActionOrder()))
        require(items.map { it.candidate.fingerprint }.distinct().size == items.size)
        require(
            omittedCandidateFingerprints ==
                omittedCandidateFingerprints.distinct().sorted()
        )
        require(
            items.none { it.candidate.fingerprint in omittedCandidateFingerprints }
        )
        require(
            fingerprint == expectedPlanFingerprint(
                sourceCycleId,
                identifiabilityFingerprint,
                budgetFingerprint,
                policyFingerprint,
                items,
                omittedCandidateFingerprints,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false
}

/**
 * B522 M6 planner.
 *
 * It ranks already-admissible information actions. allowedKinds is an upstream owner/observation
 * policy boundary; this planner cannot widen it and every produced EvidenceActionRequest remains
 * non-executing intent.
 */
class InformationActionPlanner(
    private val policy: InformationActionPolicy = InformationActionPolicy(),
) {
    fun plan(
        sourceCycleId: String,
        budgetFingerprint: String,
        identifiability: IdentifiabilityAssessment,
        allowedKinds: Set<EvidenceActionKind>,
        candidates: Collection<InformationActionCandidate>,
    ): InformationActionPlan {
        require(
            identifiability.status != IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT
        ) {
            "Information-action planning requires an unresolved or intervention-sensitive gap"
        }
        return planForGap(
            sourceCycleId = sourceCycleId,
            budgetFingerprint = budgetFingerprint,
            gapFingerprint = identifiability.fingerprint,
            allowedKinds = allowedKinds,
            candidates = candidates,
        )
    }

    fun plan(
        sourceCycleId: String,
        budgetFingerprint: String,
        inference: MetaInferenceResult,
        allowedKinds: Set<EvidenceActionKind>,
        candidates: Collection<InformationActionCandidate>,
    ): InformationActionPlan {
        require(inference.informationRequired) {
            "Meta information-action planning requires an information gap"
        }
        return planForGap(
            sourceCycleId = sourceCycleId,
            budgetFingerprint = budgetFingerprint,
            gapFingerprint = inference.fingerprint,
            allowedKinds = allowedKinds,
            candidates = candidates,
        )
    }

    private fun planForGap(
        sourceCycleId: String,
        budgetFingerprint: String,
        gapFingerprint: String,
        allowedKinds: Set<EvidenceActionKind>,
        candidates: Collection<InformationActionCandidate>,
    ): InformationActionPlan {
        require(sourceCycleId.isNotBlank())
        require(budgetFingerprint.isNotBlank())
        require(gapFingerprint.isNotBlank())
        require(candidates.isNotEmpty()) {
            "Information-action planning requires candidates"
        }
        val canonical = candidates
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        require(canonical.size == candidates.size) {
            "Duplicate information-action candidates are not allowed"
        }

        val allowed = canonical
            .filter { it.kind in allowedKinds }
            .map { candidate ->
                val score = score(candidate)
                val requestGapFingerprint = StableFieldIds.fingerprint(
                    "information-action-gap/v1",
                    gapFingerprint,
                    candidate.interventionId,
                )
                val request = EvidenceActionRequest.create(
                    sourceCycleId = sourceCycleId,
                    gapFingerprint = requestGapFingerprint,
                    kind = candidate.kind,
                    rationale = candidate.rationale,
                    budgetFingerprint = budgetFingerprint,
                )
                PlannedInformationAction(
                    candidate = candidate,
                    request = request,
                    scoreMicros = score,
                    fingerprint = StableFieldIds.fingerprint(
                        "planned-information-action/v1",
                        candidate.fingerprint,
                        request.fingerprint(),
                        score.toString(),
                    ),
                )
            }
            .sortedWith(informationActionOrder())

        val items = allowed.take(policy.maxActions)
        val admitted = items.mapTo(hashSetOf()) { it.candidate.fingerprint }
        val omitted = canonical
            .map { it.fingerprint }
            .filterNot(admitted::contains)
            .sorted()

        return InformationActionPlan(
            sourceCycleId = sourceCycleId,
            identifiabilityFingerprint = gapFingerprint,
            budgetFingerprint = budgetFingerprint,
            policyFingerprint = policy.fingerprint(),
            items = items,
            omittedCandidateFingerprints = omitted,
            fingerprint = expectedPlanFingerprint(
                sourceCycleId = sourceCycleId,
                identifiabilityFingerprint = gapFingerprint,
                budgetFingerprint = budgetFingerprint,
                policyFingerprint = policy.fingerprint(),
                items = items,
                omittedCandidateFingerprints = omitted,
            ),
        )
    }

    private fun score(candidate: InformationActionCandidate): Long {
        val resourcePenalty = weighted(
            candidate.resourceCostMicros,
            policy.resourcePenaltyWeightMicros,
        )
        val privacyPenalty = weighted(
            candidate.privacyCostMicros,
            policy.privacyPenaltyWeightMicros,
        )
        val riskPenalty = weighted(
            candidate.riskCostMicros,
            policy.riskPenaltyWeightMicros,
        )
        val reversibilityBonus = weighted(
            candidate.reversibilityMicros,
            policy.reversibilityWeightMicros,
        )

        return (
            candidate.expectedInformationGainMicros -
                resourcePenalty -
                privacyPenalty -
                riskPenalty +
                reversibilityBonus
            ).coerceIn(0L, INFORMATION_SCORE_SCALE)
    }

    private fun weighted(
        valueMicros: Long,
        weightMicros: Long,
    ): Long = (
        valueMicros * weightMicros + INFORMATION_SCORE_SCALE / 2L
        ) / INFORMATION_SCORE_SCALE
}

private fun informationActionOrder(): Comparator<PlannedInformationAction> =
    compareByDescending<PlannedInformationAction> { it.scoreMicros }
        .thenBy { it.request.id }

private fun expectedPlanFingerprint(
    sourceCycleId: String,
    identifiabilityFingerprint: String,
    budgetFingerprint: String,
    policyFingerprint: String,
    items: List<PlannedInformationAction>,
    omittedCandidateFingerprints: List<String>,
): String = StableFieldIds.fingerprint(
    "information-action-plan/v1",
    sourceCycleId,
    identifiabilityFingerprint,
    budgetFingerprint,
    policyFingerprint,
    *items.sortedWith(informationActionOrder())
        .map { "item:${it.fingerprint}" }
        .toTypedArray(),
    *omittedCandidateFingerprints.sorted()
        .map { "omitted:$it" }
        .toTypedArray(),
)

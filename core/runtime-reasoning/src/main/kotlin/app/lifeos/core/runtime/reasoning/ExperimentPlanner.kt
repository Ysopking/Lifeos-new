package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.BoundedCausalActionProposal
import app.lifeos.core.runtime.level7.CausalDiscriminationEngine
import app.lifeos.core.runtime.level7.CausalDiscriminationRequest
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.level7.EvidenceActionRequest

data class ExperimentPlannerConfig(
    val maxExperiments: Int = 4,
    val maxTotalResourceCost: Double = 100.0,
) {
    init {
        require(maxExperiments in 1..16)
        require(maxTotalResourceCost.isFinite() && maxTotalResourceCost > 0.0)
    }
}

data class ExperimentPlanItem(
    val requestFingerprint: String,
    val proposal: BoundedCausalActionProposal,
    val evidenceAction: EvidenceActionRequest,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(proposal.actionKind == EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT)
        require(evidenceAction.kind == EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT)
        require(!proposal.executionAuthority)
        require(!evidenceAction.executionAuthority)
        require(
            fingerprint == experimentItemFingerprint(
                requestFingerprint = requestFingerprint,
                proposal = proposal,
                evidenceAction = evidenceAction,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false
}

data class ExperimentPlan(
    val sourceCycleId: String,
    val reasoningSearchFingerprint: String,
    val counterfactualBatchFingerprint: String,
    val budgetFingerprint: String,
    val items: List<ExperimentPlanItem>,
    val omittedRequestFingerprints: List<String>,
    val totalResourceCost: Double,
    val truncated: Boolean,
    val fingerprint: String,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(reasoningSearchFingerprint.isNotBlank())
        require(counterfactualBatchFingerprint.isNotBlank())
        require(budgetFingerprint.isNotBlank())
        require(items == items.sortedWith(experimentItemOrder()))
        require(items.map { it.requestFingerprint }.distinct().size == items.size)
        require(
            omittedRequestFingerprints ==
                omittedRequestFingerprints.distinct().sorted()
        )
        require(
            items.none { it.requestFingerprint in omittedRequestFingerprints }
        )
        require(totalResourceCost.isFinite() && totalResourceCost >= 0.0)
        require(
            kotlin.math.abs(
                totalResourceCost - items.sumOf { it.proposal.resourceCost }
            ) <= 1e-12
        )
        require(truncated == omittedRequestFingerprints.isNotEmpty())
        require(
            fingerprint == experimentPlanFingerprint(
                sourceCycleId = sourceCycleId,
                reasoningSearchFingerprint = reasoningSearchFingerprint,
                counterfactualBatchFingerprint = counterfactualBatchFingerprint,
                budgetFingerprint = budgetFingerprint,
                items = items,
                omittedRequestFingerprints = omittedRequestFingerprints,
                totalResourceCost = totalResourceCost,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false
}

/**
 * B370 turns already identified causal discrimination gaps into bounded SAFE_SANDBOX_EXPERIMENT
 * evidence requests. The plan is evidence acquisition intent only and has no execution authority.
 */
class ExperimentPlanner(
    private val config: ExperimentPlannerConfig = ExperimentPlannerConfig(),
    private val discriminationEngine: CausalDiscriminationEngine =
        CausalDiscriminationEngine(),
) {
    fun plan(
        sourceCycleId: String,
        reasoningSearchFingerprint: String,
        counterfactualBatchFingerprint: String,
        budgetFingerprint: String,
        requests: Collection<CausalDiscriminationRequest>,
    ): ExperimentPlan {
        require(sourceCycleId.isNotBlank())
        require(reasoningSearchFingerprint.isNotBlank())
        require(counterfactualBatchFingerprint.isNotBlank())
        require(budgetFingerprint.isNotBlank())
        require(requests.isNotEmpty()) {
            "Experiment planning requires at least one discrimination request"
        }

        val canonicalRequests = requests
            .distinctBy(::discriminationRequestFingerprint)
            .sortedWith(
                compareByDescending<CausalDiscriminationRequest> {
                    it.expectedInformationGain
                }.thenBy(::discriminationRequestFingerprint)
            )
        require(canonicalRequests.size == requests.size) {
            "Duplicate causal discrimination requests are not allowed"
        }

        val proposals = discriminationEngine.propose(
            requests = canonicalRequests,
            maxActions = config.maxExperiments,
        ).sortedWith(
            compareByDescending<BoundedCausalActionProposal> {
                it.request.expectedInformationGain
            }.thenBy { discriminationRequestFingerprint(it.request) }
        )

        val admitted = mutableListOf<ExperimentPlanItem>()
        val omitted = linkedSetOf<String>()
        var totalCost = 0.0

        proposals.forEach { proposal ->
            require(proposal.actionKind == EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT)
            val requestFingerprint = discriminationRequestFingerprint(proposal.request)
            if (totalCost + proposal.resourceCost > config.maxTotalResourceCost + 1e-12) {
                omitted += requestFingerprint
                return@forEach
            }
            val evidenceAction = EvidenceActionRequest.create(
                sourceCycleId = sourceCycleId,
                gapFingerprint = requestFingerprint,
                kind = EvidenceActionKind.SAFE_SANDBOX_EXPERIMENT,
                rationale = proposal.request.rationale,
                budgetFingerprint = budgetFingerprint,
            )
            admitted += ExperimentPlanItem(
                requestFingerprint = requestFingerprint,
                proposal = proposal,
                evidenceAction = evidenceAction,
                fingerprint = experimentItemFingerprint(
                    requestFingerprint,
                    proposal,
                    evidenceAction,
                ),
            )
            totalCost += proposal.resourceCost
        }

        val proposedFingerprints = proposals
            .mapTo(linkedSetOf()) { discriminationRequestFingerprint(it.request) }
        canonicalRequests
            .map(::discriminationRequestFingerprint)
            .filterNot(proposedFingerprints::contains)
            .forEach(omitted::add)

        val items = admitted.sortedWith(experimentItemOrder())
        val omittedCanonical = omitted.sorted()
        return ExperimentPlan(
            sourceCycleId = sourceCycleId,
            reasoningSearchFingerprint = reasoningSearchFingerprint,
            counterfactualBatchFingerprint = counterfactualBatchFingerprint,
            budgetFingerprint = budgetFingerprint,
            items = items,
            omittedRequestFingerprints = omittedCanonical,
            totalResourceCost = totalCost,
            truncated = omittedCanonical.isNotEmpty(),
            fingerprint = experimentPlanFingerprint(
                sourceCycleId = sourceCycleId,
                reasoningSearchFingerprint = reasoningSearchFingerprint,
                counterfactualBatchFingerprint = counterfactualBatchFingerprint,
                budgetFingerprint = budgetFingerprint,
                items = items,
                omittedRequestFingerprints = omittedCanonical,
                totalResourceCost = totalCost,
            ),
        )
    }
}

private fun discriminationRequestFingerprint(
    request: CausalDiscriminationRequest,
): String = StableFieldIds.fingerprint(
    "reasoning-experiment-discrimination-request/v1",
    request.interventionVariableId,
    java.lang.Double.toHexString(request.expectedInformationGain),
    request.rationale,
    *request.candidateIds.sorted().map { "candidate:" + it }.toTypedArray(),
)

private fun experimentItemFingerprint(
    requestFingerprint: String,
    proposal: BoundedCausalActionProposal,
    evidenceAction: EvidenceActionRequest,
): String = StableFieldIds.fingerprint(
    "reasoning-experiment-plan-item/v1",
    requestFingerprint,
    proposal.actionKind.name,
    java.lang.Double.toHexString(proposal.resourceCost),
    evidenceAction.id,
    evidenceAction.fingerprint(),
)

private fun experimentItemOrder(): Comparator<ExperimentPlanItem> =
    compareByDescending<ExperimentPlanItem> {
        it.proposal.request.expectedInformationGain
    }.thenBy { it.requestFingerprint }

private fun experimentPlanFingerprint(
    sourceCycleId: String,
    reasoningSearchFingerprint: String,
    counterfactualBatchFingerprint: String,
    budgetFingerprint: String,
    items: List<ExperimentPlanItem>,
    omittedRequestFingerprints: List<String>,
    totalResourceCost: Double,
): String = StableFieldIds.fingerprint(
    "reasoning-experiment-plan/v1",
    sourceCycleId,
    reasoningSearchFingerprint,
    counterfactualBatchFingerprint,
    budgetFingerprint,
    java.lang.Double.toHexString(totalResourceCost),
    *items.sortedWith(experimentItemOrder())
        .map { "item:" + it.fingerprint }
        .toTypedArray(),
    *omittedRequestFingerprints.sorted()
        .map { "omitted:" + it }
        .toTypedArray(),
)

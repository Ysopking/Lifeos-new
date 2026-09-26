package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.EvidenceActionKind

data class MetaRealizationShadowEvidence(
    val predictiveHistories: List<PredictiveHistory>,
    val candidateSignatures: List<PredictiveCandidateSignature>,
    val informationCandidates: List<InformationActionCandidate> = emptyList(),
    val allowedActionKinds: Set<EvidenceActionKind> = emptySet(),
    val budgetFingerprint: String,
) {
    init {
        require(predictiveHistories.isNotEmpty())
        require(candidateSignatures.size >= 2)
        require(budgetFingerprint.isNotBlank())
    }
}

data class MetaRealizationShadowAnalysis(
    val sourceShadowFingerprint: String,
    val sourceRevisionId: String,
    val predictiveClasses: List<PredictiveStateClass>,
    val predictiveQuotientFingerprint: String,
    val identifiability: IdentifiabilityAssessment,
    val informationPlan: InformationActionPlan?,
    val cycle: MetaRealizationCycle,
    val fingerprint: String,
) {
    init {
        require(sourceShadowFingerprint.isNotBlank())
        require(sourceRevisionId.isNotBlank())
        require(predictiveClasses.isNotEmpty())
        require(predictiveClasses == predictiveClasses.sortedBy { it.id })
        require(predictiveQuotientFingerprint.isNotBlank())
        require(cycle.sourceRevisionId == sourceRevisionId)
        require(cycle.predictiveQuotientFingerprint == predictiveQuotientFingerprint)
        require(cycle.identifiabilityFingerprint == identifiability.fingerprint)
        if (informationPlan == null) {
            require(cycle.informationPlanFingerprint == null)
        } else if (informationPlan.items.isNotEmpty()) {
            require(cycle.informationPlanFingerprint == informationPlan.fingerprint)
            require(cycle.state == MetaRealizationCycleState.AWAITING_OBSERVATION)
        }
        require(
            fingerprint == StableFieldIds.fingerprint(
                "meta-realization-shadow-analysis/v1",
                sourceShadowFingerprint,
                sourceRevisionId,
                predictiveQuotientFingerprint,
                identifiability.fingerprint,
                informationPlan?.fingerprint.orEmpty(),
                cycle.fingerprint,
                *predictiveClasses.map { it.id }.toTypedArray(),
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B530 explicit-evidence M2/M5/M6 shadow evaluator.
 *
 * Inputs are evidence supplied by upstream components. The evaluator cannot fabricate evidence,
 * widen owner-policy admission or execute any information request.
 */
class MetaRealizationShadowEvaluator(
    private val quotient: PredictiveStateQuotient = PredictiveStateQuotient(),
    private val identifiabilityAnalyzer: PredictiveIdentifiabilityAnalyzer =
        PredictiveIdentifiabilityAnalyzer(),
    private val informationPlanner: InformationActionPlanner =
        InformationActionPlanner(),
) {
    fun evaluate(
        snapshot: MetaRealizationShadowSnapshot,
        evidence: MetaRealizationShadowEvidence,
    ): MetaRealizationShadowAnalysis {
        val profileFingerprint = snapshot.cycle.realizationProfileFingerprint
        require(
            evidence.predictiveHistories.all {
                it.realizationProfileFingerprint == profileFingerprint
            }
        ) { "Predictive histories must use the shadow realization profile" }
        require(
            evidence.candidateSignatures.all {
                it.realizationProfileFingerprint == profileFingerprint
            }
        ) { "Candidate signatures must use the shadow realization profile" }

        val predictiveClasses = quotient.exact(evidence.predictiveHistories)
            .sortedBy { it.id }
        val quotientFingerprint = StableFieldIds.fingerprint(
            "meta-realization-shadow-quotient/v1",
            profileFingerprint,
            *predictiveClasses.map { it.id }.toTypedArray(),
        )
        val identifiability =
            identifiabilityAnalyzer.assess(evidence.candidateSignatures)

        var cycle = snapshot.cycle
            .predictiveReady(quotientFingerprint)
            .assessIdentifiability(identifiability)

        val plan = if (
            cycle.state == MetaRealizationCycleState.INFORMATION_REQUIRED &&
            evidence.informationCandidates.isNotEmpty()
        ) {
            informationPlanner.plan(
                sourceCycleId = cycle.cycleId,
                budgetFingerprint = evidence.budgetFingerprint,
                identifiability = identifiability,
                allowedKinds = evidence.allowedActionKinds,
                candidates = evidence.informationCandidates,
            )
        } else {
            null
        }

        if (plan != null && plan.items.isNotEmpty()) {
            cycle = cycle.awaitObservation(plan)
        }

        val fingerprint = StableFieldIds.fingerprint(
            "meta-realization-shadow-analysis/v1",
            snapshot.fingerprint,
            snapshot.realization.revisionId,
            quotientFingerprint,
            identifiability.fingerprint,
            plan?.fingerprint.orEmpty(),
            cycle.fingerprint,
            *predictiveClasses.map { it.id }.toTypedArray(),
        )
        return MetaRealizationShadowAnalysis(
            sourceShadowFingerprint = snapshot.fingerprint,
            sourceRevisionId = snapshot.realization.revisionId,
            predictiveClasses = predictiveClasses,
            predictiveQuotientFingerprint = quotientFingerprint,
            identifiability = identifiability,
            informationPlan = plan,
            cycle = cycle,
            fingerprint = fingerprint,
        )
    }
}

package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.agency.CapabilityPlanNode
import app.lifeos.core.runtime.agency.ExternalActionEdgeType
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.agency.ExternalActionReceiptGraph
import app.lifeos.core.runtime.agency.ExternalActionRequestNode
import app.lifeos.core.runtime.agency.ExternalEffectReceiptNode
import app.lifeos.core.runtime.agency.ExternalObservationNode
import app.lifeos.core.runtime.agency.OutcomeNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ActionOutcomeLearningState {
    CONFIRMED_EVIDENCE,
    CONTRADICTED_EVIDENCE,
    PARTIAL_EVIDENCE,
    UNRESOLVED,
    AMBIGUOUS,
}

enum class ActionOutcomeLearningDirection {
    POSITIVE,
    NEGATIVE,
}

data class ActionOutcomeLearningEvidence(
    val graphId: String,
    val headRevisionId: String,
    val requestFingerprint: String,
    val dispatchPlanFingerprint: String,
    val receiptFingerprint: String,
    val observationFingerprint: String?,
    val outcomeFingerprint: String?,
    val resourceIdentity: String,
    val outcomeState: ExternalActionOutcomeState?,
    val reasonCode: String,
    val state: ActionOutcomeLearningState,
    val fingerprint: String,
) {
    init {
        require(graphId.startsWith("external-action-graph:"))
        require(headRevisionId.startsWith("external-action-revision:"))
        require(requestFingerprint.matches(SHA_256_REGEX_B416))
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX_B416))
        require(receiptFingerprint.matches(SHA_256_REGEX_B416))
        require(observationFingerprint == null || observationFingerprint.matches(SHA_256_REGEX_B416))
        require(outcomeFingerprint == null || outcomeFingerprint.matches(SHA_256_REGEX_B416))
        require(resourceIdentity.isNotBlank())
        require(reasonCode.isNotBlank())
        require(
            fingerprint == learningEvidenceFingerprint(
                graphId,
                headRevisionId,
                requestFingerprint,
                dispatchPlanFingerprint,
                receiptFingerprint,
                observationFingerprint,
                outcomeFingerprint,
                resourceIdentity,
                outcomeState,
                reasonCode,
                state,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val policyAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class ActionOutcomeLearningCandidate(
    val evidenceFingerprint: String,
    val dispatchPlanFingerprint: String,
    val resourceIdentity: String,
    val direction: ActionOutcomeLearningDirection,
    val nextCycleEligible: Boolean,
    val fingerprint: String,
) {
    init {
        require(evidenceFingerprint.matches(SHA_256_REGEX_B416))
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX_B416))
        require(resourceIdentity.isNotBlank())
        require(nextCycleEligible)
        require(
            fingerprint == learningCandidateFingerprint(
                evidenceFingerprint,
                dispatchPlanFingerprint,
                resourceIdentity,
                direction,
            )
        )
    }

    val causalAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val policyAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class ActionOutcomeLearningReport(
    val evidence: ActionOutcomeLearningEvidence,
    val candidates: List<ActionOutcomeLearningCandidate>,
    val fingerprint: String,
) {
    init {
        require(candidates == candidates.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(candidates.size <= 1)
        require(
            fingerprint == learningReportFingerprint(
                evidence,
                candidates,
            )
        )
    }

    val currentCycleWorldMutationAllowed: Boolean get() = false
    val automaticPromotionAllowed: Boolean get() = false
}

/**
 * B416 consumes the exact B411 request→plan→policy→effect→observation→outcome provenance graph.
 *
 * It emits next-cycle learning candidates only for one unambiguous CONFIRMED or CONTRADICTED
 * outcome. PARTIAL/UNKNOWN/missing/ambiguous evidence never becomes a learned rule. Even a
 * confirmed observation is outcome evidence, not causal proof.
 */
class ActionOutcomeLearningEngine {
    fun evaluate(graph: ExternalActionReceiptGraph): ActionOutcomeLearningReport {
        val request = graph.nodes.values.filterIsInstance<ExternalActionRequestNode>().single()
        val plan = graph.nodes.values.filterIsInstance<CapabilityPlanNode>().single()
        val receipt = graph.nodes.values.filterIsInstance<ExternalEffectReceiptNode>().single()
        val outcomes = graph.nodes.values.filterIsInstance<OutcomeNode>()
        val graphId = graph.graphId.value
        val headRevisionId = graph.revisions.maxBy { it.revision }.revisionId.value

        if (outcomes.size != 1) {
            val reason = if (outcomes.isEmpty()) "no-outcome-evidence" else "ambiguous-multiple-outcomes"
            return report(
                graphId = graphId,
                request = request,
                plan = plan,
                receipt = receipt,
                headRevisionId = headRevisionId,
                observation = null,
                outcome = null,
                state = if (outcomes.isEmpty()) {
                    ActionOutcomeLearningState.UNRESOLVED
                } else {
                    ActionOutcomeLearningState.AMBIGUOUS
                },
                reasonCode = reason,
            )
        }

        val outcome = outcomes.single()
        val outcomeEdges = graph.edges.filter {
            it.type == ExternalActionEdgeType.OBSERVATION_CLASSIFIED_AS_OUTCOME &&
                it.to == outcome.id
        }
        if (outcomeEdges.size != 1) {
            return report(
                graphId, request, plan, receipt, headRevisionId, null, outcome,
                ActionOutcomeLearningState.AMBIGUOUS,
                "outcome-observation-lineage-ambiguous",
            )
        }

        val observation = graph.nodes[outcomeEdges.single().from] as? ExternalObservationNode
            ?: return report(
                graphId, request, plan, receipt, headRevisionId, null, outcome,
                ActionOutcomeLearningState.AMBIGUOUS,
                "outcome-observation-node-missing",
            )
        if (outcome.basisObservationId != observation.id) {
            return report(
                graphId, request, plan, receipt, headRevisionId, observation, outcome,
                ActionOutcomeLearningState.AMBIGUOUS,
                "outcome-basis-observation-mismatch",
            )
        }

        val receiptEdges = graph.edges.filter {
            it.type == ExternalActionEdgeType.RECEIPT_OBSERVED_BY &&
                it.from == receipt.id &&
                it.to == observation.id
        }
        if (receiptEdges.size != 1) {
            return report(
                graphId, request, plan, receipt, headRevisionId, observation, outcome,
                ActionOutcomeLearningState.AMBIGUOUS,
                "receipt-observation-lineage-ambiguous",
            )
        }

        val state = when (outcome.state) {
            ExternalActionOutcomeState.CONFIRMED -> ActionOutcomeLearningState.CONFIRMED_EVIDENCE
            ExternalActionOutcomeState.CONTRADICTED -> ActionOutcomeLearningState.CONTRADICTED_EVIDENCE
            ExternalActionOutcomeState.PARTIAL -> ActionOutcomeLearningState.PARTIAL_EVIDENCE
            ExternalActionOutcomeState.UNKNOWN -> ActionOutcomeLearningState.UNRESOLVED
        }
        return report(
            graphId,
            request,
            plan,
            receipt,
            headRevisionId,
            observation,
            outcome,
            state,
            outcome.reasonCode,
        )
    }

    private fun report(
        graphId: String,
        request: ExternalActionRequestNode,
        plan: CapabilityPlanNode,
        receipt: ExternalEffectReceiptNode,
        headRevisionId: String,
        observation: ExternalObservationNode?,
        outcome: OutcomeNode?,
        state: ActionOutcomeLearningState,
        reasonCode: String,
    ): ActionOutcomeLearningReport {
        val evidence = ActionOutcomeLearningEvidence(
            graphId = graphId,
            headRevisionId = headRevisionId,
            requestFingerprint = request.requestFingerprint,
            dispatchPlanFingerprint = plan.dispatchPlanFingerprint,
            receiptFingerprint = receipt.receipt.fingerprint,
            observationFingerprint = observation?.observationFingerprint,
            outcomeFingerprint = outcome?.fingerprint(),
            resourceIdentity = request.resourceIdentity,
            outcomeState = outcome?.state,
            reasonCode = reasonCode,
            state = state,
            fingerprint = learningEvidenceFingerprint(
                graphId,
                headRevisionId,
                request.requestFingerprint,
                plan.dispatchPlanFingerprint,
                receipt.receipt.fingerprint,
                observation?.observationFingerprint,
                outcome?.fingerprint(),
                request.resourceIdentity,
                outcome?.state,
                reasonCode,
                state,
            ),
        )
        val candidates = when (state) {
            ActionOutcomeLearningState.CONFIRMED_EVIDENCE -> listOf(
                candidate(evidence, ActionOutcomeLearningDirection.POSITIVE)
            )
            ActionOutcomeLearningState.CONTRADICTED_EVIDENCE -> listOf(
                candidate(evidence, ActionOutcomeLearningDirection.NEGATIVE)
            )
            ActionOutcomeLearningState.PARTIAL_EVIDENCE,
            ActionOutcomeLearningState.UNRESOLVED,
            ActionOutcomeLearningState.AMBIGUOUS -> emptyList()
        }
        return ActionOutcomeLearningReport(
            evidence = evidence,
            candidates = candidates,
            fingerprint = learningReportFingerprint(evidence, candidates),
        )
    }

    private fun candidate(
        evidence: ActionOutcomeLearningEvidence,
        direction: ActionOutcomeLearningDirection,
    ): ActionOutcomeLearningCandidate =
        ActionOutcomeLearningCandidate(
            evidenceFingerprint = evidence.fingerprint,
            dispatchPlanFingerprint = evidence.dispatchPlanFingerprint,
            resourceIdentity = evidence.resourceIdentity,
            direction = direction,
            nextCycleEligible = true,
            fingerprint = learningCandidateFingerprint(
                evidence.fingerprint,
                evidence.dispatchPlanFingerprint,
                evidence.resourceIdentity,
                direction,
            ),
        )
}

private fun learningEvidenceFingerprint(
    graphId: String,
    headRevisionId: String,
    requestFingerprint: String,
    dispatchPlanFingerprint: String,
    receiptFingerprint: String,
    observationFingerprint: String?,
    outcomeFingerprint: String?,
    resourceIdentity: String,
    outcomeState: ExternalActionOutcomeState?,
    reasonCode: String,
    state: ActionOutcomeLearningState,
): String = b416Fingerprint(
    "action-outcome-learning-evidence/v1",
    graphId,
    headRevisionId,
    requestFingerprint,
    dispatchPlanFingerprint,
    receiptFingerprint,
    observationFingerprint.orEmpty(),
    outcomeFingerprint.orEmpty(),
    resourceIdentity,
    outcomeState?.name.orEmpty(),
    reasonCode,
    state.name,
)

private fun learningCandidateFingerprint(
    evidenceFingerprint: String,
    dispatchPlanFingerprint: String,
    resourceIdentity: String,
    direction: ActionOutcomeLearningDirection,
): String = b416Fingerprint(
    "action-outcome-learning-candidate/v1",
    evidenceFingerprint,
    dispatchPlanFingerprint,
    resourceIdentity,
    direction.name,
)

private fun learningReportFingerprint(
    evidence: ActionOutcomeLearningEvidence,
    candidates: List<ActionOutcomeLearningCandidate>,
): String = b416Fingerprint(
    "action-outcome-learning-report/v1",
    evidence.fingerprint,
    *candidates.map { it.fingerprint }.toTypedArray(),
)

private fun b416Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B416 = Regex("[0-9a-f]{64}")

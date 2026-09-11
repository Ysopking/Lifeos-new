package app.lifeos.core.runtime.convergence

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import app.lifeos.core.runtime.thought.ThoughtGraphWorkingSet

data class ThoughtGraphBoundConvergenceRequest(
    val workingSetFingerprint: String,
    val source: CrossDomainConvergenceRequest,
) {
    init {
        require(workingSetFingerprint.isNotBlank())
    }
}

/**
 * Binds existing typed Field inputs to the bounded persistent Gedankenmatrix view.
 * It never reconstructs FieldEvidence from summaries and therefore cannot fabricate lost payloads.
 */
class ThoughtGraphConvergenceBinder {
    fun bind(
        workingSet: ThoughtGraphWorkingSet,
        source: CrossDomainConvergenceRequest,
    ): ThoughtGraphBoundConvergenceRequest {
        val evidenceNodes = workingSet.nodes
            .filter { it.kind == ThoughtGraphNodeKind.EVIDENCE }
            .associateBy { it.provenance.sourceId }
        val hypothesisNodes = workingSet.nodes
            .filter { it.kind == ThoughtGraphNodeKind.HYPOTHESIS }
            .associateBy { it.provenance.sourceId }
        val failures = mutableListOf<String>()

        source.domains.sortedBy { it.request.domainId.value }.forEach { input ->
            val domainId = input.request.domainId.value
            input.request.evidence.sortedBy { it.id.value }.forEach { evidence ->
                val node = evidenceNodes[evidence.id.value]
                when {
                    node == null -> failures += "missing-evidence:${evidence.id.value}"
                    node.attributes["domainId"] != domainId -> failures += "evidence-domain-mismatch:${evidence.id.value}"
                    node.provenance.sourceFingerprint != evidence.sourceFingerprint ->
                        failures += "evidence-fingerprint-mismatch:${evidence.id.value}"
                }
            }
            input.request.hypotheses.sortedBy { it.id.value }.forEach { hypothesis ->
                val node = hypothesisNodes[hypothesis.id.value]
                when {
                    node == null -> failures += "missing-hypothesis:${hypothesis.id.value}"
                    node.attributes["domainId"] != domainId -> failures += "hypothesis-domain-mismatch:${hypothesis.id.value}"
                }
            }
        }

        require(failures.isEmpty()) {
            "Convergence input is not bound to Gedankenmatrix working set: ${failures.distinct().sorted().joinToString(",")}" 
        }
        return ThoughtGraphBoundConvergenceRequest(
            workingSetFingerprint = workingSet.fingerprint,
            source = source,
        )
    }
}

/**
 * V5 orchestration boundary: bounded Gedankenmatrix -> existing Field convergence -> durable decision.
 * Informational V4 WorldFormula output is deliberately absent from this API.
 */
class ThoughtGraphConvergenceService(
    private val decisions: DurableConvergenceDecisionCoordinator,
    private val convergence: ConvergenceCoordinator = ConvergenceCoordinator(),
    private val binder: ThoughtGraphConvergenceBinder = ThoughtGraphConvergenceBinder(),
) {
    suspend fun convergeAndDecide(
        workingSet: ThoughtGraphWorkingSet,
        source: CrossDomainConvergenceRequest,
        capabilityGaps: List<CapabilityGap> = emptyList(),
    ): ConvergenceDecisionCheckpoint {
        val bound = binder.bind(workingSet, source)
        val result = convergence.coordinate(bound.source)
        return decisions.decide(
            ConvergenceDecisionRequest(
                source = bound.source,
                convergence = result,
                capabilityGaps = capabilityGaps,
                workingSetFingerprint = bound.workingSetFingerprint,
            )
        )
    }
}

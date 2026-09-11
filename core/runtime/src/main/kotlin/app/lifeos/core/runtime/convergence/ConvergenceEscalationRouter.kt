package app.lifeos.core.runtime.convergence

import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.GapSeverity

/** Routes only demonstrated gaps; never invents a search or tool-workshop request. */
class ConvergenceEscalationRouter {
    fun route(
        request: ConvergenceDecisionRequest,
        evidenceRequests: List<ConvergenceEvidenceRequest>,
    ): ConvergenceEscalationRequest? {
        val sourceFingerprint = request.sourceFingerprint()
        val blockingMissingCapabilities = request.capabilityGaps
            .filter { gap ->
                gap.type == CapabilityGapType.CAPABILITY_MISSING &&
                    gap.requirement.severity in setOf(GapSeverity.BLOCKING, GapSeverity.CRITICAL) &&
                    gap.candidateProviderIds.isEmpty()
            }
            .sortedBy(::capabilityGapFingerprint)
        if (blockingMissingCapabilities.isNotEmpty()) {
            return ConvergenceEscalationRequest(
                target = ConvergenceEscalationTarget.TOOL_WORKSHOP,
                reason = "demonstrated-blocking-capability-gap",
                hypothesisIds = evidenceRequests.flatMap { it.hypothesisIds }.distinct().sortedBy { it.value },
                capabilityIds = blockingMissingCapabilities
                    .map { it.requirement.capabilityId.value }
                    .distinct()
                    .sorted(),
                sourceFingerprint = sourceFingerprint,
            )
        }

        if (evidenceRequests.isNotEmpty()) {
            return ConvergenceEscalationRequest(
                target = ConvergenceEscalationTarget.DEEP_SEARCH,
                reason = "demonstrated-evidence-gap",
                hypothesisIds = evidenceRequests.flatMap { it.hypothesisIds }.distinct().sortedBy { it.value },
                evidenceRequestIds = evidenceRequests.map { it.id }.distinct().sortedBy { it.value },
                sourceFingerprint = sourceFingerprint,
            )
        }
        return null
    }
}

package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.time.Instant

data class GeneratedToolRequest(
    val requestPhotonId: PhotonId,
    val capabilityId: CapabilityId,
    val severity: GapSeverity,
    val gapType: CapabilityGapType,
    val requiredInputs: Set<String>,
    val requiredOutputs: Set<String>,
    val candidateProviderIds: List<String>,
    val requestedBy: String,
    val requestedAt: Instant,
) {
    init {
        require(requestedBy.isNotBlank()) { "Generated-tool request actor must not be blank" }
        require(requiredInputs.none { it.isBlank() }) { "Generated-tool request inputs must not be blank" }
        require(requiredOutputs.none { it.isBlank() }) { "Generated-tool request outputs must not be blank" }
        require(candidateProviderIds.none { it.isBlank() }) { "Generated-tool candidate providers must not be blank" }
        require(candidateProviderIds.distinct().size == candidateProviderIds.size) {
            "Generated-tool candidate providers must be unique"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-request/v1",
        requestPhotonId.value,
        capabilityId.value,
        severity.name,
        gapType.name,
        requestedBy,
        requestedAt.toString(),
        *requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
        *requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
        *candidateProviderIds.sorted().map { "candidate:$it" }.toTypedArray(),
    )

    /** A request records intent only. It never grants build, execution or promotion authority. */
    val activationAllowed: Boolean = false

    fun matches(gap: CapabilityGap): Boolean =
        capabilityId == gap.requirement.capabilityId &&
            severity == gap.requirement.severity &&
            gapType == gap.type &&
            requiredInputs == gap.requirement.requiredInputs &&
            requiredOutputs == gap.requirement.requiredOutputs &&
            candidateProviderIds.sorted() == gap.candidateProviderIds.sorted()

    companion object {
        fun fromGap(
            gap: CapabilityGap,
            requestPhotonId: PhotonId,
            requestedBy: String,
            requestedAt: Instant,
        ): GeneratedToolRequest = GeneratedToolRequest(
            requestPhotonId = requestPhotonId,
            capabilityId = gap.requirement.capabilityId,
            severity = gap.requirement.severity,
            gapType = gap.type,
            requiredInputs = gap.requirement.requiredInputs,
            requiredOutputs = gap.requirement.requiredOutputs,
            candidateProviderIds = gap.candidateProviderIds.distinct().sorted(),
            requestedBy = requestedBy,
            requestedAt = requestedAt,
        )
    }
}

class ToolGenerationApproval private constructor(
    val requestId: String,
    val requestPhotonId: PhotonId,
    val capabilityId: CapabilityId,
    val approverId: String,
    val approvedAt: Instant,
) {
    init {
        require(requestId.isNotBlank()) { "Tool-generation approval request id must not be blank" }
        require(approverId.isNotBlank()) { "Tool-generation approver must not be blank" }
    }

    val id: String = StableFieldIds.fingerprint(
        "tool-generation-approval/v1",
        requestId,
        requestPhotonId.value,
        capabilityId.value,
        approverId,
        approvedAt.toString(),
    )

    /** Approval authorizes one Genesis request only; it never authorizes activation/promotion. */
    val activationAllowed: Boolean = false

    fun matches(request: GeneratedToolRequest): Boolean =
        requestId == request.id &&
            requestPhotonId == request.requestPhotonId &&
            capabilityId == request.capabilityId &&
            !approvedAt.isBefore(request.requestedAt)

    companion object {
        fun issueForExplicitUserAction(
            request: GeneratedToolRequest,
            approverId: String,
            approvedAt: Instant,
        ): ToolGenerationApproval {
            require(approverId.isNotBlank()) { "Explicit tool-generation approval requires an actor" }
            require(!approvedAt.isBefore(request.requestedAt)) {
                "Tool-generation approval cannot predate its request"
            }
            return ToolGenerationApproval(
                requestId = request.id,
                requestPhotonId = request.requestPhotonId,
                capabilityId = request.capabilityId,
                approverId = approverId,
                approvedAt = approvedAt,
            )
        }
    }
}

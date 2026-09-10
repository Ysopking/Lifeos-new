package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

object GeneratedToolRequestPhotonCodec {
    const val REQUEST_MIME = "application/vnd.lifeos.generated-tool-request+text"
    const val APPROVAL_MIME = "application/vnd.lifeos.tool-generation-approval+text"
    const val REQUEST_SOURCE = "local-capability-gap-request"
    const val APPROVAL_SOURCE = "local-tool-generation-approval"
    const val USER_ACTOR = "user"

    private const val REQUEST_HEADER = "LIFEOS_CAPABILITY_GAP_REQUEST_V2"
    private const val LEGACY_REQUEST_HEADER = "LIFEOS_CAPABILITY_GAP_REQUEST_V1"
    private const val APPROVAL_HEADER = "LIFEOS_TOOL_GENERATION_APPROVAL_V1"

    fun createRequestPhoton(
        gap: CapabilityGap,
        createdAt: Instant,
        id: PhotonId = PhotonId.new(),
    ): Pair<Photon, GeneratedToolRequest> {
        val request = GeneratedToolRequest.fromGap(
            gap = gap,
            requestPhotonId = id,
            requestedBy = USER_ACTOR,
            requestedAt = createdAt,
        )
        val photon = Photon(
            id = id,
            content = encodeRequest(request),
            mimeType = REQUEST_MIME,
            phase = PhotonPhase.ACTIVE,
            confidence = 1.0,
            provenance = Provenance(
                source = REQUEST_SOURCE,
                actor = USER_ACTOR,
                createdAt = createdAt,
            ),
            tags = setOf("capability-gap", "tool-request", "user-requested"),
        )
        return photon to request
    }

    fun decodeRequest(photon: Photon): GeneratedToolRequest {
        require(photon.mimeType == REQUEST_MIME || LEGACY_REQUEST_HEADER in photon.content.lineSequence().take(1)) {
            "Photon is not a generated-tool request"
        }
        require(photon.provenance.source == REQUEST_SOURCE) {
            "Generated-tool request provenance source is invalid"
        }
        require(photon.provenance.actor == USER_ACTOR) {
            "Generated-tool request actor is invalid"
        }
        require("tool-request" in photon.tags && "capability-gap" in photon.tags) {
            "Generated-tool request tags are incomplete"
        }
        require(photon.phase != PhotonPhase.ARCHIVED) { "Archived generated-tool request is not actionable" }

        val fields = parseFields(photon.content, setOf(REQUEST_HEADER, LEGACY_REQUEST_HEADER))
        val capability = requireField(fields, "capability")
        val severity = enumValueOf<GapSeverity>(requireField(fields, "severity"))
        val gapType = enumValueOf<CapabilityGapType>(requireField(fields, "gapType"))
        val inputs = csv(requireField(fields, "requiredInputs"))
        val outputs = csv(requireField(fields, "requiredOutputs"))
        val candidates = csv(requireField(fields, "candidateProviders")).toList().sorted()

        return GeneratedToolRequest(
            requestPhotonId = photon.id,
            capabilityId = CapabilityId(capability),
            severity = severity,
            gapType = gapType,
            requiredInputs = inputs,
            requiredOutputs = outputs,
            candidateProviderIds = candidates,
            requestedBy = photon.provenance.actor,
            requestedAt = photon.provenance.createdAt,
        )
    }

    fun createApprovalPhoton(
        request: GeneratedToolRequest,
        approvedAt: Instant,
        id: PhotonId = PhotonId.new(),
    ): Pair<Photon, ToolGenerationApproval> {
        val approval = ToolGenerationApproval.issueForExplicitUserAction(
            request = request,
            approverId = USER_ACTOR,
            approvedAt = approvedAt,
        )
        val content = buildString {
            appendLine(APPROVAL_HEADER)
            append("approvalId=").appendLine(approval.id)
            append("requestId=").appendLine(request.id)
            append("requestPhotonId=").appendLine(request.requestPhotonId.value)
            append("capability=").appendLine(request.capabilityId.value)
            append("approvedAt=").append(approvedAt)
        }
        val photon = Photon(
            id = id,
            content = content,
            mimeType = APPROVAL_MIME,
            phase = PhotonPhase.ACTIVE,
            confidence = 1.0,
            provenance = Provenance(
                source = APPROVAL_SOURCE,
                actor = USER_ACTOR,
                createdAt = approvedAt,
                parentIds = setOf(request.requestPhotonId),
            ),
            relations = setOf(
                PhotonRelation(request.requestPhotonId, RelationType.REFERENCES, 1.0),
            ),
            tags = setOf("tool-generation-approval", "user-approved"),
        )
        return photon to approval
    }

    fun decodeApproval(
        photon: Photon,
        request: GeneratedToolRequest,
    ): ToolGenerationApproval {
        require(photon.mimeType == APPROVAL_MIME) { "Photon is not a tool-generation approval" }
        require(photon.provenance.source == APPROVAL_SOURCE && photon.provenance.actor == USER_ACTOR) {
            "Tool-generation approval provenance is invalid"
        }
        require(photon.phase != PhotonPhase.ARCHIVED) { "Archived tool-generation approval is invalid" }
        require("tool-generation-approval" in photon.tags && "user-approved" in photon.tags) {
            "Tool-generation approval tags are incomplete"
        }
        require(request.requestPhotonId in photon.provenance.parentIds) {
            "Tool-generation approval parent does not match request photon"
        }
        require(
            photon.relations.any {
                it.target == request.requestPhotonId && it.type == RelationType.REFERENCES && it.weight == 1.0
            }
        ) { "Tool-generation approval relation does not match request photon" }

        val fields = parseFields(photon.content, setOf(APPROVAL_HEADER))
        require(requireField(fields, "requestId") == request.id) { "Approval request id mismatch" }
        require(requireField(fields, "requestPhotonId") == request.requestPhotonId.value) {
            "Approval request photon id mismatch"
        }
        require(requireField(fields, "capability") == request.capabilityId.value) {
            "Approval capability mismatch"
        }
        val approvedAt = Instant.parse(requireField(fields, "approvedAt"))
        require(approvedAt == photon.provenance.createdAt) { "Approval timestamp mismatch" }
        val approval = ToolGenerationApproval.issueForExplicitUserAction(
            request = request,
            approverId = photon.provenance.actor,
            approvedAt = approvedAt,
        )
        require(requireField(fields, "approvalId") == approval.id) { "Approval fingerprint mismatch" }
        return approval
    }

    fun toGap(request: GeneratedToolRequest): CapabilityGap = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = request.capabilityId,
            severity = request.severity,
            requiredInputs = request.requiredInputs,
            requiredOutputs = request.requiredOutputs,
        ),
        type = request.gapType,
        candidateProviderIds = request.candidateProviderIds,
    )

    private fun encodeRequest(request: GeneratedToolRequest): String = buildString {
        appendLine(REQUEST_HEADER)
        append("requestId=").appendLine(request.id)
        append("capability=").appendLine(request.capabilityId.value)
        append("severity=").appendLine(request.severity.name)
        append("gapType=").appendLine(request.gapType.name)
        append("requiredInputs=").appendLine(request.requiredInputs.sorted().joinToString(","))
        append("requiredOutputs=").appendLine(request.requiredOutputs.sorted().joinToString(","))
        append("candidateProviders=").append(request.candidateProviderIds.sorted().joinToString(","))
    }

    private fun parseFields(content: String, headers: Set<String>): Map<String, String> {
        val lines = content.lineSequence().toList()
        require(lines.firstOrNull() in headers) { "Generated-tool photon header is invalid" }
        val fields = linkedMapOf<String, String>()
        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Generated-tool photon field is malformed" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(key in REQUEST_KEYS || key in APPROVAL_KEYS) { "Unknown generated-tool photon field: $key" }
            require(fields.put(key, value) == null) { "Duplicate generated-tool photon field: $key" }
        }
        return fields
    }

    private fun requireField(fields: Map<String, String>, key: String): String =
        requireNotNull(fields[key]) { "Generated-tool photon missing field: $key" }

    private fun csv(value: String): Set<String> =
        value.split(',').asSequence().map(String::trim).filter(String::isNotBlank).toSet()

    private val REQUEST_KEYS = setOf(
        "requestId", "capability", "severity", "gapType", "requiredInputs", "requiredOutputs", "candidateProviders"
    )
    private val APPROVAL_KEYS = setOf(
        "approvalId", "requestId", "requestPhotonId", "capability", "approvedAt"
    )
}

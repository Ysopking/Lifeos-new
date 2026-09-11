package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType

/**
 * Honest non-user provenance for autonomous capability-gap admission. This is intent/evidence only;
 * V14 TOOL_REQUEST and TOOL_EXECUTION remain the actual authority checks and this Photon can never
 * activate or promote a generated provider.
 */
object AutonomousToolWorkshopRequestPhoton {
    const val MIME = "application/vnd.lifeos.autonomous-tool-workshop-request+text"
    const val SOURCE = "autonomous-capability-gap-request"
    const val ACTOR = "lifeos"
    private const val HEADER = "LIFEOS_AUTONOMOUS_TOOL_WORKSHOP_REQUEST_V1"
    private const val ID_PREFIX = "autonomous-tool-request_"

    fun create(
        gap: CapabilityGap,
        sourcePhoton: Photon,
    ): Pair<Photon, GeneratedToolRequest> {
        require(sourcePhoton.revision > 0L)
        val id = PhotonId(
            ID_PREFIX + StableFieldIds.fingerprint(
                "autonomous-tool-workshop-request-photon/v1",
                sourcePhoton.id.value,
                sourcePhoton.revision.toString(),
                gap.requirement.capabilityId.value,
                gap.requirement.severity.name,
                gap.type.name,
                *gap.requirement.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
                *gap.requirement.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
                *gap.candidateProviderIds.distinct().sorted().map { "candidate:$it" }.toTypedArray(),
            )
        )
        val request = GeneratedToolRequest.fromGap(
            gap = gap,
            requestPhotonId = id,
            requestedBy = ACTOR,
            requestedAt = sourcePhoton.provenance.createdAt,
        )
        val photon = Photon(
            id = id,
            revision = 1L,
            content = encode(request, sourcePhoton),
            mimeType = MIME,
            phase = PhotonPhase.ACTIVE,
            confidence = 1.0,
            provenance = Provenance(
                source = SOURCE,
                actor = ACTOR,
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(sourcePhoton.id),
            ),
            relations = setOf(
                PhotonRelation(sourcePhoton.id, RelationType.REFERENCES, 1.0),
            ),
            tags = setOf("capability-gap", "tool-request", "autonomous-request", "non-activating"),
        )
        return photon to request
    }

    fun decode(photon: Photon, sourcePhoton: Photon): GeneratedToolRequest {
        require(photon.mimeType == MIME && photon.phase != PhotonPhase.ARCHIVED)
        require(photon.id.value.startsWith(ID_PREFIX))
        require(photon.provenance.source == SOURCE && photon.provenance.actor == ACTOR)
        require(sourcePhoton.id in photon.provenance.parentIds)
        require(
            photon.relations.any {
                it.target == sourcePhoton.id && it.type == RelationType.REFERENCES && it.weight == 1.0
            }
        )
        require(setOf("capability-gap", "tool-request", "autonomous-request", "non-activating")
            .all(photon.tags::contains))
        val fields = parse(photon.content)
        require(fields.getValue("sourcePhotonId") == sourcePhoton.id.value)
        require(fields.getValue("sourceRevision").toLong() == sourcePhoton.revision)
        val gap = CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId(fields.getValue("capability")),
                severity = enumValueOf(fields.getValue("severity")),
                requiredInputs = csv(fields.getValue("requiredInputs")),
                requiredOutputs = csv(fields.getValue("requiredOutputs")),
            ),
            type = enumValueOf(fields.getValue("gapType")),
            candidateProviderIds = csv(fields.getValue("candidateProviders")).toList().sorted(),
        )
        val request = GeneratedToolRequest.fromGap(
            gap = gap,
            requestPhotonId = photon.id,
            requestedBy = photon.provenance.actor,
            requestedAt = photon.provenance.createdAt,
        )
        require(fields.getValue("requestId") == request.id)
        require(!request.activationAllowed)
        return request
    }

    private fun encode(request: GeneratedToolRequest, sourcePhoton: Photon): String = buildString {
        appendLine(HEADER)
        append("requestId=").appendLine(request.id)
        append("sourcePhotonId=").appendLine(sourcePhoton.id.value)
        append("sourceRevision=").appendLine(sourcePhoton.revision)
        append("capability=").appendLine(request.capabilityId.value)
        append("severity=").appendLine(request.severity.name)
        append("gapType=").appendLine(request.gapType.name)
        append("requiredInputs=").appendLine(request.requiredInputs.sorted().joinToString(","))
        append("requiredOutputs=").appendLine(request.requiredOutputs.sorted().joinToString(","))
        append("candidateProviders=").append(request.candidateProviderIds.sorted().joinToString(","))
    }

    private fun parse(content: String): Map<String, String> {
        val lines = content.lineSequence().toList()
        require(lines.firstOrNull() == HEADER)
        val fields = linkedMapOf<String, String>()
        lines.drop(1).filter(String::isNotBlank).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            val key = line.substring(0, separator)
            require(key in KEYS) { "Unknown autonomous workshop request field: $key" }
            require(fields.put(key, line.substring(separator + 1)) == null) {
                "Duplicate autonomous workshop request field: $key"
            }
        }
        require(fields.keys == KEYS)
        return fields
    }

    private fun csv(value: String): Set<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    private val KEYS = linkedSetOf(
        "requestId",
        "sourcePhotonId",
        "sourceRevision",
        "capability",
        "severity",
        "gapType",
        "requiredInputs",
        "requiredOutputs",
        "candidateProviders",
    )
}

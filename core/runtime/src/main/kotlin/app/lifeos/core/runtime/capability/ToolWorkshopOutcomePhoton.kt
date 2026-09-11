package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType

enum class ToolWorkshopEvolutionRoute {
    NOVEL_CANARY,
    HOT_SWAP_CANDIDATE,
    NONE,
}

/**
 * Deterministic terminal evidence for V11. TRIAL_READY becomes an explicit handoff into the already
 * existing evolution/hot-swap safety pipelines; it never claims provider activation. Rejections and
 * interruptions become learning evidence that can be consumed by later workshop strategy tuning.
 */
object ToolWorkshopOutcomePhoton {
    const val MIME = "application/vnd.lifeos.tool-workshop-outcome+text"
    private const val ID_PREFIX = "tool-workshop-outcome_"

    fun create(snapshot: ToolWorkshopJobSnapshot): Photon {
        require(snapshot.terminal) { "Workshop outcome Photon requires a terminal job" }
        val route = route(snapshot)
        val detail = snapshot.lastDetail.orEmpty()
        val id = PhotonId(
            ID_PREFIX + StableFieldIds.fingerprint(
                "tool-workshop-outcome-photon/v1",
                snapshot.definition.id.value,
                snapshot.state.name,
                snapshot.stageFingerprint.orEmpty(),
                detail,
                route.name,
            )
        )
        val requestPhotonId = PhotonId(snapshot.definition.sourcePhotonId)
        return Photon(
            id = id,
            revision = 1L,
            content = buildString {
                appendLine("LIFEOS_TOOL_WORKSHOP_OUTCOME_V1")
                append("jobId=").appendLine(snapshot.definition.id.value)
                append("toolId=").appendLine(snapshot.toolId)
                append("capability=").appendLine(snapshot.definition.capabilityId.value)
                append("state=").appendLine(snapshot.state.name)
                append("route=").appendLine(route.name)
                append("stageFingerprint=").appendLine(snapshot.stageFingerprint.orEmpty())
                append("detail=").append(detail)
            },
            mimeType = MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 0.8,
            energy = 0.5,
            confidence = 1.0,
            provenance = Provenance(
                source = "autonomous-tool-workshop",
                actor = "lifeos",
                createdAt = snapshot.definition.createdAt,
                parentIds = setOf(requestPhotonId),
            ),
            relations = setOf(
                PhotonRelation(requestPhotonId, RelationType.DERIVED_FROM, 1.0),
            ),
            tags = buildSet {
                add("tool-workshop")
                add("tool-workshop-outcome")
                add(snapshot.state.name.lowercase())
                when (route) {
                    ToolWorkshopEvolutionRoute.NOVEL_CANARY,
                    ToolWorkshopEvolutionRoute.HOT_SWAP_CANDIDATE -> {
                        add("evolution-handoff")
                        add("non-activating")
                    }
                    ToolWorkshopEvolutionRoute.NONE -> add("learning-evidence")
                }
            },
        )
    }

    fun route(snapshot: ToolWorkshopJobSnapshot): ToolWorkshopEvolutionRoute = when {
        snapshot.state != ToolWorkshopJobState.TRIAL_READY -> ToolWorkshopEvolutionRoute.NONE
        snapshot.definition.gapType == CapabilityGapType.CAPABILITY_MISSING ->
            ToolWorkshopEvolutionRoute.NOVEL_CANARY
        else -> ToolWorkshopEvolutionRoute.HOT_SWAP_CANDIDATE
    }
}

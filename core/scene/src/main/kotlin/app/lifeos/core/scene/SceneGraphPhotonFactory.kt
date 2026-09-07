package app.lifeos.core.scene

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

data class SceneGraphPhoton(
    val photon: Photon,
    val graph: ProceduralSceneGraph,
)

class SceneGraphPhotonFactory {
    fun create(
        graph: ProceduralSceneGraph,
        goalPhotonId: PhotonId,
        createdAt: Instant = Instant.now(),
    ): SceneGraphPhoton {
        val photon = Photon(
            content = serialize(graph),
            mimeType = "application/vnd.lifeos.scene+text",
            phase = PhotonPhase.CREATED,
            semanticMass = 1.2 + graph.nodes.size * 0.08 + graph.actions.size * 0.12,
            energy = 1.0,
            confidence = graph.confidence,
            provenance = Provenance(
                source = "procedural-scene-core",
                actor = "ProceduralSceneCompiler",
                createdAt = createdAt,
                parentIds = setOf(goalPhotonId),
            ),
            relations = setOf(PhotonRelation(goalPhotonId, RelationType.DERIVED_FROM, graph.confidence)),
            tags = setOf("scene", "scene-graph", "procedural", "render-input"),
        )
        return SceneGraphPhoton(photon, graph)
    }

    private fun serialize(graph: ProceduralSceneGraph): String = buildString {
        append("scene/v1\n")
        append("id=").append(graph.sceneId).append('\n')
        append("confidence=").append(graph.confidence).append('\n')
        append("objective=").append(escape(graph.sourceObjective)).append('\n')
        append("lighting=").append(graph.environment.lightingMode.name).append('\n')
        graph.environment.locationText?.let { append("location=").append(escape(it)).append('\n') }
        graph.environment.dateText?.let { append("date=").append(escape(it)).append('\n') }
        graph.environment.timeText?.let { append("time=").append(escape(it)).append('\n') }
        append("camera=").append(graph.camera.presetId).append('|').append(graph.camera.focalLengthMm).append('\n')
        graph.nodes.sortedBy { it.id.value }.forEach { node ->
            append("node.").append(node.id.value).append('=')
                .append(node.role.name).append('|')
                .append(escape(node.semanticType)).append('|')
                .append(node.geometry.primitive.name).append('|')
                .append(escape(node.geometry.recipeId)).append('|')
                .append(node.geometry.poseId?.let(::escape) ?: "-").append('|')
                .append(node.transform.position.x).append(',')
                .append(node.transform.position.y).append(',')
                .append(node.transform.position.z).append('\n')
        }
        graph.actions.forEachIndexed { index, action ->
            append("action.").append(index).append('=')
                .append(action.type.name).append('|')
                .append(action.actorIds.joinToString(",") { it.value }).append('|')
                .append(action.objectIds.joinToString(",") { it.value }).append('\n')
        }
        graph.warnings.forEach { warning -> append("warning=").append(escape(warning)).append('\n') }
    }.trimEnd()

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("=", "\\=")
        .replace("|", "\\|")
}

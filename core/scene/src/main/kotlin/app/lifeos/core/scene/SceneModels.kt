package app.lifeos.core.scene

@JvmInline
value class SceneNodeId(val value: String) {
    init { require(value.isNotBlank()) }
    companion object { fun deterministic(value: String) = SceneNodeId(value) }
}

data class SceneVec3(val x: Double, val y: Double, val z: Double) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
}

data class SceneTransform(
    val position: SceneVec3 = SceneVec3(0.0, 0.0, 0.0),
    val rotationDeg: SceneVec3 = SceneVec3(0.0, 0.0, 0.0),
    val scale: SceneVec3 = SceneVec3(1.0, 1.0, 1.0),
)

enum class GeometryPrimitive {
    PARAMETRIC_HUMANOID,
    SPHERE,
    PLANE,
    BOX,
}

data class ProceduralGeometry(
    val primitive: GeometryPrimitive,
    val recipeId: String,
    val dimensionsMeters: SceneVec3,
    val poseId: String? = null,
    val materialClass: String,
    val seed: Long,
) {
    init {
        require(recipeId.isNotBlank())
        require(dimensionsMeters.x > 0.0 && dimensionsMeters.y > 0.0 && dimensionsMeters.z > 0.0)
        require(materialClass.isNotBlank())
    }
}

enum class SceneNodeRole { ACTOR, OBJECT, GROUND, ENVIRONMENT }

data class SceneNode(
    val id: SceneNodeId,
    val role: SceneNodeRole,
    val semanticType: String,
    val geometry: ProceduralGeometry,
    val transform: SceneTransform,
    val confidence: Double,
) {
    init {
        require(semanticType.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

enum class SceneActionType {
    PLAY_FOOTBALL,
    RUN,
    SIT,
    STAND,
    GENERIC_INTERACTION,
}

data class SceneAction(
    val type: SceneActionType,
    val actorIds: List<SceneNodeId>,
    val objectIds: List<SceneNodeId> = emptyList(),
    val confidence: Double,
) {
    init {
        require(actorIds.isNotEmpty())
        require(confidence in 0.0..1.0)
    }
}

enum class LightingMode {
    ASTRONOMICAL_IF_RESOLVED,
    SYNTHETIC_NEUTRAL,
}

data class SceneEnvironment(
    val locationText: String? = null,
    val dateText: String? = null,
    val timeText: String? = null,
    val lightingMode: LightingMode,
    val groundMaterial: String,
    val resolvedLatitudeDeg: Double? = null,
    val resolvedLongitudeDeg: Double? = null,
    val resolvedZoneId: String? = null,
    val resolvedInstantUtc: String? = null,
    val sunAzimuthDeg: Double? = null,
    val sunElevationDeg: Double? = null,
) {
    init {
        resolvedLatitudeDeg?.let { require(it in -90.0..90.0) }
        resolvedLongitudeDeg?.let { require(it in -180.0..180.0) }
        resolvedZoneId?.let { require(it.isNotBlank()) }
        resolvedInstantUtc?.let { require(it.isNotBlank()) }
        sunAzimuthDeg?.let { require(it.isFinite()) }
        sunElevationDeg?.let { require(it.isFinite()) }
        val geoComplete = (resolvedLatitudeDeg == null) == (resolvedLongitudeDeg == null)
        require(geoComplete) { "Resolved latitude and longitude must be present together" }
    }
}

data class SceneCamera(
    val presetId: String = "scene-medium-action-v1",
    val focalLengthMm: Double = 42.0,
    val position: SceneVec3 = SceneVec3(0.0, -8.0, 2.1),
    val target: SceneVec3 = SceneVec3(0.0, 0.0, 1.0),
) {
    init {
        require(presetId.isNotBlank())
        require(focalLengthMm > 0.0)
    }
}

data class ProceduralSceneGraph(
    val sceneId: String,
    val sourceObjective: String,
    val nodes: List<SceneNode>,
    val actions: List<SceneAction>,
    val environment: SceneEnvironment,
    val camera: SceneCamera,
    val styleHints: Set<String>,
    val confidence: Double,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(sceneId.isNotBlank())
        require(sourceObjective.isNotBlank())
        require(nodes.isNotEmpty())
        require(confidence in 0.0..1.0)
        val ids = nodes.map { it.id }
        require(ids.distinct().size == ids.size) { "Scene node ids must be unique" }
        val idSet = ids.toSet()
        actions.forEach { action ->
            require(action.actorIds.all { it in idSet })
            require(action.objectIds.all { it in idSet })
        }
    }
}

sealed interface SceneCompileResult {
    data class Compiled(val graph: ProceduralSceneGraph) : SceneCompileResult
    data class Blocked(val reasons: List<String>) : SceneCompileResult {
        init { require(reasons.isNotEmpty()) }
    }
}

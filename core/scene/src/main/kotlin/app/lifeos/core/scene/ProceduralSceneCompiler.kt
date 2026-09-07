package app.lifeos.core.scene

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.SemanticEntity
import java.security.MessageDigest
import kotlin.math.min

class ProceduralSceneCompiler {
    fun compile(goal: GoalFrame): SceneCompileResult {
        if (goal.intent != IntentType.CREATE_IMAGE) {
            return SceneCompileResult.Blocked(listOf("goal intent is not CREATE_IMAGE"))
        }
        val severeAmbiguities = goal.ambiguities.filter { it.severity >= 0.90 }
        if (severeAmbiguities.isNotEmpty()) {
            return SceneCompileResult.Blocked(severeAmbiguities.map { "language ambiguity:${it.code}" })
        }

        val persons = goal.entities.filter { it.type == EntityType.PERSON }
        val objects = goal.entities.filter { it.type == EntityType.OBJECT }
        if (persons.isEmpty() && objects.isEmpty()) {
            return SceneCompileResult.Blocked(listOf("no supported procedural scene subject was understood"))
        }

        val nodes = mutableListOf<SceneNode>()
        val warnings = mutableListOf<String>()
        val personCount = resolvePersonCount(goal.entities, persons)
        if (persons.isNotEmpty()) {
            repeat(personCount) { index -> nodes += humanNode(index, personCount, goal.confidence) }
        }
        objects.forEachIndexed { index, entity ->
            objectNode(entity, index, goal.confidence)?.let(nodes::add)
                ?: warnings.add("unsupported object:${entity.normalizedValue}")
        }
        if (nodes.none { it.role == SceneNodeRole.ACTOR || it.role == SceneNodeRole.OBJECT }) {
            return SceneCompileResult.Blocked(listOf("understood subjects have no procedural geometry recipe"))
        }

        val action = resolveAction(goal, nodes)
        val football = action?.type == SceneActionType.PLAY_FOOTBALL
        nodes += groundNode(football)

        val location = goal.entities.firstOrNull { it.type == EntityType.LOCATION }?.normalizedValue
        val date = goal.entities.firstOrNull { it.type == EntityType.DATE }?.normalizedValue
        val time = goal.entities.firstOrNull { it.type == EntityType.TIME }?.normalizedValue
        val lightingMode = if (location != null && date != null && time != null) {
            LightingMode.ASTRONOMICAL_IF_RESOLVED
        } else {
            LightingMode.SYNTHETIC_NEUTRAL
        }
        if (location != null && lightingMode != LightingMode.ASTRONOMICAL_IF_RESOLVED) {
            warnings += "location present without complete date/time; astronomical lighting deferred"
        }

        val styleHints = goal.entities
            .filter { it.type == EntityType.STYLE }
            .mapTo(linkedSetOf()) { it.normalizedValue }
        val confidenceInputs = goal.entities
            .filter { it.type in setOf(EntityType.PERSON, EntityType.OBJECT, EntityType.ACTION, EntityType.LOCATION, EntityType.DATE, EntityType.TIME) }
            .map { it.confidence }
        val sceneConfidence = min(
            goal.confidence,
            if (confidenceInputs.isEmpty()) goal.confidence else confidenceInputs.average(),
        )

        return SceneCompileResult.Compiled(
            ProceduralSceneGraph(
                sceneId = deterministicSceneId(goal.objective),
                sourceObjective = goal.objective,
                nodes = nodes,
                actions = listOfNotNull(action),
                environment = SceneEnvironment(
                    locationText = location,
                    dateText = date,
                    timeText = time,
                    lightingMode = lightingMode,
                    groundMaterial = if (football) "grass-diffuse" else "neutral-ground",
                ),
                camera = cameraFor(action, personCount),
                styleHints = styleHints,
                confidence = sceneConfidence,
                warnings = warnings,
            )
        )
    }

    private fun resolvePersonCount(entities: List<SemanticEntity>, persons: List<SemanticEntity>): Int {
        if (persons.isEmpty()) return 0
        val firstPerson = persons.minBy { it.tokenStart }
        val nearbyNumber = entities
            .filter { it.type == EntityType.NUMBER && it.tokenEndExclusive <= firstPerson.tokenStart }
            .filter { firstPerson.tokenStart - it.tokenEndExclusive <= 2 }
            .maxByOrNull { it.tokenEndExclusive }
            ?.normalizedValue
            ?.toIntOrNull()
        return (nearbyNumber ?: persons.size).coerceIn(1, 16)
    }

    private fun humanNode(index: Int, total: Int, confidence: Double): SceneNode {
        val spacing = 1.8
        val x = (index - (total - 1) / 2.0) * spacing
        val pose = when {
            total >= 2 && index == 0 -> "football-kick-prep-v1"
            total >= 2 && index == 1 -> "football-receive-ready-v1"
            else -> "neutral-standing-v1"
        }
        return SceneNode(
            id = SceneNodeId.deterministic("actor-$index"),
            role = SceneNodeRole.ACTOR,
            semanticType = "person",
            geometry = ProceduralGeometry(
                primitive = GeometryPrimitive.PARAMETRIC_HUMANOID,
                recipeId = "humanoid-proportions-adult-neutral-v1",
                dimensionsMeters = SceneVec3(0.55, 0.32, 1.72),
                poseId = pose,
                materialClass = "human-generic-layered",
                seed = stableSeed("actor-$index"),
            ),
            transform = SceneTransform(position = SceneVec3(x, 0.0, 0.86)),
            confidence = confidence,
        )
    }

    private fun objectNode(entity: SemanticEntity, index: Int, confidence: Double): SceneNode? {
        val normalized = entity.normalizedValue
        val recipe = when (normalized) {
            "fussball", "football", "soccer", "ball" -> ProceduralGeometry(
                primitive = GeometryPrimitive.SPHERE,
                recipeId = "football-size5-v1",
                dimensionsMeters = SceneVec3(0.22, 0.22, 0.22),
                materialClass = "football-leather",
                seed = stableSeed("football-$index"),
            )
            "auto", "car" -> ProceduralGeometry(
                primitive = GeometryPrimitive.BOX,
                recipeId = "car-envelope-generic-v1",
                dimensionsMeters = SceneVec3(4.4, 1.8, 1.5),
                materialClass = "painted-metal-glass",
                seed = stableSeed("car-$index"),
            )
            "haus", "house" -> ProceduralGeometry(
                primitive = GeometryPrimitive.BOX,
                recipeId = "house-envelope-generic-v1",
                dimensionsMeters = SceneVec3(8.0, 6.0, 5.0),
                materialClass = "building-generic",
                seed = stableSeed("house-$index"),
            )
            else -> null
        } ?: return null
        val position = if (recipe.primitive == GeometryPrimitive.SPHERE) {
            SceneVec3(0.0, 0.75, recipe.dimensionsMeters.z / 2.0)
        } else {
            SceneVec3(0.0, 2.5 + index * 2.0, recipe.dimensionsMeters.z / 2.0)
        }
        return SceneNode(
            id = SceneNodeId.deterministic("object-$index-$normalized"),
            role = SceneNodeRole.OBJECT,
            semanticType = normalized,
            geometry = recipe,
            transform = SceneTransform(position = position),
            confidence = min(confidence, entity.confidence),
        )
    }

    private fun resolveAction(goal: GoalFrame, nodes: List<SceneNode>): SceneAction? {
        val actionEntity = goal.entities.firstOrNull { it.type == EntityType.ACTION }
        val actors = nodes.filter { it.role == SceneNodeRole.ACTOR }.map { it.id }
        if (actors.isEmpty()) return null
        val objects = nodes.filter { it.role == SceneNodeRole.OBJECT }
        val hasFootball = objects.any { it.semanticType in setOf("fussball", "football", "soccer", "ball") }
        val type = when {
            hasFootball && actionEntity?.normalizedValue in setOf("spielen", "spielt", "play", "playing") -> SceneActionType.PLAY_FOOTBALL
            actionEntity?.normalizedValue in setOf("laufen", "laufend", "run", "running") -> SceneActionType.RUN
            actionEntity?.normalizedValue in setOf("sitzen", "sitzt", "sit", "sitting") -> SceneActionType.SIT
            actionEntity?.normalizedValue in setOf("stehen", "steht", "stand", "standing") -> SceneActionType.STAND
            actionEntity != null -> SceneActionType.GENERIC_INTERACTION
            else -> return null
        }
        return SceneAction(
            type = type,
            actorIds = actors,
            objectIds = if (type == SceneActionType.PLAY_FOOTBALL) objects.filter { it.semanticType in setOf("fussball", "football", "soccer", "ball") }.map { it.id } else emptyList(),
            confidence = actionEntity?.confidence ?: goal.confidence,
        )
    }

    private fun groundNode(football: Boolean) = SceneNode(
        id = SceneNodeId.deterministic("ground-0"),
        role = SceneNodeRole.GROUND,
        semanticType = if (football) "football-ground" else "ground",
        geometry = ProceduralGeometry(
            primitive = GeometryPrimitive.PLANE,
            recipeId = if (football) "football-pitch-local-v1" else "neutral-ground-v1",
            dimensionsMeters = SceneVec3(30.0, 20.0, 0.05),
            materialClass = if (football) "grass-diffuse" else "neutral-ground",
            seed = 1L,
        ),
        transform = SceneTransform(position = SceneVec3(0.0, 0.0, -0.025)),
        confidence = 1.0,
    )

    private fun cameraFor(action: SceneAction?, personCount: Int): SceneCamera = when (action?.type) {
        SceneActionType.PLAY_FOOTBALL -> SceneCamera(
            presetId = "football-medium-action-v1",
            focalLengthMm = 50.0,
            position = SceneVec3(0.0, -10.0, 2.2),
            target = SceneVec3(0.0, 0.5, 1.0),
        )
        else -> SceneCamera(
            presetId = if (personCount > 3) "group-wide-v1" else "scene-medium-action-v1",
            focalLengthMm = if (personCount > 3) 35.0 else 42.0,
        )
    }

    private fun deterministicSceneId(objective: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(objective.toByteArray(Charsets.UTF_8))
        return "scene-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun stableSeed(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (digest[i].toLong() and 0xffL)
        return result
    }
}

package app.lifeos.core.scene

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProceduralSceneCompilerTest {
    private val language = LanguageUnderstandingEngine()
    private val compiler = ProceduralSceneCompiler()

    @Test
    fun `football request becomes two humanoids ball ground action and camera`() {
        val understood = language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")
        assertEquals(IntentType.CREATE_IMAGE, understood.goal.intent)
        assertTrue(understood.goal.entities.any { it.type == EntityType.PERSON })

        val result = assertIs<SceneCompileResult.Compiled>(compiler.compile(understood.goal))
        val graph = result.graph

        assertEquals(2, graph.nodes.count { it.role == SceneNodeRole.ACTOR })
        val ball = graph.nodes.single { it.semanticType == "fussball" }
        assertEquals(GeometryPrimitive.SPHERE, ball.geometry.primitive)
        assertEquals("football-size5-v1", ball.geometry.recipeId)
        assertEquals(1, graph.nodes.count { it.role == SceneNodeRole.GROUND })
        assertEquals(SceneActionType.PLAY_FOOTBALL, graph.actions.single().type)
        assertEquals(2, graph.actions.single().actorIds.size)
        assertEquals(listOf(ball.id), graph.actions.single().objectIds)
        assertEquals("football-medium-action-v1", graph.camera.presetId)
        assertEquals("grass-diffuse", graph.environment.groundMaterial)
        assertEquals(LightingMode.SYNTHETIC_NEUTRAL, graph.environment.lightingMode)
    }

    @Test
    fun `complete location date and time request selects astronomical lighting intent`() {
        val understood = language.understand(
            "Erzeuge ein Bild von zwei Leuten, die in Berlin am 07.09.2026 um 17:30 Fußball spielen."
        )
        val graph = assertIs<SceneCompileResult.Compiled>(compiler.compile(understood.goal)).graph

        assertEquals("berlin", graph.environment.locationText)
        assertEquals("07.09.2026", graph.environment.dateText)
        assertEquals("17:30", graph.environment.timeText)
        assertEquals(LightingMode.ASTRONOMICAL_IF_RESOLVED, graph.environment.lightingMode)
    }

    @Test
    fun `same semantic objective produces stable scene id geometry seeds and poses`() {
        val goal = language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.").goal
        val first = assertIs<SceneCompileResult.Compiled>(compiler.compile(goal)).graph
        val second = assertIs<SceneCompileResult.Compiled>(compiler.compile(goal)).graph

        assertEquals(first.sceneId, second.sceneId)
        assertEquals(first.nodes.map { it.geometry.seed }, second.nodes.map { it.geometry.seed })
        assertEquals(first.nodes.map { it.geometry.poseId }, second.nodes.map { it.geometry.poseId })
    }

    @Test
    fun `unsupported empty scene remains blocked rather than inventing geometry`() {
        val goal = language.understand("Erzeuge ein Bild.").goal
        val result = assertIs<SceneCompileResult.Blocked>(compiler.compile(goal))
        assertTrue(result.reasons.any { it.contains("no supported procedural scene subject") })
    }

    @Test
    fun `scene graph becomes derived photon with goal provenance`() {
        val understood = language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")
        val goal = GoalPhotonFactory().create(
            understood,
            sourcePhotonId = PhotonId("chat-source"),
            createdAt = Instant.parse("2026-09-07T18:00:00Z"),
        )
        val graph = assertIs<SceneCompileResult.Compiled>(compiler.compile(understood.goal)).graph
        val scene = SceneGraphPhotonFactory().create(
            graph = graph,
            goalPhotonId = goal.photon.id,
            createdAt = Instant.parse("2026-09-07T18:00:01Z"),
        )

        assertEquals("application/vnd.lifeos.scene+text", scene.photon.mimeType)
        assertTrue("scene-graph" in scene.photon.tags)
        assertEquals(setOf(goal.photon.id), scene.photon.provenance.parentIds)
        val relation = scene.photon.relations.single()
        assertEquals(goal.photon.id, relation.target)
        assertEquals(RelationType.DERIVED_FROM, relation.type)
        assertTrue(scene.photon.content.startsWith("scene/v1\n"))
    }
}

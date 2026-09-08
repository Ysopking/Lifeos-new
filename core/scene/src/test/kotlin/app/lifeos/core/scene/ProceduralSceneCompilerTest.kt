package app.lifeos.core.scene

import app.lifeos.core.language.GoalPhotonFactory
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
    fun `two people playing football compile into deterministic scene graph`() {
        val understood = language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")
        val result = assertIs<SceneCompileResult.Compiled>(compiler.compile(understood.goal))
        val graph = result.graph

        assertEquals(2, graph.nodes.count { it.role == SceneNodeRole.ACTOR })
        assertTrue(graph.nodes.any { it.semanticType == "fussball" })
        assertTrue(graph.nodes.any { it.role == SceneNodeRole.GROUND })
        assertEquals(SceneActionType.PLAY_FOOTBALL, graph.actions.single().type)
        assertEquals("football-medium-action-v1", graph.camera.presetId)
    }

    @Test
    fun `same objective produces stable scene identity and geometry seeds`() {
        val a = assertIs<SceneCompileResult.Compiled>(
            compiler.compile(language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.").goal)
        ).graph
        val b = assertIs<SceneCompileResult.Compiled>(
            compiler.compile(language.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.").goal)
        ).graph

        assertEquals(a.sceneId, b.sceneId)
        assertEquals(a.nodes.map { it.geometry.seed }, b.nodes.map { it.geometry.seed })
        assertEquals(a.nodes.map { it.transform }, b.nodes.map { it.transform })
    }

    @Test
    fun `location date and time request astronomical lighting`() {
        val graph = assertIs<SceneCompileResult.Compiled>(
            compiler.compile(
                language.understand(
                    "Erzeuge ein Bild von zwei Leuten die in Berlin am 07.09.2026 um 17:30 Fußball spielen."
                ).goal
            )
        ).graph

        assertEquals("berlin", graph.environment.locationText)
        assertEquals("07.09.2026", graph.environment.dateText)
        assertEquals("17:30", graph.environment.timeText)
        assertEquals(LightingMode.ASTRONOMICAL_IF_RESOLVED, graph.environment.lightingMode)
    }

    @Test
    fun `unsupported subject blocks instead of inventing geometry`() {
        val result = compiler.compile(language.understand("Erzeuge ein Bild einer Galaxie.").goal)
        assertIs<SceneCompileResult.Blocked>(result)
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
        assertTrue(scene.photon.content.startsWith("scene/v2\n"))
    }
}

package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalKnowledgeGoalEngineTest {
    private val engine = LocalKnowledgeGoalEngine()
    private val now = Instant.parse("2026-09-10T18:00:00Z")

    @Test
    fun `memory stores stripped payload and exact source provenance`() {
        val source = photon("source", "Merke dir, dass meine Balkonbank 68 cm hoch ist.")
        val goalId = PhotonId("goal")

        val result = assertIs<LocalKnowledgeGoalResult.Produced>(
            engine.execute(
                goal = goal(IntentType.STORE_OR_REMEMBER, "store_or_remember: ${source.content}"),
                sourcePhoton = source,
                goalPhotonId = goalId,
                photons = listOf(source),
                createdAt = now,
            )
        )

        assertEquals(LocalKnowledgeGoalKind.MEMORY_STORED, result.kind)
        assertEquals("meine Balkonbank 68 cm hoch ist.", result.photon.content)
        assertEquals(LocalKnowledgeGoalEngine.MEMORY_MIME, result.photon.mimeType)
        assertTrue("memory" in result.photon.tags)
        assertEquals(setOf(source.id, goalId), result.photon.provenance.parentIds)
        assertEquals(listOf(source.id), result.evidencePhotonIds)
        assertTrue(result.photon.relations.any { it.target == source.id && it.type == RelationType.DERIVED_FROM })
    }

    @Test
    fun `query returns only matching local evidence in deterministic order`() {
        val source = photon("source", "Was weißt du über meine Balkonbank?", createdAt = now)
        val memory = photon(
            "memory",
            "Meine Balkonbank ist 68 cm hoch und steht auf Holzbalken.",
            tags = setOf("memory"),
            createdAt = now.minusSeconds(30),
        )
        val weaker = photon(
            "weaker",
            "Die Balkonbank bekommt eine Holzauflage.",
            createdAt = now.minusSeconds(10),
        )
        val unrelated = photon("other", "Heute gibt es Nudeln.")
        val oldAnswer = photon(
            "old-answer",
            "Balkonbank Balkonbank 68 cm",
            tags = setOf("local-query-answer"),
        )
        val toolRequest = photon(
            "request",
            "capability=Balkonbank",
            tags = setOf("tool-request"),
        )

        val result = assertIs<LocalKnowledgeGoalResult.Produced>(
            engine.execute(
                goal = goal(IntentType.QUERY, "query: ${source.content}"),
                sourcePhoton = source,
                goalPhotonId = PhotonId("goal"),
                photons = listOf(unrelated, weaker, oldAnswer, memory, toolRequest, source),
                createdAt = now,
            )
        )

        assertEquals(LocalKnowledgeGoalKind.QUERY_ANSWER, result.kind)
        assertEquals(listOf(memory.id, weaker.id), result.evidencePhotonIds)
        assertTrue(result.photon.content.contains(memory.content))
        assertTrue(result.photon.content.contains(weaker.content))
        assertFalse(result.photon.content.contains(unrelated.content))
        assertFalse(result.evidencePhotonIds.contains(oldAnswer.id))
        assertFalse(result.evidencePhotonIds.contains(toolRequest.id))
        assertEquals(LocalKnowledgeGoalEngine.ANSWER_MIME, result.photon.mimeType)
        assertTrue("evidence-backed" in result.photon.tags)
    }

    @Test
    fun `query with no matching local evidence states no match instead of synthesizing`() {
        val source = photon("source", "Was weißt du über Quantenananas?")
        val result = assertIs<LocalKnowledgeGoalResult.Produced>(
            engine.execute(
                goal = goal(IntentType.QUERY, "query: ${source.content}"),
                sourcePhoton = source,
                goalPhotonId = PhotonId("goal"),
                photons = listOf(source, photon("other", "Meine Balkonbank ist aus Holz.")),
                createdAt = now,
            )
        )

        assertEquals(emptyList(), result.evidencePhotonIds)
        assertEquals("Keine passende lokale Information gefunden.", result.photon.content)
        assertTrue(result.photon.relations.none { it.target == PhotonId("other") })
    }

    @Test
    fun `continue remains unsupported until a real resume executor exists`() {
        val source = photon("source", "Weiter")
        val result = engine.execute(
            goal = goal(IntentType.CONTINUE, "continue: Weiter"),
            sourcePhoton = source,
            goalPhotonId = PhotonId("goal"),
            photons = listOf(source),
            createdAt = now,
        )

        assertEquals(LocalKnowledgeGoalResult.Unsupported(IntentType.CONTINUE), result)
        assertFalse(engine.supports(IntentType.CONTINUE))
    }

    private fun goal(intent: IntentType, objective: String) = GoalFrame(
        intent = intent,
        objective = objective,
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.92,
        language = LanguageCode.DE,
    )

    private fun photon(
        id: String,
        content: String,
        tags: Set<String> = setOf("chat"),
        createdAt: Instant = now.minusSeconds(60),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance("test", "user", createdAt),
        tags = tags,
    )
}

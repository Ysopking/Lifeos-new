package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalConversationGoalEngineTest {
    private val engine = LocalConversationGoalEngine()
    private val now = Instant.parse("2026-09-14T12:30:00Z")

    @Test
    fun germanGreetingProducesBoundedConversationFact() {
        val source = photon("source", "Hallo", tags = setOf("chat", "chat:user"))
        val result = engine.execute(
            goal = conversationGoal(LanguageCode.DE, "conversation: Hallo"),
            sourcePhoton = source,
            goalPhotonId = PhotonId("goal"),
            photons = listOf(source),
            createdAt = now,
        ) as LocalConversationGoalResult.Produced

        assertEquals(LocalConversationMove.GREETING, result.move)
        assertEquals("Hallo. Ich bin bereit.", result.photon.content)
        assertTrue("local-conversation-response" in result.photon.tags)
        assertTrue(result.evidencePhotonIds.isEmpty())
    }

    @Test
    fun englishGratitudeProducesEnglishConversationFact() {
        val source = photon("source", "Thank you", tags = setOf("chat", "chat:user"))
        val result = engine.execute(
            goal = conversationGoal(LanguageCode.EN, "conversation: Thank you"),
            sourcePhoton = source,
            goalPhotonId = PhotonId("goal"),
            photons = listOf(source),
            createdAt = now,
        ) as LocalConversationGoalResult.Produced

        assertEquals(LocalConversationMove.GRATITUDE, result.move)
        assertEquals("You're welcome.", result.photon.content)
    }

    @Test
    fun contextualConversationUsesOnlyMatchingDurableLocalEvidence() {
        val source = photon("source", "LIFEOS photon memory context", tags = setOf("chat", "chat:user"))
        val matching = photon(
            "memory",
            "Der LIFEOS Photon-Kontext bleibt dauerhaft im lokalen Memory erhalten.",
            tags = setOf("memory"),
            confidence = 0.92,
        )
        val unrelated = photon(
            "other",
            "Heute scheint die Sonne.",
            tags = setOf("memory"),
        )
        val result = engine.execute(
            goal = conversationGoal(LanguageCode.DE, "conversation: LIFEOS photon memory context"),
            sourcePhoton = source,
            goalPhotonId = PhotonId("goal"),
            photons = listOf(unrelated, matching, source),
            createdAt = now,
        ) as LocalConversationGoalResult.Produced

        assertEquals(LocalConversationMove.CONTEXTUAL_REPLY, result.move)
        assertEquals(listOf(matching.id), result.evidencePhotonIds)
        assertTrue(result.photon.content.contains("LIFEOS Photon-Kontext"))
        assertTrue(matching.id in result.photon.provenance.parentIds)
        assertTrue(unrelated.id !in result.photon.provenance.parentIds)
    }

    private fun conversationGoal(language: LanguageCode, objective: String) = GoalFrame(
        intent = IntentType.CONVERSATION,
        objective = objective,
        confidence = 0.95,
        language = language,
    )

    private fun photon(
        id: String,
        content: String,
        tags: Set<String> = emptySet(),
        confidence: Double = 1.0,
    ) = Photon(
        id = PhotonId(id),
        content = content,
        confidence = confidence,
        provenance = Provenance("test", "tester", now.minusSeconds(10)),
        tags = tags,
    )
}

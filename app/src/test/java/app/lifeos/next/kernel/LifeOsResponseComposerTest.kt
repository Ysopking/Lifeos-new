package app.lifeos.next.kernel

import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsResponseComposerTest {
    private val understanding = LanguageUnderstandingEngine()

    @Test
    fun conversationUsesNaturalSemanticResponseInsteadOfInternalMetadataOrLegacyCannedReply() {
        val result = submission("Hallo")

        val response = LifeOsResponseComposer.compose(result)

        assertFalse(response == "Ich bin da. Wobei soll ich dir helfen?")
        assertTrue(response in setOf("Hallo!", "Hi!", "Hey!"))
        assertFalse(response.contains("Gesprächskontext", ignoreCase = true))
        assertFalse(response.contains("semantisch erfasst", ignoreCase = true))
    }

    @Test
    fun englishConversationGeneratesNaturalEnglishCourtesySurface() {
        // "Thank you" is deterministically classified as CONVERSATION and carries an English marker.
        val result = submission("Thank you")

        val response = LifeOsResponseComposer.compose(result)

        assertTrue(response in setOf("You're welcome!", "Gladly!", "Of course!"))
        assertFalse(response.contains("conversation context", ignoreCase = true))
        assertFalse(response.contains("captured semantically", ignoreCase = true))
        assertFalse(response.contains("Gesprächskontext", ignoreCase = true))
    }

    @Test
    fun germanCheckInProducesNaturalReadyResponse() {
        val result = submission("Wie geht es dir?")

        val response = LifeOsResponseComposer.compose(result)

        assertTrue(response.contains("bereit", ignoreCase = true))
        assertFalse(response.contains("Gesprächskontext", ignoreCase = true))
        assertFalse(response.contains("semantisch erfasst", ignoreCase = true))
    }

    @Test
    fun evidenceBackedKnowledgeAnswerSurvivesSemanticResponseGeneration() {
        val result = submission("Was weißt du über Berlin?").copy(
            localKnowledge = LocalKnowledgeExecutionResult.Produced(
                kind = LocalKnowledgeGoalKind.QUERY_ANSWER,
                output = PhotonSubmissionResult(
                    photon = Photon(
                        content = "Berlin ist als lokale Evidenz gespeichert.",
                        confidence = 0.91,
                        provenance = Provenance(
                            source = "test-answer",
                            actor = "lifeos",
                            createdAt = Instant.parse("2026-09-14T12:00:00Z"),
                        ),
                        tags = setOf("answer", "evidence-backed"),
                    ),
                    processingQueued = true,
                ),
                evidencePhotonIds = emptyList(),
            )
        )

        val response = LifeOsResponseComposer.compose(result)

        assertTrue(response.contains("Berlin", ignoreCase = true))
        assertTrue(response.contains("Evidenz", ignoreCase = true))
    }

    private fun submission(text: String): LanguageSubmissionResult {
        val source = Photon(
            content = text,
            provenance = Provenance(
                source = "test-chat",
                actor = "user",
                createdAt = Instant.parse("2026-09-14T12:00:00Z"),
            ),
            tags = setOf("chat", "chat:user"),
        )
        return LanguageSubmissionResult(
            source = PhotonSubmissionResult(source, processingQueued = true),
            understanding = understanding.understand(text),
        )
    }
}

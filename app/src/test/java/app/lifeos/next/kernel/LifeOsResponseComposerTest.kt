package app.lifeos.next.kernel

import app.lifeos.core.language.GoalPhotonFactory
import app.lifeos.core.language.LanguageUnderstandingEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.goal.LocalConversationMove
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeOsResponseComposerTest {
    private val understanding = LanguageUnderstandingEngine()
    private val goalPhotonFactory = GoalPhotonFactory()
    private val at = Instant.parse("2026-09-14T12:00:00Z")

    @AfterTest
    fun clearConversationResults() {
        ConversationExecutionResultRegistry.clear()
    }

    @Test
    fun conversationUsesDurablePlannedFactAndSemanticGenerationInsteadOfLegacyCannedReply() {
        val result = submission("Hallo")
        publishConversation(
            result = result,
            move = LocalConversationMove.GREETING,
            content = "Hallo. Ich bin bereit.",
        )

        val response = LifeOsResponseComposer.compose(result)

        assertFalse(response == "Ich bin da. Wobei soll ich dir helfen?")
        assertTrue(response.contains("Hallo", ignoreCase = true))
        assertTrue(response.contains("bereit", ignoreCase = true))
    }

    @Test
    fun englishConversationUsesPlannedEnglishFact() {
        // "Thank you" is both deterministically classified as CONVERSATION and carries the English
        // marker "you"; a bare "Hello" intentionally remains language-UNKNOWN in the normalizer.
        val result = submission("Thank you")
        publishConversation(
            result = result,
            move = LocalConversationMove.GRATITUDE,
            content = "You're welcome.",
        )

        val response = LifeOsResponseComposer.compose(result)

        assertTrue(response.contains("welcome", ignoreCase = true))
        assertFalse(response.contains("Gespräch", ignoreCase = true))
    }

    @Test
    fun evidenceGroundedConversationPreservesPlannerEvidenceInOwnerVisibleText() {
        val result = submission("LIFEOS photon memory context")
        publishConversation(
            result = result,
            move = LocalConversationMove.CONTEXTUAL_REPLY,
            content = "Ich habe deinen Gesprächsbeitrag erfasst. Dazu passt aus deinem lokalen Kontext: Der LIFEOS Photon-Kontext bleibt im lokalen Memory erhalten.",
            evidencePhotonIds = listOf(PhotonId("memory-evidence")),
        )

        val response = LifeOsResponseComposer.compose(result)

        assertTrue(response.contains("Evidenz", ignoreCase = true))
        assertTrue(response.contains("LIFEOS Photon-Kontext", ignoreCase = true))
        assertTrue(response.contains("Memory", ignoreCase = true))
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
                            createdAt = at,
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

    private fun publishConversation(
        result: LanguageSubmissionResult,
        move: LocalConversationMove,
        content: String,
        evidencePhotonIds: List<PhotonId> = emptyList(),
    ) {
        val goalId = requireNotNull(result.goalPhoton).photon.id
        ConversationExecutionResultRegistry.publish(
            goalId,
            LocalConversationExecutionResult.Produced(
                move = move,
                photon = Photon(
                    content = content,
                    confidence = 0.95,
                    provenance = Provenance(
                        source = "local-conversation-planner",
                        actor = "LocalConversationGoalEngine",
                        createdAt = at,
                        parentIds = setOf(goalId) + evidencePhotonIds,
                    ),
                    tags = setOf("conversation-response", "local-conversation-response", "result"),
                ),
                evidencePhotonIds = evidencePhotonIds,
            )
        )
    }

    private fun submission(text: String): LanguageSubmissionResult {
        val source = Photon(
            content = text,
            provenance = Provenance(
                source = "test-chat",
                actor = "user",
                createdAt = at,
            ),
            tags = setOf("chat", "chat:user"),
        )
        val parsed = understanding.understand(text)
        val goalPhoton = goalPhotonFactory.create(
            result = parsed,
            sourcePhotonId = source.id,
            createdAt = at,
        )
        return LanguageSubmissionResult(
            source = PhotonSubmissionResult(source, processingQueued = true),
            understanding = parsed,
            goalPhoton = goalPhoton,
        )
    }
}

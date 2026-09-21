package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.language.ReferenceExpression
import app.lifeos.core.language.ReferenceKind
import app.lifeos.core.language.ResolvedReference
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalCommunicationGoalEngineTest {
    private val at = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `resolved reference wins over newer result`() {
        val target = photon("answer-a", "Gewählte Antwort", tags = setOf("answer", "result"), secondsAgo = 120)
        val newer = photon("answer-b", "Neuere Antwort", tags = setOf("answer", "result"), secondsAgo = 30)
        val result = LocalCommunicationGoalEngine().prepare(
            goal = goal(
                references = listOf(
                    ResolvedReference(
                        expression = ReferenceExpression(ReferenceKind.THIS, "dieses Ergebnis", setOf("result"), 0.9),
                        targetPhotonId = target.id,
                        score = 0.91,
                    )
                )
            ),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-share"),
            photons = listOf(target, newer),
        )

        val prepared = assertIs<LocalCommunicationGoalResult.Prepared>(result).share
        assertEquals(target.id, prepared.target.id)
        assertEquals(LocalShareKind.TEXT, prepared.kind)
        assertEquals("text/plain", prepared.mediaType)
    }

    @Test
    fun `exact bound semantic result wins without context lookup`() {
        val bound = photon(
            "memory-bound",
            "Semantic-Recovery-Notiz",
            mime = LocalKnowledgeGoalEngine.MEMORY_MIME,
            tags = setOf("memory", "local-memory"),
        )

        val prepared = assertIs<LocalCommunicationGoalResult.Prepared>(
            LocalCommunicationGoalEngine().prepare(
                goal = goal(),
                sourcePhoton = source(),
                goalPhotonId = PhotonId("goal-share"),
                photons = emptyList(),
                boundResultPhoton = bound,
            )
        ).share

        assertEquals(bound, prepared.target)
    }

    @Test
    fun `unshareable exact bound result never falls back to another photon`() {
        val invalidBound = photon("bound-goal", "goal/v4", tags = setOf("goal"))
        val fallback = photon("fallback", "Other result", tags = setOf("answer", "result"))

        val result = LocalCommunicationGoalEngine().prepare(
            goal = goal(),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-share"),
            photons = listOf(fallback),
            boundResultPhoton = invalidBound,
        )

        assertEquals(
            "bound-share-source-unshareable",
            assertIs<LocalCommunicationGoalResult.Blocked>(result).reason,
        )
    }

    @Test
    fun `missing reference falls back to newest real result`() {
        val old = photon("old", "Alt", tags = setOf("answer"), secondsAgo = 90)
        val latest = photon("latest", "Neu", tags = setOf("deepsearch-answer"), secondsAgo = 10)

        val prepared = assertIs<LocalCommunicationGoalResult.Prepared>(
            LocalCommunicationGoalEngine().prepare(
                goal = goal(),
                sourcePhoton = source(),
                goalPhotonId = PhotonId("goal-share"),
                photons = listOf(old, latest),
            )
        ).share

        assertEquals(latest.id, prepared.target.id)
    }

    @Test
    fun `image result is prepared as png without exposing delivery authority`() {
        val image = photon(
            "image-1",
            "lifeos-image-ref-v1\nassetId=a",
            mime = LocalCommunicationGoalEngine.IMAGE_REFERENCE_MIME,
            tags = setOf("image", "result"),
        )

        val prepared = assertIs<LocalCommunicationGoalResult.Prepared>(
            LocalCommunicationGoalEngine().prepare(
                goal = goal(),
                sourcePhoton = source(),
                goalPhotonId = PhotonId("goal-share"),
                photons = listOf(image),
            )
        ).share

        assertEquals(LocalShareKind.IMAGE, prepared.kind)
        assertEquals("image/png", prepared.mediaType)
    }

    @Test
    fun `no shareable result blocks instead of sharing request or goal`() {
        val result = LocalCommunicationGoalEngine().prepare(
            goal = goal(),
            sourcePhoton = source(),
            goalPhotonId = PhotonId("goal-share"),
            photons = listOf(
                photon("goal-old", "goal/v2", tags = setOf("goal")),
                photon("request", "gap", tags = setOf("tool-request")),
            ),
        )

        assertEquals("share-source-missing", assertIs<LocalCommunicationGoalResult.Blocked>(result).reason)
    }

    @Test
    fun `handoff receipt says share sheet opened and never claims delivery`() {
        val target = photon("answer", "Antwort", tags = setOf("answer", "result"))
        val prepared = LocalSharePreparation(source().id, PhotonId("goal-share"), target, LocalShareKind.TEXT)
        val receipt = LocalCommunicationGoalEngine().createHandoffReceipt(prepared, at)

        assertTrue(receipt.content.contains("status=share-sheet-opened"))
        assertTrue(!receipt.content.contains("delivered", ignoreCase = true))
        assertEquals(setOf(source().id, PhotonId("goal-share"), target.id), receipt.provenance.parentIds)
        assertTrue("share-handoff" in receipt.tags)
    }

    private fun goal(references: List<ResolvedReference> = emptyList()) = GoalFrame(
        intent = IntentType.COMMUNICATE,
        objective = "share result",
        entities = emptyList(),
        references = references,
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )

    private fun source() = photon("source-share", "Teile dieses Ergebnis")

    private fun photon(
        id: String,
        content: String,
        mime: String = "text/plain",
        tags: Set<String> = setOf("chat"),
        secondsAgo: Long = 60,
    ) = Photon(
        id = PhotonId(id),
        content = content,
        mimeType = mime,
        provenance = Provenance("test", "user", at.minusSeconds(secondsAgo)),
        tags = tags,
    )
}

package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageConstraintAndContextTest {
    @Test
    fun `extracts negation modality and adjustments`() {
        val result = LanguageUnderstandingEngine().understand(
            "Mach das Bild wärmer, aber die Schatten sollen realistisch bleiben und ohne KI."
        )

        assertTrue(result.goal.constraints.any { it.key == "adjustment" && it.value == "temperature:warm:+" })
        assertTrue(result.goal.constraints.any { it.key == "require" && it.value.contains("realistisch") })
        assertTrue(result.goal.constraints.any { it.key == "exclude" && it.value == "ki" })
    }

    @Test
    fun `context builder selects newest unarchived goal and image kinds`() {
        val oldGoal = Photon(
            id = PhotonId("goal-old"),
            content = "old goal",
            phase = PhotonPhase.ACTIVE,
            provenance = Provenance("test", "test", Instant.parse("2026-09-07T10:00:00Z")),
            tags = setOf("goal"),
        )
        val newGoal = Photon(
            id = PhotonId("goal-new"),
            content = "new goal",
            provenance = Provenance("test", "test", Instant.parse("2026-09-07T11:00:00Z")),
            tags = setOf("goal"),
        )
        val image = Photon(
            id = PhotonId("image-1"),
            content = "football image",
            mimeType = "image/png",
            provenance = Provenance("test", "test", Instant.parse("2026-09-07T09:00:00Z")),
            tags = setOf("result"),
        )
        val context = PhotonLanguageContextBuilder().build(
            listOf(oldGoal, newGoal, image),
            now = Instant.parse("2026-09-07T12:00:00Z"),
        )

        assertEquals(PhotonId("goal-new"), context.activeGoalId)
        assertEquals("image", context.items.single { it.photonId == image.id }.kind)
        assertTrue(context.items.single { it.photonId == newGoal.id }.active)
        assertTrue("football" in context.items.single { it.photonId == image.id }.contentTerms)
    }

    @Test
    fun `continuation and resumed goal photons do not replace substantive active goal`() {
        val substantive = Photon(
            id = PhotonId("goal-substantive"),
            content = "goal/v2\nintent=BUILD_OR_IMPLEMENT",
            mimeType = "application/vnd.lifeos.goal+text",
            provenance = Provenance("test", "test", Instant.parse("2026-09-10T10:00:00Z")),
            tags = setOf("goal", "intent:build_or_implement"),
        )
        val continuation = Photon(
            id = PhotonId("goal-continue"),
            content = "goal/v2\nintent=CONTINUE",
            mimeType = "application/vnd.lifeos.goal+text",
            provenance = Provenance("test", "test", Instant.parse("2026-09-10T10:01:00Z")),
            tags = setOf("goal", "intent:continue"),
        )
        val resumed = Photon(
            id = PhotonId("goal-resumed"),
            content = substantive.content,
            mimeType = "application/vnd.lifeos.goal+text",
            provenance = Provenance("goal-resume", "GoalResumeEngine", Instant.parse("2026-09-10T10:02:00Z")),
            tags = substantive.tags + "goal-resumed",
        )

        val context = PhotonLanguageContextBuilder().build(
            listOf(substantive, continuation, resumed),
            now = Instant.parse("2026-09-10T11:00:00Z"),
        )

        assertEquals(substantive.id, context.activeGoalId)
        assertTrue(context.items.single { it.photonId == substantive.id }.active)
    }

    @Test
    fun `repeated continue resolves the substantive persisted goal`() {
        val source = Photon(
            id = PhotonId("source-substantive"),
            content = "Baue die nächste sichere Komponente.",
            provenance = Provenance("test", "user", Instant.parse("2026-09-10T10:00:00Z")),
            tags = setOf("chat"),
        )
        val substantive = GoalPhotonFactory().create(
            result = LanguageUnderstandingEngine().understand(source.content),
            sourcePhotonId = source.id,
            createdAt = Instant.parse("2026-09-10T10:00:01Z"),
        ).photon
        val continuation = Photon(
            id = PhotonId("goal-continue"),
            content = "goal/v2\nintent=CONTINUE",
            mimeType = "application/vnd.lifeos.goal+text",
            provenance = Provenance("test", "test", Instant.parse("2026-09-10T10:01:00Z")),
            tags = setOf("goal", "intent:continue"),
        )
        val resumed = Photon(
            id = PhotonId("goal-resumed"),
            content = substantive.content,
            mimeType = substantive.mimeType,
            confidence = substantive.confidence,
            provenance = Provenance("goal-resume", "GoalResumeEngine", Instant.parse("2026-09-10T10:02:00Z")),
            tags = substantive.tags + "goal-resumed",
        )
        val context = PhotonLanguageContextBuilder().build(
            listOf(source, substantive, continuation, resumed),
            now = Instant.parse("2026-09-10T10:03:00Z"),
        )

        val result = LanguageUnderstandingEngine().understand("Weiter", context)

        assertEquals(IntentType.CONTINUE, result.goal.intent)
        assertEquals(substantive.id, result.goal.references.single().targetPhotonId)
        assertTrue(result.goal.ambiguities.none { it.code == "unresolved_reference" })
    }

    @Test
    fun `context records are metadata and never become language candidates`() {
        val target = Photon(
            id = PhotonId("image-target"),
            content = "real image",
            mimeType = "image/png",
            phase = PhotonPhase.ACTIVE,
            provenance = Provenance("test", "test", Instant.parse("2026-09-10T10:00:00Z")),
            tags = setOf("image"),
        )
        val contextRecord = Photon(
            id = PhotonId("ctx_deadbeef"),
            content = "context/v1\ntargetId=image-target",
            mimeType = "application/vnd.lifeos.context+text",
            phase = PhotonPhase.ACTIVE,
            provenance = Provenance("durable-context", "test", Instant.parse("2026-09-10T10:01:00Z")),
            tags = setOf("context-record", "context-kind:image"),
        )

        val context = PhotonLanguageContextBuilder().build(
            listOf(target, contextRecord),
            now = Instant.parse("2026-09-10T11:00:00Z"),
        )

        assertEquals(listOf(target.id), context.items.map { it.photonId })
    }
}

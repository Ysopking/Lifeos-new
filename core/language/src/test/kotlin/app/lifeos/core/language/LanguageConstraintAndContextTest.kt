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
}

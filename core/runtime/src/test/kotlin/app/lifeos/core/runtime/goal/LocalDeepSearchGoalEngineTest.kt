package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalDeepSearchGoalEngineTest {
    private val at = Instant.parse("2026-09-10T20:00:00Z")

    @Test
    fun `search returns traceable local photon evidence without network source`() = runTest {
        val balcony = photon(
            "balcony",
            "Die Balkonbank ist 160 cm lang und hat eine Sitzhöhe von 68 cm.",
            confidence = 0.96,
        )
        val unrelated = photon("other", "Die Küche braucht neue Lampen.")

        val result = assertIs<LocalDeepSearchGoalResult.Produced>(
            LocalDeepSearchGoalEngine().execute(
                goal = searchGoal("Suche Balkonbank Sitzhöhe"),
                sourcePhoton = photon("request", "Suche Balkonbank Sitzhöhe"),
                goalPhotonId = PhotonId("goal-search"),
                photons = listOf(balcony, unrelated),
                createdAt = at,
            )
        )

        assertEquals(DeepSearchStatus.RESOLVED, result.result.status)
        assertEquals(listOf(balcony.id), result.evidencePhotonIds)
        assertTrue(result.photon.content.contains("Balkonbank"))
        assertTrue(result.photon.relations.any { it.target == balcony.id })
        assertTrue("deepsearch-answer" in result.photon.tags)
        assertTrue(result.result.blockedSourceIds.isEmpty())
    }

    @Test
    fun `close local findings remain explicit alternatives instead of forced winner`() = runTest {
        val first = photon("a", "Projekt Ki nutzt Photonen für gespeicherte Information.", confidence = 0.92)
        val second = photon("b", "Projekt Ki behandelt Informationen als Photonen im Feld.", confidence = 0.92)

        val result = assertIs<LocalDeepSearchGoalResult.Produced>(
            LocalDeepSearchGoalEngine().execute(
                goal = searchGoal("Suche Projekt Ki Photonen"),
                sourcePhoton = photon("request", "Suche Projekt Ki Photonen"),
                goalPhotonId = PhotonId("goal-search"),
                photons = listOf(first, second),
                createdAt = at,
            )
        )

        assertEquals(DeepSearchStatus.UNRESOLVED, result.result.status)
        assertTrue(result.result.best != null)
        assertFalse(result.result.alternatives.isEmpty())
        assertEquals(setOf(first.id, second.id), result.evidencePhotonIds.toSet())
        assertTrue(result.photon.content.contains("nicht eindeutig"))
    }

    @Test
    fun `search with no matching primary evidence returns honest empty result`() = runTest {
        val priorAnswer = photon(
            "old-answer",
            "Balkonbank 68 cm",
            tags = setOf("answer", "deepsearch-answer"),
        )
        val unrelated = photon("other", "Küchenlampe")

        val result = assertIs<LocalDeepSearchGoalResult.Produced>(
            LocalDeepSearchGoalEngine().execute(
                goal = searchGoal("Suche Balkonbank"),
                sourcePhoton = photon("request", "Suche Balkonbank"),
                goalPhotonId = PhotonId("goal-search"),
                photons = listOf(priorAnswer, unrelated),
                createdAt = at,
            )
        )

        assertTrue(result.evidencePhotonIds.isEmpty())
        assertTrue(result.photon.content.contains("Keine passende lokale DeepSearch-Evidenz"))
        assertTrue(result.result.status != DeepSearchStatus.RESOLVED)
    }

    private fun searchGoal(text: String) = GoalFrame(
        intent = IntentType.SEARCH,
        objective = "search: $text",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )

    private fun photon(
        id: String,
        content: String,
        confidence: Double = 1.0,
        tags: Set<String> = setOf("chat"),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        confidence = confidence,
        provenance = Provenance("test", "user", at.minusSeconds(60)),
        tags = tags,
    )
}

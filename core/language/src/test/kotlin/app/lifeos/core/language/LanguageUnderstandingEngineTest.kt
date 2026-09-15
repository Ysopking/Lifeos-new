package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageUnderstandingEngineTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `understands offline image creation scene`() {
        val result = engine.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        assertEquals(LanguageCode.DE, result.goal.language)
        assertTrue(result.goal.entities.any { it.type == EntityType.IMAGE })
        assertTrue(result.goal.entities.any { it.type == EntityType.NUMBER && it.normalizedValue == "2" })
        assertTrue(result.goal.entities.any { it.type == EntityType.OBJECT && it.normalizedValue == "fussball" })
        assertTrue(result.goal.entities.any { it.type == EntityType.ACTION && it.normalizedValue == "spielen" })
        assertNotNull(result.linguisticField)
        assertTrue(result.linguisticField!!.semanticActivation("FOOTBALL") > 0.5)
        assertTrue(result.goal.confidence > 0.65)
    }

    @Test
    fun `German remind me with temporal cues becomes schedule not memory`() {
        val result = engine.understand("Erinnere mich morgen um 16:30 an den Termin")

        assertEquals(IntentType.SCHEDULE, result.goal.intent)
        assertTrue(result.goal.entities.any {
            it.type == EntityType.DATE && it.normalizedValue == "relative:tomorrow"
        })
        assertTrue(result.goal.entities.any {
            it.type == EntityType.TIME && it.normalizedValue == "16:30"
        })
        assertFalse(result.intentEvidence.first().intent == IntentType.STORE_OR_REMEMBER)
    }

    @Test
    fun `German remember this remains memory intent`() {
        val result = engine.understand("Erinnere dich daran, dass mein Fahrrad im Keller steht")

        assertEquals(IntentType.STORE_OR_REMEMBER, result.goal.intent)
        assertFalse(result.intentEvidence.any { it.intent == IntentType.SCHEDULE })
    }

    @Test
    fun `resolves image from yesterday for transformation`() {
        val imageId = PhotonId("image-yesterday")
        val now = Instant.parse("2026-09-07T18:00:00Z")
        val context = LanguageContext(
            now = now,
            items = listOf(
                LanguageContextItem(
                    photonId = imageId,
                    kind = "image",
                    tags = setOf("image", "result"),
                    createdAt = Instant.parse("2026-09-06T18:30:00Z"),
                    active = false,
                    contentTerms = setOf("bild", "fussball"),
                )
            ),
        )

        val result = engine.understand(
            "Mach das Bild von gestern etwas wärmer, aber die Schatten sollen realistisch bleiben.",
            context,
        )

        assertEquals(IntentType.TRANSFORM_IMAGE, result.goal.intent)
        assertTrue(result.goal.references.any { it.targetPhotonId == imageId && it.score >= 0.55 })
        assertFalse(result.goal.ambiguities.any { it.code == "image_source_missing" })
        assertTrue(result.goal.entities.any { it.type == EntityType.DATE && it.normalizedValue == "relative:yesterday" })
        assertTrue(result.goal.entities.any { it.type == EntityType.STYLE && it.normalizedValue == "realistisch" })
    }

    @Test
    fun `continue resolves active goal`() {
        val goalId = PhotonId("active-goal")
        val context = LanguageContext(
            activeGoalId = goalId,
            now = Instant.parse("2026-09-07T18:00:00Z"),
            items = listOf(
                LanguageContextItem(
                    photonId = goalId,
                    kind = "goal",
                    tags = setOf("goal"),
                    createdAt = Instant.parse("2026-09-07T17:55:00Z"),
                    active = true,
                    contentTerms = setOf("mmsi"),
                )
            ),
        )

        val result = engine.understand("Weiter", context)

        assertEquals(IntentType.CONTINUE, result.goal.intent)
        assertEquals(goalId, result.goal.references.single().targetPhotonId)
        assertTrue(result.goal.references.single().score > 0.8)
    }

    @Test
    fun `keeps close reference candidates as ambiguity`() {
        val created = Instant.parse("2026-09-07T17:00:00Z")
        val context = LanguageContext(
            now = Instant.parse("2026-09-07T18:00:00Z"),
            items = listOf(
                LanguageContextItem(PhotonId("image-a"), "image", setOf("image"), created, false, setOf("bild")),
                LanguageContextItem(PhotonId("image-b"), "image", setOf("image"), created, false, setOf("bild")),
            ),
        )

        val result = engine.understand("Mach dieses Bild heller", context)

        assertEquals(IntentType.TRANSFORM_IMAGE, result.goal.intent)
        assertTrue(result.goal.ambiguities.any { it.code == "reference_competition" })
    }

    @Test
    fun `goal photon preserves provenance field trace and structured intent`() {
        val source = PhotonId("chat-photon")
        val result = engine.understand("Suche Bilder aus Berlin")
        val goalPhoton = GoalPhotonFactory().create(
            result = result,
            sourcePhotonId = source,
            createdAt = Instant.parse("2026-09-07T18:00:00Z"),
        )

        assertEquals(result.goal, goalPhoton.frame)
        assertEquals("application/vnd.lifeos.goal+text", goalPhoton.photon.mimeType)
        assertTrue("goal" in goalPhoton.photon.tags)
        assertTrue("intent:search" in goalPhoton.photon.tags)
        assertEquals(setOf(source), goalPhoton.photon.provenance.parentIds)
        val relation = assertNotNull(goalPhoton.photon.relations.singleOrNull())
        assertEquals(source, relation.target)
        assertEquals(RelationType.DERIVED_FROM, relation.type)
        assertTrue(goalPhoton.photon.content.startsWith("goal/v3\nintent=SEARCH"))
        assertTrue(
            goalPhoton.photon.content.contains(
                "semantic.fingerprint=${result.goal.semanticGraph.fingerprint}"
            )
        )
        assertTrue(goalPhoton.photon.content.contains("field.iterations="))
    }

    @Test
    fun `unknown phrasing remains explicit instead of invented`() {
        val result = engine.understand("Flombari zentaku")
        assertEquals(IntentType.UNKNOWN, result.goal.intent)
        assertTrue(result.intentEvidence.single().reasons.any { it.contains("no deterministic") })
        assertTrue(result.goal.confidence < 0.6)
    }
}

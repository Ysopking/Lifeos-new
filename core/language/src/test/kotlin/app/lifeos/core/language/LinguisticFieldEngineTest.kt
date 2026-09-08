package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinguisticFieldEngineTest {
    private val normalizer = UtteranceNormalizer()
    private val engine = LinguisticFieldEngine()

    @Test
    fun `sentence field pulls misspelled football into stable meaning`() {
        val isolated = engine.converge(normalizer.normalize("fussbll"))
        val contextual = engine.converge(normalizer.normalize("zwei leute spielen fussbll"))

        val isolatedScore = isolated.resolutions.firstOrNull { it.semanticTag == "FOOTBALL" }?.confidence ?: 0.0
        val football = assertNotNull(contextual.resolutions.firstOrNull { it.semanticTag == "FOOTBALL" })

        assertEquals("fussball", football.canonical)
        assertTrue(football.confidence > isolatedScore)
        assertTrue(football.confidence >= 0.55)
        assertTrue(contextual.interactions.any {
            it.sourceConceptId == "football" && it.targetConceptId == "action.play" && it.force > 0.0
        })
    }

    @Test
    fun `active photon context attracts uncertain lexical candidate`() {
        val context = LanguageContext(
            now = Instant.parse("2026-09-08T12:00:00Z"),
            items = listOf(
                LanguageContextItem(
                    photonId = PhotonId("football-context"),
                    kind = "scene",
                    tags = setOf("scene", "football"),
                    createdAt = Instant.parse("2026-09-08T11:59:00Z"),
                    active = true,
                    contentTerms = setOf("fussball", "football"),
                    confidence = 1.0,
                )
            ),
        )

        val withoutContext = engine.converge(normalizer.normalize("fussbll"))
        val withContext = engine.converge(normalizer.normalize("fussbll"), context)
        val withoutScore = withoutContext.resolutions.firstOrNull { it.semanticTag == "FOOTBALL" }?.confidence ?: 0.0
        val withFootball = assertNotNull(withContext.resolutions.firstOrNull { it.semanticTag == "FOOTBALL" })

        assertTrue(withFootball.confidence > withoutScore)
    }

    @Test
    fun `same field input converges byte-for-byte deterministically`() {
        val utterance = normalizer.normalize("Erzeuge ein Bild von zwei Leuten die Fussbll spielen nachts unter Flutlicht")
        val first = engine.converge(utterance)
        val second = engine.converge(utterance)

        assertEquals(first, second)
        assertTrue(first.iterations in 1..6)
        assertTrue(first.totalEnergy > 0.0)
    }

    @Test
    fun `language engine uses field to recover object missing from exact rules`() {
        val result = LanguageUnderstandingEngine().understand(
            "Erzeuge ein Bild von zwei Leuten die Fussbll spielen",
        )

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        assertTrue(result.goal.entities.any {
            it.type == EntityType.OBJECT && it.normalizedValue == "fussball" && it.rawText.equals("Fussbll", ignoreCase = true)
        })
        assertTrue(result.linguisticField?.semanticActivation("FOOTBALL") ?: 0.0 >= 0.55)
    }

    @Test
    fun `night and floodlight form a semantic attraction pair`() {
        val field = engine.converge(normalizer.normalize("nachts unter flutlichtern"))

        assertTrue(field.semanticActivation("NIGHT") >= 0.55)
        assertTrue(field.semanticActivation("FLOODLIGHT") >= 0.55)
        assertTrue(field.interactions.any {
            setOf(it.sourceConceptId, it.targetConceptId) == setOf("night", "floodlight") && it.force > 0.0
        })
    }
}

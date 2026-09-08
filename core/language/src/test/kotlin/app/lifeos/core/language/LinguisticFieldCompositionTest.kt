package app.lifeos.core.language

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinguisticFieldCompositionTest {
    private val normalizer = UtteranceNormalizer()

    @Test
    fun `grapheme field exposes character attraction and missing letter`() {
        val trace = GraphemeFieldEngine().compare("Fussbll", "Fussball")

        assertTrue(trace.orthographicAffinity > 0.75)
        assertTrue(trace.bonds.count { it.kind == GraphemeBondKind.MATCH } >= 6)
        assertTrue(trace.bonds.any { it.kind == GraphemeBondKind.INSERTION && it.expected == 'a' })
    }

    @Test
    fun `phonetic field attracts spelling variants without neural model`() {
        val field = DeterministicPhoneticField()
        val affinity = field.affinity("Fusbal", "Fußball")

        assertTrue(affinity >= 0.70)
        assertEquals(field.signature("Fusbal"), field.signature("Fußball"))
    }

    @Test
    fun `compound field binds football and floodlight inside one token`() {
        val utterance = normalizer.normalize("Fussballflutlicht")
        val binding = CompoundFieldResolver().resolve(utterance).single()

        assertEquals(listOf("FOOTBALL", "FLOODLIGHT"), binding.components.map { it.semanticTag })
        assertTrue(binding.confidence > 0.80)
    }

    @Test
    fun `sentence semantics feed back into uncertain football spelling`() {
        val utterance = normalizer.normalize("Leute spielen Fussbll")
        val result = LinguisticFieldEngine().converge(utterance)

        assertTrue(result.resolutions.any { it.semanticTag == "FOOTBALL" })
        assertTrue(
            result.topDownRevisions.any {
                it.conceptId == "football" && it.semanticForce > 0.0
            },
        )
    }

    @Test
    fun `photon context participates in top down revision`() {
        val utterance = normalizer.normalize("Fussbll")
        val context = LanguageContext(
            now = Instant.parse("2026-09-08T00:00:00Z"),
            items = listOf(
                LanguageContextItem(
                    photonId = app.lifeos.core.model.PhotonId("football-context"),
                    kind = "scene",
                    tags = setOf("scene"),
                    createdAt = Instant.parse("2026-09-07T23:59:00Z"),
                    active = true,
                    contentTerms = setOf("fussball", "FOOTBALL"),
                    confidence = 1.0,
                ),
            ),
        )
        val result = LinguisticFieldEngine().converge(utterance, context)

        assertTrue(result.topDownRevisions.any { it.conceptId == "football" && it.photonForce > 0.0 })
    }

    @Test
    fun `compound semantic components become downstream entities`() {
        val result = LanguageUnderstandingEngine().understand(
            "Erzeuge ein Bild vom Fussballflutlicht nachts",
        )

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        val objects = result.goal.entities.filter { it.type == EntityType.OBJECT }.map { it.normalizedValue }.toSet()
        assertTrue("fussball" in objects)
        assertTrue("flutlicht" in objects)
        assertTrue(result.linguisticField?.compoundBindings?.isNotEmpty() == true)
    }

    @Test
    fun `composition field remains deterministic`() {
        val utterance = normalizer.normalize("Leute spielen Fussballflutlicht nachts")
        val engine = LinguisticFieldEngine()

        val first = engine.converge(utterance)
        val second = engine.converge(utterance)

        assertEquals(first, second)
    }
}

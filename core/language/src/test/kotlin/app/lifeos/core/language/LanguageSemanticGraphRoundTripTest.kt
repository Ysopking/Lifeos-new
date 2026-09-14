package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageSemanticGraphRoundTripTest {
    private val understanding = LanguageUnderstandingEngine()
    private val generation = LanguageGenerationEngine(understanding = understanding)
    private val responses = LanguageResponseGenerationEngine(understanding = understanding)

    @Test
    fun `understanding preserves condition cause negation modality and quantity`() {
        val result = understanding.understand(
            "Wenn die Datei größer als 10 MB ist, darf sie nicht hochgeladen werden, weil der Speicher voll ist."
        )
        val graph = result.goal.semanticGraph

        assertTrue(graph.links.any { it.type == SemanticLinkType.CONDITION })
        assertTrue(graph.links.any { it.type == SemanticLinkType.CAUSE })
        assertTrue(graph.clauses.any { it.polarity == SemanticPolarity.NEGATIVE })
        assertTrue(graph.clauses.any { it.modality == SemanticModality.MAY })
        assertTrue(graph.clauses.flatMap { it.quantities }.any { it.value == "10" && it.unit == "mb" })
    }

    @Test
    fun `goal generation cannot silently drop scoped semantic structure`() {
        val original = understanding.understand(
            "Wenn die Datei größer als 10 MB ist, darf sie nicht hochgeladen werden, weil der Speicher voll ist."
        )

        val generated = generation.generate(original.goal)
        val reparsed = generated.winner.roundTrip.goal.semanticGraph

        assertTrue(generated.winner.semanticGraphCoverage >= 0.99)
        assertTrue(reparsed.links.any { it.type == SemanticLinkType.CONDITION })
        assertTrue(reparsed.links.any { it.type == SemanticLinkType.CAUSE })
        assertTrue(reparsed.clauses.any { it.polarity == SemanticPolarity.NEGATIVE })
        assertTrue(reparsed.clauses.any { it.modality == SemanticModality.MAY })
    }

    @Test
    fun `factual response generation uses the same semantic graph contract`() {
        val factText = "Der Export darf nicht starten, weil die Prüfsumme fehlt."
        val factGraph = understanding.understand(factText).goal.semanticGraph
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.EVIDENCE,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = factText,
                    confidence = 0.94,
                    semanticGraph = factGraph,
                )
            ),
        )

        val generated = responses.generate(target)
        val reparsed = generated.winner.roundTrip.goal.semanticGraph

        assertTrue(generated.winner.semanticGraphCoverage >= 0.99)
        assertTrue(reparsed.clauses.any { it.polarity == SemanticPolarity.NEGATIVE })
        assertTrue(reparsed.clauses.any { it.modality == SemanticModality.MAY })
        assertTrue(reparsed.links.any { it.type == SemanticLinkType.CAUSE })
    }

    @Test
    fun `semantic graph extraction is deterministic`() {
        val text = "Falls zwei Dateien fehlen, muss der Import stoppen."

        val first = understanding.understand(text).goal.semanticGraph
        val second = understanding.understand(text).goal.semanticGraph

        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
    }
}

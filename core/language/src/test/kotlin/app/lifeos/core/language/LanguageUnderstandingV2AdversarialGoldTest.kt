package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageUnderstandingV2AdversarialGoldTest {
    private val engine = LanguageUnderstandingEngine()
    private val normalizer = UtteranceNormalizer()
    private val now = Instant.parse("2026-09-21T12:00:00Z")

    @Test
    fun `negation mutations never authorize external communication`() {
        val cases = listOf(
            "Sende die Mail nicht.",
            "Bitte sende die Mail nicht.",
            "Sende keinesfalls die Mail.",
            "Nicht die Mail senden.",
            "Er sagte: Sende die Mail nicht.",
        )

        cases.forEach { text ->
            val result = engine.understand(text, LanguageContext(now = now))
            assertFalse(
                SemanticExecutionGate.externalEffectAllowed(result.goal),
                text,
            )
        }
    }

    @Test
    fun `paraphrase search family keeps one descriptive search meaning`() {
        val cases = listOf(
            "Suche den Bescheid.",
            "Schau bitte nach dem Bescheid.",
            "Sieh nach dem Bescheid.",
            "Guck nach dem Bescheid.",
            "Finde heraus wo der Bescheid ist.",
            "Prüf den Bescheid.",
        )

        cases.forEach { text ->
            val result = engine.understand(text, LanguageContext(now = now))
            assertEquals(IntentType.SEARCH, result.goal.intent, text)
            assertTrue(
                result.goal.semanticActionGraph.nodes.any {
                    it.frame.predicate == PredicateConcept.SEARCH
                },
                text,
            )
        }
    }

    @Test
    fun `question command minimal pair never turns question into executable command`() {
        val command = engine.understand("Suche den Bescheid.", LanguageContext(now = now))
        val question = engine.understand("Wie suche ich den Bescheid?", LanguageContext(now = now))

        assertTrue(command.goal.semanticActionGraph.nodes.any {
            it.frame.predicate == PredicateConcept.SEARCH
        })
        assertFalse(question.goal.semanticActionGraph.nodes.any {
            it.frame.predicate == PredicateConcept.SEARCH && it.executable
        })
    }

    @Test
    fun `discourse graph favors active revision but preserves all bounded candidates`() {
        val activeId = PhotonId("active-document")
        val oldId = PhotonId("older-document")
        val context = LanguageContext(
            items = listOf(
                LanguageContextItem(
                    photonId = activeId,
                    kind = "file",
                    tags = setOf("file", "semantic:notice"),
                    createdAt = now.minusSeconds(10),
                    active = true,
                    contentTerms = setOf("bescheid"),
                    revisionRef = PhotonRevisionRef(activeId, 3L),
                    semanticTypes = setOf("file", "notice"),
                ),
                LanguageContextItem(
                    photonId = oldId,
                    kind = "file",
                    tags = setOf("file"),
                    createdAt = now.minusSeconds(3600),
                    active = false,
                    contentTerms = setOf("bescheid"),
                    revisionRef = PhotonRevisionRef(oldId, 1L),
                    semanticTypes = setOf("file"),
                ),
            ),
            now = now,
        )

        val discourse = DiscourseStateProjector().project(context)

        assertEquals(2, discourse.focus.size)
        assertEquals(activeId, discourse.focus.first().ref.photonId)
        assertTrue(discourse.fingerprint.isNotBlank())
    }

    @Test
    fun `dependency syntax records root negation and separable particle evidence`() {
        val utterance = normalizer.normalize("Schick die Mail nicht ab.")
        val graph = LanguageSemanticGraphExtractor().extract(utterance, emptyList())
        val syntax = DeterministicDependencySyntaxParser().parse(utterance, graph)

        assertTrue(syntax.rootTokenIndices.isNotEmpty())
        assertTrue(syntax.arcs.any { it.relation == SyntaxRelation.NEGATION })
        assertTrue(syntax.arcs.any { it.relation == SyntaxRelation.PARTICLE })
    }

    @Test
    fun `fine operator scopes keep only except and correction explicit`() {
        val utterance = normalizer.normalize("Sende nur den Bericht, außer der Entwurf ist neuer.")
        val graph = LanguageSemanticGraphExtractor().extract(utterance, emptyList())
        val scopes = graph.clauses.flatMap { ScopeCueDetectorV2().detect(utterance, it) }

        assertTrue(scopes.any { it.type == SemanticOperatorType.ONLY })
        assertTrue(scopes.any { it.type == SemanticOperatorType.EXCEPT })
    }

    @Test
    fun `quantity temporal v4 captures approximation day part and recurrence`() {
        val parsed = QuantityTemporalEngine().parse(
            utterance = normalizer.normalize("Jeden zweiten Dienstag nachmittags knapp 300 Euro."),
            referenceInstant = now,
            zoneId = ZoneId.of("Europe/Berlin"),
        )

        val amount = assertNotNull(parsed.quantities.firstOrNull {
            it.currency?.currencyCode == "EUR"
        })
        assertTrue(amount.approximate)
        assertTrue(parsed.dayParts.any { it.dayPart == DayPart.AFTERNOON })
        val recurrence = assertNotNull(parsed.recurrences.firstOrNull())
        assertEquals(2, recurrence.interval)
        assertEquals(DayOfWeek.TUESDAY, recurrence.weekday)
    }

    @Test
    fun `interpretation lattice keeps alternatives separate from execution authority`() {
        val result = engine.understand("Gib Anna Bescheid.", LanguageContext(now = now))

        assertTrue(result.goal.interpretationLattice.candidates.isNotEmpty())
        assertEquals(
            result.goal.interpretationLattice.winner?.intent,
            result.goal.interpretationLattice.candidates.firstOrNull()?.intent,
        )
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `semantic correction projection never rewrites original utterance`() {
        val utterance = normalizer.normalize("snden")
        val field = LinguisticFieldResult(
            resolutions = listOf(
                LinguisticFieldResolution(
                    tokenIndex = 0,
                    rawToken = "snden",
                    canonical = "senden",
                    semanticTag = "COMMUNICATE",
                    entityType = null,
                    confidence = 0.91,
                    alternatives = emptyList(),
                )
            ),
            intentField = emptyList(),
            interactions = emptyList(),
            converged = true,
            iterations = 1,
            totalEnergy = 0.91,
        )

        val corrections = SemanticCorrectionEngine().project(utterance, field)

        assertEquals("snden", utterance.original)
        assertEquals("senden", corrections.single().canonical)
    }

    @Test
    fun `nested condition produces explicit action group and stays fail closed`() {
        val result = engine.understand(
            "Wenn X passiert, sende die Mail.",
            LanguageContext(now = now),
        )

        assertTrue(result.goal.semanticActionGraph.groups.any {
            it.type == SemanticActionGroupType.CONDITIONAL
        })
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `indirect pragmatic request stays descriptive only`() {
        val act = PragmaticActResolver().resolve(
            normalizer.normalize("Könntest du den Bescheid suchen?")
        )

        assertEquals(PragmaticActType.INDIRECT_REQUEST, act.type)
        assertTrue(act.descriptiveOnly)
    }
}

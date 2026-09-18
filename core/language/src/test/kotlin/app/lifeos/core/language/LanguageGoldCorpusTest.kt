package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageGoldCorpusTest {
    private val engine = LanguageUnderstandingEngine()
    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun `questions about action topics never become actions`() {
        val cases = listOf(
            "Wie erstelle ich ein Bild?" to PredicateConcept.CREATE_IMAGE,
            "Wann ist mein Termin?" to PredicateConcept.QUERY,
            "Was ist eine E-Mail?" to PredicateConcept.QUERY,
            "Warum wurde die Mail gesendet?" to PredicateConcept.COMMUNICATE,
        )

        cases.forEach { (text, predicate) ->
            val result = engine.understand(text, context())
            assertEquals(IntentType.QUERY, result.goal.intent, text)
            assertTrue(
                result.goal.semanticActionGraph.nodes.any {
                    it.type == SemanticActionNodeType.QUERY &&
                        (it.frame.predicate == predicate || predicate == PredicateConcept.QUERY)
                },
                text,
            )
            assertTrue(result.goal.semanticActionGraph.executableNodes.isEmpty(), text)
            assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal), text)
        }
    }

    @Test
    fun `send and do not send form a safety pair`() {
        val positive = engine.understand("Sende diese Mail.", context())
        val negative = engine.understand("Sende diese Mail nicht.", context())

        val positiveNode = positive.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }
        val negativeNode = negative.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(positiveNode.executable)
        assertFalse(positiveNode.frame.negated)
        assertTrue(negativeNode.frame.negated)
        assertFalse(negativeNode.executable)
        assertNotEquals(
            positive.goal.semanticActionGraph.fingerprint,
            negative.goal.semanticActionGraph.fingerprint,
        )
    }

    @Test
    fun `desire negation cannot authorize communication`() {
        val result = engine.understand(
            "Ich möchte nicht, dass du Nachrichten sendest.",
            context(),
        )
        val send = result.goal.semanticActionGraph.nodes.firstOrNull {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(send == null || !send.executable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `quoted command is categorically non executable`() {
        val result = engine.understand("Er sagte: „Sende die Mail.“", context())
        val send = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(send.frame.quoted)
        assertFalse(send.executable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `conditional send creates dependency and remains unresolved`() {
        val result = engine.understand("Wenn X passiert, sende die Mail.", context())
        val graph = result.goal.semanticActionGraph
        val send = graph.nodes.single { it.frame.predicate == PredicateConcept.COMMUNICATE }

        assertTrue(send.unresolvedCondition)
        assertFalse(send.executable)
        assertTrue(graph.edges.any { it.to == send.id && it.type == SemanticActionEdgeType.IF })
    }

    @Test
    fun `search then send creates two ordered actions and result dependency`() {
        val result = engine.understand(
            "Suche den Bescheid und sende ihn anschließend an Peter.",
            context(),
        )
        val graph = result.goal.semanticActionGraph
        val search = graph.nodes.single { it.frame.predicate == PredicateConcept.SEARCH }
        val send = graph.nodes.single { it.frame.predicate == PredicateConcept.COMMUNICATE }

        assertTrue(search.executable)
        assertTrue(send.executable)
        assertEquals("Peter", send.frame.roles.getValue(SemanticRole.RECIPIENT).normalized)
        assertTrue(
            graph.edges.any {
                it.from == search.id &&
                    it.to == send.id &&
                    it.type == SemanticActionEdgeType.USES_RESULT_OF
            }
        )
    }

    @Test
    fun `debt role inversion produces different graphs`() {
        val first = engine.understand("Peter schuldet Anna 100 Euro.", context())
        val second = engine.understand("Anna schuldet Peter 100 Euro.", context())
        val firstFrame = first.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.OWE
        }.frame
        val secondFrame = second.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.OWE
        }.frame

        assertEquals("Peter", firstFrame.roles.getValue(SemanticRole.DEBTOR).normalized)
        assertEquals("Anna", firstFrame.roles.getValue(SemanticRole.CREDITOR).normalized)
        assertEquals("Anna", secondFrame.roles.getValue(SemanticRole.DEBTOR).normalized)
        assertEquals("Peter", secondFrame.roles.getValue(SemanticRole.CREDITOR).normalized)
        assertNotEquals(
            first.goal.semanticActionGraph.fingerprint,
            second.goal.semanticActionGraph.fingerprint,
        )
    }

    @Test
    fun `semantic image reference prefers the dog image`() {
        val dog = item(
            id = "image-dog",
            revision = 3,
            terms = setOf("bild", "hund", "garten"),
            active = false,
        )
        val cat = item(
            id = "image-cat",
            revision = 2,
            terms = setOf("bild", "katze", "garten"),
            active = true,
        )
        val result = engine.understand(
            "Mach das Bild mit dem Hund heller.",
            context(items = listOf(cat, dog)),
        )

        val reference = assertNotNull(result.goal.references.maxByOrNull { it.score })
        assertEquals(dog.revisionRef, reference.targetPhotonRef)
        val transform = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.TRANSFORM_IMAGE
        }
        assertEquals(dog.revisionRef, transform.frame.roles[SemanticRole.OBJECT]?.referencePhoton)
    }

    @Test
    fun `other image reference avoids the active image`() {
        val active = item(
            id = "image-active",
            revision = 5,
            terms = setOf("bild", "hund"),
            active = true,
        )
        val other = item(
            id = "image-other",
            revision = 2,
            terms = setOf("bild", "katze"),
            active = false,
        )
        val result = engine.understand(
            "Mach das andere Bild heller.",
            context(items = listOf(active, other)),
        )

        val reference = assertNotNull(result.goal.references.maxByOrNull { it.score })
        assertEquals(ReferenceKind.OTHER, reference.expression.kind)
        assertEquals(other.revisionRef, reference.targetPhotonRef)
    }

    @Test
    fun `not more than is amount bound not action negation`() {
        val result = engine.understand("Überweise nicht mehr als 100 Euro.", context())
        val pay = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.PAY
        }
        val quantity = result.goal.quantityTemporal.quantities.single {
            it.currency?.currencyCode == "EUR" && it.value == BigDecimal("100")
        }

        assertFalse(pay.frame.negated)
        assertTrue(ScopeType.EXCLUSION in pay.frame.scopeTypes)
        assertEquals(QuantityComparator.LESS_OR_EQUAL, quantity.comparator)
    }

    @Test
    fun `temporal correction does not negate reminder action`() {
        val result = engine.understand(
            "Erinnere mich nicht morgen, sondern am Freitag.",
            context(),
        )
        val schedule = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.SCHEDULE
        }

        assertFalse(schedule.frame.negated)
        assertTrue(ScopeType.EXCLUSION in schedule.frame.scopeTypes)
        assertTrue(result.goal.quantityTemporal.temporals.any {
            it.sourceText.lowercase().contains("morgen")
        })
        assertTrue(result.goal.quantityTemporal.temporals.any {
            it.sourceText.lowercase().contains("freitag")
        })
    }

    @Test
    fun `authority notice amount and objection deadline form domain graph`() {
        val result = engine.understand(
            "Der Jobcenter-Bescheid fordert 312 Euro zurück und die Widerspruchsfrist endet am 4. Oktober.",
            context(),
        )
        val domain = result.goal.domainSemanticGraph

        assertTrue(result.goal.semanticEntitiesV2.any {
            it.typeId == EntityTypeRegistry.AUTHORITY.id
        })
        assertTrue(result.goal.semanticEntitiesV2.any {
            it.typeId == EntityTypeRegistry.NOTICE.id
        })
        assertTrue(result.goal.semanticEntitiesV2.any {
            it.typeId == EntityTypeRegistry.DEADLINE.id
        })
        assertTrue(result.goal.quantityTemporal.quantities.any {
            it.currency?.currencyCode == "EUR" && it.value == BigDecimal("312")
        })
        assertTrue(result.goal.quantityTemporal.temporals.any {
            it.sourceText.lowercase().contains("4. oktober")
        })
        assertTrue(domain.relations.any { it.type == DomainSemanticRelationType.ISSUED })
        assertTrue(domain.relations.any { it.type == DomainSemanticRelationType.HAS_AMOUNT })
        assertTrue(domain.relations.any { it.type == DomainSemanticRelationType.HAS_DEADLINE })
    }

    @Test
    fun `modal hypothetical and mutation cues never silently raise readiness`() {
        val mutations = listOf(
            "Falls es passt, sende die Mail.",
            "Wenn es klappt, sende die Mail.",
            "Vielleicht könnte man die Mail senden.",
            "Du solltest die Mail senden.",
            "Angeblich soll die Mail gesendet werden.",
            "Sende die Mail nie.",
            "Sende keine Nachricht.",
        )

        mutations.forEach { text ->
            val result = engine.understand(text, context())
            assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal), text)
        }
    }

    private fun context(
        items: List<LanguageContextItem> = emptyList(),
    ): LanguageContext = LanguageContext(
        items = items,
        now = now,
        zoneId = "Europe/Berlin",
    )

    private fun item(
        id: String,
        revision: Long,
        terms: Set<String>,
        active: Boolean,
    ): LanguageContextItem {
        val photonId = PhotonId(id)
        return LanguageContextItem(
            photonId = photonId,
            kind = "image",
            tags = setOf("image"),
            createdAt = now.minusSeconds(revision),
            active = active,
            contentTerms = terms,
            normalizedTerms = terms,
            confidence = 1.0,
            revisionRef = PhotonRevisionRef(photonId, revision),
            semanticTypes = setOf("image"),
        )
    }
}

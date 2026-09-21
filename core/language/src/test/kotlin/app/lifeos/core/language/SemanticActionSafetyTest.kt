package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticActionSafetyTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `image creation question is query not action`() {
        val result = engine.understand("Wie erstelle ich ein Bild?")

        assertTrue(result.intentEvidence.any { it.intent == IntentType.CREATE_IMAGE })
        assertEquals(IntentType.QUERY, result.goal.intent)
        val node = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.CREATE_IMAGE
        }
        assertEquals(SpeechActType.QUESTION, node.frame.speechAct.type)
        assertEquals(SemanticActionNodeType.QUERY, node.type)
        assertFalse(node.executable)
        assertTrue(result.goal.semanticActionGraph.executableNodes.isEmpty())
    }

    @Test
    fun `direct image creation command is executable`() {
        val result = engine.understand("Erstelle ein Bild.")

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        val node = assertNotNull(
            result.goal.semanticActionGraph.executableNodeFor(IntentType.CREATE_IMAGE)
        )
        assertEquals(SpeechActType.COMMAND, node.frame.speechAct.type)
        assertTrue(SemanticRole.OBJECT in node.frame.roles)
        assertTrue(SemanticExecutionGate.evaluate(result.goal).allowed)
    }

    @Test
    fun `negated communication can never authorize external effect`() {
        val result = engine.understand("Sende diese Mail nicht.")
        val node = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(node.frame.negated)
        assertFalse(node.executable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
        assertTrue(result.goal.ambiguities.any { it.code == "negated_action" })
    }

    @Test
    fun `quoted communication remains non executable`() {
        val result = engine.understand("Er sagte: „Sende die Mail.“")
        val node = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(node.frame.quoted)
        assertFalse(node.executable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `conditional communication remains blocked until condition is resolved`() {
        val result = engine.understand("Wenn X passiert, sende die Mail.")
        val graph = result.goal.semanticActionGraph
        val action = graph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(action.unresolvedCondition)
        assertFalse(action.executable)
        assertTrue(graph.edges.any {
            it.to == action.id && it.type == SemanticActionEdgeType.IF
        })
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `search then send binds pronoun to previous result without partial legacy execution`() {
        val result = engine.understand("Suche den Bescheid und sende ihn mir anschließend.")
        val graph = result.goal.semanticActionGraph
        val search = graph.nodes.single { it.frame.predicate == PredicateConcept.SEARCH }
        val send = graph.nodes.single { it.frame.predicate == PredicateConcept.COMMUNICATE }

        assertTrue(search.executable)
        assertTrue(send.executable)
        assertTrue(graph.edges.any {
            it.from == search.id &&
                it.to == send.id &&
                it.type == SemanticActionEdgeType.USES_RESULT_OF
        })
        val admission = SemanticExecutionGate.evaluate(result.goal)
        assertFalse(admission.allowed)
        assertEquals("semantic-multi-action-requires-action-graph-router", admission.reason)
    }

    @Test
    fun `context resolved pronoun still binds the immediately previous action result`() {
        val now = Instant.parse("2026-09-21T17:00:00Z")
        val ambientRef = PhotonRevisionRef(PhotonId("ambient-result"), 7L)
        val result = engine.understand(
            "Merke dir die Semantic-Recovery-Notiz und sende sie mir anschließend.",
            LanguageContext(
                now = now,
                items = listOf(
                    LanguageContextItem(
                        photonId = ambientRef.photonId,
                        kind = "result",
                        tags = setOf("result", "answer"),
                        createdAt = now.minusSeconds(1),
                        active = true,
                        contentTerms = setOf("semantic", "recovery", "notiz"),
                        revisionRef = ambientRef,
                    )
                ),
            ),
        )
        val graph = result.goal.semanticActionGraph
        val memory = graph.nodes.single { it.frame.predicate == PredicateConcept.STORE_MEMORY }
        val send = graph.nodes.single { it.frame.predicate == PredicateConcept.COMMUNICATE }

        assertTrue(
            result.goal.references.any { it.targetPhotonRef == ambientRef },
            "Fixture must prove ambient context resolved the pronoun before action-graph binding",
        )
        assertTrue(
            graph.edges.any {
                it.from == memory.id &&
                    it.to == send.id &&
                    it.type == SemanticActionEdgeType.USES_RESULT_OF
            },
            "Immediate compositional result must outrank ambient context for the downstream action",
        )
    }

    @Test
    fun `debt roles preserve who owes whom`() {
        val first = engine.understand("Peter schuldet Anna 100 Euro.")
        val second = engine.understand("Anna schuldet Peter 100 Euro.")

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
        assertEquals("100", firstFrame.roles.getValue(SemanticRole.AMOUNT).normalized)
        assertEquals("EUR", firstFrame.roles.getValue(SemanticRole.CURRENCY).normalized)
    }

    @Test
    fun `same input produces same semantic action fingerprint`() {
        val first = engine.understand("Erstelle ein Bild.")
        val second = engine.understand("Erstelle ein Bild.")

        assertEquals(
            first.goal.semanticActionGraph.fingerprint,
            second.goal.semanticActionGraph.fingerprint,
        )
        assertEquals(first.goal.semanticActionGraph, second.goal.semanticActionGraph)
    }
}

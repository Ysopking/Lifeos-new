package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalModalRealityEngineTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun explicitTomorrowRequestIsFuturePossibilityNotCurrentState() {
        val result = engine.understand(
            "Erinnere mich morgen um 10 Uhr daran.",
            LanguageContext(now = now),
        )
        val proposition = result.goal.languageRealization.propositions.first()

        assertEquals(LanguageTemporalStatus.FUTURE, proposition.temporalStatus)
        assertEquals(LanguageRepresentationLevel.POSSIBILITY, proposition.representation)
        assertTrue(LanguageModalStatus.REQUESTED in proposition.modalStatuses)
        assertTrue(LanguageModalStatus.POSSIBLE in proposition.modalStatuses)
        assertFalse(proposition.directWorldTruthClaimAllowed)
    }

    @Test
    fun rememberedPastAssertionUsesHistoryRepresentation() {
        val result = engine.understand(
            "Ich erinnere mich, gestern war der Termin.",
            LanguageContext(now = now),
        )

        assertTrue(
            result.goal.temporalModalReality.propositions.any {
                it.temporalStatus == LanguageTemporalStatus.PAST &&
                    LanguageModalStatus.REMEMBERED in it.modalStatuses
            }
        )
        assertTrue(
            result.goal.languageRealization.propositions.any {
                it.representation == LanguageRepresentationLevel.HISTORY
            }
        )
    }

    @Test
    fun counterfactualRemainsPossibility() {
        val result = engine.understand(
            "Wenn ich mehr Zeit hätte, würde ich die Datei ändern.",
            LanguageContext(now = now),
        )

        assertTrue(
            result.goal.temporalModalReality.propositions.any {
                LanguageModalStatus.COUNTERFACTUAL in it.modalStatuses
            }
        )
        assertTrue(
            result.goal.languageRealization.propositions.any {
                it.epistemicStatus == LanguageEpistemicStatus.COUNTERFACTUAL &&
                    it.representation == LanguageRepresentationLevel.POSSIBILITY
            }
        )
    }

    @Test
    fun temporalRealityOrderingIsDeterministic() {
        val utterance = UtteranceNormalizer().normalize("test")
        val a = node("a", SpeechActType.ASSERTION)
        val z = node("z", SpeechActType.REQUEST)
        val graphA = graph(listOf(z, a))
        val graphB = graph(listOf(a, z))
        val temporal = QuantityTemporalResult(emptyList(), emptyList())
        val resolver = TemporalModalRealityEngine()

        val first = resolver.resolve(utterance, graphA, temporal, now)
        val second = resolver.resolve(utterance, graphB, temporal, now)

        assertEquals(first, second)
        assertEquals(
            first.propositions.map { it.nodeId.value }.sorted(),
            first.propositions.map { it.nodeId.value },
        )
    }

    private fun graph(
        nodes: List<SemanticActionNode>,
    ) = SemanticActionGraph(
        nodes = nodes,
        edges = emptyList(),
        scopes = emptyList(),
        fingerprint = StableCognitiveIds.fingerprint(
            "temporal-modal-test-graph/v1",
            *nodes.map { it.id.value }.sorted().toTypedArray(),
        ),
    )

    private fun node(
        seed: String,
        speechActType: SpeechActType,
    ): SemanticActionNode {
        val id = SemanticNodeId.create("temporal-modal-test", seed)
        val span = TextSpan(0, 4)
        val evidence = listOf(SemanticEvidence("test", "test", 1.0, span))
        return SemanticActionNode(
            id = id,
            type = if (speechActType == SpeechActType.QUESTION) {
                SemanticActionNodeType.QUERY
            } else {
                SemanticActionNodeType.ACTION
            },
            frame = PredicateFrame(
                nodeId = id,
                clauseId = 0,
                predicate = PredicateConcept.QUERY,
                roles = emptyMap(),
                scopeTypes = emptySet(),
                speechAct = SpeechAct(
                    type = speechActType,
                    confidence = 1.0,
                    evidence = evidence,
                    span = span,
                ),
                confidence = 1.0,
                evidence = evidence,
            ),
            requiredRoles = emptySet(),
            unresolvedRoles = emptySet(),
            unresolvedReference = false,
            unresolvedCondition = false,
            externalSideEffect = false,
            executionReadiness = 0.0,
        )
    }
}

package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageRealizationEngineTest {
    private val engine = LanguageRealizationEngine()
    private val utterance = NormalizedUtterance(
        original = "test",
        normalized = "test",
        language = LanguageCode.EN,
        tokens = listOf(
            LanguageToken("test", "test", TokenKind.WORD, 0, 4)
        ),
    )

    @Test
    fun assertionIsBeliefNotActualWorldState() {
        val state = engine.realize(
            utterance,
            graph(node(SpeechActType.ASSERTION)),
        )
        val proposition = state.propositions.single()

        assertEquals(LanguageRepresentationLevel.ACTUAL, state.utteranceRepresentation)
        assertEquals(
            LanguageEpistemicStatus.OBSERVED_UTTERANCE,
            state.utteranceEpistemicStatus,
        )
        assertEquals(LanguageRepresentationLevel.BELIEF, proposition.representation)
        assertEquals(
            LanguageEpistemicStatus.SPEAKER_ASSERTED,
            proposition.epistemicStatus,
        )
        assertFalse(proposition.directWorldTruthClaimAllowed)
        assertFalse(state.directWorldStateMutationAllowed)
    }

    @Test
    fun commandIsPossibilityAndNeverExecutionAuthority() {
        val proposition = engine.realize(
            utterance,
            graph(node(SpeechActType.COMMAND)),
        ).propositions.single()

        assertEquals(LanguageRepresentationLevel.POSSIBILITY, proposition.representation)
        assertEquals(LanguageEpistemicStatus.UNRESOLVED, proposition.epistemicStatus)
        assertTrue(LanguageModalStatus.REQUESTED in proposition.modalStatuses)
        assertTrue(LanguageModalStatus.POSSIBLE in proposition.modalStatuses)
        assertFalse(proposition.executionAuthority)
    }

    @Test
    fun quotedHypotheticalCannotBecomeAssertedFact() {
        val proposition = engine.realize(
            utterance,
            graph(
                node(
                    speechAct = SpeechActType.ASSERTION,
                    scopes = setOf(ScopeType.QUOTATION, ScopeType.HYPOTHETICAL),
                )
            ),
        ).propositions.single()

        assertEquals(LanguageRepresentationLevel.PROJECTED, proposition.representation)
        assertEquals(LanguageEpistemicStatus.QUOTED, proposition.epistemicStatus)
        assertTrue(LanguageModalStatus.QUOTED in proposition.modalStatuses)
        assertTrue(LanguageModalStatus.HYPOTHETICAL in proposition.modalStatuses)
        assertTrue("quotation" in proposition.unresolvedReasons)
        assertTrue("hypothetical" in proposition.unresolvedReasons)
    }

    @Test
    fun realizationOrderingIsDeterministicBySemanticNodeId() {
        val a = node(SpeechActType.ASSERTION, seed = "a")
        val z = node(SpeechActType.QUESTION, seed = "z")
        val first = engine.realize(utterance, graph(z, a))
        val second = engine.realize(utterance, graph(a, z))

        assertEquals(first, second)
        assertEquals(
            first.propositions.map { it.nodeId.value }.sorted(),
            first.propositions.map { it.nodeId.value },
        )
    }

    @Test
    fun languageUnderstandingEnginePublishesRealizationState() {
        val result = LanguageUnderstandingEngine().understand("Lösche die Datei.")

        assertTrue(result.goal.languageRealization.propositions.isNotEmpty())
        assertFalse(result.goal.languageRealization.executionAuthority)
        assertFalse(result.goal.languageRealization.directWorldStateMutationAllowed)
    }

    private fun graph(vararg nodes: SemanticActionNode): SemanticActionGraph =
        SemanticActionGraph(
            nodes = nodes.toList(),
            edges = emptyList(),
            scopes = emptyList(),
            fingerprint = StableCognitiveIds.fingerprint(
                "test-semantic-action-graph/v1",
                *nodes.map { it.id.value }.sorted().toTypedArray(),
            ),
        )

    private fun node(
        speechAct: SpeechActType,
        scopes: Set<ScopeType> = emptySet(),
        seed: String = speechAct.name,
    ): SemanticActionNode {
        val id = SemanticNodeId.create("language-realization-test", seed)
        val span = TextSpan(0, 4)
        val frame = PredicateFrame(
            nodeId = id,
            clauseId = 0,
            predicate = PredicateConcept.DELETE,
            roles = emptyMap(),
            scopeTypes = scopes,
            speechAct = SpeechAct(
                type = speechAct,
                confidence = 1.0,
                evidence = listOf(
                    SemanticEvidence(
                        source = "test",
                        detail = "test",
                        strength = 1.0,
                        span = span,
                    )
                ),
                span = span,
            ),
            confidence = 1.0,
            evidence = listOf(
                SemanticEvidence(
                    source = "test",
                    detail = "test",
                    strength = 1.0,
                    span = span,
                )
            ),
        )
        return SemanticActionNode(
            id = id,
            type = when (speechAct) {
                SpeechActType.QUESTION -> SemanticActionNodeType.QUERY
                else -> SemanticActionNodeType.ACTION
            },
            frame = frame,
            requiredRoles = emptySet(),
            unresolvedRoles = emptySet(),
            unresolvedReference = false,
            unresolvedCondition = ScopeType.CONDITION in scopes,
            externalSideEffect = true,
            executionReadiness = 1.0,
        )
    }
}

package app.lifeos.core.language

import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticPropositionGraphTest {
    private val realizationEngine = LanguageRealizationEngine()
    private val builder = SemanticPropositionGraphBuilder()
    private val utterance = NormalizedUtterance(
        original = "test",
        normalized = "test",
        language = LanguageCode.EN,
        tokens = listOf(LanguageToken("test", "test", TokenKind.WORD, 0, 4)),
    )

    @Test
    fun directAssertionIsSpeakerPropositionNotWorldFact() {
        val node = node(SpeechActType.ASSERTION, seed = "assert")
        val actionGraph = graph(listOf(node))
        val graph = builder.build(
            actionGraph,
            realizationEngine.realize(utterance, actionGraph),
        )

        val proposition = graph.nodes.single()
        assertEquals(PropositionSourceKind.DIRECT_SPEAKER, proposition.source.kind)
        assertEquals(
            LanguageRepresentationLevel.BELIEF,
            proposition.realization.representation,
        )
        assertFalse(proposition.directWorldTruthAuthority)
        assertFalse(graph.directWorldStateMutationAllowed)
    }

    @Test
    fun quotedPropositionKeepsUnresolvedAttribution() {
        val node = node(
            speechAct = SpeechActType.ASSERTION,
            seed = "quoted",
            scopes = setOf(ScopeType.QUOTATION),
        )
        val actionGraph = graph(listOf(node))
        val graph = builder.build(
            actionGraph,
            realizationEngine.realize(utterance, actionGraph),
        )

        val source = graph.nodes.single().source
        assertEquals(PropositionSourceKind.QUOTED_SPEECH, source.kind)
        assertFalse(source.attributionResolved)
        assertEquals(null, source.actorReference)
    }

    @Test
    fun languageCausalEdgeRemainsClaimNotWorldCausality() {
        val cause = node(SpeechActType.ASSERTION, seed = "cause")
        val effect = node(SpeechActType.ASSERTION, seed = "effect")
        val actionEdge = SemanticActionEdge(
            from = cause.id,
            to = effect.id,
            type = SemanticActionEdgeType.CAUSES,
            confidence = 0.8,
        )
        val actionGraph = graph(listOf(cause, effect), listOf(actionEdge))
        val graph = builder.build(
            actionGraph,
            realizationEngine.realize(utterance, actionGraph),
        )

        assertEquals(
            PropositionRelationType.CLAIMS_CAUSAL_RELATION,
            graph.edges.single().relation,
        )
        assertFalse(graph.edges.single().worldCausalityAuthority)
        assertFalse(graph.worldCausalityAuthority)
    }

    @Test
    fun graphIsDeterministicAcrossActionNodeOrdering() {
        val a = node(SpeechActType.ASSERTION, seed = "a")
        val z = node(SpeechActType.QUESTION, seed = "z")
        val firstAction = graph(listOf(z, a))
        val secondAction = graph(listOf(a, z))
        val first = builder.build(
            firstAction,
            realizationEngine.realize(utterance, firstAction),
        )
        val second = builder.build(
            secondAction,
            realizationEngine.realize(utterance, secondAction),
        )

        assertEquals(first, second)
    }

    @Test
    fun languageUnderstandingPublishesPropositionGraph() {
        val result = LanguageUnderstandingEngine().understand("Lösche die Datei.")

        assertTrue(result.goal.propositionGraph.nodes.isNotEmpty())
        assertEquals(
            result.goal.semanticActionGraph.nodes.size,
            result.goal.propositionGraph.nodes.size,
        )
        assertFalse(result.goal.propositionGraph.directWorldStateMutationAllowed)
    }

    private fun graph(
        nodes: List<SemanticActionNode>,
        edges: List<SemanticActionEdge> = emptyList(),
    ): SemanticActionGraph = SemanticActionGraph(
        nodes = nodes,
        edges = edges,
        scopes = emptyList(),
        fingerprint = StableCognitiveIds.fingerprint(
            "semantic-proposition-test-graph/v1",
            *buildList {
                nodes.map { it.id.value }.sorted().forEach(::add)
                edges.map { it.id.value }.sorted().forEach(::add)
            }.toTypedArray(),
        ),
    )

    private fun node(
        speechAct: SpeechActType,
        seed: String,
        scopes: Set<ScopeType> = emptySet(),
    ): SemanticActionNode {
        val id = SemanticNodeId.create("semantic-proposition-test", seed)
        val span = TextSpan(0, 4)
        val evidence = listOf(
            SemanticEvidence("test", "test", 1.0, span)
        )
        return SemanticActionNode(
            id = id,
            type = if (speechAct == SpeechActType.QUESTION) {
                SemanticActionNodeType.QUERY
            } else {
                SemanticActionNodeType.ASSERTION
            },
            frame = PredicateFrame(
                nodeId = id,
                clauseId = 0,
                predicate = PredicateConcept.QUERY,
                roles = emptyMap(),
                scopeTypes = scopes,
                speechAct = SpeechAct(
                    type = speechAct,
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
            unresolvedCondition = ScopeType.CONDITION in scopes,
            externalSideEffect = false,
            executionReadiness = 0.0,
        )
    }
}

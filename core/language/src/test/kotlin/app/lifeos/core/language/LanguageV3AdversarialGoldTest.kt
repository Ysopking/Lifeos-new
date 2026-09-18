package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageV3AdversarialGoldTest {
    private val engine = LanguageUnderstandingEngine()
    private val normalizer = UtteranceNormalizer()

    @Test
    fun `multi predicate clause emits separate frames without intent authority`() {
        val utterance = normalizer.normalize("Suche den Bescheid sende ihn mir")
        val graph = LanguageSemanticGraphExtractor().extract(utterance, emptyList())
        val acts = SpeechActParser().parse(utterance, graph)
        val frames = PredicateFrameParser().parse(
            utterance = utterance,
            graph = graph,
            speechActs = acts,
            references = emptyList(),
        )

        assertEquals(
            listOf(PredicateConcept.SEARCH, PredicateConcept.COMMUNICATE),
            frames.map { it.predicate },
        )
        assertTrue(frames.map { it.nodeId }.distinct().size == 2)
    }

    @Test
    fun `local communication preparation cannot imply external effect authority`() {
        val local = engine.understand("Sende diese Mail.")
        val localNode = local.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(localNode.executable)
        assertTrue(SemanticRole.RECIPIENT in localNode.externalRequiredRoles)
        assertTrue(SemanticRole.RECIPIENT in localNode.unresolvedExternalRoles)
        assertFalse(localNode.externalEffectExecutable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(local.goal))

        val addressed = engine.understand("Sende diese Mail an Frau Anna Müller.")
        val addressedNode = addressed.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }

        assertTrue(addressedNode.executable)
        assertTrue(addressedNode.unresolvedExternalRoles.isEmpty())
        assertTrue(addressedNode.externalEffectExecutable)
        assertTrue(SemanticExecutionGate.externalEffectAllowed(addressed.goal))
    }

    @Test
    fun `condition scope binds explicit IF relation identity`() {
        val result = engine.understand("Wenn X passiert, sende die Mail.")
        val ifEdges = result.goal.semanticActionGraph.edges.filter {
            it.type == SemanticActionEdgeType.IF
        }
        assertTrue(ifEdges.isNotEmpty())

        val conditionScopes = result.goal.semanticActionGraph.scopes.filter {
            it.type == ScopeType.CONDITION
        }
        assertTrue(conditionScopes.isNotEmpty())
        assertTrue(conditionScopes.any { scope ->
            scope.targets.any { target ->
                target.kind == SemanticScopeTargetKind.RELATION &&
                    target.edgeId in ifEdges.map { it.id }.toSet()
            }
        })
    }

    @Test
    fun `negation and exclusion never become clause wide truth`() {
        val negated = engine.understand("Sende die Mail nicht.")
        val negation = negated.goal.semanticActionGraph.scopes.single {
            it.type == ScopeType.NEGATION
        }
        assertTrue(negation.targets.all { it.kind == SemanticScopeTargetKind.PREDICATE })

        val exclusion = engine.understand("Sende nicht die Mail sondern den Bericht.")
        val exclusionScope = exclusion.goal.semanticActionGraph.scopes.firstOrNull {
            it.type == ScopeType.EXCLUSION
        }
        assertNotNull(exclusionScope)
        assertTrue(exclusionScope.targets.any { it.kind == SemanticScopeTargetKind.ROLE })
    }

    @Test
    fun `domain binding cannot cross clause document boundaries`() {
        val utterance = normalizer.normalize("Bescheid Forderung. Rechnung Schuld.")
        val graph = LanguageSemanticGraphExtractor().extract(utterance, emptyList())

        fun index(word: String): Int = utterance.tokens.indexOfFirst { it.normalized == word }
            .also { require(it >= 0) }

        fun entity(
            type: SemanticEntityTypeDefinition,
            value: String,
            token: Int,
        ) = SemanticEntityV2(
            typeId = type.id,
            rawText = value,
            normalizedValue = value,
            tokenStart = token,
            tokenEndExclusive = token + 1,
            confidence = 1.0,
            source = "b145-independent-fixture",
        )

        val domain = DomainSemanticInterpreter().interpret(
            utterance = utterance,
            semanticGraph = graph,
            entities = listOf(
                entity(EntityTypeRegistry.NOTICE, "notice-a", index("bescheid")),
                entity(EntityTypeRegistry.CLAIM, "claim-a", index("forderung")),
                entity(EntityTypeRegistry.INVOICE, "invoice-b", index("rechnung")),
                entity(EntityTypeRegistry.DEBT, "debt-b", index("schuld")),
            ),
            quantityTemporal = QuantityTemporalResult(emptyList(), emptyList()),
            actionGraph = SemanticActionGraph.empty(),
        )

        val byValue = domain.nodes.associateBy { it.value }
        val notice = assertNotNull(byValue["notice-a"])
        val claim = assertNotNull(byValue["claim-a"])
        val invoice = assertNotNull(byValue["invoice-b"])
        val debt = assertNotNull(byValue["debt-b"])
        val contains = domain.relations.filter { it.type == DomainSemanticRelationType.CONTAINS }

        assertTrue(contains.any { it.from == notice.id && it.to == claim.id })
        assertTrue(contains.any { it.from == invoice.id && it.to == debt.id })
        assertFalse(contains.any { it.from == notice.id && it.to == debt.id })
        assertFalse(contains.any { it.from == invoice.id && it.to == claim.id })
    }

    @Test
    fun `entity v3 extracts person organization and address as productive typed entities`() {
        val baseline = DeterministicEntityPipelineV2()
        val v3 = EntityEngineV3(baseline)
        val result = v3.extract(
            normalizer.normalize(
                "Schicke die Unterlagen an Frau Anna Müller bei Acme GmbH in Hauptstraße 12."
            )
        )

        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.PERSON.id && "Anna Müller" in it.rawText
        })
        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.ORGANIZATION.id && "Acme GmbH" in it.rawText
        })
        assertTrue(result.entities.any {
            it.typeId == EntityTypeRegistry.ADDRESS.id && "Hauptstraße 12" in it.rawText
        })
    }

    @Test
    fun `reference v3 keeps semantically exact old revision inside bounded top k`() {
        val now = Instant.parse("2026-09-18T12:00:00Z")
        val targetId = PhotonId("target-dog-image")
        val items = (0 until 50).map { index ->
            val target = index == 49
            val id = if (target) targetId else PhotonId("image-$index")
            val terms = if (target) setOf("bild", "hund") else setOf("bild", "katze")
            LanguageContextItem(
                photonId = id,
                kind = "image",
                tags = setOf("image"),
                createdAt = now.minusSeconds(index.toLong() * 60L),
                active = index == 0,
                contentTerms = terms,
                normalizedTerms = terms,
                confidence = 1.0,
                revisionRef = PhotonRevisionRef(id, index.toLong() + 1L),
                semanticTypes = setOf("image"),
            )
        }
        val context = LanguageContext(items = items, now = now)
        val expression = ReferenceExpression(
            kind = ReferenceKind.THAT,
            rawText = "das Bild mit dem Hund",
            preferredKinds = setOf("image"),
            confidence = 0.95,
        )

        val indexed = ReferenceCandidateIndexV3(context).candidates(expression)
        val ranked = ReferenceResolver().rankRevisionRefs(expression, context)

        assertTrue(indexed.size <= 32)
        assertTrue(indexed.any { it.item.photonId == targetId })
        assertTrue(ranked.size <= 12)
        assertEquals(targetId, ranked.first().first.photonId)
    }

    @Test
    fun `BK tree lookup is bounded and typo stable without full form scan`() {
        var keyCalls = 0
        val entries = (0 until 500).map { "token" + it.toString().padStart(3, '0') } + "senden"
        val tree = BkTreeIndex(entries) {
            keyCalls += 1
            it
        }
        keyCalls = 0

        val result = tree.search(
            rawQuery = "snden",
            maxDistance = 1,
            limit = 4,
            maxVisited = 32,
        )

        assertTrue(result.any { it.value == "senden" })
        assertTrue(keyCalls <= 64, "bounded BK lookup exceeded search budget: $keyCalls")
    }
}

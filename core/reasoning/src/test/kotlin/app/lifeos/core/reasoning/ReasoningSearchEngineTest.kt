package app.lifeos.core.reasoning

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningSearchEngineTest {
    private val at = Instant.parse("2026-09-21T18:30:00Z")
    private val domainId = StableFieldIds.domain("reasoning-search-test")
    private val problemBuilder = ProblemStateGraphBuilder()
    private val hypothesisBuilder = ProblemHypothesisSeedBuilder()

    @Test
    fun `two binary questions produce four complete structural states`() {
        val fixture = fixture()
        val result = ReasoningSearchEngine().search(fixture.seed)

        assertFalse(result.truncated)
        assertEquals(4, result.states.size)
        assertEquals(4, result.completeStates.size)
        assertTrue(result.states.all { it.selectedHypothesisIds.size == 2 })
        assertTrue(result.states.all { it.unresolvedCompetitionKeys.isEmpty() })
    }

    @Test
    fun `metrics come only from admitted evidence contradiction and assumption dependencies`() {
        val fixture = fixture()
        val result = ReasoningSearchEngine().search(fixture.seed)

        val metrics = result.states.map { it.metrics }
        assertTrue(metrics.any {
            it.supportingEvidenceCount == 2 &&
                it.supportingWeight > 0.0 &&
                it.contradictionWeight == 0.0
        })
        assertTrue(metrics.any { it.contradictionWeight > 0.0 })
        assertTrue(metrics.any { it.assumptionDependencyWeight > 0.0 })
        assertTrue(metrics.all { it.unresolvedCompetitionCount == 0 })
    }

    @Test
    fun `reordered questions and alternatives keep search identity and order`() {
        val first = fixture(reverseQuestions = false, reverseAlternatives = false)
        val second = fixture(reverseQuestions = true, reverseAlternatives = true)

        val firstResult = ReasoningSearchEngine().search(first.seed)
        val secondResult = ReasoningSearchEngine().search(second.seed)

        assertEquals(first.seed.fingerprint, second.seed.fingerprint)
        assertEquals(firstResult.fingerprint, secondResult.fingerprint)
        assertEquals(firstResult.states, secondResult.states)
    }

    @Test
    fun `expanded state budget reports truncation without pretending completion`() {
        val fixture = fixture()
        val result = ReasoningSearchEngine(
            ReasoningSearchConfig(
                maxExpandedStates = 2,
                maxFrontierStates = 16,
            )
        ).search(fixture.seed)

        assertTrue(result.truncated)
        assertEquals(2, result.exploredStates)
        assertTrue(result.completeStates.isEmpty())
        assertTrue(result.states.all { it.unresolvedCompetitionKeys.isNotEmpty() })
    }

    @Test
    fun `frontier budget is deterministic and explicitly truncated`() {
        val fixture = fixture()
        val first = ReasoningSearchEngine(
            ReasoningSearchConfig(
                maxExpandedStates = 64,
                maxFrontierStates = 2,
            )
        ).search(fixture.seed)
        val second = ReasoningSearchEngine(
            ReasoningSearchConfig(
                maxExpandedStates = 64,
                maxFrontierStates = 2,
            )
        ).search(fixture.seed)

        assertTrue(first.truncated)
        assertEquals(2, first.states.size)
        assertEquals(first, second)
    }

    private fun fixture(
        reverseQuestions: Boolean = false,
        reverseAlternatives: Boolean = false,
    ): Fixture {
        val evidenceA = evidence(
            semanticKey = "fixture.a",
            sourceId = "evidence-a",
            payload = "A",
        )
        val evidenceB = evidence(
            semanticKey = "fixture.b",
            sourceId = "evidence-b",
            payload = "B",
        )
        val problem = problem(evidenceA, evidenceB)
        val unknowns = problem.unknowns.sortedBy { it.semanticKey }
        val firstUnknown = unknowns[0]
        val secondUnknown = unknowns[1]
        val facts = problem.facts.associateBy { it.semanticKey.removePrefix("fact:") }
        val assumptions = problem.assumptions.associateBy { it.semanticKey }

        val q1a = ProblemHypothesisAlternative(
            semanticKey = "q1-a",
            claim = "Question one alternative A.",
            supportingFactNodeIds = setOf(facts.getValue("fixture.a").id),
        )
        val q1b = ProblemHypothesisAlternative(
            semanticKey = "q1-b",
            claim = "Question one alternative B.",
            contradictingFactNodeIds = setOf(facts.getValue("fixture.a").id),
            assumptionNodeIds = setOf(assumptions.getValue("assumption:stable").id),
        )
        val q2a = ProblemHypothesisAlternative(
            semanticKey = "q2-a",
            claim = "Question two alternative A.",
            supportingFactNodeIds = setOf(facts.getValue("fixture.b").id),
        )
        val q2b = ProblemHypothesisAlternative(
            semanticKey = "q2-b",
            claim = "Question two alternative B.",
            contradictingFactNodeIds = setOf(facts.getValue("fixture.b").id),
        )

        fun ordered(
            first: ProblemHypothesisAlternative,
            second: ProblemHypothesisAlternative,
        ): List<ProblemHypothesisAlternative> =
            if (reverseAlternatives) listOf(second, first) else listOf(first, second)

        val questions = listOf(
            ProblemHypothesisQuestion(
                unknownNodeId = firstUnknown.id,
                alternatives = ordered(q1a, q1b),
            ),
            ProblemHypothesisQuestion(
                unknownNodeId = secondUnknown.id,
                alternatives = ordered(q2a, q2b),
            ),
        ).let { if (reverseQuestions) it.reversed() else it }

        val seed = hypothesisBuilder.build(
            problem = problem,
            domainId = domainId,
            evidence = listOf(evidenceB, evidenceA),
            questions = questions,
        )
        return Fixture(seed)
    }

    private fun problem(
        evidenceA: FieldEvidence,
        evidenceB: FieldEvidence,
    ): ProblemStateGraph = problemBuilder.build(
        goal = GoalFrame(
            intent = IntentType.QUERY,
            objective = "Resolve two independent unknowns.",
            entities = emptyList(),
            references = emptyList(),
            constraints = emptyList(),
            ambiguities = listOf(
                Ambiguity(
                    code = "unknown-a",
                    message = "Unknown A",
                    alternatives = listOf("a1", "a2"),
                    severity = 1.0,
                )
            ),
            confidence = 0.95,
            language = LanguageCode.EN,
        ),
        sourcePhoton = Photon(
            id = PhotonId("reasoning-search-source"),
            revision = 2L,
            content = "Resolve two independent unknowns.",
            provenance = Provenance(
                source = "reasoning-search-test",
                actor = "owner",
                createdAt = at,
            ),
        ),
        facts = listOf(
            ProblemFactInput(
                semanticKey = evidenceA.semanticKey,
                statement = "Fact A",
                confidence = 0.60,
                evidence = listOf(evidenceA),
            ),
            ProblemFactInput(
                semanticKey = evidenceB.semanticKey,
                statement = "Fact B",
                confidence = 0.60,
                evidence = listOf(evidenceB),
            ),
        ),
        additionalUnknowns = listOf(
            ProblemUnknownInput(
                semanticKey = "unknown:b",
                statement = "Unknown B",
                confidence = 1.0,
            )
        ),
        assumptions = listOf(
            ProblemAssumptionInput(
                semanticKey = "assumption:stable",
                statement = "The environment is stable.",
                confidence = 0.5,
            )
        ),
    )

    private fun evidence(
        semanticKey: String,
        sourceId: String,
        payload: String,
    ): FieldEvidence = FieldEvidence.create(
        domainId = domainId,
        sourcePhotonId = PhotonId(sourceId),
        sourceRevision = 1L,
        kind = EvidenceKind.DOCUMENT_FACT,
        semanticKey = semanticKey,
        confidence = 0.60,
        reliability = EvidenceReliability(0.8, "test"),
        authority = SourceAuthority.USER_PROVIDED,
        observedAt = at,
        payload = EvidencePayload.text(payload),
        explanation = "test evidence",
    )

    private data class Fixture(
        val seed: ProblemHypothesisSeed,
    )
}

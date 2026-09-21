package app.lifeos.core.reasoning

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.HypothesisState
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProblemHypothesisSeedTest {
    private val at = Instant.parse("2026-09-21T18:00:00Z")
    private val domainId = StableFieldIds.domain("reasoning-test")
    private val problemBuilder = ProblemStateGraphBuilder()
    private val seedBuilder = ProblemHypothesisSeedBuilder()

    @Test
    fun `two alternatives become competing existing field hypotheses`() {
        val evidence = evidence()
        val problem = problem(evidence)
        val unknown = problem.unknowns.single()
        val fact = problem.facts.single()
        val assumption = problem.assumptions.single()

        val seed = seedBuilder.build(
            problem = problem,
            domainId = domainId,
            evidence = listOf(evidence),
            questions = listOf(
                ProblemHypothesisQuestion(
                    unknownNodeId = unknown.id,
                    alternatives = listOf(
                        ProblemHypothesisAlternative(
                            semanticKey = "local-cause",
                            claim = "The issue is caused by local state.",
                            supportingFactNodeIds = setOf(fact.id),
                            assumptionNodeIds = setOf(assumption.id),
                            priorConfidence = 0.6,
                        ),
                        ProblemHypothesisAlternative(
                            semanticKey = "remote-cause",
                            claim = "The issue is caused by remote state.",
                            contradictingFactNodeIds = setOf(fact.id),
                            priorConfidence = 0.4,
                        ),
                    ),
                )
            ),
        )

        assertEquals(2, seed.hypotheses.size)
        assertTrue(seed.hypotheses.all { it.state == HypothesisState.COMPETING })
        assertEquals(1, seed.graph.competitionGroups.size)
        assertEquals(2, seed.graph.competitionGroups.single().nodeIds.size)
        assertTrue(seed.hypotheses.all { it.conflicts.size == 1 })

        val supported = seed.hypotheses.single {
            it.explanation == "The issue is caused by local state."
        }
        assertEquals(
            listOf(EvidenceRelationType.SUPPORTS),
            supported.evidenceLinks.map { it.relation },
        )
        assertEquals(evidence.id, supported.evidenceLinks.single().evidenceId)

        val contradicted = seed.hypotheses.single {
            it.explanation == "The issue is caused by remote state."
        }
        assertEquals(
            listOf(EvidenceRelationType.CONTRADICTS),
            contradicted.evidenceLinks.map { it.relation },
        )
    }

    @Test
    fun `assumptions stay graph dependencies and never become evidence links`() {
        val evidence = evidence()
        val problem = problem(evidence)
        val unknown = problem.unknowns.single()
        val assumption = problem.assumptions.single()

        val seed = seedBuilder.build(
            problem = problem,
            domainId = domainId,
            evidence = listOf(evidence),
            questions = listOf(
                question(
                    unknownNodeId = unknown.id,
                    first = ProblemHypothesisAlternative(
                        semanticKey = "assumption-dependent",
                        claim = "Alternative depends on an assumption.",
                        assumptionNodeIds = setOf(assumption.id),
                    ),
                    second = ProblemHypothesisAlternative(
                        semanticKey = "other",
                        claim = "Alternative does not depend on it.",
                    ),
                )
            ),
        )

        val assumptionDependent = seed.hypotheses.single {
            it.explanation == "Alternative depends on an assumption."
        }
        assertTrue(assumptionDependent.evidenceLinks.isEmpty())
        val assumptionFieldNode = seed.problemNodeBindings.getValue(assumption.id)
        val hypothesisFieldNode = seed.graph.nodes.single {
            it.kind.name == "HYPOTHESIS" &&
                it.attributes["alternativeSemanticKey"] == "assumption-dependent"
        }
        assertTrue(
            seed.graph.relations.any {
                it.source == assumptionFieldNode &&
                    it.target == hypothesisFieldNode.id &&
                    it.type.name == "DEPENDS_ON"
            }
        )
    }

    @Test
    fun `exact B366 evidence fingerprint is mandatory even when evidence id matches`() {
        val original = evidence(confidence = 0.60)
        val problem = problem(original)
        val alteredSameId = evidence(confidence = 0.55)
        assertEquals(original.id, alteredSameId.id)

        val unknown = problem.unknowns.single()
        val failure = assertFailsWith<IllegalArgumentException> {
            seedBuilder.build(
                problem = problem,
                domainId = domainId,
                evidence = listOf(alteredSameId),
                questions = listOf(
                    question(
                        unknownNodeId = unknown.id,
                        first = ProblemHypothesisAlternative("a", "A"),
                        second = ProblemHypothesisAlternative("b", "B"),
                    )
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("exact source revision/content"))
    }

    @Test
    fun `single alternative and non unknown targets fail closed`() {
        val evidence = evidence()
        val problem = problem(evidence)

        assertFailsWith<IllegalArgumentException> {
            ProblemHypothesisQuestion(
                unknownNodeId = problem.unknowns.single().id,
                alternatives = listOf(
                    ProblemHypothesisAlternative("single", "Only one")
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            seedBuilder.build(
                problem = problem,
                domainId = domainId,
                evidence = listOf(evidence),
                questions = listOf(
                    question(
                        unknownNodeId = problem.goalNodeId,
                        first = ProblemHypothesisAlternative("a", "A"),
                        second = ProblemHypothesisAlternative("b", "B"),
                    )
                ),
            )
        }
    }

    @Test
    fun `alternative order cannot change canonical seed identity`() {
        val evidence = evidence()
        val problem = problem(evidence)
        val unknown = problem.unknowns.single()
        val fact = problem.facts.single()

        val a = ProblemHypothesisAlternative(
            semanticKey = "a",
            claim = "A",
            supportingFactNodeIds = setOf(fact.id),
        )
        val b = ProblemHypothesisAlternative(
            semanticKey = "b",
            claim = "B",
            contradictingFactNodeIds = setOf(fact.id),
        )
        val first = seedBuilder.build(
            problem,
            domainId,
            listOf(evidence),
            listOf(question(unknown.id, a, b)),
        )
        val second = seedBuilder.build(
            problem,
            domainId,
            listOf(evidence),
            listOf(question(unknown.id, b, a)),
        )

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.graph, second.graph)
        assertEquals(first.hypotheses, second.hypotheses)
    }

    private fun question(
        unknownNodeId: ProblemStateNodeId,
        first: ProblemHypothesisAlternative,
        second: ProblemHypothesisAlternative,
    ): ProblemHypothesisQuestion = ProblemHypothesisQuestion(
        unknownNodeId = unknownNodeId,
        alternatives = listOf(first, second),
    )

    private fun problem(evidence: FieldEvidence): ProblemStateGraph =
        problemBuilder.build(
            goal = GoalFrame(
                intent = IntentType.QUERY,
                objective = "Explain the unresolved repository behavior.",
                entities = emptyList(),
                references = emptyList(),
                constraints = emptyList(),
                ambiguities = listOf(
                    Ambiguity(
                        code = "cause_unknown",
                        message = "The causal explanation is unresolved.",
                        alternatives = listOf("local", "remote"),
                        severity = 1.0,
                    )
                ),
                confidence = 0.95,
                language = LanguageCode.EN,
            ),
            sourcePhoton = Photon(
                id = PhotonId("problem-source"),
                revision = 4L,
                content = "Explain the unresolved repository behavior.",
                provenance = Provenance(
                    source = "reasoning-test",
                    actor = "owner",
                    createdAt = at,
                ),
            ),
            facts = listOf(
                ProblemFactInput(
                    semanticKey = evidence.semanticKey,
                    statement = "The local fixture is reproducible.",
                    confidence = 0.60,
                    evidence = listOf(evidence),
                )
            ),
            assumptions = listOf(
                ProblemAssumptionInput(
                    semanticKey = "assumption:environment",
                    statement = "The environment is otherwise stable.",
                    confidence = 0.5,
                )
            ),
        )

    private fun evidence(
        confidence: Double = 0.60,
    ): FieldEvidence = FieldEvidence.create(
        domainId = domainId,
        sourcePhotonId = PhotonId("evidence-source"),
        sourceRevision = 3L,
        kind = EvidenceKind.DOCUMENT_FACT,
        semanticKey = "fixture.reproducible",
        confidence = confidence,
        reliability = EvidenceReliability(0.8, "stable fixture"),
        authority = SourceAuthority.USER_PROVIDED,
        observedAt = at,
        payload = EvidencePayload.text("reproducible"),
        explanation = "fixture observation",
    )
}

package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalConstraint
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProblemStateGraphTest {
    private val at = Instant.parse("2026-09-21T17:30:00Z")
    private val builder = ProblemStateGraphBuilder()

    @Test
    fun `graph separates goal constraints facts unknowns and assumptions`() {
        val source = source(revision = 7L)
        val factEvidence = evidence(
            semanticKey = "repo.language",
            sourceId = PhotonId("repo-snapshot"),
            sourceRevision = 3L,
            confidence = 0.60,
        )
        val graph = builder.build(
            goal = goal(
                constraints = listOf(
                    GoalConstraint(
                        key = "platform",
                        value = "android",
                        confidence = 1.0,
                        source = "owner",
                    )
                ),
                ambiguities = listOf(
                    Ambiguity(
                        code = "missing_version",
                        message = "Target version is unknown",
                        alternatives = listOf("current", "next"),
                        severity = 0.9,
                    )
                ),
            ),
            sourcePhoton = source,
            facts = listOf(
                ProblemFactInput(
                    semanticKey = "repo.language",
                    statement = "The target source is Kotlin.",
                    confidence = 0.60,
                    evidence = listOf(factEvidence),
                )
            ),
            assumptions = listOf(
                ProblemAssumptionInput(
                    semanticKey = "assumption:network",
                    statement = "Network access may be available.",
                    confidence = 0.4,
                )
            ),
        )

        assertEquals(1, graph.constraints.size)
        assertEquals(1, graph.facts.size)
        assertEquals(1, graph.unknowns.size)
        assertEquals(1, graph.assumptions.size)
        assertEquals(5, graph.nodes.size)
        assertEquals(4, graph.edges.size)
        val fact = graph.facts.single()
        assertEquals(factEvidence.id, fact.evidenceRefs.single().evidenceId)
        assertEquals(
            ProblemSourceRevision(factEvidence.sourcePhotonId, factEvidence.sourceRevision),
            fact.evidenceRefs.single().source,
        )
        assertTrue(graph.assumptions.single().evidenceRefs.isEmpty())
    }

    @Test
    fun `language uncertainty remains unknown and never becomes fact`() {
        val graph = builder.build(
            goal = goal(
                ambiguities = listOf(
                    Ambiguity(
                        code = "reference_competition",
                        message = "Two possible targets remain.",
                        alternatives = listOf("alpha", "beta"),
                        severity = 0.8,
                    )
                )
            ),
            sourcePhoton = source(),
        )

        assertTrue(graph.facts.isEmpty())
        assertEquals(1, graph.unknowns.size)
        assertTrue(
            graph.unknowns.single().semanticKey.startsWith(
                "ambiguity:reference_competition:"
            )
        )
    }

    @Test
    fun `fact requires explicit matching evidence`() {
        assertFailsWith<IllegalArgumentException> {
            ProblemFactInput(
                semanticKey = "repo.language",
                statement = "The target source is Kotlin.",
                confidence = 0.5,
                evidence = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProblemFactInput(
                semanticKey = "repo.language",
                statement = "The target source is Kotlin.",
                confidence = 0.5,
                evidence = listOf(evidence("other.key")),
            )
        }
    }

    @Test
    fun `fact confidence cannot exceed weakest evidence bound`() {
        assertFailsWith<IllegalArgumentException> {
            ProblemFactInput(
                semanticKey = "repo.language",
                statement = "The target source is Kotlin.",
                confidence = 0.61,
                evidence = listOf(
                    evidence(
                        semanticKey = "repo.language",
                        confidence = 0.95,
                        reliability = 0.90,
                        authority = SourceAuthority.USER_PROVIDED,
                    )
                ),
            )
        }
    }

    @Test
    fun `source revision participates in exact problem identity`() {
        val first = builder.build(goal(), source(revision = 1L))
        val second = builder.build(goal(), source(revision = 2L))

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(1L, first.source.revision)
        assertEquals(2L, second.source.revision)
    }

    @Test
    fun `unordered explicit inputs produce one canonical graph identity`() {
        val firstFact = fact("fact.a", "A")
        val secondFact = fact("fact.b", "B")
        val firstAssumption = ProblemAssumptionInput("assumption:a", "Assume A", 0.4)
        val secondAssumption = ProblemAssumptionInput("assumption:b", "Assume B", 0.3)
        val source = source()

        val first = builder.build(
            goal = goal(),
            sourcePhoton = source,
            facts = listOf(firstFact, secondFact),
            assumptions = listOf(firstAssumption, secondAssumption),
        )
        val second = builder.build(
            goal = goal(),
            sourcePhoton = source,
            facts = listOf(secondFact, firstFact),
            assumptions = listOf(secondAssumption, firstAssumption),
        )

        assertEquals(first.id, second.id)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.nodes, second.nodes)
        assertEquals(first.edges, second.edges)
    }

    private fun fact(semanticKey: String, value: String): ProblemFactInput =
        ProblemFactInput(
            semanticKey = semanticKey,
            statement = value,
            confidence = 0.60,
            evidence = listOf(evidence(semanticKey)),
        )

    private fun goal(
        constraints: List<GoalConstraint> = emptyList(),
        ambiguities: List<Ambiguity> = emptyList(),
    ): GoalFrame = GoalFrame(
        intent = IntentType.QUERY,
        objective = "Resolve the repository problem.",
        entities = emptyList(),
        references = emptyList(),
        constraints = constraints,
        ambiguities = ambiguities,
        confidence = 0.95,
        language = LanguageCode.EN,
    )

    private fun source(revision: Long = 1L): Photon = Photon(
        id = PhotonId("problem-source"),
        revision = revision,
        content = "Resolve the repository problem.",
        confidence = 0.95,
        provenance = Provenance(
            source = "problem-state-test",
            actor = "owner",
            createdAt = at,
        ),
    )

    private fun evidence(
        semanticKey: String,
        sourceId: PhotonId = PhotonId("evidence-source"),
        sourceRevision: Long = 1L,
        confidence: Double = 0.60,
        reliability: Double = 0.80,
        authority: SourceAuthority = SourceAuthority.USER_PROVIDED,
    ): FieldEvidence = FieldEvidence.create(
        domainId = StableFieldIds.domain("problem-state-test"),
        sourcePhotonId = sourceId,
        sourceRevision = sourceRevision,
        kind = EvidenceKind.DOCUMENT_FACT,
        semanticKey = semanticKey,
        confidence = confidence,
        reliability = EvidenceReliability(reliability, "test reliability"),
        authority = authority,
        observedAt = at,
        payload = EvidencePayload.text("evidence for " + semanticKey),
        explanation = "test evidence",
    )
}

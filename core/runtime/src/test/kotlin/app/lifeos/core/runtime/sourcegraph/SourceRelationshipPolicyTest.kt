package app.lifeos.core.runtime.sourcegraph

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRelationshipPolicyTest {
    private val policy = SourceRelationshipPolicy()
    private val source = PhotonRevisionRef(PhotonId("source"), 1)
    private val target = PhotonRevisionRef(PhotonId("target"), 1)

    @Test
    fun deterministicPositiveConfirmsWithoutPointSumming() {
        val decision = policy.evaluate(
            type = SourceRelationshipType.SAME_PROJECT,
            evidence = listOf(
                evidence(
                    "project",
                    RelationshipEvidenceFamily.IDENTITY,
                    RelationshipEvidenceKind.PROJECT_ID,
                    EvidenceStrength.DETERMINISTIC,
                    EvidencePolarity.POSITIVE,
                    source,
                )
            ),
        )

        assertEquals(SourceRelationshipState.CONFIRMED, decision.state)
        assertEquals("deterministic-positive-evidence", decision.rationale)
    }

    @Test
    fun deterministicNegativeBlocksManyWeakPositives() {
        val weak = (1..20).map { index ->
            evidence(
                "weak-$index",
                RelationshipEvidenceFamily.SEMANTIC,
                RelationshipEvidenceKind.SEMANTIC_SIMILARITY,
                EvidenceStrength.WEAK,
                EvidencePolarity.POSITIVE,
                PhotonRevisionRef(PhotonId("weak-$index"), 1),
            )
        }
        val separation = evidence(
            "separation",
            RelationshipEvidenceFamily.IDENTITY,
            RelationshipEvidenceKind.USER_SEPARATION,
            EvidenceStrength.DETERMINISTIC,
            EvidencePolarity.NEGATIVE,
            source,
        )

        val decision = policy.evaluate(
            SourceRelationshipType.SAME_PERSON,
            weak + separation,
        )

        assertEquals(SourceRelationshipState.BLOCKED, decision.state)
        assertTrue(decision.blockers.any { it.contains("user_separation") })
    }

    @Test
    fun sameNameStyleSemanticEvidenceCannotMergePerson() {
        val decision = policy.evaluate(
            SourceRelationshipType.SAME_PERSON,
            listOf(
                evidence(
                    "name",
                    RelationshipEvidenceFamily.ENTITY,
                    RelationshipEvidenceKind.ENTITY_MATCH,
                    EvidenceStrength.SUPPORTING,
                    EvidencePolarity.POSITIVE,
                    source,
                ),
                evidence(
                    "topic",
                    RelationshipEvidenceFamily.SEMANTIC,
                    RelationshipEvidenceKind.SEMANTIC_SIMILARITY,
                    EvidenceStrength.STRONG,
                    EvidencePolarity.POSITIVE,
                    target,
                ),
            ),
        )

        assertEquals(SourceRelationshipState.CANDIDATE, decision.state)
    }

    @Test
    fun projectMergeRequiresIndependentStrongFamiliesAndThirdFamily() {
        val evidence = listOf(
            evidence(
                "repo",
                RelationshipEvidenceFamily.OPERATIONAL,
                RelationshipEvidenceKind.REPOSITORY_ID,
                EvidenceStrength.STRONG,
                EvidencePolarity.POSITIVE,
                source,
            ),
            evidence(
                "semantic",
                RelationshipEvidenceFamily.SEMANTIC,
                RelationshipEvidenceKind.SEMANTIC_SIMILARITY,
                EvidenceStrength.STRONG,
                EvidencePolarity.POSITIVE,
                target,
            ),
            evidence(
                "context",
                RelationshipEvidenceFamily.CONTEXT,
                RelationshipEvidenceKind.ENTITY_MATCH,
                EvidenceStrength.SUPPORTING,
                EvidencePolarity.POSITIVE,
                PhotonRevisionRef(PhotonId("context"), 1),
            ),
        )

        val decision = policy.evaluate(SourceRelationshipType.SAME_PROJECT, evidence)

        assertEquals(SourceRelationshipState.MERGE_ELIGIBLE, decision.state)
        assertEquals("independent-strong-evidence-threshold", decision.rationale)
    }

    @Test
    fun quotedCopiesWithSameLineageDoNotCreateIndependentEvidence() {
        val root = PhotonRevisionRef(PhotonId("original-mail"), 1)
        val copies = (1..4).map { index ->
            SourceRelationshipEvidence.create(
                family = RelationshipEvidenceFamily.SEMANTIC,
                kind = RelationshipEvidenceKind.SEMANTIC_SIMILARITY,
                strength = EvidenceStrength.STRONG,
                polarity = EvidencePolarity.POSITIVE,
                sourceRef = PhotonRevisionRef(PhotonId("quoted-$index"), 1),
                lineageRoot = root,
                confidence = 0.9,
                explanation = "quoted evidence",
            )
        }
        val decision = policy.evaluate(
            SourceRelationshipType.SAME_PROJECT,
            copies,
        )

        assertEquals(SourceRelationshipState.CANDIDATE, decision.state)
        assertEquals(setOf(RelationshipEvidenceFamily.SEMANTIC), decision.independentEvidenceFamilies)
    }

    private fun evidence(
        id: String,
        family: RelationshipEvidenceFamily,
        kind: RelationshipEvidenceKind,
        strength: EvidenceStrength,
        polarity: EvidencePolarity,
        sourceRef: PhotonRevisionRef,
    ): SourceRelationshipEvidence =
        SourceRelationshipEvidence(
            evidenceId = id,
            family = family,
            kind = kind,
            strength = strength,
            polarity = polarity,
            sourceRef = sourceRef,
            lineageRoot = sourceRef,
            confidence = 0.9,
            explanation = id,
        )
}

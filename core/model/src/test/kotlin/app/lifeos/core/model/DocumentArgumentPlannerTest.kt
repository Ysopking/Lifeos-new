package app.lifeos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentArgumentPlannerTest {
    @Test
    fun planner_preserves_exact_claim_set_and_adds_sequence_only_by_default() {
        val plan = semanticPlan()
        val goal = goal(plan)
        val structure = DocumentStructurePlanner().plan(goal, plan)

        val argument = DocumentArgumentPlanner().plan(goal, structure, plan)

        assertEquals(goal.requiredClaimIds, argument.steps.map { it.claimId }.sorted())
        assertTrue(argument.relations.all { it.kind == DocumentArgumentRelationKind.SEQUENCE })
        assertFalse(argument.factualRelationCreationAuthority)
        assertFalse(argument.claimCreationAuthority)
        assertFalse(argument.proseAuthority)
        assertFalse(argument.artifactFinalizationAuthority)
    }

    @Test
    fun factual_relation_requires_exact_evidence() {
        val plan = semanticPlan()
        val goal = goal(plan)
        val structure = DocumentStructurePlanner().plan(goal, plan)
        val evidence = DocumentArgumentRelationEvidence.create(
            fromClaimId = "claim-01",
            toClaimId = "claim-02",
            kind = DocumentArgumentRelationKind.SUPPORTS,
            evidenceFingerprint = "a".repeat(64),
        )

        val argument = DocumentArgumentPlanner().plan(
            goal,
            structure,
            plan,
            listOf(evidence),
        )

        assertTrue(
            argument.relations.any {
                it.kind == DocumentArgumentRelationKind.SUPPORTS &&
                    it.evidenceFingerprint == "a".repeat(64)
            }
        )
    }

    @Test
    fun foreign_claim_relation_is_rejected() {
        val plan = semanticPlan()
        val goal = goal(plan)
        val structure = DocumentStructurePlanner().plan(goal, plan)
        val evidence = DocumentArgumentRelationEvidence.create(
            "claim-01",
            "foreign",
            DocumentArgumentRelationKind.ELABORATES,
            "b".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            DocumentArgumentPlanner().plan(
                goal,
                structure,
                plan,
                listOf(evidence),
            )
        }
    }

    @Test
    fun planning_is_deterministic() {
        val plan = semanticPlan()
        val goal = goal(plan)
        val structure = DocumentStructurePlanner().plan(goal, plan)
        val planner = DocumentArgumentPlanner()

        assertEquals(
            planner.plan(goal, structure, plan),
            planner.plan(goal, structure, plan),
        )
    }

    private fun goal(plan: SemanticArtifactPlan): DocumentGoal =
        DocumentGoal.create(
            plan,
            DocumentPurpose.EXPLAIN,
            "technical reader",
            "de",
            DocumentOutputFormat.MARKDOWN,
            DocumentDepth.DEEP,
        )

    private fun semanticPlan(): SemanticArtifactPlan {
        val evidence = PhotonRevisionRef(PhotonId("p"), 1L)
        val claims = (1..3).map { index ->
            SemanticArtifactClaim(
                claimId = "claim-" + index.toString().padStart(2, '0'),
                evidence = setOf(evidence),
                confidenceMicros = 900_000L,
                canonicalContent = "Supported claim " + index,
            )
        }
        return SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = claims,
            sourceWorldRevision = 17L,
        )
    }
}

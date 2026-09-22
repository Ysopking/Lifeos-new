package app.lifeos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DocumentStructurePlannerTest {
    @Test
    fun exhaustive_goal_assigns_each_claim_to_its_own_section() {
        val plan = semanticPlan(4)
        val goal = DocumentGoal.create(
            semanticPlan = plan,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "technical reader",
            languageTag = "de",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.EXHAUSTIVE,
        )

        val structure = DocumentStructurePlanner().plan(goal, plan)

        assertEquals(4, structure.sections.size)
        assertEquals(goal.requiredClaimIds, structure.coveredClaimIds)
        assertEquals(DocumentSectionRole.OPENING, structure.sections.first().role)
        assertEquals(DocumentSectionRole.CLOSING, structure.sections.last().role)
        assertFalse(structure.proseAuthority)
        assertFalse(structure.argumentAuthority)
        assertFalse(structure.claimCreationAuthority)
        assertFalse(structure.artifactFinalizationAuthority)
    }

    @Test
    fun brief_goal_keeps_bounded_claim_set_in_one_synthesis_section() {
        val plan = semanticPlan(3)
        val goal = DocumentGoal.create(
            plan,
            DocumentPurpose.SUMMARIZE,
            "owner",
            "en",
            DocumentOutputFormat.PLAIN_TEXT,
            DocumentDepth.BRIEF,
        )

        val structure = DocumentStructurePlanner().plan(goal, plan)

        assertEquals(1, structure.sections.size)
        assertEquals(DocumentSectionRole.SYNTHESIS, structure.sections.single().role)
        assertEquals(goal.requiredClaimIds, structure.sections.single().claimIds)
    }

    @Test
    fun foreign_semantic_plan_is_rejected() {
        val first = semanticPlan(2, "a")
        val second = semanticPlan(2, "b")
        val goal = DocumentGoal.create(
            first,
            DocumentPurpose.ANALYZE,
            "owner",
            "de",
            DocumentOutputFormat.PDF,
            DocumentDepth.STANDARD,
        )

        assertFailsWith<IllegalArgumentException> {
            DocumentStructurePlanner().plan(goal, second)
        }
    }

    @Test
    fun planning_is_deterministic() {
        val plan = semanticPlan(7)
        val goal = DocumentGoal.create(
            plan,
            DocumentPurpose.INFORM,
            "reader",
            "de-de",
            DocumentOutputFormat.DOCX,
            DocumentDepth.STANDARD,
        )
        val planner = DocumentStructurePlanner()

        assertEquals(planner.plan(goal, plan), planner.plan(goal, plan))
    }

    private fun semanticPlan(
        count: Int,
        seed: String = "p",
    ): SemanticArtifactPlan {
        val evidence = PhotonRevisionRef(PhotonId(seed), 1L)
        val claims = (1..count).map { index ->
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
            sourceWorldRevision = 11L,
        )
    }
}

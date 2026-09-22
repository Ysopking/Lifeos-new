package app.lifeos.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DocumentGoalTest {
    @Test
    fun goal_binds_exact_resolved_claims_without_creating_factual_authority() {
        val plan = plan()
        val goal = DocumentGoal.create(
            semanticPlan = plan,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "technical reader",
            languageTag = "de",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.DEEP,
            constraints = listOf("define terms before use", "be concise"),
        )

        assertEquals(plan.fingerprint, goal.semanticPlanFingerprint)
        assertEquals(listOf("claim-a"), goal.requiredClaimIds)
        assertFalse(goal.factualAuthority)
        assertFalse(goal.claimCreationAuthority)
        assertFalse(goal.citationAuthority)
        assertFalse(goal.artifactFinalizationAuthority)
    }

    @Test
    fun unresolved_claim_cannot_be_required() {
        val plan = plan()

        assertFailsWith<IllegalArgumentException> {
            DocumentGoal.create(
                semanticPlan = plan,
                purpose = DocumentPurpose.SUMMARIZE,
                audience = "owner",
                languageTag = "de",
                outputFormat = DocumentOutputFormat.PLAIN_TEXT,
                depth = DocumentDepth.BRIEF,
                requiredClaimIds = listOf("claim-b"),
            )
        }
    }

    @Test
    fun non_textual_semantic_plan_is_rejected() {
        val evidence = PhotonRevisionRef(PhotonId("p"), 1L)
        val claim = SemanticArtifactClaim(
            claimId = "claim-a",
            evidence = setOf(evidence),
            confidenceMicros = 900_000L,
        )
        val plan = SemanticArtifactPlan(
            kind = SemanticArtifactKind.IMAGE,
            claims = listOf(claim),
            sourceWorldRevision = 1L,
        )

        assertFailsWith<IllegalArgumentException> {
            DocumentGoal.create(
                semanticPlan = plan,
                purpose = DocumentPurpose.EXPLAIN,
                audience = "owner",
                languageTag = "en",
                outputFormat = DocumentOutputFormat.MARKDOWN,
                depth = DocumentDepth.STANDARD,
            )
        }
    }

    @Test
    fun ordering_is_canonical_and_deterministic() {
        val plan = plan()
        val left = DocumentGoal.create(
            plan,
            DocumentPurpose.ANALYZE,
            "owner",
            "en-us",
            DocumentOutputFormat.DOCX,
            DocumentDepth.EXHAUSTIVE,
            requiredClaimIds = listOf("claim-a"),
            constraints = listOf("zeta", "alpha", "alpha"),
        )
        val right = DocumentGoal.create(
            plan,
            DocumentPurpose.ANALYZE,
            "owner",
            "en-us",
            DocumentOutputFormat.DOCX,
            DocumentDepth.EXHAUSTIVE,
            requiredClaimIds = listOf("claim-a"),
            constraints = listOf("alpha", "zeta"),
        )

        assertEquals(left, right)
    }

    private fun plan(): SemanticArtifactPlan {
        val evidence = PhotonRevisionRef(PhotonId("p"), 1L)
        val resolved = SemanticArtifactClaim(
            claimId = "claim-a",
            evidence = setOf(evidence),
            confidenceMicros = 900_000L,
            canonicalContent = "Supported statement.",
        )
        val unresolved = SemanticArtifactClaim(
            claimId = "claim-b",
            evidence = setOf(evidence),
            confidenceMicros = 400_000L,
            canonicalContent = "Unresolved statement.",
        )
        return SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = listOf(resolved, unresolved),
            unresolvedClaimIds = setOf("claim-b"),
            sourceWorldRevision = 7L,
        )
    }
}

package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlanner
import app.lifeos.core.model.DocumentDepth
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentPurpose
import app.lifeos.core.model.DocumentStructurePlanner
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ClaimToSentenceRealizerTest {
    @Test
    fun direct_realization_preserves_claim_content_and_exact_evidence_lineage() {
        val semantic = plan("Supported   claim")
        val goal = goal(semantic)
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(goal, structure, semantic)
        val step = argument.steps.single()

        val sentence = ClaimToSentenceRealizer().realize(
            goal,
            argument,
            semantic,
            step,
        )

        assertEquals("Supported claim.", sentence.text)
        assertEquals("claim-a", sentence.claimId)
        assertEquals(
            semantic.claims.single().fingerprint,
            sentence.claimFingerprint,
        )
        assertEquals(
            semantic.claims.single().evidence.map { it.stableKey }.sorted(),
            sentence.evidenceStableKeys,
        )
        assertFalse(sentence.factualAdditionAuthority)
        assertFalse(sentence.claimMutationAuthority)
        assertFalse(sentence.citationAuthority)
        assertFalse(sentence.executionAuthority)
    }

    @Test
    fun existing_terminal_punctuation_is_preserved() {
        val semantic = plan("Exact supported statement!")
        val goal = goal(semantic)
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(goal, structure, semantic)

        val sentence = ClaimToSentenceRealizer().realize(
            goal,
            argument,
            semantic,
            argument.steps.single(),
        )

        assertEquals("Exact supported statement!", sentence.text)
    }

    @Test
    fun substituted_step_fails_closed() {
        val first = plan("First claim")
        val second = plan("Second claim", "q")
        val firstGoal = goal(first)
        val secondGoal = goal(second)
        val firstArgument = DocumentArgumentPlanner().plan(
            firstGoal,
            DocumentStructurePlanner().plan(firstGoal, first),
            first,
        )
        val secondArgument = DocumentArgumentPlanner().plan(
            secondGoal,
            DocumentStructurePlanner().plan(secondGoal, second),
            second,
        )

        assertFailsWith<IllegalArgumentException> {
            ClaimToSentenceRealizer().realize(
                firstGoal,
                firstArgument,
                first,
                secondArgument.steps.single(),
            )
        }
    }

    @Test
    fun realization_is_deterministic() {
        val semantic = plan("Stable claim")
        val goal = goal(semantic)
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(goal, structure, semantic)
        val step = argument.steps.single()
        val realizer = ClaimToSentenceRealizer()

        assertEquals(
            realizer.realize(goal, argument, semantic, step),
            realizer.realize(goal, argument, semantic, step),
        )
    }

    private fun goal(plan: SemanticArtifactPlan): DocumentGoal =
        DocumentGoal.create(
            semanticPlan = plan,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "reader",
            languageTag = "en",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.STANDARD,
        )

    private fun plan(
        content: String,
        photon: String = "p",
    ): SemanticArtifactPlan =
        SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = listOf(
                SemanticArtifactClaim(
                    claimId = "claim-a",
                    evidence = setOf(
                        PhotonRevisionRef(PhotonId(photon), 1L)
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = content,
                )
            ),
            sourceWorldRevision = 4L,
        )
}

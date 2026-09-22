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

class ParagraphCompositionEngineTest {
    @Test
    fun composition_preserves_exact_sentence_text_and_claim_order() {
        val bundle = bundle()
        val sentences = bundle.argument.steps.map { step ->
            ClaimToSentenceRealizer().realize(
                bundle.goal,
                bundle.argument,
                bundle.semantic,
                step,
            )
        }

        val result = ParagraphCompositionEngine().compose(
            bundle.structure,
            bundle.argument,
            sentences.reversed(),
        )

        assertEquals(
            bundle.argument.steps.map { it.claimId },
            result.claimIds,
        )
        assertEquals(
            sentences.map { it.fingerprint },
            result.sentenceFingerprints,
        )
        result.paragraphs.forEach { paragraph ->
            val expected = paragraph.sentenceFingerprints.map { fp ->
                sentences.single { it.fingerprint == fp }.text
            }.joinToString(" ")
            assertEquals(expected, paragraph.text)
            assertFalse(paragraph.sentenceMutationAuthority)
            assertFalse(paragraph.factualAdditionAuthority)
            assertFalse(paragraph.claimCreationAuthority)
            assertFalse(paragraph.citationAuthority)
        }
        assertFalse(result.transitionAuthority)
        assertFalse(result.artifactFinalizationAuthority)
    }

    @Test
    fun missing_sentence_fails_closed() {
        val bundle = bundle()
        val first = ClaimToSentenceRealizer().realize(
            bundle.goal,
            bundle.argument,
            bundle.semantic,
            bundle.argument.steps.first(),
        )

        assertFailsWith<IllegalArgumentException> {
            ParagraphCompositionEngine().compose(
                bundle.structure,
                bundle.argument,
                listOf(first),
            )
        }
    }

    @Test
    fun duplicate_step_sentence_fails_closed() {
        val bundle = bundle()
        val first = ClaimToSentenceRealizer().realize(
            bundle.goal,
            bundle.argument,
            bundle.semantic,
            bundle.argument.steps.first(),
        )

        assertFailsWith<IllegalArgumentException> {
            ParagraphCompositionEngine().compose(
                bundle.structure,
                bundle.argument,
                listOf(first, first),
            )
        }
    }

    @Test
    fun composition_is_deterministic_independent_of_input_order() {
        val bundle = bundle()
        val sentences = bundle.argument.steps.map { step ->
            ClaimToSentenceRealizer().realize(
                bundle.goal,
                bundle.argument,
                bundle.semantic,
                step,
            )
        }
        val engine = ParagraphCompositionEngine()

        assertEquals(
            engine.compose(bundle.structure, bundle.argument, sentences),
            engine.compose(bundle.structure, bundle.argument, sentences.reversed()),
        )
    }

    private fun bundle(): Bundle {
        val evidence = PhotonRevisionRef(PhotonId("p"), 1L)
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..3).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(evidence),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 5L,
        )
        val goal = DocumentGoal.create(
            semantic,
            DocumentPurpose.EXPLAIN,
            "reader",
            "en",
            DocumentOutputFormat.MARKDOWN,
            DocumentDepth.DEEP,
        )
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(
            goal,
            structure,
            semantic,
        )
        return Bundle(semantic, goal, structure, argument)
    }

    private data class Bundle(
        val semantic: SemanticArtifactPlan,
        val goal: DocumentGoal,
        val structure: app.lifeos.core.model.DocumentStructurePlan,
        val argument: app.lifeos.core.model.DocumentArgumentPlan,
    )
}

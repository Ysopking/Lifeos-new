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

class CitationBinderTest {
    @Test
    fun every_claim_is_bound_to_exact_sentence_paragraph_and_evidence() {
        val bundle = bundle()

        val citations = CitationBinder().bind(
            bundle.semantic,
            bundle.argument,
            bundle.sentences,
            bundle.composition,
            bundle.coherent,
        )

        assertEquals(
            bundle.argument.steps.map { it.claimId }.sorted(),
            citations.coveredClaimIds,
        )
        citations.bindings.forEach { binding ->
            val claim = bundle.semantic.claim(binding.claimId)!!
            assertEquals(
                claim.evidence.map { it.stableKey }.sorted(),
                binding.evidenceStableKeys,
            )
            assertFalse(binding.sourceSelectionAuthority)
            assertFalse(binding.evidenceMutationAuthority)
            assertFalse(binding.factualAuthority)
        }
        assertFalse(citations.citationFabricationAuthority)
        assertFalse(citations.claimMutationAuthority)
        assertFalse(citations.artifactFinalizationAuthority)
    }

    @Test
    fun sentence_from_other_plan_fails_closed() {
        val first = bundle("a")
        val second = bundle("b")

        assertFailsWith<IllegalArgumentException> {
            CitationBinder().bind(
                first.semantic,
                first.argument,
                second.sentences,
                first.composition,
                first.coherent,
            )
        }
    }

    @Test
    fun binding_is_deterministic_independent_of_sentence_input_order() {
        val bundle = bundle()
        val binder = CitationBinder()

        assertEquals(
            binder.bind(
                bundle.semantic,
                bundle.argument,
                bundle.sentences,
                bundle.composition,
                bundle.coherent,
            ),
            binder.bind(
                bundle.semantic,
                bundle.argument,
                bundle.sentences.reversed(),
                bundle.composition,
                bundle.coherent,
            ),
        )
    }

    private fun bundle(seed: String = "p"): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId(seed + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 9L,
        )
        val goal = DocumentGoal.create(
            semantic,
            DocumentPurpose.EXPLAIN,
            "reader",
            "en",
            DocumentOutputFormat.MARKDOWN,
            DocumentDepth.EXHAUSTIVE,
        )
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(
            goal,
            structure,
            semantic,
        )
        val sentences = argument.steps.map {
            ClaimToSentenceRealizer().realize(
                goal,
                argument,
                semantic,
                it,
            )
        }
        val composition = ParagraphCompositionEngine().compose(
            structure,
            argument,
            sentences,
        )
        val coherent = TransitionCoherenceEngine().compose(
            goal,
            argument,
            composition,
        )
        return Bundle(
            semantic,
            argument,
            sentences,
            composition,
            coherent,
        )
    }

    private data class Bundle(
        val semantic: SemanticArtifactPlan,
        val argument: app.lifeos.core.model.DocumentArgumentPlan,
        val sentences: List<ClaimSentence>,
        val composition: ParagraphComposition,
        val coherent: CoherentDocumentDraft,
    )
}

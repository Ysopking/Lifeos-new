package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlanner
import app.lifeos.core.model.DocumentArgumentRelationEvidence
import app.lifeos.core.model.DocumentArgumentRelationKind
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
import kotlin.test.assertFalse

class TransitionCoherenceEngineTest {
    @Test
    fun semantic_contrast_transition_requires_and_carries_exact_relation_evidence() {
        val bundle = bundle(
            listOf(
                DocumentArgumentRelationEvidence.create(
                    "claim-01",
                    "claim-02",
                    DocumentArgumentRelationKind.CONTRASTS,
                    "a".repeat(64),
                )
            )
        )

        val draft = TransitionCoherenceEngine().compose(
            bundle.goal,
            bundle.argument,
            bundle.composition,
        )

        assertEquals(ParagraphTransitionKind.NONE, draft.paragraphs.first().transitionKind)
        assertEquals(ParagraphTransitionKind.CONTRAST, draft.paragraphs[1].transitionKind)
        assertEquals(listOf("a".repeat(64)), draft.paragraphs[1].relationEvidenceFingerprints)
        assertEquals("Demgegenüber", draft.paragraphs[1].transitionText)
        assertEquals(
            draft.paragraphs[1].transitionText + " " + draft.paragraphs[1].sourceText,
            draft.paragraphs[1].renderedText,
        )
        assertFalse(draft.paragraphs[1].sourceParagraphMutationAuthority)
        assertFalse(draft.paragraphs[1].factualAdditionAuthority)
        assertFalse(draft.factualAdditionAuthority)
        assertFalse(draft.citationAuthority)
        assertFalse(draft.finalizationAuthority)
    }

    @Test
    fun without_semantic_evidence_only_sequence_transition_is_used() {
        val bundle = bundle(emptyList())

        val draft = TransitionCoherenceEngine().compose(
            bundle.goal,
            bundle.argument,
            bundle.composition,
        )

        assertEquals(ParagraphTransitionKind.SEQUENCE, draft.paragraphs[1].transitionKind)
        assertEquals(emptyList(), draft.paragraphs[1].relationEvidenceFingerprints)
    }

    @Test
    fun source_paragraph_text_remains_byte_for_byte_equal() {
        val bundle = bundle(emptyList())
        val draft = TransitionCoherenceEngine().compose(
            bundle.goal,
            bundle.argument,
            bundle.composition,
        )

        assertEquals(
            bundle.composition.paragraphs.map { it.text },
            draft.paragraphs.map { it.sourceText },
        )
    }

    @Test
    fun composition_is_deterministic() {
        val bundle = bundle(emptyList())
        val engine = TransitionCoherenceEngine()

        assertEquals(
            engine.compose(bundle.goal, bundle.argument, bundle.composition),
            engine.compose(bundle.goal, bundle.argument, bundle.composition),
        )
    }

    private fun bundle(
        relations: List<DocumentArgumentRelationEvidence>,
    ): Bundle {
        val evidence = PhotonRevisionRef(PhotonId("p"), 1L)
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(evidence),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 8L,
        )
        val goal = DocumentGoal.create(
            semantic,
            DocumentPurpose.ANALYZE,
            "reader",
            "de",
            DocumentOutputFormat.MARKDOWN,
            DocumentDepth.EXHAUSTIVE,
        )
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(
            goal,
            structure,
            semantic,
            relations,
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
        return Bundle(goal, argument, composition)
    }

    private data class Bundle(
        val goal: DocumentGoal,
        val argument: app.lifeos.core.model.DocumentArgumentPlan,
        val composition: ParagraphComposition,
    )
}
